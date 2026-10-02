package com.raincat.dolby_beta;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Environment;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.FileHelper;
import com.raincat.dolby_beta.helper.NotificationHelper;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.hook.AdAndUpdateHook;
import com.raincat.dolby_beta.hook.AutoSignInHook;
import com.raincat.dolby_beta.hook.BlackHook;
import com.raincat.dolby_beta.hook.CdnHook;
import com.raincat.dolby_beta.hook.CommentHotClickHook;
import com.raincat.dolby_beta.hook.DownloadMD5Hook;
import com.raincat.dolby_beta.hook.EAPIHook;
import com.raincat.dolby_beta.hook.GrayHook;
import com.raincat.dolby_beta.hook.HideSidebarHook;
import com.raincat.dolby_beta.hook.HideTabHook;
import com.raincat.dolby_beta.hook.InternalDialogHook;
import com.raincat.dolby_beta.hook.MagiskFixHook;
import com.raincat.dolby_beta.hook.AdExtraHook;
import com.raincat.dolby_beta.hook.PlayerActivityHook;
import com.raincat.dolby_beta.hook.ProxyHook;
import com.raincat.dolby_beta.hook.SettingHook;
import com.raincat.dolby_beta.hook.UserProfileHook;
import com.raincat.dolby_beta.utils.Tools;

