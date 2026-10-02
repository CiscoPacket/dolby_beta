package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.os.Bundle;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 评论区优先显示“最热”内容 (每首歌首屏默认最热，不物理调换Tab顺序，歌曲内可自由切换推荐/最新)
 *     version: 10.0
 *
 *     实现策略:
 *     - 请求侧: CommentRequestData 构造后、以及构建请求的入参对象上, 按 "sortType" 字段名精确写入 2,
 *       绝不按"参数值恰好是 0/1/99"盲改 (主构造器 int 顺序为 resType/limit/sortType/pageType,
 *       按值改会把 resType=0 的歌曲评论误改成 2);
 *     - 响应侧: EAPIHelper.modifyCommentHot 在强制窗口内把响应的 currentSortType/sortType/defaultSortType 改为 2;
 *     - 窗口: 新 threadId (新歌) 或评论容器 onCreate/onDestroy 时重置; 首个响应被修改后关闭;
 *       3 秒兜底超时。歌曲内后续请求 (含用户切推荐/最新) 完全放行。
 * </pre>
 */
public class CommentHotClickHook {
    private static volatile String sCurrentSongThreadId = null;
    /** 会话重置时间戳，用于时间窗口判断 */
    private static volatile long sSessionResetTime = 0;
    /** 最大时间窗口（毫秒）：兜底超时，防止响应永远不来 */
    private static final long FORCE_HOT_WINDOW_MAX_MS = 3000;
    /** 首个响应已成功修改后立即关闭窗口 */
    private static volatile boolean sResponseModified = false;
    private static volatile boolean sDexKitHooksInitialized = false;
    /** 已注册过 hook 的类名, 防止早期命名解析与 DexKit 解析对同一类重复注册 */
    private static final Set<String> sHookedClassNames = new HashSet<>();

    public CommentHotClickHook(Context context) {
        initEarlyHooks(context);
    }

