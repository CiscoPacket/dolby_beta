package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedBridge.hookMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 精简Tab (首页仅保留“我的”与“发现/首页”，适配 9.6.05 NavigationTabLayout 与 th0.o 数据总线)
 *     version: 4.0
 * </pre>
 */
public class HideTabHook {
    public HideTabHook(Context context, int versionCode) {
        if (versionCode < 138)
            return;

        // 1. 适配新版网易云 (9.x 统一Tab数据源提供者，DexKit动态解析或th0.o)
        Class<?> th0Class = ClassHelper.TabManager.getClazz(context);
        if (th0Class == null) {
            th0Class = XposedHelpers.findClassIfExists("th0.o", context.getClassLoader());
        }
        if (th0Class != null) {
            // A. 获取全部 Tab code 列表 (Z1)
            try {
                XposedHelpers.findAndHookMethod(th0Class, "Z1", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        if (param.getResult() instanceof String[]) {
                            String[] original = (String[]) param.getResult();
                            param.setResult(filterTabArray(original));
                        } else {
                            param.setResult(new String[]{"main", "mine"});
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] hook th0.o.Z1 failed: " + t);
            }

            // B. 依据 position 获取 Tab code (V1)
            try {
                XposedHelpers.findAndHookMethod(th0Class, "V1", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        int pos = (int) param.args[0];
                        if (pos == 0) {
                            param.setResult("main");
                        } else if (pos == 1) {
                            param.setResult("mine");
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] hook th0.o.V1 failed: " + t);
            }

            // C. 依据 Tab code 获取 position (Y1)
            try {
                XposedHelpers.findAndHookMethod(th0Class, "Y1", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        String code = (String) param.args[0];
                        if (code == null) return;
                        if (isHomeTab(code)) {
                            param.setResult(0);
                        } else if (isMineTab(code)) {
                            param.setResult(1);
                        } else {
                            param.setResult(-1);
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] hook th0.o.Y1 failed: " + t);
            }

            // C2. 默认Tab设为"我的" (N1 返回默认Tab code)
            try {
                XposedHelpers.findAndHookMethod(th0Class, "N1", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        param.setResult("mine");
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] hook th0.o.N1 failed: " + t);
            }

            // D. Hook 所有返回 CopyOnWriteArrayList / List 的数据生成方法 (如 p2, W1)
            for (Method m : th0Class.getDeclaredMethods()) {
                if (List.class.isAssignableFrom(m.getReturnType())) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                super.afterHookedMethod(param);
                                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                                    return;
                                Object res = param.getResult();
                                if (res instanceof List) {
                                    List<?> list = (List<?>) res;
                                    List<?> filtered = filterTabList(list);
                                    if (res instanceof CopyOnWriteArrayList || m.getReturnType().isAssignableFrom(CopyOnWriteArrayList.class)) {
                                        param.setResult(new CopyOnWriteArrayList<>(filtered));
                                    } else {
                                        param.setResult(new ArrayList<>(filtered));
                                    }
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
                // Hook 接收 List 入参并保存的方法 (如 l2, m2)
                Class<?>[] pTypes = m.getParameterTypes();
                if (pTypes.length > 0 && List.class.isAssignableFrom(pTypes[0])) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                super.beforeHookedMethod(param);
                                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                                    return;
                                if (param.args != null && param.args.length > 0 && param.args[0] instanceof List) {
                                    List<?> list = (List<?>) param.args[0];
                                    List<?> filtered = filterTabList(list);
                                    if (param.args[0] instanceof CopyOnWriteArrayList) {
                                        param.args[0] = new CopyOnWriteArrayList<>(filtered);
                                    } else {
                                        param.args[0] = new ArrayList<>(filtered);
                                    }
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 2. 9.6+ 底部栏 UI 渲染控件 (NavigationTabLayout) 拦截多余 Tab 并均分宽度
        Class<?> navTabClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.theme.ui.NavigationTabLayout", context.getClassLoader());
        if (navTabClass != null) {
            XC_MethodHook addTabHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                        return;
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        Object g0Obj = param.args[0];
                        String tag = null;
                        try {
                            Object tagObj = XposedHelpers.callMethod(g0Obj, "j");
                            if (tagObj != null) tag = tagObj.toString();
                        } catch (Throwable ignored) {
                        }
                        if (tag == null) {
                            try {
                                Object tagObj = XposedHelpers.getObjectField(g0Obj, "tag");
                                if (tagObj != null) tag = tagObj.toString();
                            } catch (Throwable ignored) {
                            }
                        }
                        if (tag != null && !isHomeTab(tag) && !isMineTab(tag)) {
                            // 阻断添加搜索、动态、漫游等非首页/我的Tab
                            param.setResult(null);
                            return;
                        }
                        if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                            if (isMineTab(tag)) {
                                param.args[1] = 1;
                            } else if (isHomeTab(tag)) {
                                param.args[1] = 0;
                            }
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                        return;
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        Object g0Obj = param.args[0];
                        String tag = null;
                        try {
                            Object tagObj = XposedHelpers.callMethod(g0Obj, "j");
                            if (tagObj != null) tag = tagObj.toString();
                        } catch (Throwable ignored) {
                        }
                        if (tag == null) {
                            try {
                                Object tagObj = XposedHelpers.getObjectField(g0Obj, "tag");
                                if (tagObj != null) tag = tagObj.toString();
                            } catch (Throwable ignored) {
                            }
                        }
                        if (isMineTab(tag)) {
                            try {
                                XposedHelpers.callMethod(g0Obj, "o", 1);
                            } catch (Throwable ignored) {
                            }
                        } else if (isHomeTab(tag)) {
                            try {
                                XposedHelpers.callMethod(g0Obj, "o", 0);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (param.thisObject instanceof LinearLayout) {
                        adjustNavigationTabLayout((LinearLayout) param.thisObject);
                    }
                }
            };

            for (Method m : navTabClass.getDeclaredMethods()) {
                String mn = m.getName();
                if ("c".equals(mn) || "d".equals(mn)) {
                    try {
                        XposedBridge.hookMethod(m, addTabHook);
                    } catch (Throwable ignored) {
                    }
                }
            }

            try {
                XposedHelpers.findAndHookMethod(navTabClass, "setSelectedTabView", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        int pos = (int) param.args[0];
                        if (param.thisObject instanceof LinearLayout) {
                            LinearLayout ll = (LinearLayout) param.thisObject;
                            if (pos >= 1) {
                                int lastVisibleIdx = -1;
                                for (int i = 0; i < ll.getChildCount(); i++) {
                                    View child = ll.getChildAt(i);
                                    if (child != null && child.getVisibility() == View.VISIBLE) {
                                        lastVisibleIdx = i;
                                    }
                                }
                                if (lastVisibleIdx > 0) {
                                    param.args[0] = lastVisibleIdx;
                                } else {
                                    int count = ll.getChildCount();
                                    if (count > 1) {
                                        param.args[0] = count - 1;
                                    }
                                }
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        if (param.thisObject instanceof LinearLayout) {
                            updateTabHighlight((LinearLayout) param.thisObject, (int) param.args[0]);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            try {
                XposedHelpers.findAndHookMethod(navTabClass, "getSelectedTabPosition", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        int pos = (int) param.getResult();
                        // Map any position > 0 to logical 1 (我的)
                        if (pos > 0) {
                            param.setResult(1);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            try {
                XposedHelpers.findAndHookMethod(navTabClass, "getTabCount", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        int count = (int) param.getResult();
                        if (count > 2) {
                            param.setResult(2);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            try {
                XposedHelpers.findAndHookMethod(navTabClass, "onLayout", boolean.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        if (param.thisObject instanceof LinearLayout) {
                            adjustNavigationTabLayout((LinearLayout) param.thisObject);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 3. Tab 项 (com.netease.cloudmusic.theme.ui.g0) 位置获取修正，避免“我的”选中后不发光
        Class<?> g0Class = XposedHelpers.findClassIfExists("com.netease.cloudmusic.theme.ui.g0", context.getClassLoader());
        if (g0Class != null) {
            for (Method m : g0Class.getDeclaredMethods()) {
                if ("g".equals(m.getName()) && m.getReturnType() == int.class && m.getParameterTypes().length == 0) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                                    return;
                                int pos = (int) param.getResult();
                                // Map any position > 0 to logical 1 (我的)
                                if (pos > 0) {
                                    param.setResult(1);
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 3.1 9.x+ MainActivity 页面初始化与 Intent 路由拦截，确保默认打开"我的"
        Class<?> mainActivityClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.activity.MainActivity", context.getClassLoader());
        if (mainActivityClass != null) {
            try {
                XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        if (param.thisObject instanceof android.app.Activity) {
                            android.app.Activity act = (android.app.Activity) param.thisObject;
                            Intent intent = act.getIntent();
                            if (intent != null) {
                                intent.putExtra("SELECT_PAGE_INDEX", 1);
                                intent.putExtra("tabCode", "mine");
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            for (Method m : mainActivityClass.getDeclaredMethods()) {
                if ("Hc".equals(m.getName()) && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == Intent.class) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                                    return;
                                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Intent) {
                                    Intent intent = (Intent) param.args[0];
                                    intent.putExtra("SELECT_PAGE_INDEX", 1);
                                    intent.putExtra("tabCode", "mine");
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                    break;
                }
            }
        }

        // 4. 兼容旧版基于反射 MainActivitySuperClass 的 Tab 注入
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

            Method viewPagerInitMethod = ClassHelper.MainActivitySuperClass.getViewPagerInitMethod(context);
            if (viewPagerInitMethod != null) {
                hookMethod(viewPagerInitMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Intent) {
                            Intent intent = (Intent) param.args[0];
                            // Force default page to 我的 (last tab)
                            intent.putExtra("SELECT_PAGE_INDEX", 1);
                        }
                    }
                });
            }
        }

        // 4. 底部栏 BottomTabView 兼容旧版
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
                    findAndHookMethod(bottomTabViewClass, refreshM.getName(), List.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                                return;
                            List<String> list = new ArrayList<>();
                            list.add("main");
                            list.add("mine");
                            param.args[0] = list;
                        }
                    });
                }
            }
        }
    }
    private static volatile int sCurrentSelectedChildIdx = -1;

    private static void updateTabHighlight(LinearLayout ll, int selectedChildIdx) {
        if (ll == null) return;
        sCurrentSelectedChildIdx = selectedChildIdx;
        for (int i = 0; i < ll.getChildCount(); i++) {
            View child = ll.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            boolean isSelected = (i == selectedChildIdx);
            setTabChildSelected(child, isSelected);
        }
    }

    private static void setTabChildSelected(View v, boolean isSelected) {
        if (v == null) return;
        v.setSelected(isSelected);
        v.setActivated(isSelected);
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            tv.setSelected(isSelected);
            tv.setActivated(isSelected);
            tv.setTypeface(isSelected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            tv.setAlpha(isSelected ? 1.0f : 0.65f);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setTabChildSelected(vg.getChildAt(i), isSelected);
            }
        }
    }

    private static void adjustNavigationTabLayout(LinearLayout ll) {
        if (ll == null) return;
        int childCount = ll.getChildCount();
        int visibleCount = 0;
        for (int i = 0; i < childCount; i++) {
            View child = ll.getChildAt(i);
            if (child == null) continue;
            String tag = getTabIdentifier(child.getTag());
            if (isSearchOrSocial(tag) || visibleCount >= 2) {
                if (child.getVisibility() != View.GONE) {
                    child.setVisibility(View.GONE);
                }
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                    if (llp.weight != 0 || llp.width != 0) {
                        llp.weight = 0;
                        llp.width = 0;
                        child.setLayoutParams(llp);
                    }
                }
            } else {
                visibleCount++;
                if (child.getVisibility() != View.VISIBLE) {
                    child.setVisibility(View.VISIBLE);
                }
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                    if (llp.weight != 1.0f || llp.width != 0) {
                        llp.weight = 1.0f;
                        llp.width = 0;
                        child.setLayoutParams(llp);
                    }
                }
            }
        }
        // After layout adjustment, highlight target tab (default to last visible tab '我的')
        int lastVisibleIdx = -1;
        for (int i = 0; i < ll.getChildCount(); i++) {
            View child = ll.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE) {
                lastVisibleIdx = i;
            }
        }
        int target = sCurrentSelectedChildIdx >= 0 ? sCurrentSelectedChildIdx : lastVisibleIdx;
        if (target >= 0) {
            updateTabHighlight(ll, target);
        }
    }

    public static boolean isSearchOrSocial(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.contains("search") || lower.contains("搜索")
                || lower.contains("social") || lower.contains("动态")
                || lower.contains("roam") || lower.contains("漫游")
                || lower.contains("radio") || lower.contains("电台")
                || lower.contains("moment") || lower.contains("look") || lower.contains("直播");
    }

    public static boolean isHomeTab(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        if (isSearchOrSocial(lower)) {
            return false;
        }
        return lower.contains("find") || lower.contains("发现")
                || lower.contains("home") || lower.contains("首页")
                || lower.contains("main") || lower.contains("explore")
                || lower.contains("discovery");
    }

    public static boolean isMineTab(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.contains("mine") || lower.contains("我的")
                || lower.contains("user") || lower.contains("profile")
                || lower.contains("account");
    }

    private static String getTabIdentifier(Object item) {
        if (item == null) return "";
        if (item instanceof String) return (String) item;
        for (String fName : new String[]{"title", "code", "name", "tag", "id"}) {
            try {
                Object val = XposedHelpers.getObjectField(item, fName);
                if (val != null) return val.toString();
            } catch (Throwable ignored) {
            }
        }
        try {
            Object val = XposedHelpers.callMethod(item, "j");
            if (val != null) return val.toString();
        } catch (Throwable ignored) {
        }
        return item.toString();
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

    @SuppressWarnings("unchecked")
    public static <T> List<T> filterTabList(List<T> list) {
        if (list == null || list.size() <= 2) return list;
        T homeItem = null;
        T mineItem = null;
        for (T item : list) {
            if (item == null) continue;
            String idStr = getTabIdentifier(item);
            if (isMineTab(idStr)) {
                mineItem = item;
            } else if (isHomeTab(idStr)) {
                if (homeItem == null) homeItem = item;
            }
        }
        if (homeItem == null && !list.isEmpty()) homeItem = list.get(0);
        if (mineItem == null && list.size() > 1) mineItem = list.get(list.size() - 1);

        List<T> result = new ArrayList<>();
        if (homeItem != null) result.add(homeItem);
        if (mineItem != null && mineItem != homeItem) result.add(mineItem);

        if (result.size() < 2 && list.size() >= 2) {
            result.clear();
            result.add(list.get(0));
            result.add(list.get(list.size() - 1));
        }
        return result;
    }
}