import java.io.File;
import java.io.IOException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookOther {
    private static String PACKAGE_NAME;
    int versionCode = 0;
    //进程初始化状态
    public boolean playProcessInit = false;
    public boolean mainProcessInit = false;
    //主线程反编译dex完成后通知可以对play进程进行hook了
    private final String msg_hook_play_process = "hookPlayProcess";
    //play进程初始化完成通知主线程
    private final String msg_play_process_init_finish = "playProcessInitFinish";
    //发通知
    public static final String msg_send_notification = "sendNotification";


    public HookOther(XC_LoadPackage.LoadPackageParam lpparam) {
        PACKAGE_NAME=lpparam.packageName;
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.netease.cloudmusic.NeteaseMusicApplication", lpparam.classLoader),
                "attachBaseContext", Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Context context = (Context) param.thisObject;
                        if(PACKAGE_NAME.equals("com.netease.cloudmusic.lite"))
                        {
                            versionCode = 140;
                        }else {
                            versionCode = 8010050;
                        }

                        //初始化仓库
                        ExtraHelper.init(context);
                        //初始化设置 (必须在 DebugLogger.init 之前, 否则调试开关读取不到, 冷启动日志收集不会启动)
                        SettingHelper.init(context);
                        //初始化调试日志系统与崩溃捕获
                        DebugLogger.init(context);
                        //初始化ClassHelper
                        ClassHelper.init(context, versionCode);

                        final String processName = Tools.getCurrentProcessName(context);
                        if (processName.equals(PACKAGE_NAME)) {
                            //设置
                            new SettingHook(context, versionCode);
                            //总开关
                            if (!SettingHelper.getInstance().getSetting(SettingHelper.master_key))
                                return;
                            //音源代理
                            new ProxyHook(context, false);
                            //黑胶
                            new BlackHook(context, versionCode);
                            if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                                deleteAdAndTinker();
                            }
                            //不变灰
                            new GrayHook(context);
                            //自动签到
                            new AutoSignInHook(context, versionCode);
                            //去广告与去升级
                            new AdAndUpdateHook(context, versionCode);
                            //修复magisk冲突导致的无法读写外置sd卡
                            new MagiskFixHook(context);
                            //去掉内测与听歌识曲弹窗
                            new InternalDialogHook(context, versionCode);
                            //美化与界面定制（不依赖DexKit，主线程同步立即注册，避免时机过晚导致不生效）
                            //逐个隔离异常: 任何一个 hook 构造失败都不能杀死后续注册 (尤其 EAPIHook 所依赖的 getCacheClassList)
                            safeHook("HideTabHook", () -> new HideTabHook(context, versionCode));
                            safeHook("HideSidebarHook", () -> new HideSidebarHook(context, versionCode));
                            safeHook("PlayerActivityHook", () -> new PlayerActivityHook(context, versionCode));
                            safeHook("CommentHotClickHook", () -> new CommentHotClickHook(context));
                            safeHook("AdExtraHook", () -> new AdExtraHook());

                            ClassHelper.getCacheClassList(context, versionCode, () -> {
                                //获取账号信息
                                safeHook("UserProfileHook", () -> new UserProfileHook(context));
                                //网络访问
                                safeHook("EAPIHook", () -> new EAPIHook(context));
                                //下载MD5校验
                                safeHook("DownloadMD5Hook", () -> new DownloadMD5Hook(context));
                                safeHook("CdnHook", () -> new CdnHook(context, versionCode));
                                safeHook("CommentHotClickDexKit", () -> CommentHotClickHook.initDexKitHooks(context));
                                //精简Tab 的 DexKit 结构特征兜底 (后台线程执行, 避免主线程 DexKit 扫描卡顿)
                                safeHook("HideTabDexKitFallback", () -> HideTabHook.onCacheClassListReady(context));

                                mainProcessInit = true;
                                if (mainProcessInit && playProcessInit)
                                    context.sendBroadcast(new Intent(msg_hook_play_process));
                            });
                            IntentFilter intentFilter = new IntentFilter();
                            intentFilter.addAction(msg_play_process_init_finish);
                            intentFilter.addAction(msg_send_notification);
                            context.registerReceiver(new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context c, Intent intent) {
                                    try {
                                        if (intent == null) return;
                                        if (msg_play_process_init_finish.equals(intent.getAction())) {
                                            playProcessInit = true;
                                            if (mainProcessInit && playProcessInit)
                                                context.sendBroadcast(new Intent(msg_hook_play_process));
                                        } else if (msg_send_notification.equals(intent.getAction())) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                                                NotificationHelper.getInstance(context).sendUnLockNotification(context, intent.getIntExtra("code", 0x10),
                                                        intent.getStringExtra("title"), intent.getStringExtra("title"), intent.getStringExtra("message"));
                                            XposedBridge.log(intent.getStringExtra("title") + "：" + intent.getStringExtra("message"));
                                        }
                                    } catch (Throwable t) {
                                        DebugLogger.e("HookOther", "BroadcastReceiver onReceive error: " + t.getMessage(), t);
                                    }
                                }
                            }, intentFilter);
                        } else if (processName.equals(PACKAGE_NAME + ":play") && SettingHelper.getInstance().getSetting(SettingHelper.master_key)) {
                            //音源代理
                            new ProxyHook(context, true);
                            IntentFilter intentFilter = new IntentFilter();
                            intentFilter.addAction(msg_hook_play_process);
                            intentFilter.addAction(SettingHelper.refresh_setting);
                            context.registerReceiver(new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context c, Intent intent) {
                                    if (intent == null) return;
                                    if (SettingHelper.refresh_setting.equals(intent.getAction())) {
                                        SettingHelper.getInstance().refreshSetting(context);
                                    } else if (msg_hook_play_process.equals(intent.getAction())) {
                                        ClassHelper.getCacheClassList(context, versionCode, () -> {
                                            new EAPIHook(context);
                                            new CdnHook(context, versionCode);
                                        });
                                    }
                                }
                            }, intentFilter);
                            context.sendBroadcast(new Intent(msg_play_process_init_finish));
                        }
                    }
                });
    }

    /**
     * 单个 hook 注册的异常隔离: 一个 hook 失败不影响其余功能与后续初始化
     */
    private static void safeHook(String name, Runnable registration) {
        try {
            registration.run();
        } catch (Throwable t) {
            DebugLogger.e("HookOther", name + " init error: " + t.getMessage(), t);
            XposedBridge.log("[dolby_beta] " + name + " init error: " + t.getMessage());
        }
    }

    private void deleteAdAndTinker() {
        try {
            //广告缓存路径
            String CACHE_PATH3 = Environment.getExternalStorageDirectory() + "/netease/cloudmusic/lite/Ad";
            if(PACKAGE_NAME.equals("com.hihonor.cloudmusic"))
            {
                CACHE_PATH3 = Environment.getExternalStorageDirectory() + "/hihonor/cloudmusic/Ad";
            }
            String CACHE_PATH4 = Environment.getExternalStorageDirectory() + "/Android/data/" + PACKAGE_NAME + "/cache/Ad";
            String TINKER_PATH = "/data/data/" + PACKAGE_NAME + "/tinker";

            FileHelper.deleteDirectory(CACHE_PATH3);
            FileHelper.deleteDirectory(CACHE_PATH4);

            File tinkerFile = new File(TINKER_PATH);
            if (tinkerFile.exists() && tinkerFile.isDirectory())
                FileHelper.deleteDirectory(TINKER_PATH);
            if (!tinkerFile.exists())
                tinkerFile.createNewFile();

            String command = "chmod 000 " + tinkerFile.getAbsolutePath();
            Runtime runtime = Runtime.getRuntime();
            runtime.exec(command);
        } catch (Throwable t) {
            DebugLogger.e("HookOther", "deleteAdAndTinker error: " + t.getMessage(), t);
        }
    }
}