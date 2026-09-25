package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.raincat.dolby_beta.helper.SettingHelper;

import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 移除Banner (首页Banner、歌单Banner、每日推荐Banner等)
 *     version: 2.0
 * </pre>
 */
public class HideBannerHook {
    public HideBannerHook(Context context, final int versionCode) {
        final XC_MethodHook bannerAttachHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_banner_hide_key))
                    return;
                View view = (View) param.thisObject;
                ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
                if (layoutParams != null) {
                    layoutParams.height = 1; // 改成0在部分版本可能影响下拉刷新事件分发
                    view.setLayoutParams(layoutParams);
                }
                view.setVisibility(View.GONE);
            }
        };

        // 1. 首页 MainBannerContainer
        String mainBannerContainerClassString = "com.netease.cloudmusic.ui.MainBannerContainer";
        if (versionCode < 138) {
            mainBannerContainerClassString = "com.netease.cloudmusic.ui.NeteaseMusicViewFlipper";
        }
        Class<?> mainBannerClass = XposedHelpers.findClassIfExists(mainBannerContainerClassString, context.getClassLoader());
        if (mainBannerClass != null) {
            try {
                XposedHelpers.findAndHookMethod(mainBannerClass, "onAttachedToWindow", bannerAttachHook);
            } catch (Throwable ignored) {
            }
            try {
                XposedBridge.hookAllConstructors(mainBannerClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_banner_hide_key))
                            return;
                        View view = (View) param.thisObject;
                        view.setVisibility(View.GONE);
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 2. 歌单 PlaylistBanner
        String playlistBannerContainerClassString = "com.netease.cloudmusic.ui.PlaylistBanner";
        Class<?> playlistBannerClass = XposedHelpers.findClassIfExists(playlistBannerContainerClassString, context.getClassLoader());
        if (playlistBannerClass != null) {
            try {
                XposedBridge.hookAllConstructors(playlistBannerClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_banner_hide_key))
                            return;
                        final View view = (View) param.thisObject;
                        view.post(() -> {
                            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
                            if (layoutParams != null) {
                                layoutParams.height = 0;
                                view.setLayoutParams(layoutParams);
                            }
                            view.setVisibility(View.GONE);
                        });
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 3. 数据层拦截 BannerViewHelper (清空数据，触发网易云自带折叠逻辑)
        Class<?> bannerHelperClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.BannerViewHelper", context.getClassLoader());
        if (bannerHelperClass != null) {
            for (Method m : bannerHelperClass.getDeclaredMethods()) {
                String mn = m.getName();
                if ("setBannerSync".equals(mn) || "attachView".equals(mn) || "setBanners".equals(mn)) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_banner_hide_key)) {
                                    for (int i = 0; i < param.args.length; i++) {
                                        if (param.args[i] instanceof java.util.List) {
                                            param.args[i] = new ArrayList<>();
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

        // 4. 适配新版 9.x 发现页/推荐页各类 Banner ViewHolder (折叠高宽并隐藏)
        String[] bannerViewHolderClasses = new String[]{
                "com.netease.cloudmusic.discovery.view.module.banner.DiscoveryBannerViewHolder",
                "com.netease.cloudmusic.discovery.view.module.banner.v3.BannerViewHolderV3",
                "com.netease.cloudmusic.discovery.view.module.banner.music.MusicBannerViewHolder",
                "com.netease.cloudmusic.discovery.view.module.banner.rcmd.OperationRcmdBannerViewHolder",
                "com.netease.cloudmusic.discovery.view.module.banner.rcmd.RcmdBannerViewHolder",
                "com.netease.cloudmusic.music.biz.voice.homepage.recommend.banner.BannerViewHolder",
                "com.netease.cloudmusic.module.childmode.viewholder.BannerViewHolder"
        };
        for (String vhName : bannerViewHolderClasses) {
            Class<?> vhClass = XposedHelpers.findClassIfExists(vhName, context.getClassLoader());
            if (vhClass != null) {
                XposedBridge.hookAllConstructors(vhClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_banner_hide_key)) return;
                        try {
                            View itemView = (View) XposedHelpers.getObjectField(param.thisObject, "itemView");
                            collapseBannerView(itemView);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                for (Method m : vhClass.getDeclaredMethods()) {
                    String mn = m.getName();
                    if (mn.startsWith("onBind") || mn.equals("bind") || mn.equals("attach") || mn.equals("init")) {
                        try {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_banner_hide_key)) return;
                                    try {
                                        View itemView = (View) XposedHelpers.getObjectField(param.thisObject, "itemView");
                                        collapseBannerView(itemView);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }

        // 5. 次级/其他Banner组件
        String[] otherBannerClasses = new String[]{
                "com.netease.cloudmusic.ui.DailyMusicBannerView",
                "com.netease.cloudmusic.ui.AdBannerView",
                "com.netease.cloudmusic.ui.CommentBannerViewContainer",
                "com.netease.cloudmusic.newmusic.DayAndNewMusicBannerView"
        };
        for (String clsName : otherBannerClasses) {
            Class<?> cls = XposedHelpers.findClassIfExists(clsName, context.getClassLoader());
            if (cls != null) {
                try {
                    if (View.class.isAssignableFrom(cls)) {
                        XposedHelpers.findAndHookMethod(cls, "onAttachedToWindow", bannerAttachHook);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void collapseBannerView(View view) {
        if (view == null) return;
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = 0;
            lp.height = 0;
            view.setLayoutParams(lp);
        }
    }
}
