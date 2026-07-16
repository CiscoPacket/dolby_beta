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
 * author : RainCat (Refactored & Merged by Assistant)
 * time   : 2026/07/16
 * desc   : 双轨制闭环代理拦截引擎 —— OkHttp 路由重定向 + 系统级 JSON 劫持 + 动态自学习自愈 Hook
 * version: 3.5 (极致终身免更新版)
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

    // 缓存已经动态自适应 Hook 过的混淆类与方法，防止重复注入
    private static final Set<String> hookedMethods = new HashSet<>();

    public ProxyHook(Context context, boolean isPlayProcess) {
        XposedBridge.log("DolbyBeta: [免更新系统级双模拦截引擎] 正在启动...");

        // ==========================================
        // 1. 原生 OkHttp 拦截重定向逻辑
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
                        for (String url : whiteUrlList) {
                            if (urlObj != null && urlObj.toString().contains(url)) {
                                setProxy(context, client);
                                break;
                            }
                        }
                    }
                }
            });
            XposedBridge.log("DolbyBeta: OkHttp 网络层代理注入器挂载成功！");
        } else {
            XposedBridge.log("DolbyBeta: 警告！未找到 okhttp3.RealCall 类，网络层代理注入器未挂载。");
        }

        // 绕过网易云 Cronet 干扰
        Class<?> okHttpClientBuilderClass = findClassIfExists("okhttp3.OkHttpClient$Builder", context.getClassLoader());
        if (okHttpClientBuilderClass != null) {
            XposedBridge.hookAllMethods(okHttpClientBuilderClass, "addInterceptor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if (param.args[0] != null && param.args[0].getClass().getName().contains("com.netease.cloudmusic.network.cronet")) {
                        param.setResult(param.thisObject);
                    }
                }
            });
        }

        // 调起 UnblockNeteaseMusic 本地 Node 服务进程
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
        // 2. 注入核心黑科技：系统级 JSON 劫持器 (方案 A + 方案 B)
        // ==========================================
        initJsonHijack(context.getClassLoader());
    }

    /**
     * 设置本地代理分流
     */
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
     * 系统级 JSON 无缝篡改及自适应学习引擎
     */
    private void initJsonHijack(final ClassLoader classLoader) {
        // [维度 A]：系统 JSONObject 构造方法拦截器 (即使密文网络解密，一旦加载直接篡改)
        try {
            XposedHelpers.findAndHookConstructor(JSONObject.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String json = (String) param.args[0];
                    if (json == null) return;

                    // 音源 API 特征指纹校验
                    if (json.contains("\"url\"") && json.contains("\"br\"") && json.contains("\"size\"") && json.contains("\"code\"")) {
                        XposedBridge.log("DolbyBeta: [根源劫持] 捕获到目标音源 JSON 密文解密产物！");
                        traceCallerAndLog("JSONObject.<init>");

                        try {
                            JSONObject root = new JSONObject(json);
                            if (patchAudioJson(root)) {
                                param.args[0] = root.toString();
                                XposedBridge.log("DolbyBeta: [根源劫持] 音源 JSON 解锁篡改完成并覆写原始构造器！");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("DolbyBeta: [根源劫持] 覆写失败: " + t.getMessage());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("DolbyBeta: JSONObject 构造方法 Hook 失败: " + t.getMessage());
        }

        // [维度 B]：系统属性 optString 提取器 + 动态混淆分析器
        try {
            XposedHelpers.findAndHookMethod(JSONObject.class, "optString", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if ("url".equals(key)) {
                        JSONObject jsonObject = (JSONObject) param.thisObject;

                        if (jsonObject.has("br") && jsonObject.has("size") && jsonObject.has("code")) {
                            String originalUrl = (String) param.getResult();

                            if (originalUrl == null || originalUrl.isEmpty() || !originalUrl.contains("music.126.net")) {
                                return;
                            }

                            XposedBridge.log("DolbyBeta: [属性劫持] 检测到业务正在读取音频 url 属性，自动开启溯源注入...");
                            
                            // 自学习自适应 Hook，抓取当前版本最新混淆类
                            traceAndAutoHookCaller(classLoader);

                            // 原地改掉返回值
                            String proxyUrl = getProxyUrl(originalUrl);
                            param.setResult(proxyUrl);

                            // 同步内存数据
                            jsonObject.put("url", proxyUrl);
                            jsonObject.put("code", 200);
                            jsonObject.put("fee", 0);
                            jsonObject.put("payed", 1);
                            jsonObject.put("level", "lossless");
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
        // 调用 DolbyBeta 插件原本的内置 EAPIHelper 代理
        try {
            String modifiedStr = com.raincat.dolby_beta.helper.EAPIHelper.modifyPlayer(new JSONObject().put("url", originalUrl).toString());
            JSONObject res = new JSONObject(modifiedStr);
            return res.optString("url", originalUrl);
        } catch (Throwable e) {
            return originalUrl;
        }
    }

    private static void traceCallerAndLog(String tag) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            if (className.startsWith("com.netease.cloudmusic")
                    && !className.contains("JSONObject")
                    && !className.contains("ProxyHook")) {
                XposedBridge.log("DolbyBeta: [" + tag + "] 触发源调用栈 -> "
                        + className + "#" + element.getMethodName()
                        + " (Line: " + element.getLineNumber() + ")");
                break;
            }
        }
    }

    /**
     * 堆栈溯源并自适应在运行时 Hook 最新混淆目标
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
                        && !className.contains("java.lang")) {

                    String targetKey = className + "#" + methodName;

                    synchronized (hookedMethods) {
                        if (!hookedMethods.contains(targetKey)) {
                            hookedMethods.add(targetKey);

                            XposedBridge.log("DolbyBeta: [自适应自愈] 捕获到当前版本业务解析函数: " + targetKey + "，准备实施动态二级保护...");

                            try {
                                Class<?> targetClazz = XposedHelpers.findClass(className, classLoader);
                                for (java.lang.reflect.Method m : targetClazz.getDeclaredMethods()) {
                                    if (m.getName().equals(methodName)) {
                                        Class<?>[] paramTypes = m.getParameterTypes();
                                        if (paramTypes.length == 1 && paramTypes[0] == JSONObject.class) {

                                            XposedHelpers.findAndHookMethod(targetClazz, methodName, JSONObject.class, new XC_MethodHook() {
                                                @Override
                                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                                    XposedBridge.log("DolbyBeta: [自适应注入成功触发] 已阻断并锁定当前新混淆点: " + targetKey);
                                                    JSONObject jsonObject = (JSONObject) param.args[0];
                                                    if (jsonObject != null) {
                                                        patchAudioJson(jsonObject);
                                                    }
                                                }
                                            });

                                            XposedBridge.log("DolbyBeta: [自适应自愈] 二级自适应 Hook 成功锁定至 -> " + targetKey);
                                            break;
                                        }
                                    }
                                }
                            } catch (Throwable innerEx) {
                                XposedBridge.log("DolbyBeta: [自适应自愈] 部署自愈 Hook 失败: " + innerEx.getMessage());
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("DolbyBeta: [自适应自愈] 溯源检测异常: " + t.getMessage());
        }
    }
}
