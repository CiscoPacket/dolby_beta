package com.raincat.dolby_beta.hook;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.helper.SettingHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedBridge.hookMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 精简Tab (仅保留“首页”与“我的”并均分宽度，冷启动默认进入“我的”，自由切换不回弹，选中文字加粗高亮)
 *     version: 8.0 (DexKit/结构动态匹配版)
 *
 *     方法/字段定位策略 (不依赖混淆名):
 *     - NavigationTabLayout populateFromPagerAdapter(混淆名 q): DexKit 特征 = 方法体引用字符串
 *       "populateFromPagerAdapter" (trace 标记, 跨版本稳定); 名称+形状作快速路径;
 *     - MainActivity.Hc (页面分发): DexKit 特征 = (Intent) 参数 + 引用 "SELECT_PAGE_INDEX"/"SELECT_SUB_PAGE_ID";
 *     - MainActivity.Qf: DexKit 特征 = 无参返回 java.lang.Boolean 且调用 Boolean.valueOf(Z)，
 *       多候选时宁可不挂 (它只是默认进"我的"的三重保险之一);
 *     - MainActivity.v (移除开屏): DexKit 特征 = 无参 void 且引用 "removeLoadingAndShowMainPage is called from";
 *     - TabManager 默认 Tab (混淆名 N1): 运行时语义验证 —— 无参 String 方法中返回值属于
 *       Tab code 集合 (取自 holder 单例的 String[] getter) 的那一个; 名称+形状作回退;
 *     - Tab 项 (混淆类 g0) 的 code/View/selected 方法与 NTL 的列表/ViewPager2 字段:
 *       运行时按返回类型/参数形状唯一性解析, 名称作回退; 失败则退回按位置的首尾保留分支。
 *     名称快路径在构造时同步注册 (当前版本零额外开销); DexKit 兜底经 getCacheClassList 后台补注册。
 * </pre>
 */
public class HideTabHook {
    private static volatile boolean sHasSwitchedToMineOnStartup = false;
    /** 已注册 hook 的角色名, 防止名称快路径与 DexKit 兜底重复注册 */
    private static final Set<String> sRegisteredHooks = new HashSet<>();
    private static volatile boolean sDexKitFallbackDone = false;

    private static volatile Method sPopulateMethod;
    private static volatile Method sDefaultTabMethod;
    private static volatile Method sDismissSplashMethod;
    private static final TabAccessor sAccessor = new TabAccessor();

    private final Context context;
    private final int versionCode;

    public HideTabHook(Context context, int versionCode) {
        this.context = context;
        this.versionCode = versionCode;
        DebugLogger.i("HideTabHook", "Initializing HideTabHook, versionCode=" + versionCode);

        // 仅做名称+形状快路径注册 (当前版本零 DexKit 开销);
        // DexKit 结构特征兜底由 Hook 的 getCacheClassList 回调触发 onCacheClassListReady (后台线程)
        init(context);
    }

    /**
     * 由 Hook 的 getCacheClassList 回调调用 (每进程恰好一次, 避免与主回调形成双重解析竞态)。
     * DexKit 全量扫描在后台线程执行, 不能放主线程。
     */
    public static void onCacheClassListReady(Context context) {
        if (sDexKitFallbackDone) return;
        new Thread(() -> initDexKitFallback(context), "HideTabDexKitFallback").start();
    }

