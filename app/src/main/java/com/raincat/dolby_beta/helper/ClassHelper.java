package com.raincat.dolby_beta.helper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import com.annimon.stream.Stream;

import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;
import static de.robv.android.xposed.XposedHelpers.findMethodsByExactParameters;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 类加载与跨版本动态适配 (DexKit)
 *     version: 2.0
 * </pre>
 */
public class ClassHelper {
    private static ClassLoader classLoader = null;
    private static int versionCode = 0;
    private static boolean isDexKitLoaded = false;
    private static volatile boolean isInitialized = false;

    private static final String PREF_NAME = "dolby_dexkit_cache";
    private static final String KEY_INTERCEPTOR = "interceptor_class_";
    private static final String KEY_HTTP_RESPONSE = "http_response_class_";
    private static final String KEY_HTTP_URL = "http_url_class_";
    private static final String KEY_HTTP_PARAMS = "http_params_class_";
    private static final String KEY_DOWNLOAD_TRANSFER = "download_transfer_class_";
    private static final String KEY_SIDEBAR_ITEM = "sidebar_item_class_";
    private static final String KEY_AD = "ad_class_";
    private static final String KEY_COOKIE = "cookie_class_";
    private static final String KEY_COOKIE_ABSTRACT = "cookie_abstract_class_";
    private static final String KEY_BOTTOM_TAB = "bottom_tab_class_";
    private static final String KEY_TAB_MANAGER = "tab_manager_class_";
    private static final String KEY_COMMENT_REQUEST_BUILDER = "comment_request_builder_class_";
    private static final String KEY_PLAYER_CUSTOM_BG = "player_custom_bg_class_";
    private static final String KEY_PLAYER_CHILD_BG = "player_child_bg_class_";

    public interface OnCacheClassListener {
        void onGet();
    }

    public static synchronized void init(final Context context, final int version) {
        if (classLoader == null && context != null) {
            classLoader = context.getClassLoader();
            versionCode = version;
        }
    }

