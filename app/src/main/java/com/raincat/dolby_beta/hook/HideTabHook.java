package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.content.Intent;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
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
 *     desc   : 精简Tab (首页仅保留“我的”与“发现/首页”，排除搜索、漫游等)
 *     version: 3.0
 * </pre>
 */
public class HideTabHook {
    public HideTabHook(Context context, int versionCode) {
        if (versionCode < 138)
            return;

        // 1. 适配新版网易云 (9.x 统一Tab数据源提供者 th0.o)
        Class<?> th0Class = XposedHelpers.findClassIfExists("th0.o", context.getClassLoader());
        if (th0Class != null) {
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
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] hook th0.o.Z1 failed: " + t);
            }

            // Hook 所有返回 CopyOnWriteArrayList / List 的数据生成方法 (如 p2, W1)
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

        // 2. 兼容旧版基于反射 MainActivitySuperClass 的 Tab 注入
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
                            intent.putExtra("SELECT_PAGE_INDEX", 0);
                        }
                    }
                });
            }
        }

        // 3. 底部栏 BottomTabView 兼容
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
                            list.add("mine");
                            list.add("main");
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
                            list.add("mine");
                            list.add("main");
                            param.args[0] = list;
                        }
                    });
                }
            }
        }
    }

    public static boolean isHomeTab(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        if (lower.contains("search") || lower.contains("搜索")
                || lower.contains("roam") || lower.contains("漫游")
                || lower.contains("radio") || lower.contains("电台")
                || lower.contains("dynamic") || lower.contains("动态")
                || lower.contains("moment") || lower.contains("look") || lower.contains("直播")) {
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
        return item.toString();
    }

    public static String[] filterTabArray(String[] original) {
        if (original == null || original.length <= 2) return original;
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
        if (homeTab == null) homeTab = original[0];
        if (mineTab == null) mineTab = original[original.length - 1];

        if (homeTab != null && mineTab != null && !homeTab.equals(mineTab)) {
            return new String[]{homeTab, mineTab};
        } else if (original.length >= 2) {
            return new String[]{original[0], original[original.length - 1]};
        }
        return original;
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