package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.view.View;

import com.raincat.dolby_beta.helper.SettingHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 隐藏小红点与各类气泡提示
 *     version: 2.0
 * </pre>
 */
public class HideBubbleHook {
    public HideBubbleHook(Context context) {
        String[] bubbleClassNames = new String[]{
                "com.netease.cloudmusic.ui.MessageBubbleView",
                "com.netease.cloudmusic.theme.ui.MessageBubbleView",
                "com.netease.cloudmusic.ui.CommonMessageBubbleView",
                "com.netease.cloudmusic.widget.bubble.BubbleView",
                "com.netease.cloudmusic.theme.ui.MenuIconShortBubbleView",
                "com.netease.cloudmusic.theme.ui.MenuIconLongBubbleView",
                "com.netease.cloudmusic.module.hint.view.BottomBubbleHintContainer",
                "com.netease.cloudmusic.module.hint.view.containers.BubbleHintContainer",
                "com.netease.cloudmusic.module.mymusic.view.RedDotView",
                "com.netease.cloudmusic.module.mymusic.view.RedDotViewWithBorder",
                "com.netease.cloudmusic.music.biz.circle.ui.RedDotBubbleView",
                "com.netease.cloudmusic.module.listentogether.mic.LTRedDotView",
                "com.netease.cloudmusic.ui.BadgeView",
                "com.netease.cloudmusic.music.biz.voice.widget.CustomBadgeView",
                "com.netease.cloudmusic.main.hint.BottomTabGuideBubbleNativeView",
                "com.netease.cloudmusic.main.hint.BottomTabGuideBubbleContainer",
                "com.netease.cloudmusic.main.hint.BottomTabDSLBubbleHintView",
                "com.netease.cloudmusic.main.hint.FMAwardVipTabBubbleHintView",
                "com.netease.cloudmusic.module.player.controller.BottomTabDSLBubbleHintView",
                "com.netease.cloudmusic.module.player.controller.BottomTabDSLBubbleHintV2View",
                "com.netease.cloudmusic.module.player.controller.hintview.TopTabDSLBubbleHintView",
                "com.netease.cloudmusic.discovery.view.widget.titleview.DiscoveryTitleBubbleView",
                "com.netease.cloudmusic.discovery.view.arkview.ArkTabBubbleView"
        };

        final Set<Class<?>> bubbleClasses = new HashSet<>();
        XC_MethodHook hideAttachHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_bubble_hide_key)) {
                    ((View) param.thisObject).setVisibility(View.GONE);
                }
            }
        };

        XC_MethodHook noDrawHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_bubble_hide_key)) {
                    param.setResult(null);
                }
            }
        };

        for (String name : bubbleClassNames) {
            Class<?> c = findClassIfExists(name, context.getClassLoader());
            if (c != null) {
                bubbleClasses.add(c);
                try {
                    findAndHookMethod(c, "onAttachedToWindow", hideAttachHook);
                } catch (Throwable ignored) {
                }
                try {
                    findAndHookMethod(c, "onDraw", android.graphics.Canvas.class, noDrawHook);
                } catch (Throwable ignored) {
                }
            }
        }

        // Hook 红点状态模型 (PlayerListRedDotState)
        Class<?> redDotStateClass = findClassIfExists("com.netease.cloudmusic.module.player.redux.meta.PlayerListRedDotState", context.getClassLoader());
        if (redDotStateClass != null) {
            XC_MethodHook falseHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_bubble_hide_key)) {
                        param.setResult(false);
                    }
                }
            };
            try {
                findAndHookMethod(redDotStateClass, "getShowDot", falseHook);
            } catch (Throwable ignored) {
            }
            try {
                findAndHookMethod(redDotStateClass, "getRedDotVisible", falseHook);
            } catch (Throwable ignored) {
            }
        }

        findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                if ((int) param.args[0] == View.GONE) {
                    return;
                }
                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_bubble_hide_key)) {
                    return;
                }
                Class<?> objCls = param.thisObject.getClass();
                if (bubbleClasses.contains(objCls)) {
                    param.args[0] = View.GONE;
                    return;
                }
                String name = objCls.getName();
                if (name.contains("MessageBubbleView")
                        || name.contains("MenuIconShortBubbleView")
                        || name.contains("MenuIconLongBubbleView")
                        || name.contains("BottomBubbleHintContainer")
                        || name.contains("RedDotView")
                        || name.contains("RedDotBubbleView")
                        || (name.contains("BubbleHint") && !name.contains("ChatBubble"))
                        || (name.contains("BadgeView") && !name.contains("Chat"))) {
                    bubbleClasses.add(objCls);
                    param.args[0] = View.GONE;
                }
            }
        });
    }
}