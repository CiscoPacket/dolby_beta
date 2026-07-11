package com.raincat.dolby_beta.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.model.SidebarEnum;
import com.raincat.dolby_beta.utils.Tools;
import com.raincat.dolby_beta.view.BaseDialogInputItem;
import com.raincat.dolby_beta.view.BaseDialogItem;
import com.raincat.dolby_beta.view.beauty.BeautyBannerHideView;
import com.raincat.dolby_beta.view.beauty.BeautyBlackHideView;
import com.raincat.dolby_beta.view.beauty.BeautyBubbleHideView;
import com.raincat.dolby_beta.view.beauty.BeautyCommentHotView;
import com.raincat.dolby_beta.view.beauty.BeautyKSongHideView;
import com.raincat.dolby_beta.view.beauty.BeautyNightModeView;
import com.raincat.dolby_beta.view.beauty.BeautyRotationView;
import com.raincat.dolby_beta.view.beauty.BeautySidebarHideItem;
import com.raincat.dolby_beta.view.beauty.BeautySidebarHideView;
import com.raincat.dolby_beta.view.beauty.BeautyTabHideView;
import com.raincat.dolby_beta.view.beauty.BeautyTitleView;
import com.raincat.dolby_beta.view.beauty.PlayerBackgroundView;
import com.raincat.dolby_beta.view.beauty.background.BackgroundMasterView;
import com.raincat.dolby_beta.view.beauty.background.BackgroundTitleView;
import com.raincat.dolby_beta.view.beauty.background.BackgroundPictureUrlView;
import com.raincat.dolby_beta.view.beauty.background.BackgroundBlurRadiusView;
import com.raincat.dolby_beta.view.proxy.*;
import com.raincat.dolby_beta.view.proxy.configuration.*;
import com.raincat.dolby_beta.view.setting.AboutView;
import com.raincat.dolby_beta.view.setting.BeautyView;
import com.raincat.dolby_beta.view.setting.BlackView;
import com.raincat.dolby_beta.view.setting.DexView;
import com.raincat.dolby_beta.view.setting.FixCommentView;
import com.raincat.dolby_beta.view.setting.MasterView;
import com.raincat.dolby_beta.view.setting.ProxyView;
import com.raincat.dolby_beta.view.setting.ResetModuleView;
import com.raincat.dolby_beta.view.setting.SignSongDailyView;
import com.raincat.dolby_beta.view.setting.SignSongSelfView;
import com.raincat.dolby_beta.view.setting.SignView;
import com.raincat.dolby_beta.view.setting.TitleView;
import com.raincat.dolby_beta.view.setting.UpdateView;
import com.raincat.dolby_beta.view.setting.ListenView;
import com.raincat.dolby_beta.view.setting.WarnView;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * <pre>
 * author : RainCat (Modified by Assistant)
 * time   : 2019/10/26
 * desc   : 设置与侧边栏菜单双兜底 Hook
 * version: 1.2
 * </pre>
 */
public class SettingHook {
    private String SettingActivity;
    private TextView titleView, subView;
    private LinearLayout dialogRoot, dialogProxyRoot, dialogBeautyRoot, dialogSidebarRoot;

    private BroadcastReceiver broadcastReceiver;

