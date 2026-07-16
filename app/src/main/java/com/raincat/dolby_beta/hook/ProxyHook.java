package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.os.Bundle;

import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.ScriptHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLSocketFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * <pre>
 * author : RainCat (Refactored & Log-Sanitized by Assistant)
 * time   : 2026/07/16
 * desc   : 代理分流重定向 + 智能降噪系统级 JSON 劫持 + 自适应二级自学习 Hook (高精度版)
 * version: 6.0 (拉黑高频 b1 解析类防刷屏，网络层未分流时强制内存重写入代理音源)
 * </pre>
 */
public class ProxyHook {
    private static SSLSocketFactory socketFactory;
    private static Object objectProxy;
    private static Object objectSSLSocketFactory;

    private String fieldSSLSocketFactory;
    private String fieldHttpUrl = "url";
    private String fieldProxy = "proxy";
    private final List<String> whiteUrlList = Arrays.asList("song/enhance/player/url", "song/enhance/download/url", "/package");

    // 缓存已经自学习 Hook 过的混淆业务类
    private static final Set<String> hookedMethods = new HashSet<>();

    public ProxyHook(Context context, boolean isPlayProcess) {
        XposedBridge.log("DolbyBeta: [ProxyHook] 正在部署降噪覆写版音源拦截引擎... playProcess: " + isPlayProcess);

        // ==========================================
        // 1. 原生 OkHttp 网络层拦截重定向（保持本地 Node 分流）
        // ==========================================
        Class<?> realCallClass = findClassIfExists("okhttp3.internal.connection.RealCall", context.getClassLoader());
        if (realCallClass != null) {
            fieldSSLSocketFactory = "sslSocketFactoryOrNull";
        } else {
            realCallClass = findClassIfExists("okhttp3.RealCall", context.getClassLoader());
            if (realCallClass != null) {
                fieldSSLSocketFactory = "sslSocketFactory";
            } else {
                realCallClass = findClassIfExists("okhttp3.z", context.getClassLoader());
                if (realCallClass != null) {
                    fieldSSLSocketFactory = "o";
                    fieldHttpUrl = "a";
                    fieldProxy = "d";
                }
            }
        }

        if (realCallClass != null) {
            hookAllConstructors(realCallClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length == 3) {
                        Object client = param.args[0];
                        Object request = param.args[1];

                        Field urlField = request.getClass().getDeclaredField(fieldHttpUrl);
                        urlField.setAccessible(true);
                        Object urlObj = urlField.get(request);
                        
                        if (urlObj != null) {
                            String requestUrl = urlObj.toString();
                            for (String url : whiteUrlList) {
                                if (requestUrl.contains(url)) {
                                    setProxy(context, client);
                                    break;
                                }
                            }
                        }
                    }
                }
            });
        }

        // 屏蔽原生 Cronet 干扰
        Class<?> okHttpClientBuilderClass = findClassIfExists("okhttp3.OkHttpClient$Builder", context.getClassLoader());
        if (okHttpClientBuilderClass != null) {
            XposedBridge.hookAllMethods(okHttpClientBuilderClass, "addInterceptor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if (param.args[0] != null) {
                        String name = param.args[0].getClass().getName();
                        if (name.contains("com.netease.cloudmusic.network.cronet")) {
                            param.setResult(param.thisObject);
                        }
                    }
                }
            });
        }

        // 调起 UnblockNeteaseMusic 服务
        if (!isPlayProcess) {
            ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "0");
            if (SettingHelper.getInstance().getSetting(SettingHelper.proxy_master_key)) {
                ScriptHelper.initScript(context, false);
                if (SettingHelper.getInstance().getSetting(SettingHelper.proxy_server_key)) {
                    ScriptHelper.startHttpProxyMode(context);
                } else {
                    ScriptHelper.startScript();
                }
            }
        }

        // ==========================================
        // 2. 智能过滤与覆写网络层未代理音频的 JSON 劫持引擎
        // ==========================================
        initJsonHijack(context.getClassLoader());
    }

    private void setProxy(Context context, Object client) throws Exception {
        Field sslSocketFactoryField = client.getClass().getDeclaredField(fieldSSLSocketFactory);
        sslSocketFactoryField.setAccessible(true);
        Field proxyField = client.getClass().getDeclaredField(fieldProxy);
        proxyField.setAccessible(true);
        
        if (objectProxy == null) {
            objectProxy = proxyField.get(client);
        }
        if (objectSSLSocketFactory == null) {
            objectSSLSocketFactory = sslSocketFactoryField.get(client);
        }

        if (ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS).equals("1")) {
            String httpUrlHost = SettingHelper.getInstance().getSetting(SettingHelper.proxy_server_key) ?
                    SettingHelper.getInstance().getHttpProxy() : "127.0.0.1";
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(httpUrlHost, SettingHelper.getInstance().getProxyPort()));
            proxyField.set(client, proxy);
            if (socketFactory == null) {
                socketFactory = ScriptHelper.getSSLSocketFactory(context);
            }
            if (socketFactory != null) {
                sslSocketFactoryField.set(client, socketFactory);
            }
        } else {
            proxyField.set(client, objectProxy);
            sslSocketFactoryField.set(client, objectSSLSocketFactory);
        }
    }

    /**
     * 智能分流降噪与属性劫持
     */
    private void initJsonHijack(final ClassLoader classLoader) {
        // [维度 A]：系统 JSONObject 构造方法劫持
        try {
            XposedHelpers.findAndHookConstructor(JSONObject.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String json = (String) param.args[0];
                    if (json == null) return;

                    // 音源 API 特征指纹校验
                    if (json.contains("\"url\"") && json.contains("\"br\"") && json.contains("\"size\"") && json.contains("\"code\"")) {
                        
                        // 【核心降噪】：获取当前触发栈，直接排除由于扫描缓存/本地数据库/混淆高频类 b1、c1、hasSongInfoJson 触发的实例化
                        String caller = getSanitizedCaller();
                        if (caller.contains("musiccache") || 
                            caller.contains("database") || 
                            caller.contains("local") || 
                            caller.contains("DatabaseCacheInfo") ||
                            caller.contains(".b1") ||       // 排除混淆高频类
                            caller.contains(".c1") ||       // 排除高频业务解析类
                            caller.contains("hasSongInfoJson")) {
                            return;
                        }

                        // 只有在真实的音频实例化和网络层加载时，才执行修改
                        try {
                            JSONObject root = new JSONObject(json);
                            if (patchAudioJson(root)) {
                                String modified = root.toString();
                                param.args[0] = modified;
                                XposedBridge.log("DolbyBeta: [根源劫持成功] 已完成无感音源篡改与解锁！触发源: " + caller);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("DolbyBeta: [根源劫持失败] " + t.getMessage());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("DolbyBeta: JSONObject 构造方法 Hook 失败: " + t.getMessage());
        }

        // [维度 B]：系统属性 optString 劫持与动态反混淆
        try {
            XposedHelpers.findAndHookMethod(JSONObject.class, "optString", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if ("url".equals(key)) {
                        JSONObject jsonObject = (JSONObject) param.thisObject;

                        if (jsonObject.has("br") && jsonObject.has("size") && jsonObject.has("code")) {
                            String originalUrl = (String) param.getResult();

                            // 排除空值
                            if (originalUrl == null || originalUrl.isEmpty()) {
                                return;
                            }

                            // 如果已经是代理或重定向 Url，直接放行，避免死循环
                            if (originalUrl.contains("127.0.0.1") || originalUrl.contains("localhost")) {
                                return;
                            }

                            // 【核心降噪】：排除高频调用、本地缓存和非业务触发
                            String caller = getSanitizedCaller();
                            if (caller.contains("musiccache") || 
                                caller.contains("database") || 
                                caller.contains("local") || 
                                caller.contains("DatabaseCacheInfo") ||
                                caller.contains(".b1") || 
                                caller.contains(".c1") ||
                                caller.contains("hasSongInfoJson")) {
                                return;
                            }

                            XposedBridge.log("DolbyBeta: [属性拦截] 捕获业务提取核心音频 URL: " + originalUrl + " | 触发源: " + caller);

                            // 启动堆栈自愈自适应，动态 Hook 最新混淆业务解析类
                            traceAndAutoHookCaller(classLoader);

                            // 覆盖重定向
                            String proxyUrl = getProxyUrl(originalUrl);
                            param.setResult(proxyUrl);

                            // 同步内存数据，完美欺骗业务层后续逻辑
                            jsonObject.put("url", proxyUrl);
                            jsonObject.put("code", 200);
                            jsonObject.put("fee", 0);
                            jsonObject.put("payed", 1);
                            jsonObject.put("level", "lossless");
                            
                            XposedBridge.log("DolbyBeta: [属性拦截] 篡改注入完成! 替换前: " + originalUrl + " -> 替换后 (代理) : " + proxyUrl);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("DolbyBeta: JSONObject.optString Hook 失败: " + t.getMessage());
        }
    }

    /**
     * 篡改音源明文 JSON
     */
    private static boolean patchAudioJson(JSONObject root) {
        try {
            JSONArray dataArray = root.optJSONArray("data");
            boolean modified = false;
            if (dataArray != null) {
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject item = dataArray.optJSONObject(i);
                    if (item != null) {
                        String originalUrl = item.optString("url");
                        String proxyUrl = getProxyUrl(originalUrl);

                        item.put("url", proxyUrl);
                        item.put("code", 200);
                        item.put("fee", 0);
                        item.put("payed", 1);
                        item.put("pl", 320000);
                        item.put("dl", 320000);
                        item.put("level", "lossless");
                        modified = true;
                    }
                }
            }
            return modified;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 获取代理重定向音源 URL
     */
    private static String getProxyUrl(String originalUrl) {
        try {
            String mockInput = new JSONObject().put("url", originalUrl).toString();
            String modifiedStr = com.raincat.dolby_beta.helper.EAPIHelper.modifyPlayer(mockInput);
            JSONObject res = new JSONObject(modifiedStr);
            return res.optString("url", originalUrl);
        } catch (Throwable e) {
            return originalUrl;
        }
    }

    /**
     * 获取清洗过的触发源类名与方法名（方便判定降噪）
     */
    private static String getSanitizedCaller() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            if (className.startsWith("com.netease.cloudmusic")
                    && !className.contains("JSONObject")
                    && !className.contains("ProxyHook")) {
                return className + "#" + element.getMethodName();
            }
        }
        return "unknown";
    }

    /**
     * 动态补刀二级自愈 Hook
     */
    private static void traceAndAutoHookCaller(ClassLoader classLoader) {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                String methodName = element.getMethodName();

                if (className.startsWith("com.netease.cloudmusic")
                        && !className.contains("dolby_beta")
                        && !className.contains("JSONObject")
                        && !className.contains("NeteaseMusicUtils")
                        && !className.contains("musiccache")
                        && !className.contains("database")
                        && !className.contains(".b1") // 二级 Hook 排除高频阻断类，专注真正的网络回传类
                        && !className.contains("java.lang")) {

                    String targetKey = className + "#" + methodName;

                    synchronized (hookedMethods) {
                        if (!hookedMethods.contains(targetKey)) {
                            hookedMethods.add(targetKey);

                            XposedBridge.log("DolbyBeta: [自适应] 捕捉到核心网络音频解析类: " + targetKey + "，开始在内存中实施补刀...");

                            try {
                                Class<?> targetClazz = XposedHelpers.findClass(className, classLoader);
                                for (java.lang.reflect.Method m : targetClazz.getDeclaredMethods()) {
                                    if (m.getName().equals(methodName)) {
                                        Class<?>[] paramTypes = m.getParameterTypes();
                                        if (paramTypes.length == 1 && paramTypes[0] == JSONObject.class) {

                                            XposedHelpers.findAndHookMethod(targetClazz, methodName, JSONObject.class, new XC_MethodHook() {
                                                @Override
                                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                                    XposedBridge.log("DolbyBeta: [自适应补刀成功触发] 完美覆盖新混淆函数: " + targetKey);
                                                    JSONObject jsonObject = (JSONObject) param.args[0];
                                                    if (jsonObject != null) {
                                                        patchAudioJson(jsonObject);
                                                    }
                                                }
                                            });

                                            XposedBridge.log("DolbyBeta: [自适应] 内存自愈二级 Hook 成功挂载至 -> " + targetKey);
                                            break;
                                        }
                                    }
                                }
                            } catch (Throwable innerEx) {
                                XposedBridge.log("DolbyBeta: [自适应] 内存自愈二级 Hook 挂载失败: " + innerEx.getMessage());
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("DolbyBeta: [自适应] 溯源解析发生异常: " + t.getMessage());
        }
    }
}
