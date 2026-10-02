package com.raincat.dolby_beta.hook;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 播放页hook (黑胶停转、隐藏唱片黑胶)
 *     version: 4.0
 * </pre>
 */
public class PlayerActivityHook {

    public PlayerActivityHook(final Context context, final int versionCode) {
        // 1. 播放界面 (PlayerActivity) - 唱片黑胶隐藏守护
        Class<?> playerActivityClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.activity.PlayerActivity", context.getClassLoader());
        if (playerActivityClass != null) {
            XposedHelpers.findAndHookMethod(playerActivityClass, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    if (param.thisObject instanceof Activity) {
                        final Activity activity = (Activity) param.thisObject;
                        final View decorView = activity.getWindow().getDecorView();
                        decorView.post(() -> applyBlackHideFromDecorView(decorView));
                        decorView.postDelayed(() -> applyBlackHideFromDecorView(decorView), 500);
                    }
                }
            });

            try {
                XposedHelpers.findAndHookMethod(playerActivityClass, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (param.thisObject instanceof Activity) {
                            Activity activity = (Activity) param.thisObject;
                            View decorView = activity.getWindow().getDecorView();
                            applyBlackHideFromDecorView(decorView);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 2. 黑胶唱片 (PlayerDiscViewFlipper & PlayerDSLVinylView) 隐藏唱片圈并保留专辑封面
        Class<?> discFlipperClass = ClassHelper.PlayerDiscViewFlipper.getClazz(context);
        if (discFlipperClass == null) {
            discFlipperClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.PlayerDiscViewFlipper", context.getClassLoader());
        }
        if (discFlipperClass != null) {
            try {
                XposedHelpers.findAndHookMethod(discFlipperClass, "onLayout", boolean.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_black_hide_key)) {
                            final ViewGroup vg = (ViewGroup) param.thisObject;
                            vg.post(() -> hideVinylDisc(vg));
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(discFlipperClass, "switchDisc", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_black_hide_key)) {
                            final ViewGroup vg = (ViewGroup) param.thisObject;
                            vg.post(() -> hideVinylDisc(vg));
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        Class<?> playerDslVinylClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.PlayerDSLVinylView", context.getClassLoader());
        if (playerDslVinylClass != null) {
            XC_MethodHook dslVinylHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_black_hide_key)) {
                        hideDslVinylView((View) param.thisObject);
                    }
                }
            };
            try {
                XposedBridge.hookAllConstructors(playerDslVinylClass, dslVinylHook);
            } catch (Throwable ignored) {
            }
            for (Method m : playerDslVinylClass.getDeclaredMethods()) {
                if ("onBindData".equals(m.getName())) {
                    try {
                        XposedBridge.hookMethod(m, dslVinylHook);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 3. 黑胶停转 (RotationRelativeLayout & AnimationHolder)
        XC_MethodHook stopRotationHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_rotation_key)) {
                    param.setResult(null);
                }
            }
        };

        XC_MethodHook zeroRotationHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_rotation_key)) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Number) {
                        param.args[0] = 0.0f;
                    }
                }
            }
        };

        List<Class<?>> rotationClassList = new ArrayList<>();
        Class<?> dynamicRotationClass = ClassHelper.RotationRelativeLayout.getClazz(context);
        if (dynamicRotationClass != null) {
            rotationClassList.add(dynamicRotationClass);
        }
        for (String rName : new String[]{
                "com.netease.cloudmusic.ui.RotationRelativeLayout",
                "com.netease.cloudmusic.module.state.RotationRelativeLayout"
        }) {
            Class<?> rCls = XposedHelpers.findClassIfExists(rName, context.getClassLoader());
            if (rCls != null && !rotationClassList.contains(rCls)) {
                rotationClassList.add(rCls);
            }
        }

        for (Class<?> rCls : rotationClassList) {
            try {
                XposedHelpers.findAndHookMethod(rCls, "prepareAnimation", stopRotationHook);
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(rCls, "start", stopRotationHook);
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(rCls, "setRotation", float.class, zeroRotationHook);
            } catch (Throwable ignored) {
            }
        }

        String[] animHolderClasses = new String[]{
                "com.netease.cloudmusic.ui.RotationRelativeLayout$AnimationHolder",
                "com.netease.cloudmusic.module.state.RotationRelativeLayout$a"
        };
        for (String ahName : animHolderClasses) {
            Class<?> ahCls = XposedHelpers.findClassIfExists(ahName, context.getClassLoader());
            if (ahCls != null) {
                try {
                    XposedHelpers.findAndHookMethod(ahCls, "prepareAnimation", stopRotationHook);
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.findAndHookMethod(ahCls, "start", stopRotationHook);
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.findAndHookMethod(ahCls, "startAnimator", stopRotationHook);
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.findAndHookMethod(ahCls, "onAnimationUpdate", android.animation.ValueAnimator.class, stopRotationHook);
                } catch (Throwable ignored) {
                }
                for (Method m : ahCls.getDeclaredMethods()) {
                    String mn = m.getName();
                    if ("resetRotation".equals(mn) || "setInitialRotation".equals(mn) || "b".equals(mn)) {
                        try {
                            XposedBridge.hookMethod(m, zeroRotationHook);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
    }

    private static void applyBlackHideFromDecorView(View root) {
        if (root == null || !SettingHelper.getInstance().isEnable(SettingHelper.beauty_black_hide_key)) {
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            if (vg.getClass().getName().contains("PlayerDiscViewFlipper")) {
                hideVinylDisc(vg);
                return;
            }
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyBlackHideFromDecorView(vg.getChildAt(i));
            }
        }
    }

    private static void hideDslVinylView(View view) {
        if (view == null) return;
        try {
            View maskBottom = (View) XposedHelpers.getObjectField(view, "maskBottomView");
            if (maskBottom != null) {
                maskBottom.setVisibility(View.INVISIBLE);
                maskBottom.setAlpha(0.0f);
            }
        } catch (Throwable ignored) {
        }
        try {
            View maskTop = (View) XposedHelpers.getObjectField(view, "maskTopView");
            if (maskTop != null) {
                maskTop.setVisibility(View.INVISIBLE);
                maskTop.setAlpha(0.0f);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hideVinylDisc(ViewGroup flipper) {
        if (flipper == null || !SettingHelper.getInstance().isEnable(SettingHelper.beauty_black_hide_key)) return;
        for (int i = 0; i < flipper.getChildCount(); i++) {
            View child = flipper.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup rotationLayout = (ViewGroup) child;

            // 1. 递归检测 DSL Vinyl View
            checkAndHideDSLVinyl(rotationLayout);

            // 2. 传统双层或多层 ImageView 处理
            List<ImageView> imageViews = new ArrayList<>();
            findImageViews(rotationLayout, imageViews);
            if (imageViews.size() >= 2) {
                ImageView albumImage = null;
                for (ImageView iv : imageViews) {
                    try {
                        String idName = iv.getResources().getResourceEntryName(iv.getId()).toLowerCase();
                        if (idName.contains("cover") || idName.contains("album") || idName.contains("pic")) {
                            albumImage = iv;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (albumImage == null) {
                    ImageView first = imageViews.get(0);
                    ImageView second = imageViews.get(1);
                    if (first.getWidth() > second.getWidth() && second.getWidth() > 0) {
                        albumImage = second;
                    } else {
                        albumImage = first;
                    }
                }

                // 将除封面外的外圈底盘/光斑/遮罩设为不可见，封面保持原始尺寸与居中位置不偏移
                for (ImageView iv : imageViews) {
                    if (iv != albumImage) {
                        if (iv.getVisibility() != View.INVISIBLE) {
                            iv.setVisibility(View.INVISIBLE);
                        }
                    }
                }
            }
        }
    }

    private static void checkAndHideDSLVinyl(ViewGroup root) {
        if (root == null) return;
        if (root.getClass().getName().contains("PlayerDSLVinylView")) {
            hideDslVinylView(root);
            return;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c instanceof ViewGroup) {
                checkAndHideDSLVinyl((ViewGroup) c);
            }
        }
    }

    private static void findImageViews(ViewGroup root, List<ImageView> out) {
        if (root == null || out == null) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c instanceof ImageView) {
                out.add((ImageView) c);
            } else if (c instanceof ViewGroup) {
                findImageViews((ViewGroup) c, out);
            }
        }
    }
}
