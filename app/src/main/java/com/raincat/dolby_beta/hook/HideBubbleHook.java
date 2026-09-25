package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.view.View;

import com.raincat.dolby_beta.helper.SettingHelper;

import java.util.ArrayList;
import java.util.List;

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
                "com.netease.cloudmusic.module.hint.view.BottomBubbleHintContainer"
        };

        final List<Class<?>> bubbleClasses = new ArrayList<>();
        for (String name : bubbleClassNames) {
            Class<?> c = findClassIfExists(name, context.getClassLoader());
            if (c != null) {
                bubbleClasses.add(c);
                try {
                    findAndHookMethod(c, "onAttachedToWindow", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_bubble_hide_key)) {
                                ((View) param.thisObject).setVisibility(View.GONE);
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }

        findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_bubble_hide_key)) {
                    return;
                }
                Class<?> objCls = param.thisObject.getClass();
                String name = objCls.getName();
                if (bubbleClasses.contains(objCls)
                        || name.contains("MessageBubbleView")
                        || name.contains("MenuIconShortBubbleView")
                        || name.contains("MenuIconLongBubbleView")
                        || name.contains("BottomBubbleHintContainer")) {
                    param.args[0] = View.GONE;
                }
            }
        });
    }
}