    private void init(Context context) {
        ClassLoader cl = context.getClassLoader();

        // 1. 9.6+ 底部栏 UI 渲染控件 (NavigationTabLayout, DexKit 动态匹配)
        Class<?> navTabClass = ClassHelper.NavigationTabLayout.getClazz(context);
        if (navTabClass == null) {
            navTabClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.theme.ui.NavigationTabLayout", cl);
        }
        if (navTabClass != null) {
            DebugLogger.i("HideTabHook", "Found NavigationTabLayout class: " + navTabClass.getName());

            // A. populateFromPagerAdapter 执行完后调整底部栏布局（隐藏多余 Tab，保留首页与我的并均分宽度）
            Method populate = findNoArgVoidByName(navTabClass, "q");
            if (populate != null) {
                registerPopulate(populate);
            }

            // B. 选中Tab时高亮发光处理 (文字加粗、纯白高亮，非选中项半透明) —— setSelectedTabView 为未混淆真名
            try {
                findAndHookMethod(navTabClass, "setSelectedTabView", int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        if (param.thisObject instanceof LinearLayout) {
                            int pos = (int) param.args[0];
                            DebugLogger.d("HideTabHook", "setSelectedTabView pos=" + pos);
                            updateTabHighlight((LinearLayout) param.thisObject, pos);
                        }
                    }
                });
            } catch (Throwable t) {
                DebugLogger.e("HideTabHook", "Hook NavigationTabLayout.setSelectedTabView failed", t);
            }
        } else {
            DebugLogger.w("HideTabHook", "NavigationTabLayout not found in classloader");
        }

        // 2. 默认Tab设置 (TabManager 返回默认Tab code)
        Class<?> tabManagerClass = ClassHelper.TabManager.getClazz(context);
        if (tabManagerClass == null) {
            tabManagerClass = XposedHelpers.findClassIfExists("th0.o", cl);
        }
        if (tabManagerClass != null) {
            Method defaultTab = findStringNoArgByName(tabManagerClass, "N1");
            if (defaultTab != null) {
                registerDefaultTab(defaultTab);
            }
        }

        // 3. MainActivity 原生流程引导与首屏开屏状态守护
        Class<?> mainActivityClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.activity.MainActivity", cl);
        if (mainActivityClass != null) {
            DebugLogger.i("HideTabHook", "Found MainActivity class: " + mainActivityClass.getName());

            // A. Qf 原生启动Tab判断：返回 true 让原生走 "mine" 分发流程并完成开屏释放
            Method qf = findBooleanBoxedNoArgByName(mainActivityClass, "Qf");
            if (qf != null) {
                registerStartupMine(qf);
            }

            // B. Hc(Intent) 页面分发：冷启动时确保默认页进入“我的”
            Method hc = findIntentMethodByName(mainActivityClass, "Hc");
            if (hc != null) {
                registerSelectPageDispatch(hc);
            }

            try {
                findAndHookMethod(mainActivityClass, "onDestroy", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        sHasSwitchedToMineOnStartup = false;
                        DebugLogger.d("HideTabHook", "MainActivity onDestroy, reset sHasSwitchedToMineOnStartup");
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 4. 旧版网易云（无 NavigationTabLayout）兼容
        if (navTabClass == null) {
            List<Method> setTabItemMethods = ClassHelper.MainActivitySuperClass.getTabItemStringMethods(context);
            if (setTabItemMethods != null && setTabItemMethods.size() != 0) {
                for (Method method : setTabItemMethods) {
                    hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                                return;
                            if (param.args[0] == null || ((String[]) param.args[0]).length < 2)
                                return;
                            String[] tabNames = (String[]) param.args[0];
                            param.args[0] = filterTabArray(tabNames);
                        }
                    });
                }
            }

            if (versionCode >= 8000010) {
                Class<?> bottomTabViewClass = ClassHelper.BottomTabView.getClazz(context);
                if (bottomTabViewClass != null) {
                    Method initM = ClassHelper.BottomTabView.getTabInitMethod(context);
                    if (initM != null) {
                        findAndHookMethod(bottomTabViewClass, initM.getName(), new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                super.afterHookedMethod(param);
                                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                                    return;
                                List<String> list = new ArrayList<>();
                                list.add("main");
                                list.add("mine");
                                param.setResult(list);
                            }
                        });
                    }

                    Method refreshM = ClassHelper.BottomTabView.getTabRefreshMethod(context);
                    if (refreshM != null) {
                        findAndHookMethod(bottomTabViewClass, refreshM.getName(), new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                super.afterHookedMethod(param);
                                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                                    return;
                                List<String> list = new ArrayList<>();
                                list.add("main");
                                list.add("mine");
                                param.setResult(list);
                            }
                        });
                    }
                }
            }
        }
    }

    /**
     * DexKit 结构特征兜底: 名称快路径未注册到的 hook 在此补齐。
     * 全部特征为跨版本稳定的字符串标记/框架方法签名, 不依赖混淆名。
     */
    private static void initDexKitFallback(Context context) {
        if (sDexKitFallbackDone) return;
        sDexKitFallbackDone = true;
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> navTabClass = ClassHelper.NavigationTabLayout.getClazz(context);
            if (navTabClass == null) {
                navTabClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.theme.ui.NavigationTabLayout", cl);
            }
            if (navTabClass != null && !sRegisteredHooks.contains("populate")) {
                List<Method> list = ClassHelper.findDeclaredMethods(context, navTabClass, "void", 0,
                        new String[]{"populateFromPagerAdapter"}, null);
                if (!list.isEmpty()) {
                    registerPopulate(list.get(0));
                    DebugLogger.i("HideTabHook", "populate method resolved via DexKit: " + list.get(0).getName());
                }
            }

            Class<?> mainActivityClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.activity.MainActivity", cl);
            if (mainActivityClass != null) {
                if (!sRegisteredHooks.contains("startupMine")) {
                    List<Method> list = ClassHelper.findDeclaredMethods(context, mainActivityClass,
                            "java.lang.Boolean", 0, null,
                            new String[]{"Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;"});
                    // 多候选时语义不确定, 宁可不挂 (Qf 只是默认进"我的"的三重保险之一)
                    if (list.size() == 1) {
                        registerStartupMine(list.get(0));
                        DebugLogger.i("HideTabHook", "startupMine method resolved via DexKit: " + list.get(0).getName());
                    } else {
                        DebugLogger.w("HideTabHook", "startupMine candidates=" + list.size() + ", skip");
                    }
                }
                if (!sRegisteredHooks.contains("selectPageDispatch")) {
                    List<Method> list = ClassHelper.findDeclaredMethods(context, mainActivityClass,
                            "void", null,
                            new String[]{"SELECT_PAGE_INDEX", "SELECT_SUB_PAGE_ID"}, null);
                    for (Method m : list) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 1 && p[0] == Intent.class) {
                            registerSelectPageDispatch(m);
                            DebugLogger.i("HideTabHook", "selectPageDispatch resolved via DexKit: " + m.getName());
                            break;
                        }
                    }
                }
                if (sDismissSplashMethod == null) {
                    List<Method> list = ClassHelper.findDeclaredMethods(context, mainActivityClass, "void", 0,
                            new String[]{"removeLoadingAndShowMainPage is called from"}, null);
                    if (!list.isEmpty()) {
                        sDismissSplashMethod = list.get(0);
                        DebugLogger.i("HideTabHook", "dismissSplash method resolved via DexKit: " + list.get(0).getName());
                    }
                }
            }

            if (sDefaultTabMethod == null && !sRegisteredHooks.contains("defaultTab")) {
                Class<?> tabManagerClass = ClassHelper.TabManager.getClazz(context);
                if (tabManagerClass == null) {
                    tabManagerClass = XposedHelpers.findClassIfExists("th0.o", cl);
                }
                Method m = resolveDefaultTabRuntime(tabManagerClass);
                if (m != null) {
                    registerDefaultTab(m);
                    DebugLogger.i("HideTabHook", "defaultTab method resolved via runtime check: " + m.getName());
                }
            }
        } catch (Throwable t) {
            DebugLogger.e("HideTabHook", "initDexKitFallback error: " + t.getMessage(), t);
        }
    }

    // ===== 角色注册 =====

    private static void registerPopulate(Method m) {
        if (!sRegisteredHooks.add("populate")) return;
        sPopulateMethod = m;
        try {
            m.setAccessible(true);
            hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                        return;
                    if (param.thisObject instanceof LinearLayout) {
                        LinearLayout ll = (LinearLayout) param.thisObject;
                        adjustTabLayout(ll, "populateFromPagerAdapter");
                        scheduleSplashDismissWatchdog(ll);
                    }
                }
            });
            DebugLogger.i("HideTabHook", "hooked populate method: " + m.getName());
        } catch (Throwable t) {
            DebugLogger.e("HideTabHook", "hook populate failed", t);
            sRegisteredHooks.remove("populate");
        }
    }

    private static void registerDefaultTab(Method m) {
        if (!sRegisteredHooks.add("defaultTab")) return;
        sDefaultTabMethod = m;
        try {
            m.setAccessible(true);
            hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                        return;
                    param.setResult("mine");
                }
            });
            DebugLogger.i("HideTabHook", "hooked defaultTab method: " + m.getName());
        } catch (Throwable t) {
            DebugLogger.e("HideTabHook", "hook defaultTab failed", t);
            sRegisteredHooks.remove("defaultTab");
        }
    }

    private static void registerStartupMine(Method m) {
        if (!sRegisteredHooks.add("startupMine")) return;
        try {
            m.setAccessible(true);
            hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                        return;
                    param.setResult(Boolean.TRUE);
                }
            });
            DebugLogger.i("HideTabHook", "hooked startupMine method: " + m.getName());
        } catch (Throwable t) {
            DebugLogger.e("HideTabHook", "hook startupMine failed", t);
            sRegisteredHooks.remove("startupMine");
        }
    }

    private static void registerSelectPageDispatch(Method m) {
        if (!sRegisteredHooks.add("selectPageDispatch")) return;
        try {
            m.setAccessible(true);
            hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                        return;
                    Intent intent = (Intent) param.args[0];
                    if (intent != null) {
                        int page = intent.getIntExtra("SELECT_PAGE_INDEX", -1);
                        DebugLogger.i("HideTabHook", "selectPageDispatch invoked with SELECT_PAGE_INDEX=" + page);
                        if (page <= 0 && !sHasSwitchedToMineOnStartup) {
                            sHasSwitchedToMineOnStartup = true;
                            intent.putExtra("SELECT_PAGE_INDEX", 3);
                            DebugLogger.i("HideTabHook", "selectPageDispatch set SELECT_PAGE_INDEX=3 for startup");
                        }
                    }
                }
            });
            DebugLogger.i("HideTabHook", "hooked selectPageDispatch method: " + m.getName());
        } catch (Throwable t) {
            DebugLogger.e("HideTabHook", "hook selectPageDispatch failed", t);
            sRegisteredHooks.remove("selectPageDispatch");
        }
    }

    // ===== 名称 + 形状 快速解析 =====

    private static Method findNoArgVoidByName(Class<?> c, String name) {
        try {
            Method m = c.getDeclaredMethod(name);
            if (m.getReturnType() == void.class && m.getParameterTypes().length == 0) return m;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findStringNoArgByName(Class<?> c, String name) {
        try {
            Method m = c.getDeclaredMethod(name);
            if (m.getReturnType() == String.class && m.getParameterTypes().length == 0) return m;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findBooleanBoxedNoArgByName(Class<?> c, String name) {
        try {
            Method m = c.getDeclaredMethod(name);
            if (m.getReturnType() == Boolean.class && m.getParameterTypes().length == 0) return m;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findIntentMethodByName(Class<?> c, String name) {
        try {
            Method m = c.getDeclaredMethod(name, Intent.class);
            if (m.getReturnType() == void.class) return m;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 运行时语义验证定位默认 Tab 方法:
     * TabManager 中无参返回 String 的方法有多个, 其中"返回值属于 Tab code 集合"的才是默认 Tab。
     * Tab code 集合取自 holder 单例上唯一无参返回 String[] 的方法。
     */
    private static Method resolveDefaultTabRuntime(Class<?> tabManagerClass) {
        if (tabManagerClass == null) return null;
        try {
            Field holderField = null;
            String pkgPrefix = tabManagerClass.getName();
            pkgPrefix = pkgPrefix.substring(0, pkgPrefix.lastIndexOf('.') + 1);
            for (Field f : tabManagerClass.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t.isPrimitive() || t == String.class || t == tabManagerClass) continue;
                if (t.getName().startsWith(pkgPrefix)) {
                    holderField = f;
                    break;
                }
            }
            if (holderField == null) return null;
            Class<?> holderClass = holderField.getType();
            Method codesMethod = null;
            for (Method m : holderClass.getDeclaredMethods()) {
                if (m.getReturnType() == String[].class && m.getParameterTypes().length == 0) {
                    codesMethod = m;
                    break;
                }
            }
            if (codesMethod == null) return null;
            holderField.setAccessible(true);
            Object holder = holderField.get(null);
            if (holder == null) return null;
            codesMethod.setAccessible(true);
            String[] codes = (String[]) codesMethod.invoke(holder);
            if (codes == null || codes.length == 0) return null;
            Set<String> codeSet = new HashSet<>();
            for (String c : codes) {
                if (c != null) codeSet.add(c);
            }

            List<Method> matches = new ArrayList<>();
            for (Method m : tabManagerClass.getDeclaredMethods()) {
                if (m.getReturnType() != String.class || m.getParameterTypes().length != 0) continue;
                try {
                    m.setAccessible(true);
                    Object val = m.invoke(null);
                    if (val instanceof String && codeSet.contains(val)) {
                        matches.add(m);
                    }
                } catch (Throwable ignored) {
                }
            }
            return matches.size() == 1 ? matches.get(0) : null;
        } catch (Throwable t) {
            DebugLogger.d("HideTabHook", "resolveDefaultTabRuntime error: " + t);
            return null;
        }
    }

    // ===== 开屏 watchdog 与 dismiss =====

    private static void scheduleSplashDismissWatchdog(final LinearLayout ll) {
        if (ll == null) return;
        ll.postDelayed(() -> {
            try {
                Activity act = getActivity(ll.getContext());
                if (act != null && !act.isFinishing()) {
                    boolean isShowing = isSplashShowing(act);
                    if (isShowing) {
                        DebugLogger.w("HideTabHook", "Watchdog: splash still showing 1500ms after tab ready -> native dismissSplash");
                        dismissSplash(act);
                    }
                }
            } catch (Throwable t) {
                DebugLogger.d("HideTabHook", "Watchdog dismiss error: " + t);
            }
        }, 1500);
    }

    /**
     * 开屏是否仍显示。依赖 MainActivity 的 boolean 无参方法 (混淆名 n, 读开屏标志位)。
     * 解析失败返回 false (与原实现一致: 无法判断时不主动 dismiss)。
     */
    private static boolean isSplashShowing(Activity act) {
        try {
            Method n = act.getClass().getMethod("n");
            if (n.getReturnType() == boolean.class && n.getParameterTypes().length == 0) {
                return (boolean) n.invoke(act);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void dismissSplash(Activity act) {
        if (act == null || act.isFinishing()) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            act.runOnUiThread(() -> dismissSplash(act));
            return;
        }
        DebugLogger.i("HideTabHook", "dismissSplash triggered for " + act);

        // 调用 MainActivity 原生解除开屏遮罩方法
        // (DexKit 特征: 无参 void 且方法体含 "removeLoadingAndShowMainPage" 标记; 名称回退不再盲调)
        try {
            Method v = sDismissSplashMethod;
            if (v != null) {
                DebugLogger.i("HideTabHook", "dismissSplash: calling " + v.getName() + "()");
                v.setAccessible(true);
                v.invoke(act);
            } else {
                DebugLogger.d("HideTabHook", "dismissSplash: native method not resolved, skip");
            }
        } catch (Throwable t) {
            DebugLogger.d("HideTabHook", "dismissSplash invocation error: " + t);
        }
    }

    // ===== Tab 布局与高亮 =====

    private static Activity getActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static void adjustTabLayout(LinearLayout ll, String caller) {
        if (ll == null) return;
        DebugLogger.d("HideTabHook", "adjustTabLayout called by " + caller + ", childCount=" + ll.getChildCount());
        sAccessor.ensureResolved(ll);
        List<?> tabList = sAccessor.getTabItems(ll);
        if (tabList != null && !tabList.isEmpty() && sAccessor.canStyleItems()) {
            for (Object g0Item : tabList) {
                if (g0Item == null) continue;
                String code = sAccessor.getItemCode(g0Item);
                View tabView = sAccessor.getItemView(g0Item);
                if (tabView == null) continue;

                boolean isKeep = isHomeTab(code) || isMineTab(code);
                if (isKeep) {
                    if (tabView.getVisibility() != View.VISIBLE) {
                        tabView.setVisibility(View.VISIBLE);
                    }
                    ViewGroup.LayoutParams lp = tabView.getLayoutParams();
                    if (lp instanceof LinearLayout.LayoutParams) {
                        LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                        if (llp.width != 0 || llp.weight != 1.0f) {
                            llp.width = 0;
                            llp.weight = 1.0f;
                            tabView.setLayoutParams(llp);
                        }
                    }
                } else {
                    if (tabView.getVisibility() != View.GONE) {
                        tabView.setVisibility(View.GONE);
                    }
                    ViewGroup.LayoutParams lp = tabView.getLayoutParams();
                    if (lp instanceof LinearLayout.LayoutParams) {
                        LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                        if (llp.width != 0 || llp.weight != 0.0f) {
                            llp.width = 0;
                            llp.weight = 0.0f;
                            tabView.setLayoutParams(llp);
                        }
                    }
                }
            }

            int selectedIdx = sAccessor.getCurrentItem(ll);
            if (selectedIdx < 0 || selectedIdx == 0) {
                selectedIdx = 3;
            }
            updateTabHighlight(ll, selectedIdx);
            return;
        }

        // Fallback: 直接对子 View 进行布局调整 (50% / 50%，首尾保留)
        int count = ll.getChildCount();
        if (count >= 4) {
            for (int i = 0; i < count; i++) {
                View child = ll.getChildAt(i);
                if (child == null) continue;
                boolean isKeep = (i == 0 || i == count - 1);
                if (isKeep) {
                    if (child.getVisibility() != View.VISIBLE) {
                        child.setVisibility(View.VISIBLE);
                    }
                    ViewGroup.LayoutParams lp = child.getLayoutParams();
                    if (lp instanceof LinearLayout.LayoutParams) {
                        LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                        if (llp.width != 0 || llp.weight != 1.0f) {
                            llp.width = 0;
                            llp.weight = 1.0f;
                            child.setLayoutParams(llp);
                        }
                    }
                } else {
                    if (child.getVisibility() != View.GONE) {
                        child.setVisibility(View.GONE);
                    }
                    ViewGroup.LayoutParams lp = child.getLayoutParams();
                    if (lp instanceof LinearLayout.LayoutParams) {
                        LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                        if (llp.width != 0 || llp.weight != 0.0f) {
                            llp.width = 0;
                            llp.weight = 0.0f;
                            child.setLayoutParams(llp);
                        }
                    }
                }
            }
        }
    }

    private static void updateTabHighlight(LinearLayout ll, int selectedPos) {
        if (ll == null) return;
        sAccessor.ensureResolved(ll);
        List<?> tabList = sAccessor.getTabItems(ll);
        if (tabList != null && !tabList.isEmpty() && sAccessor.canStyleItems()) {
            for (int i = 0; i < tabList.size(); i++) {
                Object g0Item = tabList.get(i);
                if (g0Item == null) continue;
                boolean isSelected = (i == selectedPos);
                sAccessor.setItemSelected(g0Item, isSelected);
                View tabView = sAccessor.getItemView(g0Item);
                if (tabView != null) {
                    setTabChildSelected(tabView, isSelected);
                }
            }
            return;
        }

        // Fallback: direct children
        for (int i = 0; i < ll.getChildCount(); i++) {
            View child = ll.getChildAt(i);
            if (child == null) continue;
            boolean isSelected = (i == selectedPos);
            setTabChildSelected(child, isSelected);
        }
    }

    private static void setTabChildSelected(View v, boolean isSelected) {
        if (v == null) return;
        v.setSelected(isSelected);
        v.setActivated(isSelected);
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setSelected(isSelected);
            tv.setActivated(isSelected);
            if (isSelected) {
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setTextColor(0xFFFFFFFF);
                tv.setAlpha(1.0f);
            } else {
                tv.setTypeface(Typeface.DEFAULT);
                tv.setTextColor(0x99FFFFFF);
                tv.setAlpha(0.6f);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setTabChildSelected(vg.getChildAt(i), isSelected);
            }
        }
    }

    public static boolean isHomeTab(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.contains("home") || lower.contains("main")
                || lower.contains("discovery") || lower.contains("发现")
                || lower.contains("首页");
    }

    public static boolean isMineTab(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.contains("mine") || lower.contains("我的")
                || lower.contains("user") || lower.contains("profile")
                || lower.contains("account");
    }

    public static String[] filterTabArray(String[] original) {
        if (original == null || original.length <= 2) return new String[]{"main", "mine"};
        String homeTab = null;
        String mineTab = null;
        for (String s : original) {
            if (s == null) continue;
            if (isMineTab(s)) {
                mineTab = s;
            } else if (isHomeTab(s)) {
                if (homeTab == null) homeTab = s;
            }
        }
        if (homeTab == null) homeTab = "main";
        if (mineTab == null) mineTab = "mine";
        return new String[]{homeTab, mineTab};
    }

    /**
     * NavigationTabLayout 布局结构的运行时解析器。
     * 全部按字段/方法形状解析 (唯一性), 混淆名仅作回退; 解析失败走按位置的首尾保留分支。
     */
    private static class TabAccessor {
        boolean tried;
        Field listField;
        Field pagerField;
        Class<?> itemClass;
        Method codeGetter;
        Method viewGetter;
        Method selectSetter;

        synchronized void ensureResolved(LinearLayout ll) {
            if (tried || ll == null) return;
            tried = true;
            try {
                Class<?> ntl = ll.getClass();

                // ViewPager2 字段: 按类型形状解析 (唯一) —— androidx.viewpager2 不在编译期 classpath, 用类名判断
                for (Field f : ntl.getDeclaredFields()) {
                    if ("androidx.viewpager2.widget.ViewPager2".equals(f.getType().getName())) {
                        pagerField = f;
                        break;
                    }
                }

                // Tab 列表字段: 名称 "e" 优先, 失效后按 List 类型形状 + 元素为混淆短类名判定
                Field listCandidate = null;
                try {
                    Field e = ntl.getDeclaredField("e");
                    if (List.class.isAssignableFrom(e.getType())) listCandidate = e;
                } catch (Throwable ignored) {
                }
                List<Field> listFields = new ArrayList<>();
                for (Field f : ntl.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        listFields.add(f);
                    }
                }
                if (listCandidate == null && listFields.size() == 1) {
                    listCandidate = listFields.get(0);
                } else if (listCandidate == null) {
                    for (Field f : listFields) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(ll);
                            if (val instanceof List && !((List<?>) val).isEmpty()) {
                                Object first = ((List<?>) val).get(0);
                                if (first != null && first.getClass().getSimpleName().length() <= 4) {
                                    listCandidate = f;
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (listCandidate != null) {
                    listCandidate.setAccessible(true);
                    listField = listCandidate;
                    Object val = listField.get(ll);
                    if (val instanceof List && !((List<?>) val).isEmpty()) {
                        Object first = ((List<?>) val).get(0);
                        if (first != null) {
                            itemClass = first.getClass();
                        }
                    }
                }

                // Tab 项方法: 形状唯一性解析, 名称回退
                if (itemClass != null) {
                    codeGetter = findUniqueMethod(itemClass, String.class, 0);
                    if (codeGetter == null) codeGetter = findMethodByName(itemClass, "j", String.class);
                    viewGetter = findMethodByName(itemClass, "h", View.class);
                    if (viewGetter == null) viewGetter = findUniqueMethod(itemClass, View.class, 0);
                    selectSetter = findUniqueMethod(itemClass, void.class, 1, boolean.class);
                    if (selectSetter == null) selectSetter = findMethodByName(itemClass, "p", void.class, boolean.class);
                }
                DebugLogger.i("HideTabHook", "TabAccessor resolved: list=" + (listField != null ? listField.getName() : "null")
                        + " item=" + (itemClass != null ? itemClass.getName() : "null")
                        + " code=" + (codeGetter != null ? codeGetter.getName() : "null")
                        + " view=" + (viewGetter != null ? viewGetter.getName() : "null")
                        + " select=" + (selectSetter != null ? selectSetter.getName() : "null")
                        + " pager=" + (pagerField != null ? pagerField.getName() : "null"));
            } catch (Throwable t) {
                DebugLogger.d("HideTabHook", "TabAccessor resolve error: " + t);
            }
        }

        boolean canStyleItems() {
            return listField != null && itemClass != null && codeGetter != null && viewGetter != null;
        }

        List<?> getTabItems(LinearLayout ll) {
            if (listField == null) return null;
            try {
                Object val = listField.get(ll);
                return val instanceof List ? (List<?>) val : null;
            } catch (Throwable t) {
                return null;
            }
        }

        String getItemCode(Object item) {
            if (codeGetter == null || item == null) return null;
            try {
                Object v = codeGetter.invoke(item);
                return v instanceof String ? (String) v : null;
            } catch (Throwable t) {
                return null;
            }
        }

        View getItemView(Object item) {
            if (viewGetter == null || item == null) return null;
            try {
                Object v = viewGetter.invoke(item);
                return v instanceof View ? (View) v : null;
            } catch (Throwable t) {
                return null;
            }
        }

        void setItemSelected(Object item, boolean selected) {
            if (selectSetter == null || item == null) return;
            try {
                selectSetter.invoke(item, selected);
            } catch (Throwable ignored) {
            }
        }

        int getCurrentItem(LinearLayout ll) {
            if (pagerField == null) return -1;
            try {
                Object pager = pagerField.get(ll);
                if (pager == null) return -1;
                Object idx = XposedHelpers.callMethod(pager, "getCurrentItem");
                return idx instanceof Integer ? (Integer) idx : -1;
            } catch (Throwable t) {
                return -1;
            }
        }

        private static Method findUniqueMethod(Class<?> c, Class<?> returnType, int paramCount, Class<?>... paramTypes) {
            Method found = null;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != returnType) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != paramCount) continue;
                if (paramCount == 1 && paramTypes.length == 1 && p[0] != paramTypes[0]) continue;
                if (found == null) {
                    found = m;
                } else {
                    return null; // 多个同形状候选, 无法唯一确定
                }
            }
            return found;
        }

        private static Method findMethodByName(Class<?> c, String name, Class<?> returnType, Class<?>... paramTypes) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                if (m.getReturnType() == returnType) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
