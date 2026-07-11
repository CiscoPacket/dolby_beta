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
 * desc   : 设置注入适配
 * version: 1.1
 * </pre>
 */
public class SettingHook {
    private String SettingActivity;
    private String switchViewName = "";
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
        if (settingActivityClass == null) {
            XposedBridge.log("DolbyBeta: 警告，未找到设置界面 Activity 类 " + SettingActivity);
            return;
        }

        // 尝试自动匹配开关组件名称
        Field[] allFields = settingActivityClass.getDeclaredFields();
        for (Field field : allFields) {
            if (field.getType().getName().contains("Switch")) {
                switchViewName = field.getName();
                break;
            }
        }

        findAndHookMethod(settingActivityClass, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                final Activity activity = (Activity) param.thisObject;
                Context c = activity.getApplicationContext() != null ? activity.getApplicationContext() : activity;
                
                // 注册控制面板内部广播
                registerBroadcastReceiver(c);

                // 使用 try-catch 保护注入逻辑，防止因为新版本混淆或无开关组件导致的崩溃
                try {
                    initView(activity);
                } catch (Throwable t) {
                    XposedBridge.log("DolbyBeta: 尝试在原生控件上方注入设置按钮失败，启用强力兜底方案！失败原因: " + t.getMessage());
                    injectViewFallback(activity);
                }
            }
        });

        findAndHookMethod(settingActivityClass, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                if (broadcastReceiver != null) {
                    try {
                        Context context = (Context) param.thisObject;
                        context.unregisterReceiver(broadcastReceiver);
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void initView(final Activity activity) throws Exception {
        TextView originalText = null;
        if (switchViewName == null || switchViewName.isEmpty()) {
            throw new NoSuchFieldException("未匹配到 Switch 开关成员变量名称");
        }
        
        // 获取开关控件
        View switchCompat = (View) XposedHelpers.getObjectField(activity, switchViewName);
        if (switchCompat == null) {
            throw new NullPointerException("获取到的 Switch 开关为 null");
        }
        
        // 获取开关控件爸爸
        ViewGroup parent = (ViewGroup) switchCompat.getParent();
        // 获取开关控件爷爷
        ViewGroup grandparent = (ViewGroup) parent.getParent();

        LinearLayout linearLayout = new LinearLayout(activity);
        ViewGroup.LayoutParams layoutParams = parent.getLayoutParams();
        linearLayout.setLayoutParams(layoutParams);
        linearLayout.setBackground(parent.getBackground());
        linearLayout.setGravity(Gravity.CENTER_VERTICAL);
        linearLayout.setOrientation(LinearLayout.HORIZONTAL);
        grandparent.addView(linearLayout, 0);

        titleView = new TextView(activity);
        linearLayout.addView(titleView);
        subView = new TextView(activity);
        linearLayout.addView(subView);
        refresh();
        
        start:
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof TextView) {
                originalText = (TextView) parent.getChildAt(i);
                break;
            } else if (parent.getChildAt(i) instanceof ViewGroup) {
                for (int j = 0; j < ((ViewGroup) parent.getChildAt(i)).getChildCount(); j++) {
                    if (((ViewGroup) parent.getChildAt(i)).getChildAt(j) instanceof TextView) {
                        originalText = (TextView) ((ViewGroup) parent.getChildAt(i)).getChildAt(j);
                        break start;
                    }
                }
            }
        }

        if (originalText != null) {
            titleView.setTextColor(originalText.getTextColors());
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalText.getTextSize());
            titleView.setPadding(originalText.getPaddingLeft() == 0 ? Tools.dp2px(activity, 10) : originalText.getPaddingLeft(), 0, 0, 0);
            subView.setTextColor(originalText.getTextColors());
            subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, (int) (originalText.getTextSize() / 3.0 * 2.0));
        }

        linearLayout.setOnClickListener(view -> showSettingDialog(activity));
    }

    /**
     * 强力追加式兜底方案。
     * 无论新旧版网易云音乐，直接获取 DecorView 的主视图容器，强行在设置页最下方塞入杜比大喇叭入口。
     */
    private void injectViewFallback(final Activity activity) {
        try {
            // 获取 Activity 的顶层 Content 容器（最安全的安卓标准布局根节点）
            final ViewGroup contentParent = activity.findViewById(android.R.id.content);
            if (contentParent == null) return;

            // 尝试在最下面寻找可能的 ScrollView，如果没有，就自己包装一个置顶视图
            View mainContainer = contentParent.getChildAt(0);
            if (!(mainContainer instanceof ViewGroup)) return;
            ViewGroup rootGroup = (ViewGroup) mainContainer;

            // 寻找最深层的 LinearLayout 以便进行追加。如果找不到则退而求其次直接加在 Root
            ViewGroup targetLayout = findLinearLayoutDeep(rootGroup);
            if (targetLayout == null) {
                targetLayout = rootGroup;
            }

            final Context context = activity;
            LinearLayout appendLayout = new LinearLayout(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Tools.dp2px(context, 55));
            lp.setMargins(0, Tools.dp2px(context, 10), 0, 0);
            appendLayout.setLayoutParams(lp);
            appendLayout.setOrientation(LinearLayout.HORIZONTAL);
            appendLayout.setGravity(Gravity.CENTER_VERTICAL);
            appendLayout.setPadding(Tools.dp2px(context, 15), 0, Tools.dp2px(context, 15), 0);
            
            // 设定一个稍微显眼的背景，方便暗黑/亮色主题完美契合
            TypedValue typedValue = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)) {
                appendLayout.setBackgroundResource(typedValue.resourceId);
            }

            titleView = new TextView(context);
            titleView.setText("杜比大喇叭β [高级设置入口]");
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(0xFFE53935); // 醒目的网易云红配色
            titleView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            subView = new TextView(context);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            subView.setTextColor(0x8A000000);

            appendLayout.addView(titleView);
            appendLayout.addView(subView);
            refresh();

            appendLayout.setOnClickListener(view -> showSettingDialog(activity));
            targetLayout.addView(appendLayout);
            XposedBridge.log("DolbyBeta: 兜底设置入口成功追加在最下方。");
        } catch (Throwable e) {
            XposedBridge.log("DolbyBeta: 致命错误，兜底设置界面注入彻底失败: " + e.getMessage());
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
