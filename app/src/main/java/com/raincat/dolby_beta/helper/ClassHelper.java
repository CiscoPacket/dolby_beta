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
    private static final String KEY_NAV_TAB_LAYOUT = "nav_tab_layout_class_";
    private static final String KEY_DRAWER_ITEM_ENUM = "drawer_item_enum_class_";
    private static final String KEY_SORT_TYPE_LIST = "sort_type_list_class_";
    private static final String KEY_RESOURCE_ROUTER = "resource_router_class_";
    private static final String KEY_THEME_AGENT = "theme_agent_class_";
    private static final String KEY_THEME_CONFIG = "theme_config_class_";
    private static final String KEY_THEME_INFO = "theme_info_class_";
    private static final String KEY_ROTATION_LAYOUT = "rotation_layout_class_";
    private static final String KEY_PLAYER_DISC_FLIPPER = "player_disc_flipper_class_";
    private static final String KEY_COMMENT_REQUEST_BUILDER = "comment_request_builder_class_";
    private static final String KEY_MAIN_DRAWER_DYNAMIC_ITEM = "main_drawer_dynamic_item_class_";
    private static final String KEY_COMMENT_REQUEST_DATA = "comment_request_data_class_";
    private static final String KEY_COMMENT_REQUEST_UTIL = "comment_request_util_class_";
    private static final String KEY_MODULE_BUS = "module_bus_class_";
    private static final String KEY_AUDIO_VIP_VERDICT = "audio_vip_verdict_method_";

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

        // 缓存命中仍需校验后增补的类: 旧版本模块写入的缓存不含 ThemeConfig/ThemeInfo 等新键,
        // 若直接短路, 这些类将永远缺失 (夜间模式等依赖它们的 hook 全部失效)
        if (SettingHelper.getInstance().isEnable(SettingHelper.dex_key) && loadFromCache(context, version)
                && !hasMissingHookClasses()) {
            isInitialized = true;
            XposedBridge.log("[dolby_beta] Loaded hook classes from cache for version " + version);
            listener.onGet();
            drainPostResolveTasks();
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
                drainPostResolveTasks();
            }
        }).start();
    }

    private static final List<Runnable> postResolveTasks = new ArrayList<>();

    /**
     * 注册"类解析完成后执行"的任务。依赖 DexKit 解析结果 (ModuleBus / AudioEffectVip 判定族)
     * 的 hook 必须在解析完成后安装: BlackHook 构造发生在主线程且早于异步解析,
     * 若在构造期直接触发 DexKit 扫描会造成启动卡顿。
     * 已初始化时立即执行; 否则挂起, 由解析线程在完成后统一执行。
     */
    public static void runAfterResolve(Runnable task) {
        if (task == null) return;
        synchronized (postResolveTasks) {
            if (!isInitialized) {
                postResolveTasks.add(task);
                return;
            }
        }
        try {
            task.run();
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] postResolve task error: " + t.getMessage());
        }
    }

    private static void drainPostResolveTasks() {
        List<Runnable> tasks;
        synchronized (postResolveTasks) {
            tasks = new ArrayList<>(postResolveTasks);
            postResolveTasks.clear();
        }
        for (Runnable r : tasks) {
            try {
                r.run();
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] postResolve task error: " + t.getMessage());
            }
        }
    }

    /**
     * 缓存命中后仍缺失的 hook 依赖类。命中这些缺失时走完整 resolveClasses 流程补齐
     * (9.6.x 未混淆版本由快速命名解析补齐, 无 DexKit 开销; 混淆版本跑一次 DexKit 并重写缓存)。
     */
    private static boolean hasMissingHookClasses() {
        return ThemeConfig.clazz == null || ThemeInfo.clazz == null
                || CommentRequestData.clazz == null || CommentRequestBuilder.clazz == null
                || CommentRequestUtil.clazz == null
                || NavigationTabLayout.clazz == null || TabManager.clazz == null
                || DrawerItemEnum.clazz == null || SidebarItem.clazz == null
                || MainDrawerDynamicItem.clazz == null
                || ModuleBus.clazz == null || AudioEffectVip.verdictMethodName == null;
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

            String ntlName = sp.getString(KEY_NAV_TAB_LAYOUT + version, null);
            if (ntlName != null) NavigationTabLayout.clazz = findClassIfExists(ntlName, classLoader);

            String dieName = sp.getString(KEY_DRAWER_ITEM_ENUM + version, null);
            if (dieName != null) DrawerItemEnum.clazz = findClassIfExists(dieName, classLoader);

            String stlName = sp.getString(KEY_SORT_TYPE_LIST + version, null);
            if (stlName != null) SortTypeList.clazz = findClassIfExists(stlName, classLoader);

            String rrName = sp.getString(KEY_RESOURCE_ROUTER + version, null);
            if (rrName != null) ResourceRouter.clazz = findClassIfExists(rrName, classLoader);

            String taName = sp.getString(KEY_THEME_AGENT + version, null);
            if (taName != null) ThemeAgent.clazz = findClassIfExists(taName, classLoader);

            String tcName = sp.getString(KEY_THEME_CONFIG + version, null);
            if (tcName != null) ThemeConfig.clazz = findClassIfExists(tcName, classLoader);

            String tiName = sp.getString(KEY_THEME_INFO + version, null);
            if (tiName != null) ThemeInfo.clazz = findClassIfExists(tiName, classLoader);

            String rlName = sp.getString(KEY_ROTATION_LAYOUT + version, null);
            if (rlName != null) RotationRelativeLayout.clazz = findClassIfExists(rlName, classLoader);

            String pdfName = sp.getString(KEY_PLAYER_DISC_FLIPPER + version, null);
            if (pdfName != null) PlayerDiscViewFlipper.clazz = findClassIfExists(pdfName, classLoader);

            String mddName = sp.getString(KEY_MAIN_DRAWER_DYNAMIC_ITEM + version, null);
            if (mddName != null) MainDrawerDynamicItem.clazz = findClassIfExists(mddName, classLoader);

            String crdName = sp.getString(KEY_COMMENT_REQUEST_DATA + version, null);
            if (crdName != null) CommentRequestData.clazz = findClassIfExists(crdName, classLoader);

            String cruName = sp.getString(KEY_COMMENT_REQUEST_UTIL + version, null);
            if (cruName != null) CommentRequestUtil.clazz = findClassIfExists(cruName, classLoader);

            String mbName = sp.getString(KEY_MODULE_BUS + version, null);
            if (mbName != null) ModuleBus.clazz = findClassIfExists(mbName, classLoader);

            String avName = sp.getString(KEY_AUDIO_VIP_VERDICT + version, null);
            if (avName != null) AudioEffectVip.verdictMethodName = avName;

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
            if (NavigationTabLayout.clazz != null) editor.putString(KEY_NAV_TAB_LAYOUT + version, NavigationTabLayout.clazz.getName());
            if (DrawerItemEnum.clazz != null) editor.putString(KEY_DRAWER_ITEM_ENUM + version, DrawerItemEnum.clazz.getName());
            if (SortTypeList.clazz != null) editor.putString(KEY_SORT_TYPE_LIST + version, SortTypeList.clazz.getName());
            if (ResourceRouter.clazz != null) editor.putString(KEY_RESOURCE_ROUTER + version, ResourceRouter.clazz.getName());
            if (ThemeAgent.clazz != null) editor.putString(KEY_THEME_AGENT + version, ThemeAgent.clazz.getName());
            if (ThemeConfig.clazz != null) editor.putString(KEY_THEME_CONFIG + version, ThemeConfig.clazz.getName());
            if (ThemeInfo.clazz != null) editor.putString(KEY_THEME_INFO + version, ThemeInfo.clazz.getName());
            if (RotationRelativeLayout.clazz != null) editor.putString(KEY_ROTATION_LAYOUT + version, RotationRelativeLayout.clazz.getName());
            if (PlayerDiscViewFlipper.clazz != null) editor.putString(KEY_PLAYER_DISC_FLIPPER + version, PlayerDiscViewFlipper.clazz.getName());
            if (CommentRequestBuilder.clazz != null) editor.putString(KEY_COMMENT_REQUEST_BUILDER + version, CommentRequestBuilder.clazz.getName());
            if (MainDrawerDynamicItem.clazz != null) editor.putString(KEY_MAIN_DRAWER_DYNAMIC_ITEM + version, MainDrawerDynamicItem.clazz.getName());
            if (CommentRequestData.clazz != null) editor.putString(KEY_COMMENT_REQUEST_DATA + version, CommentRequestData.clazz.getName());
            if (CommentRequestUtil.clazz != null) editor.putString(KEY_COMMENT_REQUEST_UTIL + version, CommentRequestUtil.clazz.getName());
            if (ModuleBus.clazz != null) editor.putString(KEY_MODULE_BUS + version, ModuleBus.clazz.getName());
            if (AudioEffectVip.verdictMethodName != null) editor.putString(KEY_AUDIO_VIP_VERDICT + version, AudioEffectVip.verdictMethodName);
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
                || TabManager.clazz == null || NavigationTabLayout.clazz == null || DrawerItemEnum.clazz == null
                || SortTypeList.clazz == null || ResourceRouter.clazz == null || ThemeAgent.clazz == null
                || ThemeConfig.clazz == null || ThemeInfo.clazz == null
                || CommentRequestBuilder.clazz == null || CommentRequestData.clazz == null || CommentRequestUtil.clazz == null
                || MainDrawerDynamicItem.clazz == null) {
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

            // NavigationTabLayout
            if (NavigationTabLayout.clazz == null) {
                NavigationTabLayout.clazz = findClassIfExists("com.netease.cloudmusic.theme.ui.NavigationTabLayout", classLoader);
            }

            // DrawerItemEnum
            if (DrawerItemEnum.clazz == null) {
                DrawerItemEnum.clazz = findClassIfExists("com.netease.cloudmusic.music.biz.sidebar.ui.MainDrawer$DrawerItemEnum", classLoader);
                if (DrawerItemEnum.clazz == null) DrawerItemEnum.clazz = findClassIfExists("com.netease.cloudmusic.ui.MainDrawer$DrawerItemEnum", classLoader);
                if (DrawerItemEnum.clazz == null) DrawerItemEnum.clazz = findClassIfExists("com.netease.cloudmusic.ui.l$b", classLoader);
            }

            // SortTypeList
            if (SortTypeList.clazz == null) {
                SortTypeList.clazz = findClassIfExists("com.netease.cloudmusic.music.biz.comment.meta.SortTypeList", classLoader);
                if (SortTypeList.clazz == null) SortTypeList.clazz = findClassIfExists("com.netease.cloudmusic.module.comment2.meta.SortTypeList", classLoader);
            }

            // ResourceRouter & ThemeAgent
            if (ResourceRouter.clazz == null) {
                ResourceRouter.clazz = findClassIfExists("com.netease.cloudmusic.theme.core.ResourceRouter", classLoader);
                if (ResourceRouter.clazz == null) ResourceRouter.clazz = findClassIfExists("com.netease.cloudmusic.theme.core.b", classLoader);
            }
            if (ThemeAgent.clazz == null) {
                ThemeAgent.clazz = findClassIfExists("com.netease.cloudmusic.theme.core.ThemeAgent", classLoader);
                if (ThemeAgent.clazz == null) ThemeAgent.clazz = findClassIfExists("com.netease.cloudmusic.theme.core.c", classLoader);
            }
            if (ThemeConfig.clazz == null) {
                ThemeConfig.clazz = findClassIfExists("com.netease.cloudmusic.theme.core.ThemeConfig", classLoader);
                if (ThemeConfig.clazz == null) ThemeConfig.clazz = findClassIfExists("com.netease.cloudmusic.theme.core.f", classLoader);
            }
            if (ThemeInfo.clazz == null) {
                ThemeInfo.clazz = findClassIfExists("com.netease.cloudmusic.theme.core.ThemeInfo", classLoader);
            }

            // RotationRelativeLayout & PlayerDiscViewFlipper
            if (RotationRelativeLayout.clazz == null) {
                RotationRelativeLayout.clazz = findClassIfExists("com.netease.cloudmusic.ui.RotationRelativeLayout", classLoader);
                if (RotationRelativeLayout.clazz == null) RotationRelativeLayout.clazz = findClassIfExists("com.netease.cloudmusic.module.state.RotationRelativeLayout", classLoader);
            }
            if (PlayerDiscViewFlipper.clazz == null) {
                PlayerDiscViewFlipper.clazz = findClassIfExists("com.netease.cloudmusic.ui.PlayerDiscViewFlipper", classLoader);
            }

            // MainDrawerDynamicItem
            if (MainDrawerDynamicItem.clazz == null) {
                MainDrawerDynamicItem.clazz = findClassIfExists("com.netease.cloudmusic.music.biz.sidebar.account.MainDrawerDynamicItem", classLoader);
            }

            // CommentRequestData
            if (CommentRequestData.clazz == null) {
                CommentRequestData.clazz = findClassIfExists("com.netease.cloudmusic.music.biz.comment.meta.CommentRequestData", classLoader);
            }

            // CommentRequestUtil
            if (CommentRequestUtil.clazz == null) {
                CommentRequestUtil.clazz = findClassIfExists("com.netease.cloudmusic.music.biz.comment.q", classLoader);
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
                                        .paramTypes("com.netease.cloudmusic.music.biz.comment.meta.CommentRequestData"))
                                .usingStrings("sortType")));
                if (crbList.isEmpty()) {
                    crbList = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                    .usingStrings("/api/v2/resource/comments", "sortType")));
                }
                if (!crbList.isEmpty()) {
                    CommentRequestBuilder.clazz = crbList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found CommentRequestBuilder: " + CommentRequestBuilder.clazz.getName());
                }
            }

            // 8. NavigationTabLayout
            if (NavigationTabLayout.clazz == null) {
                ClassDataList ntlList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.theme.ui", "com.netease.cloudmusic.ui")
                        .matcher(ClassMatcher.create()
                                .superClass("android.widget.LinearLayout")
                                .addMethod(MethodMatcher.create().name("setSelectedTabView").paramTypes("int"))));
                if (ntlList.isEmpty()) {
                    ntlList = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                    .addMethod(MethodMatcher.create().name("setSelectedTabView").paramTypes("int"))
                                    .addMethod(MethodMatcher.create().name("getSelectedTabPosition").returnType("int"))));
                }
                if (!ntlList.isEmpty()) {
                    NavigationTabLayout.clazz = ntlList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found NavigationTabLayout: " + NavigationTabLayout.clazz.getName());
                }
            }

            // 9. DrawerItemEnum
            if (DrawerItemEnum.clazz == null) {
                ClassDataList dieList = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                                .superClass("java.lang.Enum")
                                .usingStrings("MY_MESSAGE", "MY_FRIEND")));
                if (!dieList.isEmpty()) {
                    DrawerItemEnum.clazz = dieList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found DrawerItemEnum: " + DrawerItemEnum.clazz.getName());
                }
            }

            // 10. SortTypeList
            if (SortTypeList.clazz == null) {
                ClassDataList stlList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.music.biz.comment", "com.netease.cloudmusic.module.comment2")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().name("parseList").paramTypes("org.json.JSONArray"))));
                if (!stlList.isEmpty()) {
                    SortTypeList.clazz = stlList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found SortTypeList: " + SortTypeList.clazz.getName());
                }
            }

            // 11. ThemeAgent —— 结构特征: 发送 CHANGE_THEME 广播 + 持有主题下载任务表 (ConcurrentHashMap)
            if (ThemeAgent.clazz == null) {
                ClassDataList taList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.theme")
                        .matcher(ClassMatcher.create()
                                .usingStrings("com.netease.cloudmusic.action.CHANGE_THEME")
                                .addFieldForType("java.util.concurrent.ConcurrentHashMap")));
                ThemeAgent.clazz = pickStaticSelfReturn(taList, classLoader);
                if (ThemeAgent.clazz != null)
                    XposedBridge.log("[dolby_beta] DexKit found ThemeAgent (structural): " + ThemeAgent.clazz.getName());
            }
            if (ThemeAgent.clazz == null) {
                ClassDataList taList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.theme")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().name("switchTheme"))));
                if (!taList.isEmpty()) {
                    ThemeAgent.clazz = taList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found ThemeAgent (by name): " + ThemeAgent.clazz.getName());
                }
            }

            // 11a. ThemeInfo —— 先由 ThemeAgent 方法参数形状推导 (ThemeAgent 换主题方法的入参即 ThemeInfo)
            if (ThemeInfo.clazz == null && ThemeAgent.clazz != null) {
                ThemeInfo.clazz = deriveThemeInfoFromAgent(ThemeAgent.clazz);
                if (ThemeInfo.clazz != null)
                    XposedBridge.log("[dolby_beta] DexKit derived ThemeInfo (from agent params): " + ThemeInfo.clazz.getName());
            }
            // 11b. ThemeInfo —— 结构特征: Parcelable + (int)/(Parcel) 双构造器
            if (ThemeInfo.clazz == null) {
                ClassDataList tiList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.theme")
                        .matcher(ClassMatcher.create()
                                .addInterface("android.os.Parcelable")
                                .addMethod(MethodMatcher.create().name("<init>").paramTypes("int"))
                                .addMethod(MethodMatcher.create().name("<init>").paramTypes("android.os.Parcel"))));
                for (ClassData cd : tiList) {
                    Class<?> c = cd.getInstance(classLoader);
                    try {
                        c.getDeclaredConstructor(int.class);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    ThemeInfo.clazz = c;
                    XposedBridge.log("[dolby_beta] DexKit found ThemeInfo (structural): " + c.getName());
                    break;
                }
            }
            if (ThemeInfo.clazz == null) {
                ClassDataList tiList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.theme")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().name("findBasePath"))
                                .addMethod(MethodMatcher.create().name("parseThemeInfo"))));
                if (!tiList.isEmpty()) {
                    ThemeInfo.clazz = tiList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found ThemeInfo (by name): " + ThemeInfo.clazz.getName());
                }
            }

            // 11c. ThemeConfig —— 结构特征: 主题 SharedPreferences keys (prev_theme / dark_theme_id_key)
            if (ThemeConfig.clazz == null) {
                ClassDataList tcList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.theme")
                        .matcher(ClassMatcher.create()
                                .usingStrings("prev_theme", "dark_theme_id_key")));
                for (ClassData cd : tcList) {
                    Class<?> c = cd.getInstance(classLoader);
                    boolean hasStaticIntGetter = false;
                    for (Method m : c.getDeclaredMethods()) {
                        if (Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0
                                && m.getReturnType() == int.class) {
                            hasStaticIntGetter = true;
                            break;
                        }
                    }
                    if (hasStaticIntGetter) {
                        ThemeConfig.clazz = c;
                        XposedBridge.log("[dolby_beta] DexKit found ThemeConfig (structural): " + c.getName());
                        break;
                    }
                }
            }
            if (ThemeConfig.clazz == null) {
                ClassDataList tcList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.theme")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().name("getPrevThemeInfo"))
                                .addMethod(MethodMatcher.create().name("getCurrentThemeId").returnType("int"))
                                .addMethod(MethodMatcher.create().name("getBlackThemeId").returnType("int"))));
                if (!tcList.isEmpty()) {
                    ThemeConfig.clazz = tcList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found ThemeConfig (by name): " + ThemeConfig.clazz.getName());
                }
            }

            // 11d. ResourceRouter —— 结构特征: 持有 ThemeInfo 类型字段 + SparseIntArray 色表 + 静态自返回单例
            if (ResourceRouter.clazz == null && ThemeInfo.clazz != null) {
                ClassDataList rrList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.theme")
                        .matcher(ClassMatcher.create()
                                .addFieldForType(ThemeInfo.clazz.getName())
                                .addFieldForType("android.util.SparseIntArray")
                                .addMethod(MethodMatcher.create().returnType("boolean").paramCount(0))));
                ResourceRouter.clazz = pickStaticSelfReturn(rrList, classLoader);
                if (ResourceRouter.clazz != null)
                    XposedBridge.log("[dolby_beta] DexKit found ResourceRouter (structural): " + ResourceRouter.clazz.getName());
            }
            if (ResourceRouter.clazz == null) {
                ClassDataList rrList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.theme")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().name("isNightTheme").returnType("boolean"))));
                if (!rrList.isEmpty()) {
                    ResourceRouter.clazz = rrList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found ResourceRouter (by name): " + ResourceRouter.clazz.getName());
                }
            }

            // 12. RotationRelativeLayout & PlayerDiscViewFlipper
            if (RotationRelativeLayout.clazz == null) {
                ClassDataList rlList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.ui", "com.netease.cloudmusic.module.state")
                        .matcher(ClassMatcher.create()
                                .superClass("android.widget.RelativeLayout")
                                .addMethod(MethodMatcher.create().name("prepareAnimation"))
                                .addMethod(MethodMatcher.create().name("start"))));
                if (!rlList.isEmpty()) {
                    RotationRelativeLayout.clazz = rlList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found RotationRelativeLayout: " + RotationRelativeLayout.clazz.getName());
                }
            }
            if (PlayerDiscViewFlipper.clazz == null) {
                ClassDataList pdfList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.ui")
                        .matcher(ClassMatcher.create()
                                .superClass("android.widget.ViewFlipper")
                                .addMethod(MethodMatcher.create().name("switchDisc"))));
                if (!pdfList.isEmpty()) {
                    PlayerDiscViewFlipper.clazz = pdfList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found PlayerDiscViewFlipper: " + PlayerDiscViewFlipper.clazz.getName());
                }
            }

            // 12. MainDrawerDynamicItem
            if (MainDrawerDynamicItem.clazz == null) {
                ClassDataList mddList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.music.biz.sidebar.account")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().name("getResourceType").returnType("java.lang.String"))
                                .addMethod(MethodMatcher.create().name("getLogInfo").returnType("java.util.Map"))));
                if (!mddList.isEmpty()) {
                    MainDrawerDynamicItem.clazz = mddList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found MainDrawerDynamicItem: " + MainDrawerDynamicItem.clazz.getName());
                }
            }

            // 13. CommentRequestData
            if (CommentRequestData.clazz == null) {
                ClassDataList crdList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.music.biz.comment", "com.netease.cloudmusic.module.comment")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create().name("getSortType").returnType("int"))
                                .addMethod(MethodMatcher.create().name("getThreadId").returnType("java.lang.String"))));
                if (crdList.isEmpty()) {
                    crdList = bridge.findClass(FindClass.create()
                            .searchPackages("com.netease.cloudmusic.music.biz.comment")
                            .matcher(ClassMatcher.create()
                                    .addMethod(MethodMatcher.create().name("getSortType").returnType("int"))));
                }
                if (!crdList.isEmpty()) {
                    CommentRequestData.clazz = crdList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found CommentRequestData: " + CommentRequestData.clazz.getName());
                }
            }

            // 14. CommentRequestUtil
            if (CommentRequestUtil.clazz == null) {
                ClassDataList cruList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.music.biz.comment")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create()
                                        .paramTypes("com.netease.cloudmusic.music.biz.comment.meta.CommentRequestData"))));
                if (cruList.isEmpty()) {
                    cruList = bridge.findClass(FindClass.create()
                            .searchPackages("com.netease.cloudmusic.music.biz.comment")
                            .matcher(ClassMatcher.create()
                                    .usingStrings("/api/v2/resource/comments")));
                }
                if (!cruList.isEmpty()) {
                    CommentRequestUtil.clazz = cruList.get(0).getInstance(classLoader);
                    XposedBridge.log("[dolby_beta] DexKit found CommentRequestUtil: " + CommentRequestUtil.clazz.getName());
                }
            }

            // 15. ModuleBus —— 全局模块总线 (反编译实锤: 模块名 "member_module" 的唯一派发入口),
            //     方法特征: 返回 Object + 2 参 + 引用字符串 "member_module" → 反射校验参数为 (String, Object[])
            if (ModuleBus.clazz == null) {
                MethodDataList mbList = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("java.lang.Object")
                                .paramCount(2)
                                .usingStrings("member_module")));
                for (MethodData md : mbList) {
                    if (ModuleBus.acceptBusMethod(md)) {
                        XposedBridge.log("[dolby_beta] DexKit found ModuleBus (member_module dispatch): " + ModuleBus.clazz.getName());
                        break;
                    }
                }
            }

            // 15b. SidebarDrawerItem (AccountItem) —— 结构特征: sidebar.account 包内同时持有
            //      DrawerItemEnum 类型字段 与 Object data 字段, 且具备 getEnumType()/getData()
            if (SidebarDrawerItem.clazz == null) {
                ClassDataList sdiList = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.music.biz.sidebar.account")
                        .matcher(ClassMatcher.create()
                                .addFieldForName("enumType")
                                .addFieldForName("data")
                                .addFieldForName("uiType")
                                .addMethod(MethodMatcher.create().name("getEnumType"))
                                .addMethod(MethodMatcher.create().name("getData"))));
                for (ClassData cd : sdiList) {
                    try {
                        if (SidebarDrawerItem.acceptClazz(cd.getInstance(classLoader))) {
                            XposedBridge.log("[dolby_beta] DexKit found SidebarDrawerItem (structural): "
                                    + SidebarDrawerItem.clazz.getName());
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 16. AudioEffectVip —— 音效/音质"是否需要VIP"判定族,
            //     结构特征: 无参返回 boolean, 方法体内引用埋点字符串 "memberBenefitsInfo==null"
            if (AudioEffectVip.verdictMethodName == null) {
                MethodDataList avList = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("boolean")
                                .paramCount(0)
                                .usingStrings("memberBenefitsInfo==null")));
                String avName = AudioEffectVip.acceptVerdictMethods(avList);
                if (avName != null) {
                    XposedBridge.log("[dolby_beta] DexKit found AudioEffectVip verdicts (structural): " + avName);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] DexKit dynamic scanning failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * 从 ThemeAgent 的方法参数中推导 ThemeInfo 类:
     * ThemeAgent 换主题方法的入参类型即 ThemeInfo, 特征为非框架类型且带 (int) 构造器。
     */
    public static Class<?> deriveThemeInfoFromAgent(Class<?> agent) {
        if (agent == null) return null;
        Class<?> fallback = null;
        for (Method m : agent.getDeclaredMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                if (p.isPrimitive() || p == String.class || p == agent) continue;
                String n = p.getName();
                if (n.startsWith("java.") || n.startsWith("android.") || n.startsWith("kotlin.")
                        || n.startsWith("org.json.")) continue;
                try {
                    p.getDeclaredConstructor(int.class);
                    return p;
                } catch (Throwable ignored) {
                }
                if (fallback == null) fallback = p;
            }
        }
        return fallback;
    }

    /**
     * 类中是否存在 静态无参且返回自身类型 的方法 (单例 getInstance 的结构特征)
     */
    static boolean hasStaticSelfReturn(Class<?> c) {
        if (c == null) return false;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0
                    && m.getReturnType() == c) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 DexKit 候选中挑出带静态自返回单例方法的类
     */
    static Class<?> pickStaticSelfReturn(ClassDataList list, ClassLoader cl) {
        if (list == null) return null;
        for (ClassData cd : list) {
            try {
                Class<?> c = cd.getInstance(cl);
                if (hasStaticSelfReturn(c)) return c;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 在指定类中查找 引用特定字符串 的 void 单参方法。
     * 用于定位 ThemeAgent.sendChangeThemeCommand(ThemeInfo) —— 该方法是所有换主题入口的最终落点,
     * 以广播 action 字符串 "com.netease.cloudmusic.action.CHANGE_THEME" 为结构特征。
     */
    public static Method findVoidMethodWithString(Context context, Class<?> declaredIn, Class<?> paramType, String usingString) {
        if (declaredIn == null || paramType == null || usingString == null) return null;
        if (!loadDexKit(context)) return null;
        String apkPath = context.getApplicationInfo().sourceDir;
        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            MethodDataList list = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("void")
                            .paramTypes(paramType.getName())
                            .usingStrings(usingString)));
            for (MethodData md : list) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (m.getDeclaringClass() == declaredIn) {
                        m.setAccessible(true);
                        return m;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] findVoidMethodWithString error: " + t.getMessage());
        }
        return null;
    }

    /**
     * DexKit 结构特征查找指定类中声明的方法。
     *
     * @param returnType   返回类型 (如 "void"/"java.lang.Boolean")，null 表示任意
     * @param paramCount   参数个数，null 表示任意
     * @param usingStrings 方法体引用的字符串常量（跨版本稳定，如 trace 标记），可空
     * @param invokes      方法体调用的方法 proto（如 "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;"），可空
     */
    public static List<Method> findDeclaredMethods(Context context, Class<?> declaredIn,
                                                   String returnType, Integer paramCount,
                                                   String[] usingStrings, String[] invokes) {
        List<Method> out = new ArrayList<>();
        if (declaredIn == null) return out;
        if (!loadDexKit(context)) return out;
        String apkPath = context.getApplicationInfo().sourceDir;
        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            MethodMatcher matcher = MethodMatcher.create()
                    .declaredClass(declaredIn.getName());
            if (returnType != null) matcher.returnType(returnType);
            if (paramCount != null) matcher.paramCount(paramCount);
            if (usingStrings != null && usingStrings.length > 0) matcher.usingStrings(usingStrings);
            if (invokes != null) {
                for (String proto : invokes) {
                    matcher.addInvoke(proto);
                }
            }
            MethodDataList list = bridge.findMethod(FindMethod.create().matcher(matcher));
            for (MethodData md : list) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (m.getDeclaringClass() == declaredIn) {
                        out.add(m);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] findDeclaredMethods error: " + t.getMessage());
        }
        return out;
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

    public static class NavigationTabLayout {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) clazz = findClassIfExists("com.netease.cloudmusic.theme.ui.NavigationTabLayout", cl);
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class DrawerItemEnum {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) {
                    clazz = findClassIfExists("com.netease.cloudmusic.music.biz.sidebar.ui.MainDrawer$DrawerItemEnum", cl);
                    if (clazz == null) clazz = findClassIfExists("com.netease.cloudmusic.ui.MainDrawer$DrawerItemEnum", cl);
                    if (clazz == null) clazz = findClassIfExists("com.netease.cloudmusic.ui.l$b", cl);
                }
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class SortTypeList {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) {
                    clazz = findClassIfExists("com.netease.cloudmusic.music.biz.comment.meta.SortTypeList", cl);
                    if (clazz == null) clazz = findClassIfExists("com.netease.cloudmusic.module.comment2.meta.SortTypeList", cl);
                }
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class ResourceRouter {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) {
                    clazz = findClassIfExists("com.netease.cloudmusic.theme.core.ResourceRouter", cl);
                    if (clazz == null) clazz = findClassIfExists("com.netease.cloudmusic.theme.core.b", cl);
                }
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class ThemeAgent {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) {
                    clazz = findClassIfExists("com.netease.cloudmusic.theme.core.ThemeAgent", cl);
                    if (clazz == null) clazz = findClassIfExists("com.netease.cloudmusic.theme.core.c", cl);
                }
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class ThemeConfig {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) {
                    clazz = findClassIfExists("com.netease.cloudmusic.theme.core.ThemeConfig", cl);
                }
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class ThemeInfo {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) {
                    clazz = findClassIfExists("com.netease.cloudmusic.theme.core.ThemeInfo", cl);
                }
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class RotationRelativeLayout {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) {
                    clazz = findClassIfExists("com.netease.cloudmusic.ui.RotationRelativeLayout", cl);
                    if (clazz == null) clazz = findClassIfExists("com.netease.cloudmusic.module.state.RotationRelativeLayout", cl);
                }
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class PlayerDiscViewFlipper {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) clazz = findClassIfExists("com.netease.cloudmusic.ui.PlayerDiscViewFlipper", cl);
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class MainDrawerDynamicItem {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) clazz = findClassIfExists("com.netease.cloudmusic.music.biz.sidebar.account.MainDrawerDynamicItem", cl);
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class CommentRequestData {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) clazz = findClassIfExists("com.netease.cloudmusic.music.biz.comment.meta.CommentRequestData", cl);
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    public static class CommentRequestUtil {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
                if (cl != null) clazz = findClassIfExists("com.netease.cloudmusic.music.biz.comment.q", cl);
            }
            return clazz;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    /**
     * 全局模块总线 (反编译实锤):
     * 功能页面通过 总线类.同步派发(String action, Object[] args) 调用跨模块能力,
     * 例如音效"是否需要VIP"判定使用 action = "ACTION_AudioActionView_isNeedVip"。
     * 结构特征 (跨版本稳定): 同一类中同时声明 静态 (String, Object[]) -> Object 与 静态 (String, Object[]) -> void 两个方法。
     */
    public static class ModuleBus {
        private static Class<?> clazz;
        private static Method dispatchMethod;   // (String, Object[]) -> Object (带返回值的同步派发)
        private static Method actionMethod;     // (String, Object[]) -> void

        /**
         * 校验候选类是否为模块总线, 是则挑出两个派发方法缓存。
         * @return 是否匹配
         */
        static boolean pickMethods(Class<?> c) {
            if (c == null || c.isInterface()) return false;
            Method dispatch = null, action = null;
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 2 || pts[0] != String.class || pts[1] != Object[].class) continue;
                if (m.getReturnType() == Object.class) dispatch = m;
                else if (m.getReturnType() == void.class) action = m;
            }
            if (dispatch == null && action == null) return false;
            dispatchMethod = dispatch;
            actionMethod = action;
            return true;
        }

        /**
         * 校验 DexKit 命中的候选 (方法级特征) 并缓存其所在类的派发方法。
         * @return 是否匹配
         */
        static boolean acceptBusMethod(MethodData md) {
            if (md == null) return false;
            try {
                Class<?> c = md.getDeclaredClass().getInstance(classLoader);
                // 严格校验: (String,Object[])->Object 与 (String,Object[])->void 必须成对出现
                if (!pickMethods(c)) return false;
                if (dispatchMethod == null || actionMethod == null) {
                    dispatchMethod = null;
                    actionMethod = null;
                    return false;
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        /** 模块总线类 (优先缓存 -> 已知命名 -> DexKit 结构匹配) */
        public static Class<?> getClazz(Context context) {
            if (clazz != null) return clazz;
            ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
            if (cl == null) return null;
            // 已知命名快速路径 (9.6.x 未混淆包名演进)
            for (String name : new String[]{"yz0.c", "com.netease.cloudmusic.module.bus.ModuleBus"}) {
                Class<?> c = findClassIfExists(name, cl);
                if (c != null && pickMethods(c)) {
                    clazz = c;
                    XposedBridge.log("[dolby_beta] ModuleBus resolved by name: " + name);
                    return clazz;
                }
            }
            resolveModuleBusByDexKit(context);
            return clazz;
        }

        /** 模块总线带返回值的同步派发方法 (String action, Object[] args) -> Object */
        public static Method getDispatchMethod(Context context) {
            if (dispatchMethod == null || actionMethod == null) {
                Class<?> c = getClazz(context);
                // 缓存命中路径仅还原了类名, 方法引用需在此补齐
                if (c != null && (dispatchMethod == null || actionMethod == null)) pickMethods(c);
            }
            if (dispatchMethod != null) dispatchMethod.setAccessible(true);
            return dispatchMethod;
        }

        /** 模块总线无返回值派发方法 (String action, Object[] args) -> void */
        public static Method getActionMethod(Context context) {
            if (dispatchMethod == null || actionMethod == null) {
                Class<?> c = getClazz(context);
                if (c != null && (dispatchMethod == null || actionMethod == null)) pickMethods(c);
            }
            if (actionMethod != null) actionMethod.setAccessible(true);
            return actionMethod;
        }

        public static void setClazz(Class<?> c) {
            clazz = c;
        }
    }

    /**
     * 播放器样式/音效配置模型 (module.playeruimode.PlayerAudioEffectConfig):
     * long id + String name + int vipType + long limitTime, 由 Moshi 适配器按 JSON key
     * "vipType"/"limitTime" 解析 —— 样式/音效条目的 VIP 标记与限时来源。
     * 结构特征 (跨版本稳定): 上述四字段并存 + getVipType()I / getLimitTime()J。
     */
    public static class PlayerAudioEffectConfig {
        private static Class<?> clazz;

        public static Class<?> getClazz(Context context) {
            if (clazz != null) return clazz;
            ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
            if (cl == null) return null;
            // 未混淆类名快速路径
            Class<?> c = findClassIfExists("com.netease.cloudmusic.module.playeruimode.PlayerAudioEffectConfig", cl);
            if (c != null && hasVipConfigShape(c)) {
                clazz = c;
                XposedBridge.log("[dolby_beta] PlayerAudioEffectConfig resolved by name: " + c.getName());
                return clazz;
            }
            resolveByDexKit(context);
            return clazz;
        }

        /** 字段/方法形状校验: id:long + name:String + vipType:int + limitTime:long + getVipType()I */
        static boolean hasVipConfigShape(Class<?> c) {
            if (c == null || c.isInterface()) return false;
            boolean id = false, name = false, vipType = false, limitTime = false;
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == long.class && "id".equals(f.getName())) id = true;
                else if (f.getType() == String.class && "name".equals(f.getName())) name = true;
                else if (f.getType() == int.class && "vipType".equals(f.getName())) vipType = true;
                else if (f.getType() == long.class && "limitTime".equals(f.getName())) limitTime = true;
            }
            if (!(id && name && vipType && limitTime)) return false;
            try {
                return c.getDeclaredMethod("getVipType").getReturnType() == int.class
                        && c.getDeclaredMethod("getLimitTime").getReturnType() == long.class;
            } catch (Throwable t) {
                return false;
            }
        }

        /** 独立 DexKit 结构匹配 (缓存/命名均未命中时) */
        private static void resolveByDexKit(Context context) {
            if (context == null || !loadDexKit(context)) return;
            String apkPath = context.getApplicationInfo().sourceDir;
            try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
                ClassDataList list = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                                .addFieldForName("vipType")
                                .addFieldForName("limitTime")
                                .addFieldForName("name")
                                .addMethod(MethodMatcher.create().name("getVipType").returnType("int"))
                                .addMethod(MethodMatcher.create().name("getLimitTime").returnType("long"))));
                for (ClassData cd : list) {
                    try {
                        Class<?> c = cd.getInstance(classLoader);
                        if (hasVipConfigShape(c)) {
                            clazz = c;
                            XposedBridge.log("[dolby_beta] DexKit found PlayerAudioEffectConfig (structural): " + c.getName());
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] resolveByDexKit(PlayerAudioEffectConfig) error: " + t.getMessage());
            }
        }
    }

    /**
     * 侧边栏原生条目模型与渲染入口 (反编译实锤):
     *   AccountItem(uiType:int, enumType:MainDrawer$DrawerItemEnum, data:Object, group:int, posInGroup:int)
     *   各 ViewHolder (AccountNormalViewHolder/AccountMessageViewHolder/... 约 15 个) 以
     *   render(AccountItem, int, wb.a) 渲染每一行。
     * 在渲染入口按"条目标识(枚举名)/条目文本"判定隐藏 —— 原生侧整行不渲染,
     * 既不留空洞也不可点击 (RN 触摸由 JS 布局树命中, 视图层隐藏无法阻止点击)。
     */
    public static class SidebarDrawerItem {
        private static Class<?> clazz;
        private static Method mGetEnumType, mGetData, mGetExtra;

        public static Class<?> getClazz(Context context) {
            if (clazz != null) return clazz;
            ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
            if (cl == null) return null;
            Class<?> c = findClassIfExists("com.netease.cloudmusic.music.biz.sidebar.account.AccountItem", cl);
            if (c != null) clazz = c;
            return clazz;
        }

        static boolean acceptClazz(Class<?> c) {
            if (c == null || c.isInterface()) return false;
            boolean enumType = false, data = false, uiType = false;
            for (Field f : c.getDeclaredFields()) {
                if ("enumType".equals(f.getName())) enumType = true;
                else if ("data".equals(f.getName())) data = true;
                else if ("uiType".equals(f.getName())) uiType = true;
            }
            if (!(enumType && data && uiType)) return false;
            try {
                c.getDeclaredMethod("getEnumType");
                c.getDeclaredMethod("getData");
                clazz = c;
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        public static Method getEnumTypeMethod(Context context) {
            if (mGetEnumType == null) {
                Class<?> c = getClazz(context);
                if (c != null) mGetEnumType = methodOrNull(c, "getEnumType");
            }
            return mGetEnumType;
        }

        public static Method getDataMethod(Context context) {
            if (mGetData == null) {
                Class<?> c = getClazz(context);
                if (c != null) mGetData = methodOrNull(c, "getData");
            }
            return mGetData;
        }

        public static Method getExtraMethod(Context context) {
            if (mGetExtra == null) {
                Class<?> c = getClazz(context);
                if (c != null) mGetExtra = methodOrNull(c, "getExtra");
            }
            return mGetExtra;
        }

        private static Method methodOrNull(Class<?> c, String name) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * DexKit 结构特征查找全部 ViewHolder 渲染入口: sidebar.account 包内
         * 声明 render(AccountItem, ...) 三参 void 方法的类。
         * 逐个反射校验首参必须是 AccountItem, 保证只命中真正的行渲染入口。
         */
        public static List<Method> getRenderMethods(Context context) {
            List<Method> out = new ArrayList<>();
            Class<?> itemClazz = getClazz(context);
            ClassLoader cl = classLoader != null ? classLoader : (context != null ? context.getClassLoader() : null);
            if (itemClazz == null) return out;
            // 命名快速路径: ViewHolder 均为未混淆的 Kotlin 类名, 逐个校验 render(AccountItem,·,·) 形状
            if (cl != null) {
                String prefix = "com.netease.cloudmusic.music.biz.sidebar.account.";
                String[] vhNames = {
                        "AccountNormalViewHolder", "AccountBaseNormalViewHolder", "AccountMessageViewHolder",
                        "AccountCloudShellViewHolder", "AccountCreatorCenterViewHolder", "AccountGroupTitleViewHolder",
                        "AccountLogoutViewHolder", "AccountPrivacyViewHolder", "AccountSBHintViewHolder",
                        "AccountSwitchViewHolder", "AccountUpgradeNewFrameworkViewHolder",
                        "AccountVipStatusViewHolder", "AccountSimpleDescViewHolder", "AccountAlarmViewHolder",
                        "StopTimerViewHolder"
                };
                for (String n : vhNames) {
                    Class<?> c = findClassIfExists(prefix + n, cl);
                    if (c == null) continue;
                    for (Method m : c.getDeclaredMethods()) {
                        if (!"render".equals(m.getName())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length == 3 && pts[0] == itemClazz && !out.contains(m)) {
                            m.setAccessible(true);
                            out.add(m);
                        }
                    }
                }
                if (!out.isEmpty()) {
                    XposedBridge.log("[dolby_beta] sidebar render entries (named): " + out.size());
                }
            }
            if (context == null || !loadDexKit(context)) return out;
            String apkPath = context.getApplicationInfo().sourceDir;
            try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
                ClassDataList list = bridge.findClass(FindClass.create()
                        .searchPackages("com.netease.cloudmusic.music.biz.sidebar.account")
                        .matcher(ClassMatcher.create()
                                .addMethod(MethodMatcher.create()
                                        .name("render")
                                        .paramCount(3)
                                        .returnType("void"))));
                for (ClassData cd : list) {
                    try {
                        Class<?> c = cd.getInstance(classLoader);
                        for (Method m : c.getDeclaredMethods()) {
                            if (!"render".equals(m.getName())) continue;
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length == 3 && pts[0] == itemClazz && !out.contains(m)) {
                                m.setAccessible(true);
                                out.add(m);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                XposedBridge.log("[dolby_beta] DexKit found sidebar render entries: " + out.size());
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] SidebarDrawerItem.getRenderMethods error: " + t.getMessage());
            }
            return out;
        }
    }

    /** 独立 DexKit 解析模块总线 (缓存未命中时的兜底) */
    private static void resolveModuleBusByDexKit(Context context) {
        if (context == null || !loadDexKit(context)) return;
        String apkPath = context.getApplicationInfo().sourceDir;
        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            MethodDataList list = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("java.lang.Object")
                            .paramCount(2)
                            .usingStrings("member_module")));
            for (MethodData md : list) {
                if (ModuleBus.acceptBusMethod(md)) {
                    XposedBridge.log("[dolby_beta] DexKit found ModuleBus (on-demand): " + ModuleBus.clazz.getName());
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] resolveModuleBusByDexKit error: " + t.getMessage());
        }
    }

    /**
     * 音效/音质"是否需要VIP"判定族 (反编译全链路实锤):
     *   ACTION_AudioActionView_isNeedVip → sb1.k.b2() → td1.k.a.f() → td1.b.m()
     *   td1.i.b(MusicAudioQuality) 按音质类型分发: DOLBY→td1.b.m() / VIVID_EFFECT→td1.p.l() /
     *   IMMERSE_EFFECT→td1.d.l() / JY_EFFECT→td1.e.l() / JY_MASTER→td1.f.l()
     * 判定语义: 返回 true = 需要VIP (调用方把 true 的音质加入锁定列表)。
     * 结构特征 (跨版本稳定): 无参返回 boolean, 方法体引用埋点字符串 "memberBenefitsInfo==null"。
     * 特征串一次命中全部 5 个判定方法, 全部恒返回 false 即解锁所有音效/音质。
     */
    public static class AudioEffectVip {
        /** "className#methodName;className#methodName;..." 缓存形式 */
        static String verdictMethodName;
        private static final List<Method> verdictMethods = new ArrayList<>();

        /**
         * 接收 DexKit 命中的候选并校验缓存。
         * @return 序列化后的缓存串, 无有效命中时返回 null
         */
        static String acceptVerdictMethods(MethodDataList list) {
            if (list == null || list.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (MethodData md : list) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (!isVerdictMethod(m)) continue;
                    m.setAccessible(true);
                    if (!verdictMethods.contains(m)) verdictMethods.add(m);
                    if (sb.length() > 0) sb.append(';');
                    sb.append(m.getDeclaringClass().getName()).append('#').append(m.getName());
                } catch (Throwable ignored) {
                }
            }
            if (verdictMethods.isEmpty()) return null;
            verdictMethodName = sb.toString();
            return verdictMethodName;
        }

        /** 判定方法校验: 无参、返回 boolean、非抽象/静态 */
        private static boolean isVerdictMethod(Method m) {
            return m != null && m.getParameterTypes().length == 0
                    && m.getReturnType() == boolean.class
                    && !Modifier.isAbstract(m.getModifiers())
                    && !Modifier.isStatic(m.getModifiers());
        }

        /**
         * 获取全部音效/音质 VIP 判定方法 (优先内存缓存 -> 缓存串反射还原 -> DexKit 现场解析)。
         * 调用方需对每个方法 hook 并强制返回 false。
         */
        public static List<Method> getVerdictMethods(Context context) {
            if (!verdictMethods.isEmpty()) return new ArrayList<>(verdictMethods);
            if (verdictMethodName != null) {
                for (String entry : verdictMethodName.split(";")) {
                    int idx = entry.indexOf('#');
                    if (idx <= 0) continue;
                    String clsName = entry.substring(0, idx);
                    String mName = entry.substring(idx + 1);
                    try {
                        Class<?> c = findClassIfExists(clsName, classLoader);
                        if (c == null) continue;
                        Method m = c.getDeclaredMethod(mName);
                        if (isVerdictMethod(m)) {
                            m.setAccessible(true);
                            verdictMethods.add(m);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (!verdictMethods.isEmpty()) return new ArrayList<>(verdictMethods);
            }
            resolveAudioEffectVipByDexKit(context);
            return new ArrayList<>(verdictMethods);
        }

        /** 独立 DexKit 现场解析 (缓存串失效时的兜底) */
        private static void resolveAudioEffectVipByDexKit(Context context) {
            if (context == null || !loadDexKit(context)) return;
            String apkPath = context.getApplicationInfo().sourceDir;
            try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
                MethodDataList list = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("boolean")
                                .paramCount(0)
                                .usingStrings("memberBenefitsInfo==null")));
                String s = acceptVerdictMethods(list);
                if (s != null) {
                    XposedBridge.log("[dolby_beta] DexKit found AudioEffectVip verdicts (on-demand): " + s);
                }
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] resolveAudioEffectVipByDexKit error: " + t.getMessage());
            }
        }
    }
}