    private void initEarlyHooks(Context context) {
        ClassLoader classLoader = context.getClassLoader();

        // 评论界面生命周期感知：新评论界面打开或销毁时重置状态
        String[] containerNames = new String[]{
                "com.netease.cloudmusic.activity.CommentActivity",
                "com.netease.cloudmusic.music.biz.comment.activity.CommentActivity",
                "com.netease.cloudmusic.music.biz.comment.fragment.CommentFragment",
                "com.netease.cloudmusic.music.biz.comment.disccomment.container.PlayerCommentFragment",
                "com.netease.cloudmusic.music.biz.comment.container.CommentContainerActivity"
        };
        for (String cName : containerNames) {
            Class<?> cCls = XposedHelpers.findClassIfExists(cName, classLoader);
            if (cCls != null) {
                try {
                    XposedHelpers.findAndHookMethod(cCls, "onCreate", Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            resetSession();
                        }
                    });
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.findAndHookMethod(cCls, "onDestroy", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            resetSession();
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }

        // 请求构建链 hook: 早期走命名解析, 混淆版本由 initDexKitHooks 在 ClassHelper 解析完成后补注册
        registerRequestHooks(context);
    }

    public static synchronized void initDexKitHooks(Context context) {
        if (sDexKitHooksInitialized) return;
        sDexKitHooksInitialized = true;
        try {
            registerRequestHooks(context);
            DebugLogger.i("CommentHotClickHook", "DexKit comment hooks initialized");
        } catch (Throwable t) {
            DebugLogger.e("CommentHotClickHook", "initDexKitHooks failed: " + t.getMessage(), t);
        }
    }

    /**
     * 注册 CommentRequestData / CommentRequestBuilder / CommentRequestUtil 的 hook。
     * 同一类只注册一次 (早期命名解析与 DexKit 解析命中的是同一个类)。
     */
    private static void registerRequestHooks(Context context) {
        Class<?> crdClass = ClassHelper.CommentRequestData.getClazz(context);
        if (crdClass != null && markHooked(crdClass)) {
            hookCommentRequestDataClass(crdClass);
        }
        // 依赖 crdClass 的两类: 仅在依赖满足时才标记已注册, 否则留给 DexKit 阶段重试
        Class<?> g0Class = ClassHelper.CommentRequestBuilder.getClazz(context);
        if (g0Class != null && crdClass != null && markHooked(g0Class)) {
            hookRequestBuilderClass(g0Class, crdClass);
        }
        Class<?> cruClass = ClassHelper.CommentRequestUtil.getClazz(context);
        if (cruClass != null && crdClass != null && markHooked(cruClass)) {
            hookRequestUtilClass(cruClass, crdClass);
        }
    }

    private static boolean markHooked(Class<?> c) {
        synchronized (sHookedClassNames) {
            return sHookedClassNames.add(c.getName());
        }
    }

    private static void hookCommentRequestDataClass(Class<?> crdClass) {
        try {
            XposedBridge.hookAllConstructors(crdClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    // 只做 threadId 跟踪 (新歌会重置强制窗口); 绝不按参数值盲改 int 参数,
                    // 主构造器 int 顺序为 resType/limit/sortType/pageType, 按值改会破坏 resType/limit
                    updateThreadId(extractThreadIdFromArgs(param.args));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    Object crd = param.thisObject;
                    if (crd == null) return;
                    if (isInForceHotWindow()) {
                        setSortTypeOnCrd(crd, 2, extractThreadIdFromCrd(crd));
                    }
                }
            });

            // 拦截 getSortType() 读侧 (构建请求参数时读取排序值)
            try {
                XposedHelpers.findAndHookMethod(crdClass, "getSortType", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                            return;
                        Object result = param.getResult();
                        if (!(result instanceof Integer)) return;
                        int current = (Integer) result;
                        if ((current == 99 || current == 1 || current == 0) && isInForceHotWindow()) {
                            param.setResult(2);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            DebugLogger.e("CommentHotClickHook", "hookCommentRequestDataClass failed: " + t.getMessage(), t);
        }
    }

    private static void hookRequestBuilderClass(Class<?> g0Class, Class<?> crdClass) {
        if (crdClass == null) return;
        try {
            XC_MethodHook g0Hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    if (param.args == null) return;
                    for (Object arg : param.args) {
                        if (arg != null && crdClass.isInstance(arg) && isInForceHotWindow()) {
                            setSortTypeOnCrd(arg, 2, extractThreadIdFromCrd(arg));
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    Object res = param.getResult();
                    if (res == null) return;
                    try {
                        Object mapObj = XposedHelpers.callMethod(res, "getParamMap");
                        if (mapObj instanceof Map) {
                            Map map = (Map) mapObj;
                            Object currentSt = map.get("sortType");
                            if (currentSt != null) {
                                int st = -1;
                                try {
                                    st = Integer.parseInt(currentSt.toString());
                                } catch (Throwable ignored) {
                                }
                                if ((st == 99 || st == 1 || st == 0) && shouldForceHot(extractThreadIdFromMap(map))) {
                                    map.put("sortType", "2");
                                    DebugLogger.i("CommentHotClickHook", "Modified CommentRequestBuilder paramMap sortType: " + st + " -> 2");
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };

            // 只 hook 入参含 CommentRequestData 的方法, 避免全方法 hook 的分发开销
            for (Method m : g0Class.getDeclaredMethods()) {
                if (!hasParamOfType(m, crdClass)) continue;
                try {
                    XposedBridge.hookMethod(m, g0Hook);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            DebugLogger.e("CommentHotClickHook", "hookRequestBuilderClass failed: " + t.getMessage(), t);
        }
    }

    private static void hookRequestUtilClass(Class<?> cruClass, Class<?> crdClass) {
        if (crdClass == null) return;
        try {
            XC_MethodHook cruHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    if (param.args == null) return;
                    for (Object arg : param.args) {
                        if (arg != null && crdClass.isInstance(arg) && isInForceHotWindow()) {
                            setSortTypeOnCrd(arg, 2, extractThreadIdFromCrd(arg));
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    Object result = param.getResult();
                    if (result == null) return;
                    try {
                        Field[] fields = result.getClass().getDeclaredFields();
                        for (Field f : fields) {
                            if (f.getType() == JSONObject.class || "json".equals(f.getName())) {
                                f.setAccessible(true);
                                Object obj = f.get(result);
                                if (obj instanceof JSONObject) {
                                    JSONObject json = (JSONObject) obj;
                                    int st = json.optInt("sortType", -1);
                                    if ((st == 99 || st == 1 || st == 0) && shouldForceHot(extractThreadIdFromJson(json))) {
                                        json.put("sortType", 2);
                                        DebugLogger.i("CommentHotClickHook", "Modified CommentRequestUtil json sortType: " + st + " -> 2");
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };

            for (Method m : cruClass.getDeclaredMethods()) {
                if (!hasParamOfType(m, crdClass)) continue;
                try {
                    XposedBridge.hookMethod(m, cruHook);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            DebugLogger.e("CommentHotClickHook", "hookRequestUtilClass failed: " + t.getMessage(), t);
        }
    }

    private static boolean hasParamOfType(Method m, Class<?> type) {
        for (Class<?> p : m.getParameterTypes()) {
            if (type.isAssignableFrom(p)) return true;
        }
        return false;
    }

    public static synchronized void resetSession() {
        sCurrentSongThreadId = null;
        sSessionResetTime = System.currentTimeMillis();
        sResponseModified = false;
        DebugLogger.i("CommentHotClickHook", "Comment session reset (maxWindow=" + FORCE_HOT_WINDOW_MAX_MS + "ms)");
    }

    /**
     * 是否在强制最热的时间窗口内。
     * 窗口在以下任一条件满足时关闭：
     * 1. 首个响应已成功修改 (sResponseModified = true)
     * 2. 超过最大时间窗口 (FORCE_HOT_WINDOW_MAX_MS)
     */
    public static synchronized boolean isInForceHotWindow() {
        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key)) {
            return false;
        }
        if (sResponseModified) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - sSessionResetTime;
        return sSessionResetTime > 0 && elapsed < FORCE_HOT_WINDOW_MAX_MS;
    }

    /**
     * 标记首个响应已成功修改为最热，关闭窗口。
     * 由 EAPIHelper.modifyCommentHot 在成功修改响应后调用。
     */
    public static synchronized void markResponseModified() {
        if (!sResponseModified) {
            sResponseModified = true;
            long elapsed = System.currentTimeMillis() - sSessionResetTime;
            DebugLogger.i("CommentHotClickHook", "Response modified -> window closed (elapsed=" + elapsed + "ms)");
        }
    }

    /**
     * 检查是否应强制最热，同时更新 threadId
     */
    public static synchronized boolean shouldForceHot(String threadId) {
        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key)) {
            return false;
        }
        updateThreadId(threadId);
        return isInForceHotWindow();
    }

    /**
     * 更新 threadId。检测到新歌 (threadId 变化) 时重置强制窗口:
     * 每首歌首屏都强制最热; 歌曲内用户切换推荐/最新不受影响 (窗口已随首个响应关闭)。
     */
    private static synchronized void updateThreadId(String threadId) {
        if (threadId == null || threadId.isEmpty()) return;
        if (threadId.equals(sCurrentSongThreadId)) return;
        boolean hadThread = sCurrentSongThreadId != null;
        sSessionResetTime = System.currentTimeMillis();
        sResponseModified = false;
        sCurrentSongThreadId = threadId;
        DebugLogger.i("CommentHotClickHook", (hadThread ? "Song changed -> " : "Comment thread -> ")
                + "reset force-hot window for threadId=" + threadId);
    }

    private static void setSortTypeOnCrd(Object crd, int newSortType, String threadId) {
        if (crd == null) return;
        boolean modified = false;
        try {
            XposedHelpers.callMethod(crd, "setSortType", newSortType);
            modified = true;
        } catch (Throwable ignored) {
        }

        Class<?> clz = crd.getClass();
        while (clz != null && clz != Object.class) {
            for (Field f : clz.getDeclaredFields()) {
                if (f.getType() == int.class && "sortType".equals(f.getName())) {
                    try {
                        f.setAccessible(true);
                        f.setInt(crd, newSortType);
                        modified = true;
                    } catch (Throwable ignored) {
                    }
                }
            }
            clz = clz.getSuperclass();
        }
        if (modified) {
            updateThreadId(threadId);
        }
    }

    private static String extractThreadIdFromArgs(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String) {
                String s = (String) arg;
                if (s.startsWith("R_") || s.startsWith("A_") || s.contains("_4_") || s.contains("_62_")) {
                    return s;
                }
            }
        }
        return null;
    }

    private static String extractThreadIdFromMap(Map map) {
        if (map == null) return null;
        for (String k : new String[]{"threadId", "thread_id", "rid", "resId", "target"}) {
            Object v = map.get(k);
            if (v != null && !v.toString().isEmpty()) {
                return v.toString();
            }
        }
        return null;
    }

    private static String extractThreadIdFromCrd(Object crd) {
        if (crd == null) return null;
        for (String mName : new String[]{"getThreadId", "getResId", "getResourceId", "getRid", "getId", "getTarget"}) {
            try {
                Method m = crd.getClass().getMethod(mName);
                Object val = m.invoke(crd);
                if (val != null && !val.toString().isEmpty()) {
                    return val.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        Class<?> clz = crd.getClass();
        while (clz != null && clz != Object.class) {
            for (String fName : new String[]{"threadId", "thread_id", "resId", "resourceId", "rid", "id", "target"}) {
                try {
                    Field f = clz.getDeclaredField(fName);
                    f.setAccessible(true);
                    Object val = f.get(crd);
                    if (val != null && !val.toString().isEmpty()) {
                        return val.toString();
                    }
                } catch (Throwable ignored) {
                }
            }
            for (Field f : clz.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    try {
                        f.setAccessible(true);
                        String s = (String) f.get(crd);
                        if (s != null && (s.startsWith("R_") || s.startsWith("A_") || s.contains("_4_") || s.contains("_62_"))) {
                            return s;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            clz = clz.getSuperclass();
        }
        return null;
    }

    private static String extractThreadIdFromJson(JSONObject json) {
        if (json == null) return null;
        for (String key : new String[]{"threadId", "thread_id", "resId", "resourceId", "rid", "id", "target"}) {
            String val = json.optString(key, "");
            if (!val.isEmpty()) {
                return val;
            }
        }
        return null;
    }
}