    public static synchronized void getCacheClassList(final Context context, final int version, final OnCacheClassListener listener) {
        init(context, version);

        if (isInitialized) {
            listener.onGet();
            return;
        }

        if (SettingHelper.getInstance().isEnable(SettingHelper.dex_key) && loadFromCache(context, version)) {
            isInitialized = true;
            XposedBridge.log("[dolby_beta] Loaded hook classes from cache for version " + version);
            listener.onGet();
            return;
        }

        new Thread(() -> {
            try {
                resolveClasses(context, version);
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] Failed to resolve hook classes: " + t.getMessage());
                t.printStackTrace();
            } finally {
                isInitialized = true;
                listener.onGet();
            }
        }).start();
    }

    private static boolean loadFromCache(Context context, int version) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String respName = sp.getString(KEY_HTTP_RESPONSE + version, null);
            String interceptorName = sp.getString(KEY_INTERCEPTOR + version, null);
            String transferName = sp.getString(KEY_DOWNLOAD_TRANSFER + version, null);

            if (respName == null || interceptorName == null || transferName == null) {
                return false;
            }

            Class<?> respClz = findClassIfExists(respName, classLoader);
            Class<?> interceptorClz = findClassIfExists(interceptorName, classLoader);
            Class<?> transferClz = findClassIfExists(transferName, classLoader);

            if (respClz == null || interceptorClz == null || transferClz == null) {
                return false;
            }

            HttpResponse.clazz = respClz;
            HttpInterceptor.clazz = interceptorClz;
            DownloadTransfer.clazz = transferClz;

            String urlName = sp.getString(KEY_HTTP_URL + version, null);
            if (urlName != null) HttpUrl.clazz = findClassIfExists(urlName, classLoader);

            String paramsName = sp.getString(KEY_HTTP_PARAMS + version, null);
            if (paramsName != null) HttpParams.clazz = findClassIfExists(paramsName, classLoader);

            String sidebarName = sp.getString(KEY_SIDEBAR_ITEM + version, null);
            if (sidebarName != null) SidebarItem.clazz = findClassIfExists(sidebarName, classLoader);

            String adName = sp.getString(KEY_AD + version, null);
            if (adName != null) Ad.clazz = findClassIfExists(adName, classLoader);

            String cookieName = sp.getString(KEY_COOKIE + version, null);
            if (cookieName != null) Cookie.clazz = findClassIfExists(cookieName, classLoader);

            String cookieAbstractName = sp.getString(KEY_COOKIE_ABSTRACT + version, null);
            if (cookieAbstractName != null) Cookie.abstractClazz = findClassIfExists(cookieAbstractName, classLoader);

            String bottomTabName = sp.getString(KEY_BOTTOM_TAB + version, null);
            if (bottomTabName != null) BottomTabView.clazz = findClassIfExists(bottomTabName, classLoader);

            String tabManagerName = sp.getString(KEY_TAB_MANAGER + version, null);
            if (tabManagerName != null) TabManager.clazz = findClassIfExists(tabManagerName, classLoader);

            String crbName = sp.getString(KEY_COMMENT_REQUEST_BUILDER + version, null);
            if (crbName != null) CommentRequestBuilder.clazz = findClassIfExists(crbName, classLoader);

            String pcbName = sp.getString(KEY_PLAYER_CUSTOM_BG + version, null);
            if (pcbName != null) PlayerCustomBackground.clazz = findClassIfExists(pcbName, classLoader);

            String pcbChildName = sp.getString(KEY_PLAYER_CHILD_BG + version, null);
            if (pcbChildName != null) PlayerChildBackground.clazz = findClassIfExists(pcbChildName, classLoader);

            return true;
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] loadFromCache error: " + t.getMessage());
            return false;
        }
    }

    private static void saveToCache(Context context, int version) {
        try {
            SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
            if (HttpResponse.clazz != null) editor.putString(KEY_HTTP_RESPONSE + version, HttpResponse.clazz.getName());
            if (HttpInterceptor.clazz != null) editor.putString(KEY_INTERCEPTOR + version, HttpInterceptor.clazz.getName());
            if (HttpUrl.clazz != null) editor.putString(KEY_HTTP_URL + version, HttpUrl.clazz.getName());
            if (HttpParams.clazz != null) editor.putString(KEY_HTTP_PARAMS + version, HttpParams.clazz.getName());
            if (DownloadTransfer.clazz != null) editor.putString(KEY_DOWNLOAD_TRANSFER + version, DownloadTransfer.clazz.getName());
            if (SidebarItem.clazz != null) editor.putString(KEY_SIDEBAR_ITEM + version, SidebarItem.clazz.getName());
            if (Ad.clazz != null) editor.putString(KEY_AD + version, Ad.clazz.getName());
            if (Cookie.clazz != null) editor.putString(KEY_COOKIE + version, Cookie.clazz.getName());
            if (Cookie.abstractClazz != null) editor.putString(KEY_COOKIE_ABSTRACT + version, Cookie.abstractClazz.getName());
            if (BottomTabView.clazz != null) editor.putString(KEY_BOTTOM_TAB + version, BottomTabView.clazz.getName());
            if (TabManager.clazz != null) editor.putString(KEY_TAB_MANAGER + version, TabManager.clazz.getName());
            if (CommentRequestBuilder.clazz != null) editor.putString(KEY_COMMENT_REQUEST_BUILDER + version, CommentRequestBuilder.clazz.getName());
            if (PlayerCustomBackground.clazz != null) editor.putString(KEY_PLAYER_CUSTOM_BG + version, PlayerCustomBackground.clazz.getName());
            if (PlayerChildBackground.clazz != null) editor.putString(KEY_PLAYER_CHILD_BG + version, PlayerChildBackground.clazz.getName());
            editor.apply();
            XposedBridge.log("[dolby_beta] Saved hook classes to cache for version " + version);
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] saveToCache error: " + t.getMessage());
        }
    }

    private static void resolveClasses(Context context, int version) {
        XposedBridge.log("[dolby_beta] Resolving classes dynamically for version " + version + "...");

        // 1. Fast heuristics via stable classes & reflection
        resolveFastReflection(context);

        // 2. If any core class is missing, leverage DexKit dynamic Dex analysis
        if (HttpResponse.clazz == null || HttpInterceptor.clazz == null || DownloadTransfer.clazz == null || Ad.clazz == null
                || TabManager.clazz == null || CommentRequestBuilder.clazz == null || PlayerCustomBackground.clazz == null
                || PlayerChildBackground.clazz == null) {
            resolveWithDexKit(context);
        }

        // 3. Fallbacks and notifications
        if (Cookie.clazz == null) {
            resolveCookie(context);
        }
        if (HttpResponse.clazz == null) {
            MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
        }
        if (DownloadTransfer.clazz == null) {
            MessageHelper.sendNotification(context, MessageHelper.transferClassNotFoundCode);
        }
        if (SidebarItem.clazz == null) {
            MessageHelper.sendNotification(context, MessageHelper.sidebarClassNotFoundCode);
        }

        // 4. Save results to cache if enabled
        if (SettingHelper.getInstance().isEnable(SettingHelper.dex_key)) {
            saveToCache(context, version);
        }
    }

    private static void resolveFastReflection(Context context) {
        try {
            // CookieStore
            Cookie.clazz = findClassIfExists("com.netease.cloudmusic.network.cookie.store.CloudMusicCookieStore", classLoader);
            Cookie.abstractClazz = findClassIfExists("com.netease.cloudmusic.network.cookie.store.AbsCookieStore", classLoader);

            // DownloadTransfer
            DownloadTransfer.clazz = findClassIfExists("com.netease.cloudmusic.module.transfer.download.r", classLoader);

            // SidebarItem
            SidebarItem.clazz = findClassIfExists("com.netease.cloudmusic.music.biz.sidebar.account.j", classLoader);

            // TabManager (th0.o fallback)
            if (TabManager.clazz == null) {
                TabManager.clazz = findClassIfExists("th0.o", classLoader);
            }

            // CommentRequestBuilder (x71.g0 fallback)
            if (CommentRequestBuilder.clazz == null) {
                CommentRequestBuilder.clazz = findClassIfExists("x71.g0", classLoader);
            }

            // PlayerCustomBackground (PlayerCustomBackgroundView fallback)
            if (PlayerCustomBackground.clazz == null) {
                PlayerCustomBackground.clazz = findClassIfExists("com.netease.cloudmusic.module.playerstyle.PlayerCustomBackgroundView", classLoader);
            }

            // PlayerChildBackground (PlayerChildBackgroundView fallback)
            if (PlayerChildBackground.clazz == null) {
                PlayerChildBackground.clazz = findClassIfExists("com.netease.cloudmusic.ui.PlayerChildBackgroundView", classLoader);
            }

            // HttpInterceptor: com.netease.cloudmusic.network.interceptor.q
            Class<?> qClass = findClassIfExists("com.netease.cloudmusic.network.interceptor.q", classLoader);
            if (qClass != null) {
                HttpInterceptor.clazz = qClass;
                // Find method b(HttpResponse, RequestBase) -> boolean
                for (Method m : qClass.getDeclaredMethods()) {
                    if ("b".equals(m.getName()) && m.getParameterTypes().length == 2) {
                        Class<?>[] p = m.getParameterTypes();
                        if (HttpResponse.clazz == null) {
                            HttpResponse.clazz = p[0];
                        }
                        break;
                    }
                }
            }

            // Inspect HttpResponse fields to find HttpUrl and HttpParams
            if (HttpResponse.clazz != null) {
                resolveHttpUrlAndParamsFromResponse();
            }

            // Ad class candidates
            String[] adCandidates = new String[]{"j40.d", "h40.d", "v60.d"};
            for (String candidate : adCandidates) {
                Class<?> c = findClassIfExists(candidate, classLoader);
                if (c != null) {
                    boolean hasAdReturn = false;
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getReturnType().getName().contains("com.netease.cloudmusic.meta.Ad")) {
                            hasAdReturn = true;
                            break;
                        }
                    }
                    if (hasAdReturn) {
                        Ad.clazz = c;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] resolveFastReflection error: " + t.getMessage());
        }
    }

    private static void resolveHttpUrlAndParamsFromResponse() {
        if (HttpResponse.clazz == null) return;
        try {
            for (Field f : HttpResponse.clazz.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (!ft.isPrimitive() && !ft.getName().startsWith("okhttp3.") && !ft.getName().startsWith("java.")) {
                    boolean hasUri = false;
                    for (Field subF : ft.getDeclaredFields()) {
                        if (subF.getType() == Uri.class) {
                            hasUri = true;
                            break;
                        }
                    }
                    if (hasUri) {
                        HttpUrl.clazz = ft;
                        for (Field subF : ft.getDeclaredFields()) {
                            Class<?> subFt = subF.getType();
                            if (!subFt.isPrimitive() && !subFt.getName().startsWith("java.") && !subFt.getName().startsWith("okhttp3.") && !subFt.getName().startsWith("android.")) {
                                for (Field pF : subFt.getDeclaredFields()) {
                                    if (pF.getType() == LinkedHashMap.class) {
                                        HttpParams.clazz = subFt;
                                        break;
                                    }
                                }
                            }
                            if (HttpParams.clazz != null) break;
                        }
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] resolveHttpUrlAndParams error: " + t.getMessage());
        }
    }

    private static synchronized boolean loadDexKit(Context context) {
        if (isDexKitLoaded) return true;
        try {
            System.loadLibrary("dexkit");
            isDexKitLoaded = true;
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] System.loadLibrary(\"dexkit\") failed: " + t.getMessage());
        }

        try {
            String modulePath = ScriptHelper.modulePath;
            if (TextUtils.isEmpty(modulePath)) {
                try {
                    modulePath = context.getPackageManager().getApplicationInfo("com.raincat.dolby_beta", 0).sourceDir;
                } catch (Throwable ignored) {}
            }
            if (!TextUtils.isEmpty(modulePath) && new File(modulePath).exists()) {
                File dir = new File(context.getFilesDir(), "dexkit_lib");
                if (!dir.exists()) dir.mkdirs();
                File soFile = new File(dir, "libdexkit.so");

                try (ZipFile zf = new ZipFile(modulePath)) {
                    String[] abis = Build.SUPPORTED_ABIS;
                    ZipEntry entry = null;
                    if (abis != null) {
                        for (String abi : abis) {
                            entry = zf.getEntry("lib/" + abi + "/libdexkit.so");
                            if (entry != null) break;
                        }
                    }
                    if (entry == null) {
                        entry = zf.getEntry("lib/arm64-v8a/libdexkit.so");
                    }
                    if (entry == null) {
                        entry = zf.getEntry("lib/armeabi-v7a/libdexkit.so");
                    }
                    if (entry != null) {
                        try (InputStream is = zf.getInputStream(entry);
                             FileOutputStream fos = new FileOutputStream(soFile)) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = is.read(buf)) > 0) {
                                fos.write(buf, 0, len);
                            }
                        }
                        System.load(soFile.getAbsolutePath());
                        isDexKitLoaded = true;
                        XposedBridge.log("[dolby_beta] Successfully loaded libdexkit.so manually from " + soFile.getAbsolutePath());
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] Manual load libdexkit.so failed: " + t.getMessage());
        }
        return false;
    }

    private static void resolveWithDexKit(Context context) {
        if (!loadDexKit(context)) {
            XposedBridge.log("[dolby_beta] DexKit native library not available, skipping DexKit search");
            return;
        }

        String apkPath = context.getApplicationInfo().sourceDir;
        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            XposedBridge.log("[dolby_beta] DexKitBridge created for " + apkPath);

            // 1. HttpInterceptor
            if (HttpInterceptor.clazz == null) {
                ClassDataList list = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.network.interceptor")
                        .matcher(ClassMatcher.create()
                                .addInterface("okhttp3.Interceptor")
                                .addMethod(MethodMatcher.create()
                                        .returnType("android.util.Pair")
                                        .paramCount(5))));
                if (!list.isEmpty()) {
                    HttpInterceptor.clazz = list.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found HttpInterceptor: " + HttpInterceptor.clazz.getName());
                }
            }

            // 2. HttpResponse from HttpInterceptor.b or by features
            if (HttpResponse.clazz == null && HttpInterceptor.clazz != null) {
                for (Method m : HttpInterceptor.clazz.getDeclaredMethods()) {
                    if ("b".equals(m.getName()) && m.getParameterTypes().length == 2) {
                        HttpResponse.clazz = m.getParameterTypes()[0];
                        XposedBridge.log("[dolby_beta] Extracted HttpResponse from HttpInterceptor: " + HttpResponse.clazz.getName());
                        break;
                    }
                }
            }

            if (HttpResponse.clazz == null) {
                ClassDataList respList = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                                .superClass("java.lang.Object")
                                .addFieldForType("okhttp3.Response")
                                .addMethod(MethodMatcher.create().returnType("okhttp3.ResponseBody"))));
                for (ClassData cd : respList) {
                    Class<?> c = cd.getInstance(classLoader);
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getExceptionTypes().length == 2) {
                            HttpResponse.clazz = c;
                            XposedBridge.log("[dolby_beta] DexKit found HttpResponse: " + c.getName());
                            break;
                        }
                    }
                    if (HttpResponse.clazz != null) break;
                }
            }

            if (HttpUrl.clazz == null || HttpParams.clazz == null) {
                resolveHttpUrlAndParamsFromResponse();
            }

            // 3. DownloadTransfer
            if (DownloadTransfer.clazz == null) {
                MethodDataList dtList = bridge.findMethod(FindMethod.create()
                        .searchPackages("com.netease.cloudmusic.module.transfer.download")
                        .matcher(MethodMatcher.create()
                                .returnType("void")
                                .paramTypes("java.io.File", "java.io.File", "long", "java.lang.Object[]")));
                if (!dtList.isEmpty()) {
                    MethodData md = dtList.get(0);
                    DownloadTransfer.clazz = md.getClassInstance(classLoader);
                    DownloadTransfer.checkMd5Method = md.getMethodInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found DownloadTransfer: " + DownloadTransfer.clazz.getName());
                }
            }

            // 4. SidebarItem
            if (SidebarItem.clazz == null) {
                ClassDataList sbList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.music.biz.sidebar.account", "com.netease.cloudmusic.module.account")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().returnType("java.lang.Throwable"))
                                .addMethod(MethodMatcher.create().returnType("java.util.List"))));
                if (!sbList.isEmpty()) {
                    SidebarItem.clazz = sbList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found SidebarItem: " + SidebarItem.clazz.getName());
                }
            }

            // 5. Ad
            if (Ad.clazz == null) {
                ClassDataList adList = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create()
                                        .returnType("com.netease.cloudmusic.meta.Ad")
                                        .paramTypes("org.json.JSONObject"))));
                for (ClassData cd : adList) {
                    if ("d".equals(cd.getSimpleName())) {
                        Class<?> c = cd.getInstance(classLoader);
                        boolean hasVideo = false;
                        for (Method m : c.getDeclaredMethods()) {
                            if (m.getReturnType().getName().contains("VideoAdInfo")) {
                                hasVideo = true;
                                break;
                            }
                            for (Class<?> p : m.getParameterTypes()) {
                                if (p.getName().contains("VideoAdInfo")) {
                                    hasVideo = true;
                                    break;
                                }
                            }
                        }
                        if (hasVideo) {
                            Ad.clazz = c;
                            XposedBridge.log("[dolby_beta] DexKit found Ad parser: " + c.getName());
                            break;
                        }
                    }
                }
            }

            // 6. TabManager (th0.o)
            if (TabManager.clazz == null) {
                ClassDataList tmList = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().returnType("java.lang.String[]").paramCount(0))
                                .addMethod(MethodMatcher.create().returnType("java.util.concurrent.CopyOnWriteArrayList"))
                                .addMethod(MethodMatcher.create().returnType("java.lang.String").paramCount(1))
                                .addMethod(MethodMatcher.create().returnType("int").paramCount(1))));
                if (!tmList.isEmpty()) {
                    TabManager.clazz = tmList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found TabManager: " + TabManager.clazz.getName());
                }
            }

            // 7. CommentRequestBuilder (x71.g0)
            if (CommentRequestBuilder.clazz == null) {
                ClassDataList crbList = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create()
                                        .returnType("com.netease.cloudmusic.module.fastload.core.PageCacheRequest$ReqParams")
                                        .paramTypes("com.netease.cloudmusic.music.biz.comment.meta.CommentRequestData"))));
                if (!crbList.isEmpty()) {
                    CommentRequestBuilder.clazz = crbList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found CommentRequestBuilder: " + CommentRequestBuilder.clazz.getName());
                }
            }

            // 8. PlayerCustomBackground (PlayerCustomBackgroundView)
            if (PlayerCustomBackground.clazz == null) {
                ClassDataList pcbList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.module.playerstyle", "com.netease.cloudmusic.ui")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().name("getCurrentBgType").returnType("java.lang.String"))
                                .addMethod(MethodMatcher.create().name("getLayoutId").returnType("int"))));
                if (!pcbList.isEmpty()) {
                    PlayerCustomBackground.clazz = pcbList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found PlayerCustomBackground: " + PlayerCustomBackground.clazz.getName());
                }
            }

            // 9. PlayerChildBackground (PlayerChildBackgroundView)
            if (PlayerChildBackground.clazz == null) {
                ClassDataList pcbChildList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.ui")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().name("setBgTopColor").paramCount(1))
                                .addMethod(MethodMatcher.create().name("calculateHSL").returnType("float[]"))));
                if (!pcbChildList.isEmpty()) {
                    PlayerChildBackground.clazz = pcbChildList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found PlayerChildBackground: " + PlayerChildBackground.clazz.getName());
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] DexKit dynamic scanning failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private static void resolveCookie(Context context) {
        if (Cookie.clazz == null) {
            Cookie.clazz = findClassIfExists("com.netease.cloudmusic.network.cookie.store.CloudMusicCookieStore", classLoader);
        }
        if (Cookie.abstractClazz == null) {
            Cookie.abstractClazz = findClassIfExists("com.netease.cloudmusic.network.cookie.store.AbsCookieStore", classLoader);
        }
    }

    public static List<String> getFilteredClasses(Pattern pattern, Comparator<String> comparator) {
        return Collections.emptyList();
    }

    public static class Cookie {
        private static Class<?> clazz, abstractClazz;

        public static String getCookie(Context context) {
            if (clazz == null) {
                resolveCookie(context);
            }
            if (clazz == null) {
                MessageHelper.sendNotification(context, MessageHelper.cookieClassNotFoundCode);
                return "";
            }

            Object cookieString = null;
            try {
                Method[] staticMethods = XposedHelpers.findMethodsByExactParameters(clazz, clazz);
                Object cookie = null;
                if (staticMethods.length != 0) {
                    cookie = XposedHelpers.callStaticMethod(clazz, staticMethods[0].getName());
                } else {
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == clazz && m.getParameterTypes().length == 0) {
                            m.setAccessible(true);
                            cookie = m.invoke(null);
                            break;
                        }
                    }
                }

                if (cookie != null) {
                    Class<?> searchClz = abstractClazz != null ? abstractClazz : clazz;
                    for (String methodName : new String[]{"getLoginCookie", "getLoginCookieValue", "getAllCookies"}) {
                        try {
                            Method m = searchClz.getDeclaredMethod(methodName);
                            m.setAccessible(true);
                            Object res = m.invoke(cookie);
                            if (res != null && !TextUtils.isEmpty(res.toString())) {
                                String s = res.toString();
                                if (s.contains("MUSIC_U=")) return s;
                                return "MUSIC_U=" + s;
                            }
                        } catch (Throwable ignored) {}
                    }

                    for (Method m : searchClz.getDeclaredMethods()) {
                        if (m.getParameterTypes().length == 0 && Modifier.isPublic(m.getModifiers()) && m.getReturnType() == String.class) {
                            m.setAccessible(true);
                            cookieString = m.invoke(cookie);
                            if (cookieString != null && !TextUtils.isEmpty(cookieString.toString())) {
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                XposedBridge.log("[dolby_beta] Cookie.getCookie error: " + e.getMessage());
            }

            return cookieString != null ? "MUSIC_U=" + cookieString : "";
        }
    }

    public static class DownloadTransfer {
        private static Class<?> clazz;
        private static Method checkMd5Method;
        private static Method checkDownloadStatusMethod;

        public static Method getCheckMd5Method(Context context) {
            if (checkMd5Method == null && clazz != null) {
                for (Method m : clazz.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() == void.class && p.length == 4 && p[0] == File.class && p[1] == File.class) {
                        checkMd5Method = m;
                        break;
                    }
                }
            }
            if (checkMd5Method == null) {
                MessageHelper.sendNotification(context, MessageHelper.transferClassNotFoundCode);
            }
            return checkMd5Method;
        }

        public static Method getCheckDownloadStatusMethod(Context context) {
            if (checkDownloadStatusMethod == null && clazz != null) {
                for (Method m : clazz.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() == long.class && p.length == 5 && p[1] == int.class && p[3] == File.class && p[4] == long.class) {
                        checkDownloadStatusMethod = m;
                        break;
                    }
                }
            }
            if (checkDownloadStatusMethod == null) {
                MessageHelper.sendNotification(context, MessageHelper.transferClassNotFoundCode);
            }
            return checkDownloadStatusMethod;
        }
    }

    public static class MainActivitySuperClass {
        private static Class<?> clazz;
        private static List<Method> methods;
        private static Method method;

        static void getClazz(Context context) {
            if (clazz == null) {
                Class<?> mainActivityClass = findClassIfExists("com.netease.cloudmusic.activity.MainActivity", context.getClassLoader());
                if (mainActivityClass != null) {
                    clazz = mainActivityClass.getSuperclass();
                }
            }
        }

        public static List<Method> getTabItemStringMethods(Context context) {
            if (clazz == null)
                getClazz(context);
            if (methods == null && clazz != null) {
                List<Method> methodList = Arrays.asList(clazz.getDeclaredMethods());
                methods = Stream.of(methodList)
                        .filter(m -> m.getParameterTypes().length >= 1)
                        .filter(m -> m.getReturnType() == void.class)
                        .filter(m -> m.getParameterTypes()[0] == String[].class)
                        .filter(m -> Modifier.isPublic(m.getModifiers()))
                        .toList();
            }
            return methods;
        }

        public static Method getViewPagerInitMethod(Context context) {
            if (method == null) {
                try {
                    Class<?> mainActivityClass = findClassIfExists("com.netease.cloudmusic.activity.MainActivity", context.getClassLoader());
                    if (mainActivityClass != null) {
                        List<Method> methodList = Arrays.asList(mainActivityClass.getDeclaredMethods());
                        method = Stream.of(methodList)
                                .filter(m -> m.getParameterTypes().length == 1)
                                .filter(m -> m.getReturnType() == void.class)
                                .filter(m -> m.getParameterTypes()[0] == Intent.class)
                                .filter(m -> Modifier.isPrivate(m.getModifiers()))
                                .findFirst()
                                .orElse(null);
                    }
                } catch (Exception e) {
                    MessageHelper.sendNotification(context, MessageHelper.tabClassNotFoundCode);
                }
            }
            return method;
        }
    }

    public static class BottomTabView {
        private static Class<?> clazz;
        private static Method initMethod, refreshMethod;

        public static Class<?> getClazz(Context context) {
            return clazz;
        }

        public static Method getTabInitMethod(Context context) {
            if (initMethod == null && clazz != null) {
                Method[] methods = findMethodsByExactParameters(clazz, ArrayList.class);
                if (methods.length != 0)
                    initMethod = methods[0];
            }
            return initMethod;
        }

        public static Method getTabRefreshMethod(Context context) {
            if (refreshMethod == null && clazz != null) {
                Method[] methods = findMethodsByExactParameters(clazz, void.class, List.class);
                if (methods.length != 0)
                    refreshMethod = methods[0];
            }
            return refreshMethod;
        }
    }

    public static class SidebarItem {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                clazz = findClassIfExists("com.netease.cloudmusic.music.biz.sidebar.account.j", cl);
            }
            return clazz;
        }
    }

    public static class CommentDataClass {
        private static Class<?> clazz;

        public static Class<?> getClazz() {
            return clazz;
        }
    }

    public static class Ad {
        private static Class<?> clazz;

        public static Class<?> getClazz() {
            return clazz;
        }

        public static List<Method> getAdMethod() {
            if (clazz == null) return null;
            try {
                List<Method> methodList = Arrays.asList(clazz.getDeclaredMethods());
                List<Method> hookMethodList = Stream.of(methodList)
                        .filter(m -> m.getReturnType().getName().contains("com.netease.cloudmusic.meta"))
                        .filter(m -> Stream.of(m.getParameterTypes()).anyMatch(c -> c == JSONObject.class))
                        .toList();
                hookMethodList.addAll(Stream.of(methodList)
                        .filter(m -> Stream.of(m.getParameterTypes()).anyMatch(c -> c.getName().contains("com.netease.cloudmusic.meta")))
                        .filter(m -> Stream.of(m.getParameterTypes()).anyMatch(c -> c == JSONObject.class))
                        .toList());
                return hookMethodList;
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static class OKHttp3Response {
        private static Class<?> clazz;
        final Object okHttp3Response;

        public OKHttp3Response(Object okHttp3Response) {
            this.okHttp3Response = okHttp3Response;
        }

        static Class<?> getClazz(Context context) {
            if (clazz == null) {
                clazz = findClassIfExists("okhttp3.Response", classLoader);
            }
            return clazz;
        }

        public Object getHeadersObject(Context context) throws IllegalAccessException, NullPointerException {
            Field[] fields = getClazz(context).getDeclaredFields();
            Field dataField = null;
            Class<?> headerClz = OKHttp3Header.getClazz(context);
            for (Field f : fields) {
                if (headerClz != null && f.getType() == headerClz) {
                    dataField = f;
                    break;
                }
                if (f.getType().getName().equals("okhttp3.Headers")) {
                    dataField = f;
                    break;
                }
            }
            if (dataField == null) {
                for (Field f : fields) {
                    for (Field subF : f.getType().getDeclaredFields()) {
                        if (subF.getType() == String[].class) {
                            dataField = f;
                            break;
                        }
                    }
                    if (dataField != null) break;
                }
            }
            if (dataField != null) {
                dataField.setAccessible(true);
                return dataField.get(okHttp3Response);
            }
            return null;
        }
    }

    public static class OKHttp3Header {
        private static Class<?> clazz;
        final Object okHttp3Header;

        public OKHttp3Header(Object okHttp3Header) {
            this.okHttp3Header = okHttp3Header;
        }

        static Class<?> getClazz(Context context) {
            if (clazz == null) {
                clazz = findClassIfExists("okhttp3.Headers", classLoader);
            }
            return clazz;
        }

        public String[] getHeaders(Context context) throws IllegalAccessException, NullPointerException {
            Field[] fields = getClazz(context).getDeclaredFields();
            Field dataField = null;
            for (Field f : fields) {
                if (f.getType() == String[].class) {
                    dataField = f;
                    break;
                }
            }
            if (dataField != null) {
                dataField.setAccessible(true);
                return (String[]) dataField.get(okHttp3Header);
            }
            return new String[0];
        }
    }

    public static class HttpResponse {
        private static Class<?> clazz;
        private static Method getResultMethod;
        final Object httpResponse;

        public HttpResponse(Object httpResponse) {
            this.httpResponse = httpResponse;
        }

        static Class<?> getClazz(Context context) {
            return clazz;
        }

        public Object getResponseObject(Context context) throws IllegalAccessException, NullPointerException {
            Field[] fields = getClazz(context).getDeclaredFields();
            Field dataField = null;
            Class<?> okRespClz = OKHttp3Response.getClazz(context);
            for (Field f : fields) {
                if (okRespClz != null && f.getType() == okRespClz) {
                    dataField = f;
                    break;
                }
                if (f.getType().getName().equals("okhttp3.Response") ||
                        Closeable.class.isAssignableFrom(f.getType())) {
                    dataField = f;
                    break;
                }
            }
            if (dataField != null) {
                dataField.setAccessible(true);
                return dataField.get(httpResponse);
            }
            return null;
        }

        public Object getEapi(Context context) throws IllegalAccessException, NullPointerException {
            Field[] fields = getClazz(context).getDeclaredFields();
            Class<?> httpUrlClz = HttpUrl.getClazz(context);
            Field dataField = null;
            if (httpUrlClz != null) {
                for (Field f : fields) {
                    if (f.getType() == httpUrlClz || httpUrlClz.isAssignableFrom(f.getType())) {
                        dataField = f;
                        break;
                    }
                }
            }
            if (dataField == null) {
                for (Field f : fields) {
                    Class<?> t = f.getType();
                    if (!t.isPrimitive() && !t.getName().startsWith("okhttp3.") && !t.getName().startsWith("java.")) {
                        for (Field subF : t.getDeclaredFields()) {
                            if (subF.getType() == Uri.class || subF.getType().getName().equals("okhttp3.Request")) {
                                dataField = f;
                                break;
                            }
                        }
                        if (dataField != null) break;
                    }
                }
            }
            if (dataField != null) {
                dataField.setAccessible(true);
                return dataField.get(httpResponse);
            }
            return null;
        }

        public static Method getResultMethod(Context context) {
            if (getResultMethod == null && clazz != null) {
                try {
                    List<Method> methodList = Arrays.asList(clazz.getDeclaredMethods());
                    // 1. 优先查找声明抛出 2 个受检异常且无参的方法 (标准 NetEase HttpResponse.b)
                    getResultMethod = Stream.of(methodList)
                            .filter(m -> m.getExceptionTypes().length == 2 && m.getParameterTypes().length == 0)
                            .findFirst()
                            .orElse(null);

                    // 2. 备用策略：查找无参且名为 b 的方法
                    if (getResultMethod == null) {
                        getResultMethod = Stream.of(methodList)
                                .filter(m -> "b".equals(m.getName()) && m.getParameterTypes().length == 0)
                                .findFirst()
                                .orElse(null);
                    }

                    // 3. 兜底策略：查找无参且返回 Object 且非基础 Object 方法
                    if (getResultMethod == null) {
                        getResultMethod = Stream.of(methodList)
                                .filter(m -> m.getParameterTypes().length == 0
                                        && m.getReturnType() == Object.class
                                        && !"getClass".equals(m.getName())
                                        && !"hashCode".equals(m.getName())
                                        && !"clone".equals(m.getName()))
                                .findFirst()
                                .orElse(null);
                    }

                    if (getResultMethod != null) {
                        DebugLogger.d("ClassHelper", "Found HttpResponse getResultMethod: " + getResultMethod.getName());
                    } else {
                        DebugLogger.e("ClassHelper", "Failed to find HttpResponse getResultMethod in " + clazz.getName(), null);
                    }
                } catch (Exception e) {
                    DebugLogger.e("ClassHelper", "getResultMethod error: " + e.getMessage(), e);
                    MessageHelper.sendNotification(context, MessageHelper.coreClassNotFoundCode);
                }
            }
            return getResultMethod;
        }
    }

    public static class HttpUrl {
        private static Class<?> clazz;

        static Class<?> getClazz(Context context) {
            return clazz;
        }

        public static Uri getUri(Context context, Object eapi) throws IllegalAccessException, NullPointerException {
            if (eapi == null) return null;
            Class<?> curr = eapi.getClass();
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    if (f.getType() == Uri.class) {
                        f.setAccessible(true);
                        return (Uri) f.get(eapi);
                    }
                }
                curr = curr.getSuperclass();
            }
            return null;
        }
    }

    public static class HttpParams {
        private static Class<?> clazz;
        private static Field paramsMap;

        static Class<?> getClazz(Context context) {
            return clazz;
        }

        static Field getParamsMapField(Context context) {
            if (paramsMap == null && clazz != null) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType() == LinkedHashMap.class) {
                        paramsMap = f;
                        paramsMap.setAccessible(true);
                        break;
                    }
                }
            }
            return paramsMap;
        }

        @SuppressWarnings("unchecked")
        public static LinkedHashMap<String, String> getParams(Context context, Object eapi) throws IllegalAccessException, NullPointerException {
            if (eapi == null) return new LinkedHashMap<>();
            Object params = null;
            Class<?> paramsClass = getClazz(context);

            if (paramsClass != null) {
                Method[] methods = findMethodsByExactParameters(eapi.getClass(), paramsClass);
                if (methods.length != 0) {
                    try {
                        params = XposedHelpers.callMethod(eapi, methods[0].getName());
                    } catch (Throwable ignored) {}
                }
                if (params == null) {
                    Class<?> curr = eapi.getClass();
                    while (curr != null && curr != Object.class) {
                        for (Field f : curr.getDeclaredFields()) {
                            if (f.getType() == paramsClass) {
                                f.setAccessible(true);
                                params = f.get(eapi);
                                break;
                            }
                        }
                        if (params != null) break;
                        curr = curr.getSuperclass();
                    }
                }
            }

            if (params == null) {
                Class<?> curr = eapi.getClass();
                while (curr != null && curr != Object.class) {
                    for (Field f : curr.getDeclaredFields()) {
                        Class<?> ft = f.getType();
                        if (!ft.isPrimitive() && !ft.getName().startsWith("java.") && !ft.getName().startsWith("okhttp3.")) {
                            for (Field subF : ft.getDeclaredFields()) {
                                if (subF.getType() == LinkedHashMap.class) {
                                    f.setAccessible(true);
                                    params = f.get(eapi);
                                    paramsClass = ft;
                                    break;
                                }
                            }
                        }
                        if (params != null) break;
                    }
                    if (params != null) break;
                    curr = curr.getSuperclass();
                }
            }

            if (params != null) {
                Field mapField = getParamsMapField(context);
                if (mapField == null && paramsClass != null) {
                    for (Field f : paramsClass.getDeclaredFields()) {
                        if (f.getType() == LinkedHashMap.class) {
                            mapField = f;
                            mapField.setAccessible(true);
                            break;
                        }
                    }
                }
                if (mapField != null) {
                    LinkedHashMap<String, String> map = (LinkedHashMap<String, String>) mapField.get(params);
                    if (map == null) map = new LinkedHashMap<>();
                    Uri uri = HttpUrl.getUri(context, eapi);
                    if (uri != null && uri.isHierarchical()) {
                        for (String name : uri.getQueryParameterNames()) {
                            String val = uri.getQueryParameter(name);
                            map.put(name, val != null ? val : "");
                        }
                    }
                    return map;
                }
            }
            return new LinkedHashMap<>();
        }
    }

    public static class HttpInterceptor {
        private static Class<?> clazz;
        private static List<Method> methodList;

        static Class<?> getClazz(Context context) {
            if (clazz == null) {
                clazz = findClassIfExists("com.netease.cloudmusic.network.interceptor.q", classLoader);
            }
            return clazz;
        }

        public static List<Method> getMethodList(Context context) {
            if (methodList == null) {
                methodList = new ArrayList<>();
                Class<?> clz = getClazz(context);
                if (clz != null) {
                    methodList.addAll(Stream.of(clz.getDeclaredMethods())
                            .filter(m -> m.getParameterTypes().length == 5)
                            .filter(m -> m.getReturnType().getName().contains("Response"))
                            .toList());
                }
            }
            return methodList;
        }
    }

    public static class TabManager {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) clazz = findClassIfExists("th0.o", cl);
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class CommentRequestBuilder {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) clazz = findClassIfExists("x71.g0", cl);
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class PlayerCustomBackground {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) clazz = findClassIfExists("com.netease.cloudmusic.module.playerstyle.PlayerCustomBackgroundView", cl);
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class PlayerChildBackground {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) clazz = findClassIfExists("com.netease.cloudmusic.ui.PlayerChildBackgroundView", cl);
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }
}
