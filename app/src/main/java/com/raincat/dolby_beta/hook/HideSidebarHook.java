package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.EAPIHelper;
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
 *     desc   : 侧边栏精简 (适配9.6.05 React Native 侧边栏、Native 数据与渲染层及旧版)
 *     version: 3.0
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

        // 2. 9.6+ React Native 侧边栏 Native View 层 Hook (ReactTextView)
        Class<?> rtvClass = XposedHelpers.findClassIfExists("com.facebook.react.views.text.ReactTextView", context.getClassLoader());
        if (rtvClass != null) {
            XC_MethodHook rtvHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isSidebarHideEnable()) return;
                    if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                    String text = param.args[0].toString().trim();
                    if (text.isEmpty()) return;
                    HashMap<String, Boolean> map = SettingHelper.getInstance().getSidebarSetting(null);
                    if (map == null || map.isEmpty()) return;
                    if (EAPIHelper.shouldHideSidebarString(text, map)) {
                        View tv = (View) param.thisObject;
                        hideRnRowContainer(tv);
                    }
                }
            };
            try {
                XposedBridge.hookAllMethods(rtvClass, "setText", rtvHook);
            } catch (Throwable ignored) {
            }
        }

        // 3. 9.6+ React Native Bridge 模块 Hook (NeteaseMusicApiModule)
        Class<?> nmApiModuleClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.music.biz.rn.reactpackage.nativemodule.NeteaseMusicApiModule", context.getClassLoader());
        if (nmApiModuleClass != null) {
            for (Method m : nmApiModuleClass.getDeclaredMethods()) {
                if ("fetchByApi".equals(m.getName()) || "fetchCurrentApi".equals(m.getName())) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!SettingHelper.getInstance().isSidebarHideEnable()) return;
                                if (param.args != null) {
                                    for (Object arg : param.args) {
                                        if (arg != null && arg.getClass().getName().contains("Promise")) {
                                            hookPromiseResolve(arg);
                                        }
                                    }
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 4. 9.x+ 新版侧边栏：Hook 数据层 (DexKit 动态解析或 com.netease.cloudmusic.music.biz.sidebar.account.j)
        Class<?> jClass = ClassHelper.SidebarItem.getClazz(context);
        if (jClass == null) {
            jClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.music.biz.sidebar.account.j", context.getClassLoader());
        }
        if (jClass != null) {
            XposedBridge.hookAllConstructors(jClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if (!SettingHelper.getInstance().isSidebarHideEnable()) return;
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
                        if (!SettingHelper.getInstance().isSidebarHideEnable()) return;
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

        // 5. 9.x+ 新版侧边栏：Hook 渲染层 ViewHolder.render 动态隐藏与折叠
        XC_MethodHook renderHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                Object item = param.args[0];
                View itemView = null;
                try {
                    itemView = (View) XposedHelpers.getObjectField(param.thisObject, "itemView");
                } catch (Throwable ignored) {
                }
                if (itemView == null && param.thisObject instanceof View) {
                    itemView = (View) param.thisObject;
                }
                if (itemView == null) return;

                if (shouldHideItem(item)) {
                    itemView.setVisibility(View.GONE);
                    ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                    if (lp != null) {
                        lp.height = 0;
                        lp.width = 0;
                        if (lp instanceof ViewGroup.MarginLayoutParams) {
                            ((ViewGroup.MarginLayoutParams) lp).setMargins(0, 0, 0, 0);
                        }
                        itemView.setLayoutParams(lp);
                    }
                    itemView.setPadding(0, 0, 0, 0);
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
        };

        String[] vhClassNames = new String[]{
                "com.netease.cloudmusic.common.nova.autobind.TypeBindingViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountBaseNormalViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountNormalViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountCloudShellViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountCreatorCenterViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountGroupTitleViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountLogoutViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountMessageViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountPrivacyViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountSBHintViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountSimpleDescViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountSwitchViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountUpgradeNewFrameworkViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.AccountVipStatusViewHolder",
                "com.netease.cloudmusic.music.biz.sidebar.account.StopTimerViewHolder"
        };

        for (String vhName : vhClassNames) {
            Class<?> vhCls = XposedHelpers.findClassIfExists(vhName, context.getClassLoader());
            if (vhCls != null) {
                for (Method m : vhCls.getDeclaredMethods()) {
                    if ("render".equals(m.getName())) {
                        try {
                            XposedBridge.hookMethod(m, renderHook);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }

        // 6. 兼容 7.x-8.x 旧版侧边栏构造函数 Hook
        Class<?> legacySidebarItemClass = ClassHelper.SidebarItem.getClazz(context);
        if (legacySidebarItemClass != null) {
            XposedBridge.hookAllConstructors(legacySidebarItemClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if (!SettingHelper.getInstance().isSidebarHideEnable()) return;
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

        // 7. 兼容早期 6.x 以下老版本
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

    private static void hideRnRowContainer(View tv) {
        if (tv == null) return;
        tv.setVisibility(View.GONE);
        View current = tv;
        for (int i = 0; i < 4; i++) {
            android.view.ViewParent parent = current.getParent();
            if (!(parent instanceof ViewGroup)) break;
            ViewGroup vg = (ViewGroup) parent;
            String name = vg.getClass().getName();
            if (vg.getChildCount() > 4 || name.contains("ScrollView") || name.contains("RecyclerView") || name.contains("ViewPager")) {
                collapseView(current);
                break;
            }
            current = vg;
            collapseView(current);
        }
    }

    private static void collapseView(View view) {
        if (view == null) return;
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.height = 0;
            lp.width = 0;
            view.setLayoutParams(lp);
        }
    }

    private static void hookPromiseResolve(Object promise) {
        if (promise == null) return;
        try {
            XposedHelpers.findAndHookMethod(promise.getClass(), "resolve", Object.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isSidebarHideEnable()) return;
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        Object result = param.args[0];
                        if (result instanceof String) {
                            param.args[0] = EAPIHelper.modifySidebar((String) result);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean shouldHideItem(Object accountItem) {
        if (accountItem == null) return false;
        if (!SettingHelper.getInstance().isSidebarHideEnable()) return false;
        HashMap<String, Boolean> settingMap = SettingHelper.getInstance().getSidebarSetting(null);
        if (settingMap == null || settingMap.isEmpty()) return false;
        try {
            Object enumObj = null;
            try {
                enumObj = XposedHelpers.callMethod(accountItem, "getEnumType");
            } catch (Throwable ignored) {
                try {
                    enumObj = XposedHelpers.getObjectField(accountItem, "enumType");
                } catch (Throwable ignored2) {
                }
            }

            String enumString = enumObj != null ? enumObj.toString() : "";
            if ("SETTING".equals(enumString)) {
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
            if (!TextUtils.isEmpty(enumString) && Boolean.TRUE.equals(settingMap.get(enumString))) {
                return true;
            }

            Object data = null;
            try {
                data = XposedHelpers.callMethod(accountItem, "getData");
            } catch (Throwable ignored) {
                try {
                    data = XposedHelpers.getObjectField(accountItem, "data");
                } catch (Throwable ignored2) {
                }
            }

            if (data != null) {
                String dataClassName = data.getClass().getName();
                if (dataClassName.contains("CloudShellInfo")) {
                    if (Boolean.TRUE.equals(settingMap.get("CLOUD_SHELL_CENTER"))) return true;
                } else if (dataClassName.contains("CreatorMessageInfo")) {
                    if (Boolean.TRUE.equals(settingMap.get("MUSICIAN")) || Boolean.TRUE.equals(settingMap.get("CREATOR_CENTER"))) return true;
                } else if (dataClassName.contains("VipItem")) {
                    if (Boolean.TRUE.equals(settingMap.get("VIP"))) return true;
                }

                // Drill into Entry -> MainDrawerDynamicItem for dynamic sidebar items
                try {
                    Object innerEntry = null;
                    if (dataClassName.contains("Entry")) {
                        innerEntry = XposedHelpers.callMethod(data, "getEntry");
                    } else {
                        try {
                            innerEntry = XposedHelpers.callMethod(data, "getEntry");
                        } catch (Throwable ignored) {}
                    }
                    if (innerEntry != null) {
                        try {
                            Object rt = XposedHelpers.callMethod(innerEntry, "getResourceType");
                            if (rt != null) {
                                String rts = rt.toString().toUpperCase();
                                if ("TICKET".equals(rts) && Boolean.TRUE.equals(settingMap.get("TICKET"))) return true;
                                if (("STORE".equals(rts) || "MALL".equals(rts) || "SHOP".equals(rts)) && (Boolean.TRUE.equals(settingMap.get("STORE")) || Boolean.TRUE.equals(settingMap.get("SHOP")))) return true;
                                if ("GAME".equals(rts) && Boolean.TRUE.equals(settingMap.get("GAME"))) return true;
                                if (Boolean.TRUE.equals(settingMap.get(rts))) return true;
                            }
                        } catch (Throwable ignored) {}

                        StringBuilder sbEntry = new StringBuilder();
                        extractStrings(innerEntry, sbEntry);
                        if (sbEntry.length() > 0 && EAPIHelper.shouldHideSidebarString(sbEntry.toString(), settingMap)) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }

                StringBuilder sb = new StringBuilder();
                extractStrings(data, sb);
                if (sb.length() > 0 && EAPIHelper.shouldHideSidebarString(sb.toString(), settingMap)) {
                    return true;
                }
            }

            StringBuilder sbItem = new StringBuilder();
            extractStrings(accountItem, sbItem);
            if (sbItem.length() > 0 && EAPIHelper.shouldHideSidebarString(sbItem.toString(), settingMap)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void extractStrings(Object obj, StringBuilder sb) {
        if (obj == null) return;
        // Try named fields first (name, text, url, description, link, style)
        for (String fName : new String[]{"name", "text", "url", "description", "link", "style", "redirectUrl"}) {
            try {
                Object val = XposedHelpers.getObjectField(obj, fName);
                if (val instanceof String && !((String) val).isEmpty()) {
                    sb.append(val).append(" ");
                }
            } catch (Throwable ignored) {
            }
        }
        // Then try 0-arg String/CharSequence getter methods
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterTypes().length == 0 && (m.getReturnType() == String.class || m.getReturnType() == CharSequence.class)) {
                try {
                    String mName = m.getName();
                    if ("toString".equals(mName) || "getClass".equals(mName) || "hashCode".equals(mName)) continue;
                    Object val = m.invoke(obj);
                    if (val != null && !val.toString().isEmpty()) {
                        sb.append(val.toString()).append(" ");
                    }
                } catch (Throwable ignored) {
                }
            }
        }
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
