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
import java.util.List;

import javax.net.ssl.SSLSocketFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * <pre>
 * author : RainCat (Refactored by Assistant)
 * time   : 2026/07/16
 * desc   : 代理分流重定向 + 终极安全 JSON 劫持 (拨乱反正版)
 * version: 9.0 (解除 b1 屏蔽，去除危险音质强制覆写，根治卡死问题)
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

    public ProxyHook(Context context, boolean isPlayProcess) {
        XposedBridge.log("DolbyBeta: [ProxyHook] 部署最终安全版拦截引擎... isPlayProcess: " + isPlayProcess);

        // ==========================================
        // 1. 原生 OkHttp 网络层代理路由分发
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

        // 屏蔽 Cronet 干扰
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

        // 调起本地 Node 代理服务
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
        // 2. 纯净安全的 JSON 内存劫持引擎
        // ==========================================
        initJsonHijack();
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
     * 系统级 JSONObject 安全无损劫持
     */
    private void initJsonHijack() {
        try {
            XposedHelpers.findAndHookConstructor(JSONObject.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String json = (String) param.args[0];
                    if (json == null) return;

                    // 核心指纹特征校验
                    if (json.contains("\"url\"") && json.contains("\"br\"") && json.contains("\"size\"") && json.contains("\"code\"")) {
                        
                        // 【只屏蔽垃圾缓存，重新放行 b1/c1 等业务解析类】
                        String caller = getSanitizedCaller();
                        if (caller.contains("musiccache") || 
                            caller.contains("database") || 
                            caller.contains("local") || 
                            caller.contains("DatabaseCacheInfo") || 
                            caller.contains("hasSongInfoJson")) {
                            return; // 明确属于本地缓存扫描，忽略不计
                        }

                        // 拦截到了真实的业务级网络响应
                        try {
                            JSONObject root = new JSONObject(json);
                            if (patchAudioJson(root)) {
                                param.args[0] = root.toString();
                                XposedBridge.log("DolbyBeta: [音源拦截成功] 触发源 -> " + caller);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("DolbyBeta: [拦截异常] " + t.getMessage());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("DolbyBeta: JSONObject 构造方法 Hook 失败: " + t.getMessage());
        }
    }

    /**
     * 最安全的 JSON 音源篡改机制（绝不干扰底层解码器）
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
                        
                        // 忽略空地址
                        if (originalUrl == null || originalUrl.isEmpty()) {
                            continue;
                        }

                        String proxyUrl = getProxyUrl(originalUrl);

                        // 只有获取到了有效的代理新链接，且链接不同时，才进行修改
                        if (proxyUrl != null && !proxyUrl.isEmpty() && !proxyUrl.equals(originalUrl)) {
                            item.put("url", proxyUrl);
                            
                            // 剥离 VIP 和试听限制
                            item.put("code", 200);
                            item.put("fee", 0);
                            item.put("payed", 1);
                            item.put("flag", 0);
                            
                            // 强力剥离试听信息节点
                            if (item.has("freeTrialInfo")) {
                                item.put("freeTrialInfo", JSONObject.NULL);
                            }
                            if (item.has("freeTimeTrialPrivilege")) {
                                item.put("freeTimeTrialPrivilege", JSONObject.NULL);
                            }

                            // 【安全警告】绝不触碰 level, br, encodeType, md5。让播放器按实际流格式解码，彻底根治卡死！
                            
                            XposedBridge.log("DolbyBeta: [解锁完成] 原始链接: " + originalUrl);
                            XposedBridge.log("DolbyBeta: [解锁完成] 代理链接: " + proxyUrl);
                            modified = true;
                        }
                    }
                }
            }
            return modified;
        } catch (Throwable t) {
            XposedBridge.log("DolbyBeta: [patchAudioJson] 修改过程发生异常: " + t.getMessage());
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
            
            if (modifiedStr != null && !modifiedStr.isEmpty()) {
                JSONObject res = new JSONObject(modifiedStr);
                return res.optString("url", originalUrl);
            }
        } catch (Throwable ignored) {}
        
        return originalUrl;
    }

    /**
     * 获取清洗过的触发源类名
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
}