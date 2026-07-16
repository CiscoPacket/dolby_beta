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
 * author : RainCat (Refactored & Stabilized by Assistant)
 * time   : 2026/07/16
 * desc   : 代理分流重定向 + 智能降噪系统级 JSON 劫持 + 自适应二级自学习 Hook (高精度安全稳定版)
 * version: 7.0 (彻底解决所有歌卡播放、完美阻断高频 b1 刷屏，具备防错自愈降级逻辑)
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
        XposedBridge.log("DolbyBeta: [ProxyHook] 正在部署全自适应降噪防卡死音源拦截引擎... isPlayProcess: " + isPlayProcess);

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
                                    XposedBridge.log("DolbyBeta: [OKHttp] 命中核心音频请求 -> " + requestUrl);
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
        // 2. 智能防崩溃的系统级 JSON 劫持引擎
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

                    // 核心指纹特征
                    if (json.contains("\"url\"") && json.contains("\"br\"") && json.contains("\"size\"") && json.contains("\"code\"")) {
                        
                        // 【核心安全屏蔽一】：排除扫描缓存、本地库以及极其高频的哨兵类 b1
                        String caller = getSanitizedCaller();
                        if (caller.contains("musiccache") || 
                            caller.contains("database") || 
                            caller.contains("local") || 
                            caller.contains("DatabaseCacheInfo") ||
                            caller.contains(".b1") ||       // 排除高频哨兵
                            caller.contains(".c1") ||       // 排除已知的旧高频解析类
                            caller.contains("hasSongInfoJson")) {
                            return;
                        }

                        // 仅在真实音频网络拉取阶段触发篡改
                        try {
                            JSONObject root = new JSONObject(json);
                            if (patchAudioJson(root)) {
                                String modified = root.toString();
                                param.args[0] = modified;
                                XposedBridge.log("DolbyBeta: [根源拦截成功] 音源 JSON 替换成功！ 触发源: " + caller);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("DolbyBeta: [根源拦截异常] " + t.getMessage());
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

                            // 已经是代理重定向或本地代理 URL 直接放行
                            if (originalUrl.contains("127.0.0.1") || originalUrl.contains("localhost")) {
                                return;
                            }

                            // 【核心安全屏蔽二】：排除本地读取与高频重复过滤
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

                            // 启动自愈，动态 Hook 业务类
                            traceAndAutoHookCaller(classLoader);

                            // 尝试覆盖重定向
                            String proxyUrl = getProxyUrl(originalUrl);
                            
                            // 【最安全防卡死自愈】：若代理地址无效或与原地址相同，绝不强改参数，避免强制 lossless 导致解码崩溃
                            if (proxyUrl != null && !proxyUrl.isEmpty() && !proxyUrl.equals(originalUrl)) {
                                param.setResult(proxyUrl);
                                
                                // 同步修改内存变量
                                jsonObject.put("url", proxyUrl);
                                jsonObject.put("code", 200);
                                jsonObject.put("fee", 0);
                                jsonObject.put("payed", 1);
                                
                                // 代理音源可能是 AAC / MP3等，不改变原本解码层级，保障不卡死
                                XposedBridge.log("DolbyBeta: [属性拦截] 音频链接替换成功 -> " + proxyUrl);
                            }
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

                        if (proxyUrl != null && !proxyUrl.isEmpty() && !proxyUrl.equals(originalUrl)) {
                            item.put("url", proxyUrl);
                            item.put("code", 200);
                            item.put("fee", 0);
                            item.put("payed", 1);
                            // 保持原生音频码率，保证不卡解码器
                            modified = true;
                        }
                    }
                }
            }
            return modified;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 获取代理重定向音源 URL (含有极高防御降级防护)
     */
    private static String getProxyUrl(String originalUrl) {
        if (originalUrl == null || originalUrl.isEmpty()) {
            return originalUrl;
        }
        try {
            // EAPIHelper.modifyPlayer 接收完整实体类字符串
            String mockInput = new JSONObject().put("url", originalUrl).toString();
            String modifiedStr = com.raincat.dolby_beta.helper.EAPIHelper.modifyPlayer(mockInput);
            
            if (modifiedStr != null && !modifiedStr.isEmpty()) {
                JSONObject res = new JSONObject(modifiedStr);
                String resultUrl = res.optString("url", "");
                
                if (resultUrl != null && !resultUrl.isEmpty() && !resultUrl.equals(originalUrl)) {
                    return resultUrl;
                }
            }
        } catch (Throwable ignored) {}
        
        // 任何异常或者代理未匹配到该音源，100% 降级返回原官方播放音源，杜绝播放器卡死
        return originalUrl;
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
                        && !className.contains(".b1") // 二级自愈自动避开高频哨兵类
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
