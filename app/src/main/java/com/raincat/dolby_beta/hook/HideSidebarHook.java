package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.model.SidebarEnum;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 侧边栏精简 (适配网易云9.x新框架与旧版)
 *     version: 2.0
 * </pre>
 */
public class HideSidebarHook {
    private Class<?> classDrawerItemEnum;

    private String classMainDrawerString = "com.netease.cloudmusic.ui.MainDrawer";
    private String classDrawerItemEnumString = "com.netease.cloudmusic.ui.MainDrawer$DrawerItemEnum";
    private String methodRefreshDrawerString = "refreshDrawer";
    private String objectMDrawerContainerString = "mDrawerContainer";

    public HideSidebarHook(Context context, int versionCode) {
        if (versionCode < 138) {
            classMainDrawerString = "com.netease.cloudmusic.ui.l";
            classDrawerItemEnumString = "com.netease.cloudmusic.ui.l$b";
            methodRefreshDrawerString = "m";
            objectMDrawerContainerString = "i";
        }

        // 1. 初始化枚举类
        classDrawerItemEnum = XposedHelpers.findClassIfExists("com.netease.cloudmusic.music.biz.sidebar.ui.MainDrawer$DrawerItemEnum", context.getClassLoader());
        if (classDrawerItemEnum == null) {
            classDrawerItemEnum = XposedHelpers.findClassIfExists(classDrawerItemEnumString, context.getClassLoader());
        }
        if (classDrawerItemEnum != null && classDrawerItemEnum.isEnum()) {
            Object[] enumConstants = classDrawerItemEnum.getEnumConstants();
            SidebarEnum.setSidebarEnum(enumConstants);
        }

        // 2. 9.x+ 新版侧边栏：Hook 数据层 (com.netease.cloudmusic.music.biz.sidebar.account.j)
        Class<?> jClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.music.biz.sidebar.account.j", context.getClassLoader());
        if (jClass != null) {
            XposedBridge.hookAllConstructors(jClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_sidebar_hide_key)) return;
                    if (param.args != null && param.args.length == 2 && param.args[1] instanceof List) {
                        List<?> list = (List<?>) param.args[1];
                        List<Object> newList = new ArrayList<>();
                        for (Object item : list) {
                            if (!shouldHideItem(item)) {
                                newList.add(item);
                            }
                        }
                        param.args[1] = newList;
                    }
                }
            });

            try {
                XposedHelpers.findAndHookMethod(jClass, "getData", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_sidebar_hide_key)) return;
                        Object result = param.getResult();
                        if (result instanceof List) {
                            List<?> list = (List<?>) result;
                            List<Object> newList = new ArrayList<>();
                            boolean changed = false;
                            for (Object item : list) {
                                if (shouldHideItem(item)) {
                                    changed = true;
                                } else {
                                    newList.add(item);
                                }
                            }
                            if (changed) {
                                param.setResult(newList);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 3. 9.x+ 新版侧边栏：Hook 渲染层 ViewHolder.render 动态隐藏与折叠
        Class<?> tbvhClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.common.nova.autobind.TypeBindingViewHolder", context.getClassLoader());
        if (tbvhClass != null) {
            for (Method m : tbvhClass.getDeclaredMethods()) {
                if ("render".equals(m.getName())) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                super.afterHookedMethod(param);
                                if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                                Object item = param.args[0];
                                String holderName = param.thisObject.getClass().getName();
                                String itemName = item.getClass().getName();
                                if (!holderName.contains(".biz.sidebar.") && !itemName.contains("AccountItem")) {
                                    return;
                                }
                                if (!(param.thisObject instanceof RecyclerView.ViewHolder)) {
                                    return;
                                }
                                View itemView = ((RecyclerView.ViewHolder) param.thisObject).itemView;
                                if (itemView == null) return;

                                if (shouldHideItem(item)) {
                                    itemView.setVisibility(View.GONE);
                                    ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                                    if (lp != null) {
                                        lp.height = 0;
                                        lp.width = 0;
                                        itemView.setLayoutParams(lp);
                                    }
                                } else {
                                    itemView.setVisibility(View.VISIBLE);
                                    ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                                    if (lp != null && lp.height == 0) {
                                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                                        itemView.setLayoutParams(lp);
                                    }
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 4. 兼容 7.x-8.x 旧版侧边栏构造函数 Hook
        Class<?> legacySidebarItemClass = ClassHelper.SidebarItem.getClazz(context);
        if (legacySidebarItemClass != null) {
            XposedBridge.hookAllConstructors(legacySidebarItemClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_sidebar_hide_key)) return;
                    HashMap<String, Boolean> map = SettingHelper.getInstance().getSidebarSetting(null);
                    if (param.args.length == 2 && param.args[1] instanceof List) {
                        List<Object> objectList = (List<Object>) param.args[1];
                        for (Iterator<Object> iterator = objectList.iterator(); iterator.hasNext(); ) {
                            try {
                                Object object = iterator.next();
                                String enumString = XposedHelpers.callMethod(object, "getEnumType").toString();
                                if (!TextUtils.isEmpty(enumString) && !enumString.equals("SETTING")) {
                                    if (enumString.equals("GROUP")) {
                                        int group = (int) XposedHelpers.callMethod(object, "getGroup");
                                        if (group == 1 && Boolean.TRUE.equals(map.get("GROUP1"))) {
                                            iterator.remove();
                                        } else if (group == 2 && Boolean.TRUE.equals(map.get("GROUP2"))) {
                                            iterator.remove();
                                        }
                                    } else if (Boolean.TRUE.equals(map.get(enumString))) {
                                        iterator.remove();
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            });
        }

        // 5. 兼容早期 6.x 以下老版本
        if (versionCode < 7003010) {
            Class<?> mainDrawerClass = XposedHelpers.findClassIfExists(classMainDrawerString, context.getClassLoader());
            if (mainDrawerClass != null) {
                XposedHelpers.findAndHookMethod(mainDrawerClass, methodRefreshDrawerString, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        removeUselessItem(param, versionCode);
                    }
                });
            }
        }
    }

    private boolean shouldHideItem(Object accountItem) {
        if (accountItem == null) return false;
        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_sidebar_hide_key)) return false;
        HashMap<String, Boolean> settingMap = SettingHelper.getInstance().getSidebarSetting(null);
        if (settingMap == null || settingMap.isEmpty()) return false;
        try {
            Object enumObj = XposedHelpers.callMethod(accountItem, "getEnumType");
            if (enumObj == null) return false;
            String enumString = enumObj.toString();
            if (TextUtils.isEmpty(enumString) || "SETTING".equals(enumString)) {
                return false;
            }
            if ("GROUP".equals(enumString)) {
                try {
                    int group = (int) XposedHelpers.callMethod(accountItem, "getGroup");
                    if (group == 1 && Boolean.TRUE.equals(settingMap.get("GROUP1"))) return true;
                    if (group == 2 && Boolean.TRUE.equals(settingMap.get("GROUP2"))) return true;
                } catch (Throwable ignored) {
                }
            }
            return Boolean.TRUE.equals(settingMap.get(enumString));
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void removeUselessItem(XC_MethodHook.MethodHookParam param, int versionCode) {
        HashMap<String, Boolean> sidebarSettingMap = SettingHelper.getInstance().getSidebarSetting(null);
        LinearLayout drawerContainer = (LinearLayout) XposedHelpers.getObjectField(param.thisObject, objectMDrawerContainerString);
        LinearLayout dynamicContainer = null;
        if (versionCode >= 138)
            dynamicContainer = (LinearLayout) XposedHelpers.getObjectField(param.thisObject, "mDynamicContainer");
        removeItemInner(drawerContainer, sidebarSettingMap);
        removeItemInner(dynamicContainer, sidebarSettingMap);

        if (Boolean.TRUE.equals(sidebarSettingMap.get("DIV2"))) {
            try {
                View div2 = (View) XposedHelpers.getObjectField(param.thisObject, "div2");
                div2.setVisibility(View.GONE);
            } catch (NoSuchFieldError ignored) {
            }
        }
        if (Boolean.TRUE.equals(sidebarSettingMap.get("DIV3"))) {
            try {
                View div3 = (View) XposedHelpers.getObjectField(param.thisObject, "div3");
                div3.setVisibility(View.GONE);
            } catch (NoSuchFieldError ignored) {
            }
        }
        if (Boolean.TRUE.equals(sidebarSettingMap.get("DIV4"))) {
            try {
                View div4 = (View) XposedHelpers.getObjectField(param.thisObject, "div4");
                div4.setVisibility(View.GONE);
            } catch (NoSuchFieldError ignored) {
            }
        }

        if (Boolean.TRUE.equals(sidebarSettingMap.get("VIP"))) {
            try {
                View mMainActivityDrawerHeaderCard = (View) XposedHelpers.getObjectField(param.thisObject, "mMainActivityDrawerHeaderCard");
                mMainActivityDrawerHeaderCard.setVisibility(View.GONE);
            } catch (NoSuchFieldError ignored) {
            }
        }
    }

    private void removeItemInner(LinearLayout container, HashMap<String, Boolean> sidebarSettingMap) {
        if (container == null) return;
        for (int i = 0; i < container.getChildCount(); i++) {
            View v = container.getChildAt(i);
            Object tag = v.getTag();
            if (tag != null && shouldRemove(tag, sidebarSettingMap)) {
                v.setVisibility(View.GONE);
            }
        }
    }

    private boolean shouldRemove(Object drawerItemEnum, HashMap<String, Boolean> sidebarSettingMap) {
        if (drawerItemEnum.getClass().getName().equals("com.netease.cloudmusic.ui.MainDrawer$DrawerItemEnum")
                || drawerItemEnum.getClass().getName().equals("com.netease.cloudmusic.ui.l$b")) {
            String name = drawerItemEnum.toString();
            return Boolean.TRUE.equals(sidebarSettingMap.get(name));
        } else {
            return false;
        }
    }
}