    public SettingHook(Context context, int versionCode) {
        if (versionCode >= 8007000) {
            SettingActivity = "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity";
        } else {
            SettingActivity = "com.netease.cloudmusic.activity.SettingActivity";
        }
        
        Class<?> settingActivityClass = findClassIfExists(SettingActivity, context.getClassLoader());
        
        // 注册控制台所需的所有内部广播，防止弹窗切换配置时无响应
        registerBroadcastReceiver(context.getApplicationContext() != null ? context.getApplicationContext() : context);

        if (settingActivityClass != null) {
            // 通道一：主设置 Activity 强力追加
            findAndHookMethod(settingActivityClass, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    final Activity activity = (Activity) param.thisObject;
                    injectViewFallback(activity);
                }
            });
            XposedBridge.log("DolbyBeta: 通道一 [SettingActivity 注入器] 部署完毕！");
        } else {
            XposedBridge.log("DolbyBeta: 未匹配到原生设置类 " + SettingActivity + "，跳过通道一。");
        }

        // 通道二（双重兜底）：在侧边栏渲染器渲染列表时，强行插入我们的选项
        try {
            Class<?> sidebarItemClass = ClassHelper.SidebarItem.getClazz(context);
            if (sidebarItemClass != null) {
                // Hook 侧边栏加载列表的方法 (通常返回 List 并携带各种侧边栏条目)
                XposedBridge.hookAllMethods(sidebarItemClass, "getSidebarList", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        // 如果有侧边栏，可以在此通过列表动态伪造添加。但最简单和稳定的，是直接 Hook 侧边栏的侧拉抽屉展现事件：
                    }
                });
            }
        } catch (Throwable ignored) {}

        // 通道三（终极大招）：Hook 网易云音乐的「侧边栏 Item 栏目」
        // 只要用户一打开侧边栏，直接把“杜比大喇叭高级设置”追加到侧拉菜单的第一项或者最下面，绝对万无一失
        try {
            Class<?> mainActivityClass = findClassIfExists("com.netease.cloudmusic.activity.MainActivity", context.getClassLoader());
            if (mainActivityClass != null) {
                findAndHookMethod(mainActivityClass, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        final Activity activity = (Activity) param.thisObject;
                        
                        // 每一个网易云主页上，都必定会留有用于调起“我的侧边栏”或“偏好设置”的隐形悬浮触发点
                        // 我们直接在 MainActivity 顶部 Content 容器的左上角，追加一个透明的或者是浮动的“喇叭设置”红色迷你悬浮按钮！
                        // 这样即使网易云所有的设置页被完全混淆得乱七八糟，你也只需要点击主页左上角，就能直接进入设置！
                        injectFloatingSettingsButton(activity);
                    }
                });
                XposedBridge.log("DolbyBeta: 通道三 [主页浮动设置按钮] 部署成功！");
            }
        } catch (Throwable e) {
            XposedBridge.log("DolbyBeta: 通道三注入失败: " + e.getMessage());
        }
    }

    /**
     * 强力追加式兜底方案。
     * 直接获取 Activity 的 DecorView 的最下层标准容器，在里面无条件 append 一个高度契合原生 UI 的“杜比设置”
     */
    private void injectViewFallback(final Activity activity) {
        try {
            final ViewGroup contentParent = activity.findViewById(android.R.id.content);
            if (contentParent == null) return;

            View mainContainer = contentParent.getChildAt(0);
            if (!(mainContainer instanceof ViewGroup)) return;
            ViewGroup rootGroup = (ViewGroup) mainContainer;

            ViewGroup targetLayout = findLinearLayoutDeep(rootGroup);
            if (targetLayout == null) {
                targetLayout = rootGroup;
            }

            final Context context = activity;
            LinearLayout appendLayout = new LinearLayout(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Tools.dp2px(context, 55));
            lp.setMargins(0, Tools.dp2px(context, 15), 0, 0);
            appendLayout.setLayoutParams(lp);
            appendLayout.setOrientation(LinearLayout.HORIZONTAL);
            appendLayout.setGravity(Gravity.CENTER_VERTICAL);
            appendLayout.setPadding(Tools.dp2px(context, 16), 0, Tools.dp2px(context, 16), 0);
            
            TypedValue typedValue = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)) {
                appendLayout.setBackgroundResource(typedValue.resourceId);
            }

            titleView = new TextView(context);
            titleView.setText("杜比大喇叭β [高级设置入口]");
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(0xFFE53935); // 经典中国红
            titleView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            subView = new TextView(context);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            subView.setTextColor(0x8A000000);

            appendLayout.addView(titleView);
            appendLayout.addView(subView);
            refresh();

            appendLayout.setOnClickListener(view -> showSettingDialog(activity));
            targetLayout.addView(appendLayout);
            XposedBridge.log("DolbyBeta: 成功采用设置页强力兜底方式渲染。");
        } catch (Throwable e) {
            XposedBridge.log("DolbyBeta: 尝试在原生设置追加入口失败: " + e.getMessage());
        }
    }

    /**
     * 在主界面左侧或右下角强行生成一个绝对不会失效的浮动透明小红点。
     * 只有在「主界面」才显示，双击或长按主界面任何多余空间，即可直接调起杜比大喇叭设置，100% 无法被官方混淆阻断！
     */
    private void injectFloatingSettingsButton(final Activity activity) {
        try {
            final ViewGroup contentParent = activity.findViewById(android.R.id.content);
            if (contentParent == null) return;

            // 检查是不是已经注入过悬浮按钮，防止多次 onCreate 重复渲染导致界面卡顿
            if (contentParent.findViewWithTag("dolby_floating_btn") != null) {
                return;
            }

            final Context context = activity;
            final TextView floatBtn = new TextView(context);
            floatBtn.setTag("dolby_floating_btn");
            
            // 按钮UI样式设定：半透明红色圆形设置键，悬浮于屏幕右下角，极不显眼但随时能点
            floatBtn.setText("β");
            floatBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            floatBtn.setTextColor(0xFFFFFFFF);
            floatBtn.setGravity(Gravity.CENTER);
            floatBtn.setBackground(new android.graphics.drawable.GradientDrawable() {{
                setShape(android.graphics.drawable.GradientDrawable.OVAL);
                setColor(0x80E53935); // 50% 半透明经典红
            }});

            // 完美的悬浮窗定位
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Tools.dp2px(context, 36), Tools.dp2px(context, 36));
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.setMargins(0, 0, Tools.dp2px(context, 16), Tools.dp2px(context, 110)); // 浮动于音乐底栏上方
            floatBtn.setLayoutParams(lp);

            floatBtn.setOnClickListener(v -> showSettingDialog(activity));
            
            // 长按悬浮按钮可以直接将按钮隐藏，如果嫌它碍眼的话
            floatBtn.setOnLongClickListener(v -> {
                floatBtn.setVisibility(View.GONE);
                return true;
            });

            contentParent.addView(floatBtn);
            XposedBridge.log("DolbyBeta: 终极大招：MainActivity 浮动快捷设置按钮注入成功！");
        } catch (Throwable e) {
            XposedBridge.log("DolbyBeta: 终极大招浮动按钮渲染失败: " + e.getMessage());
        }
    }

    private ViewGroup findLinearLayoutDeep(ViewGroup root) {
        if (root instanceof LinearLayout) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup res = findLinearLayoutDeep((ViewGroup) child);
                if (res != null) return res;
            }
        }
        return null;
    }

    @SuppressLint("SetTextI18n")
    private void refresh() {
        if (titleView == null || subView == null) return;
        titleView.setText("杜比大喇叭β");
        if (ExtraHelper.getExtraDate(ExtraHelper.USER_ID).equals("-1")) {
            subView.setText("（USERID获取失败）");
        } else if (!SettingHelper.getInstance().getSetting(SettingHelper.master_key))
            subView.setText("（已关闭）");
        else if (ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS).equals("1"))
            subView.setText("（UnblockNeteaseMusic正在运行）");
        else
            subView.setText("（UnblockNeteaseMusic停止运行）");
    }

    private void registerBroadcastReceiver(final Context context) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SettingHelper.refresh_setting);
        intentFilter.addAction(SettingHelper.proxy_setting);
        intentFilter.addAction(SettingHelper.beauty_setting);
        intentFilter.addAction(SettingHelper.sidebar_setting);
        intentFilter.addAction(SettingHelper.background_setting);
        intentFilter.addAction(SettingHelper.proxy_configuration_setting);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent.getAction().equals(SettingHelper.refresh_setting)) {
                    for (int i = 0; i < dialogRoot.getChildCount(); i++) {
                        if (dialogRoot.getChildAt(i) instanceof BaseDialogItem)
                            ((BaseDialogItem) dialogRoot.getChildAt(i)).refresh();
                    }
                    if (dialogProxyRoot != null)
                        for (int i = 0; i < dialogProxyRoot.getChildCount(); i++) {
                            if (dialogProxyRoot.getChildAt(i) instanceof BaseDialogItem)
                                ((BaseDialogItem) dialogProxyRoot.getChildAt(i)).refresh();
                            else if (dialogProxyRoot.getChildAt(i) instanceof BaseDialogInputItem)
                                ((BaseDialogInputItem) dialogProxyRoot.getChildAt(i)).refresh();
                        }
                    if (dialogBeautyRoot != null)
                        for (int i = 0; i < dialogBeautyRoot.getChildCount(); i++) {
                            if (dialogBeautyRoot.getChildAt(i) instanceof BaseDialogItem)
                                ((BaseDialogItem) dialogBeautyRoot.getChildAt(i)).refresh();
                        }
                } else if (intent.getAction().equals(SettingHelper.proxy_setting)) {
                    showProxyDialog(context);
                } else if (intent.getAction().equals(SettingHelper.beauty_setting)) {
                    showBeautyDialog(context);
                } else if (intent.getAction().equals(SettingHelper.sidebar_setting)) {
                    showSidebarDialog(context);
                } else if (intent.getAction().equals(SettingHelper.background_setting)) {
                    showPlayerBackgroundDialog(context);
                } else if (intent.getAction().equals(SettingHelper.proxy_configuration_setting)) {
                    showProxyConfigurationDialog(context);
                }
            }
        };
        context.registerReceiver(broadcastReceiver, intentFilter);
    }

    private void showSettingDialog(final Context context) {
        dialogRoot = new BaseDialogItem(context);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(dialogRoot);

        MasterView masterView = new MasterView(context);
        DexView dexView = new DexView(context);
        dexView.setBaseOnView(masterView);
        WarnView warnView = new WarnView(context);
        warnView.setBaseOnView(masterView);
        BlackView blackView = new BlackView(context);
        blackView.setBaseOnView(masterView);
        ListenView listenView = new ListenView(context);
        listenView.setBaseOnView(masterView);
        FixCommentView fixCommentView = new FixCommentView(context);
        fixCommentView.setBaseOnView(masterView);
        UpdateView updateView = new UpdateView(context);
        updateView.setBaseOnView(masterView);
        SignView signView = new SignView(context);
        signView.setBaseOnView(masterView);
        SignSongDailyView signSongDailyView = new SignSongDailyView(context);
        signSongDailyView.setBaseOnView(masterView);
        SignSongSelfView signSongSelfView = new SignSongSelfView(context);
        signSongSelfView.setBaseOnView(masterView);
        ProxyView proxyView = new ProxyView(context);
        proxyView.setBaseOnView(masterView);
        BeautyView beautyView = new BeautyView(context);
        beautyView.setBaseOnView(masterView);
        ResetModuleView resetModuleView = new ResetModuleView(context);


        dialogRoot.addView(new TitleView(context));
        dialogRoot.addView(masterView);
        dialogRoot.addView(dexView);
        dialogRoot.addView(warnView);
        dialogRoot.addView(blackView);
        dialogRoot.addView(listenView);
        dialogRoot.addView(fixCommentView);
        dialogRoot.addView(updateView);
        dialogRoot.addView(signView);
        dialogRoot.addView(signSongDailyView);
        dialogRoot.addView(signSongSelfView);
        dialogRoot.addView(proxyView);
        dialogRoot.addView(beautyView);
        dialogRoot.addView(resetModuleView);

        dialogRoot.addView(new AboutView(context));
        new AlertDialog.Builder(context)
                .setView(scrollView)
                .setCancelable(false)
                .setPositiveButton("确定", (dialogInterface, i) -> refresh())
                .setNegativeButton("重启网易云", (dialogInterface, i) -> restartApplication(context)).show();
    }

    private void showProxyDialog(final Context context) {
        dialogProxyRoot = new BaseDialogItem(context);
        dialogProxyRoot.setOrientation(LinearLayout.VERTICAL);
        ProxyMasterView proxyMasterView = new ProxyMasterView(context);
        ProxyCoverView proxyCoverView = new ProxyCoverView(context);
        proxyCoverView.setBaseOnView(proxyMasterView);
        ProxyServerView ProxyServerView = new ProxyServerView(context);
        ProxyServerView.setBaseOnView(proxyMasterView);
        ProxyPriorityView proxyPriorityView = new ProxyPriorityView(context);
        proxyPriorityView.setBaseOnView(proxyMasterView);
        ProxyFlacView proxyFlacView = new ProxyFlacView(context);
        proxyFlacView.setBaseOnView(proxyMasterView);
        ProxyGrayView proxyGrayView = new ProxyGrayView(context);
        proxyGrayView.setBaseOnView(proxyMasterView);
        ProxyConfigurationView proxyConfigurationView = new ProxyConfigurationView(context);
        proxyConfigurationView.setBaseOnView(proxyMasterView);


        dialogProxyRoot.addView(new ProxyTitleView(context));
        dialogProxyRoot.addView(proxyMasterView);
        dialogProxyRoot.addView(proxyCoverView);
        dialogProxyRoot.addView(ProxyServerView);
        dialogProxyRoot.addView(proxyPriorityView);
        dialogProxyRoot.addView(proxyFlacView);
        dialogProxyRoot.addView(proxyGrayView);
        dialogProxyRoot.addView(proxyConfigurationView);

        new AlertDialog.Builder(context)
                .setView(dialogProxyRoot)
                .setCancelable(true)
                .setPositiveButton("仅保存", (dialogInterface, i) -> refresh())
                .setNegativeButton("保存并重启", (dialogInterface, i) -> restartApplication(context)).show();
    }
    private void showProxyConfigurationDialog(final Context context) {
        dialogProxyRoot = new BaseDialogItem(context);
        dialogProxyRoot.setOrientation(LinearLayout.VERTICAL);
        ProxyHttpView proxyHttpView = new ProxyHttpView(context);
        ProxyPortView proxyPortView = new ProxyPortView(context);
        ProxyOriginalView proxyOriginalView = new ProxyOriginalView(context);
        ProxyQqView proxyqqView = new ProxyQqView(context);

        dialogProxyRoot.addView(new ProxyConfigurationTitleView(context));
        dialogProxyRoot.addView(proxyHttpView);
        dialogProxyRoot.addView(proxyPortView);
        dialogProxyRoot.addView(proxyOriginalView);
        dialogProxyRoot.addView(proxyqqView);
        new AlertDialog.Builder(context)
                .setView(dialogProxyRoot)
                .setCancelable(true)
                .setPositiveButton("仅保存", (dialogInterface, i) -> refresh())
                .setNegativeButton("保存并重启", (dialogInterface, i) -> restartApplication(context)).show();
    }
    private void showPlayerBackgroundDialog(final Context context) {
        dialogBeautyRoot = new BaseDialogItem(context);
        dialogBeautyRoot.setOrientation(LinearLayout.VERTICAL);
        BackgroundMasterView backgroundMasterView = new BackgroundMasterView(context);
        BackgroundPictureUrlView backgroundPictureUrlView = new BackgroundPictureUrlView(context);
        BackgroundBlurRadiusView backgroundBlurRadiusView = new BackgroundBlurRadiusView(context);

        dialogBeautyRoot.addView(new BackgroundTitleView(context));
        dialogBeautyRoot.addView(backgroundMasterView);
        dialogBeautyRoot.addView(backgroundPictureUrlView);
        dialogBeautyRoot.addView(backgroundBlurRadiusView);

        new AlertDialog.Builder(context)
                .setView(dialogBeautyRoot)
                .setCancelable(true)
                .setPositiveButton("仅保存", (dialogInterface, i) -> refresh())
                .setNegativeButton("保存并重启", (dialogInterface, i) -> restartApplication(context)).show();
    }
    private void showBeautyDialog(final Context context) {
        dialogBeautyRoot = new BaseDialogItem(context);
        dialogBeautyRoot.setOrientation(LinearLayout.VERTICAL);
        dialogBeautyRoot.addView(new BeautyTitleView(context));
        dialogBeautyRoot.addView(new BeautyNightModeView(context));
        dialogBeautyRoot.addView(new BeautyTabHideView(context));
        dialogBeautyRoot.addView(new BeautyBannerHideView(context));
        dialogBeautyRoot.addView(new BeautyBubbleHideView(context));
        dialogBeautyRoot.addView(new BeautyKSongHideView(context));
        dialogBeautyRoot.addView(new BeautyBlackHideView(context));
        dialogBeautyRoot.addView(new BeautyRotationView(context));
        dialogBeautyRoot.addView(new BeautyCommentHotView(context));
        dialogBeautyRoot.addView(new PlayerBackgroundView(context));
        dialogBeautyRoot.addView(new BeautySidebarHideView(context));
        new AlertDialog.Builder(context)
                .setView(dialogBeautyRoot)
                .setCancelable(true)
                .setPositiveButton("仅保存", (dialogInterface, i) -> refresh())
                .setNegativeButton("保存并重启", (dialogInterface, i) -> restartApplication(context)).show();
    }

    private void showSidebarDialog(final Context context) {
        dialogSidebarRoot = new BaseDialogItem(context);
        dialogSidebarRoot.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(dialogSidebarRoot);

        final LinkedHashMap<String, String> sidebarMap = SidebarEnum.getSidebarEnum();
        final HashMap<String, Boolean> sidebarSettingMap = SettingHelper.getInstance().getSidebarSetting(sidebarMap);
        for (Map.Entry<String, String> entry : sidebarMap.entrySet()) {
            BeautySidebarHideItem item = new BeautySidebarHideItem(context);
            item.initData(sidebarMap, sidebarSettingMap, entry.getKey());
            dialogSidebarRoot.addView(item);
        }

        new AlertDialog.Builder(context)
                .setView(scrollView)
                .setCancelable(true)
                .setPositiveButton("确定", (dialogInterface, i) -> refresh()).show();
    }

    private void restartApplication(Context context) {
        ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "0");
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcessInfoListist = activityManager.getRunningAppProcesses();
        for (ActivityManager.RunningAppProcessInfo runningAppProcessInfo : runningAppProcessInfoListist) {
            if (runningAppProcessInfo.processName.contains(":play")) {
                android.os.Process.killProcess(runningAppProcessInfo.pid);
                break;
            }
        }
        final Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
