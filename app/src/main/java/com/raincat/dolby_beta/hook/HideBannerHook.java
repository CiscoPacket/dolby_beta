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

        // 3. 数据层拦截 BannerViewHelper.setBannerSync (清空数据，触发网易云自带折叠逻辑)
        Class<?> bannerHelperClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.BannerViewHelper", context.getClassLoader());
        if (bannerHelperClass != null) {
            for (Method m : bannerHelperClass.getDeclaredMethods()) {
                if ("setBannerSync".equals(m.getName())) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_banner_hide_key)) {
                                    if (param.args.length > 0 && param.args[0] != null) {
                                        param.args[0] = new ArrayList<>();
                                    }
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 4. 次级/其他Banner组件
        String[] otherBannerClasses = new String[]{
                "com.netease.cloudmusic.ui.DailyMusicBannerView",
                "com.netease.cloudmusic.ui.AdBannerView",
                "com.netease.cloudmusic.ui.CommentBannerViewContainer",
                "com.netease.cloudmusic.music.biz.voice.homepage.recommend.banner.BannerViewHolder"
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
}
