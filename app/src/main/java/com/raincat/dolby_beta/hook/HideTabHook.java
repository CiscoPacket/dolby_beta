package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.content.Intent;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedBridge.hookMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 精简Tab (仅保留 我的、发现)
 *     version: 2.0
 * </pre>
 */
public class HideTabHook {
    public HideTabHook(Context context, int versionCode) {
        if (versionCode < 138)
            return;

        // 1. 适配新版网易云 (9.x 统一Tab数据源提供者 th0.o.Z1)
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
                            if (original != null && original.length > 2) {
                                String[] trimmed = new String[2];
                                System.arraycopy(original, 0, trimmed, 0, 2);
                                param.setResult(trimmed);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] hook th0.o.Z1 failed: " + t);
            }
        }

        // 2. 兼容旧版基于反射 MainActivitySuperClass 的 Tab 注入
        List<Method> setTabItemMethods = ClassHelper.MainActivitySuperClass.getTabItemStringMethods(context);
        if (setTabItemMethods != null && setTabItemMethods.size() != 0) {
            for (Method method : setTabItemMethods) {
                hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key))
                            return;
                        if (param.args[0] == null || ((String[]) param.args[0]).length < 2)
                            return;
                        String[] tabNames = (String[]) param.args[0];
                        String tabName = Arrays.toString(tabNames);
                        if ((tabName.contains("我的") && tabName.contains("发现")) || (tabName.contains("mine") && tabName.contains("main"))) {
                            String[] strings = new String[2];
                            System.arraycopy(tabNames, 0, strings, 0, 2);
                            param.args[0] = strings;
                        }
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
                            list.add("follow");
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
                            list.add("follow");
                            param.args[0] = list;
                        }
                    });
                }
            }
        }
    }
}