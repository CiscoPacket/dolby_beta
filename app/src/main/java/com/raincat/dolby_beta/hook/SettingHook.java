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
import android.view.ViewTreeObserver;
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
 * author : RainCat (Optimized by Assistant)
 * time   : 2026/07/12
 * desc   : 动态生命周期监听 + 智能容器包裹 + 根窗体劫持 终极方案
 * version: 2.0
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
        if (settingActivityClass == null) {
            XposedBridge.log("DolbyBeta: 未找到目标设置 Activity: " + SettingActivity);
            return;
        }

        registerBroadcastReceiver(context.getApplicationContext() != null ? context.getApplicationContext() : context);

        findAndHookMethod(settingActivityClass, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                final Activity activity = (Activity) param.thisObject;
                final View decorView = activity.getWindow().getDecorView();

                // 使用全局布局树监听器，动态捕获异步加载的 UI 节点
                decorView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        try {
                            // 检查是否已经完成了某种形式的注入，防止重复操作死循环
                            if (decorView.findViewWithTag("dolby_setting_entry") != null ||
                                decorView.findViewWithTag("dolby_scroll_wrapper") != null ||
                                decorView.findViewWithTag("dolby_content_wrapped") != null) {
                                return;
                            }

                            // 尝试执行强力注入
                            if (tryInjectSmartly(activity)) {
                                // 注入成功后解绑监听，释放资源
                                decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("DolbyBeta: 动态注入发生异常: " + t.getMessage());
                        }
                    }
                });
            }
        });

        findAndHookMethod(settingActivityClass, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                if (broadcastReceiver != null) {
                    try {
                        Context ctx = (Context) param.thisObject;
                        ctx.unregisterReceiver(broadcastReceiver);
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    /**
     * 核心多级注入逻辑
     */
    private boolean tryInjectSmartly(Activity activity) {
        View decorView = activity.getWindow().getDecorView();

        // -----------------------------------------------------------------
        // 策略 A：智能滚动容器子节点包裹法（兼容 ScrollView 和 NestedScrollView）
        // -----------------------------------------------------------------
        ViewGroup scrollContainer = findScrollContainerDeep(decorView);
        if (scrollContainer != null && scrollContainer.getChildCount() > 0) {
            View originalChild = scrollContainer.getChildAt(0);
            
            // 防重复校验
            if (originalChild.getTag() != null && originalChild.getTag().equals("dolby_scroll_wrapper")) {
                return true;
            }

            // 剥离原有的唯一子布局
            scrollContainer.removeView(originalChild);

            // 建立我们可控的垂直线性流布局
            LinearLayout scrollWrapper = new LinearLayout(activity);
            scrollWrapper.setTag("dolby_scroll_wrapper");
            scrollWrapper.setOrientation(LinearLayout.VERTICAL);
            scrollWrapper.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 创建并打标签
            View dolbyView = createSettingItemView(activity);
            
            // 重新按顺序组装：我们的入口在最顶端，原生的布局跟在下面
            scrollWrapper.addView(dolbyView);
            scrollWrapper.addView(originalChild);

            // 回填至滚动布局中
            scrollContainer.addView(scrollWrapper);
            
            refresh();
            XposedBridge.log("DolbyBeta: 【策略 A 成功】已通过滚动容器包装完成注入！");
            return true;
        }

        // -----------------------------------------------------------------
        // 策略 B：终极降维打击（针对未知复杂布局或 RecyclerView/Compose 页面的绝对兜底）
        // -----------------------------------------------------------------
        ViewGroup contentParent = activity.findViewById(android.R.id.content);
        if (contentParent != null && contentParent.getChildCount() > 0) {
            View originalRoot = contentParent.getChildAt(0);
            
            if (originalRoot.getTag() != null && originalRoot.getTag().equals("dolby_content_wrapped")) {
                return true;
            }

            // 斩断原 Activity 根视图与其父容器的连接
            contentParent.removeView(originalRoot);

            // 建立顶层全屏劫持容器
            LinearLayout contentWrapper = new LinearLayout(activity);
            contentWrapper.setTag("dolby_content_wrapped");
            contentWrapper.setOrientation(LinearLayout.VERTICAL);
            contentWrapper.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 强行把杜比大喇叭横条塞到最顶部
            View dolbyView = createSettingItemView(activity);
            contentWrapper.addView(dolbyView);

            // 让网易云原本的整个界面吃掉底下所有剩余的空间 (weight = 1.0f)
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
            originalRoot.setLayoutParams(lp);
            contentWrapper.addView(originalRoot);

            // 将拼装好的魔改版布局塞回系统根窗体
            contentParent.addView(contentWrapper);

            refresh();
            XposedBridge.log("DolbyBeta: 【策略 B 成功】已启动终极降维打击，完成顶层窗体劫持注入！");
            return true;
        }

        return false;
    }

    /**
     * 深度广度复合遍历：寻找并兼容经典 ScrollView 与 AndroidX 的 NestedScrollView
     */
    private ViewGroup findScrollContainerDeep(View view) {
        if (view == null) return null;
        String className = view.getClass().getName();
        
        if (view instanceof ScrollView || className.equals("androidx.core.widget.NestedScrollView")) {
            return (ViewGroup) view;
        }
        
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ViewGroup res = findScrollContainerDeep(vg.getChildAt(i));
                if (res != null) return res;
            }
        }
        return null;
    }

    /**
     * 创建原生风格的列表点击项
     */
    private View createSettingItemView(final Activity activity) {
        LinearLayout appendLayout = new LinearLayout(activity);
        appendLayout.setTag("dolby_setting_entry");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tools.dp2px(activity, 55));
        appendLayout.setLayoutParams(lp);
        appendLayout.setOrientation(LinearLayout.HORIZONTAL);
        appendLayout.setGravity(Gravity.CENTER_VERTICAL);
        appendLayout.setPadding(Tools.dp2px(activity, 15), 0, Tools.dp2px(activity, 15), 0);
        
        // 给予它一条灰色分隔线，看起来更像原生设置项
        appendLayout.setBackgroundColor(0xFFFFF1F1); // 淡淡的红底以示区分

        TypedValue typedValue = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)) {
            appendLayout.setBackgroundResource(typedValue.resourceId);
        }

        titleView = new TextView(activity);
        titleView.setText("⚙️ 杜比大喇叭β [高级模块配置]");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTextColor(0xFFD32F2F); // 深红高亮
        titleView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        subView = new TextView(activity);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        subView.setTextColor(0x8A000000);

        appendLayout.addView(titleView);
        appendLayout.addView(subView);
        
        appendLayout.setOnClickListener(view -> showSettingDialog(activity));
        return appendLayout;
    }

    @SuppressLint("SetTextI18n")
    private void refresh() {
        if (titleView == null || subView == null) return;
        titleView.setText("⚙️ 杜比大喇叭β [高级模块配置]");
        if (ExtraHelper.getExtraDate(ExtraHelper.USER_ID).equals("-1")) {
            subView.setText("（USERID获取失败）");
        } else if (!SettingHelper.getInstance().getSetting(SettingHelper.master_key))
            subView.setText("（已关闭）");
        else if (ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS).equals("1"))
            subView.setText("（UnblockNeteaseMusic运行中）");
        else
            subView.setText("（UnblockNeteaseMusic已停止）");
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
