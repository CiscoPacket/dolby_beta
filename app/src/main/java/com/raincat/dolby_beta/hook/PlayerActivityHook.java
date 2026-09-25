package com.raincat.dolby_beta.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageSwitcher;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.ViewFlipper;

import com.raincat.dolby_beta.helper.FastBlur;
import com.raincat.dolby_beta.helper.SettingHelper;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 播放页hook (黑胶停转、隐藏K歌/音街、隐藏唱片黑胶、自定义本地/网络背景及高斯模糊)
 *     version: 3.0
 * </pre>
 */
public class PlayerActivityHook {
    private static WeakReference<Object> sLastBgObject;
    private static WeakReference<ImageSwitcher> sLastImageSwitcher;
    private static WeakReference<View> sLastBgView;
    private static WeakReference<Context> sLastContext;
    private static String sCachedKey = null;
    private static Bitmap sCachedBitmap = null;

    public PlayerActivityHook(final Context context, final int versionCode) {
        // 1. 播放界面 (PlayerActivity)
        Class<?> playerActivityClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.activity.PlayerActivity", context.getClassLoader());
        if (playerActivityClass != null) {
            XposedHelpers.findAndHookMethod(playerActivityClass, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    if (param.thisObject instanceof Activity) {
                        final Activity activity = (Activity) param.thisObject;
                        sLastContext = new WeakReference<>(activity);
                        final View decorView = activity.getWindow().getDecorView();
                        decorView.post(() -> {
                            applyBlackHideFromDecorView(decorView);
                            applyKsongHideFromDecorView(decorView);
                        });
                        decorView.postDelayed(() -> {
                            applyBlackHideFromDecorView(decorView);
                            applyKsongHideFromDecorView(decorView);
                        }, 500);
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
                            sLastContext = new WeakReference<>(activity);
                            View decorView = activity.getWindow().getDecorView();
                            applyBlackHideFromDecorView(decorView);
                            applyKsongHideFromDecorView(decorView);
                        }
                        reloadBackground();
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 2. 黑胶唱片 (PlayerDiscViewFlipper & PlayerDSLVinylView) 自动隐藏唱片圈并放大专辑封面
        Class<?> discFlipperClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.PlayerDiscViewFlipper", context.getClassLoader());
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

        String[] rotationClasses = new String[]{
                "com.netease.cloudmusic.ui.RotationRelativeLayout",
                "com.netease.cloudmusic.module.state.RotationRelativeLayout"
        };
        for (String rName : rotationClasses) {
            Class<?> rCls = XposedHelpers.findClassIfExists(rName, context.getClassLoader());
            if (rCls != null) {
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

        // 4. 自定义播放界面背景 (PlayerBackgroundImage)
        Class<?> playerBgClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.PlayerBackgroundImage", context.getClassLoader());
        if (playerBgClass != null) {
            // 构造方法中保存引用
            XposedBridge.hookAllConstructors(playerBgClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sLastBgObject = new WeakReference<>(param.thisObject);
                    if (param.args.length > 0 && param.args[0] instanceof Context) {
                        sLastContext = new WeakReference<>((Context) param.args[0]);
                    }
                    if (param.args.length > 1 && param.args[1] instanceof ImageSwitcher) {
                        sLastImageSwitcher = new WeakReference<>((ImageSwitcher) param.args[1]);
                    }
                }
            });

            // 歌曲设置封面 Drawable 时替换为自定义高斯模糊图
            try {
                XposedHelpers.findAndHookMethod(playerBgClass, "setImageDrawable", Drawable.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        sLastBgObject = new WeakReference<>(param.thisObject);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_background_key)) {
                            return;
                        }
                        String customPath = SettingHelper.getInstance().getPictureUrl();
                        if (TextUtils.isEmpty(customPath)) {
                            return;
                        }
                        Context ctx = getContextFromBgObject(param.thisObject);
                        if (ctx == null) ctx = context;

                        String cleanPath = customPath.startsWith("file://") ? customPath.substring(7) : customPath;
                        if (cleanPath.startsWith("/")) {
                            File file = new File(cleanPath);
                            if (file.exists() && file.isFile() && file.length() > 0) {
                                int blurRadius = SettingHelper.getInstance().getBackgroundBlur();
                                Bitmap blurred = getOrLoadBlurredBitmap(file, blurRadius, ctx);
                                if (blurred != null) {
                                    param.args[0] = new BitmapDrawable(ctx.getResources(), blurred);
                                }
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            // 拦截网络封面模糊加载 setBlurCover(...)
            XC_MethodHook blurCoverHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    sLastBgObject = new WeakReference<>(param.thisObject);
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_background_key)) {
                        return;
                    }
                    String customPath = SettingHelper.getInstance().getPictureUrl();
                    if (TextUtils.isEmpty(customPath)) {
                        return;
                    }
                    Context ctx = getContextFromBgObject(param.thisObject);
                    if (ctx == null) ctx = context;

                    String cleanPath = customPath.startsWith("file://") ? customPath.substring(7) : customPath;
                    if (cleanPath.startsWith("/")) {
                        File file = new File(cleanPath);
                        if (file.exists() && file.isFile() && file.length() > 0) {
                            int blurRadius = SettingHelper.getInstance().getBackgroundBlur();
                            Bitmap blurred = getOrLoadBlurredBitmap(file, blurRadius, ctx);
                            if (blurred != null) {
                                BitmapDrawable d = new BitmapDrawable(ctx.getResources(), blurred);
                                XposedHelpers.callMethod(param.thisObject, "setImageDrawable", d);
                                param.setResult(null); // 拦截后续网络加载
                                return;
                            }
                        }
                    } else if (customPath.startsWith("http://") || customPath.startsWith("https://")) {
                        if (param.args.length > 0 && param.args[0] instanceof String) {
                            param.args[0] = customPath;
                        }
                        if (param.args.length > 1 && param.args[1] instanceof String) {
                            param.args[1] = customPath;
                        }
                    }
                }
            };

            for (Method m : playerBgClass.getDeclaredMethods()) {
                if ("setBlurCover".equals(m.getName())) {
                    try {
                        XposedBridge.hookMethod(m, blurCoverHook);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 兼容旧版混淆类
        Class<?> rClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.r", context.getClassLoader());
        if (rClass != null) {
            try {
                XposedHelpers.findAndHookMethod(rClass, "a", String.class, String.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_background_key)) {
                            return;
                        }
                        String customPath = SettingHelper.getInstance().getPictureUrl();
                        if (TextUtils.isEmpty(customPath)) {
                            return;
                        }
                        if (customPath.startsWith("http://") || customPath.startsWith("https://")) {
                            param.args[0] = customPath;
                            param.args[1] = customPath;
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 实时重新加载播放页背景
     */
    public static void reloadBackground() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_background_key)) {
                    return;
                }
                String customPath = SettingHelper.getInstance().getPictureUrl();
                if (TextUtils.isEmpty(customPath)) {
                    return;
                }
                String cleanPath = customPath.startsWith("file://") ? customPath.substring(7) : customPath;
                if (!cleanPath.startsWith("/")) {
                    return;
                }
                File file = new File(cleanPath);
                if (!file.exists() || !file.isFile() || file.length() == 0) {
                    return;
                }
                Context ctx = sLastContext != null ? sLastContext.get() : null;
                Object bgObj = sLastBgObject != null ? sLastBgObject.get() : null;
                if (ctx == null && bgObj != null) {
                    ctx = getContextFromBgObject(bgObj);
                }
                if (ctx == null && sLastImageSwitcher != null && sLastImageSwitcher.get() != null) {
                    ctx = sLastImageSwitcher.get().getContext();
                }
                if (ctx == null) {
                    return;
                }

                int blurRadius = SettingHelper.getInstance().getBackgroundBlur();
                Bitmap blurred = getOrLoadBlurredBitmap(file, blurRadius, ctx);
                if (blurred == null) {
                    return;
                }
                BitmapDrawable drawable = new BitmapDrawable(ctx.getResources(), blurred);

                if (bgObj != null) {
                    try {
                        XposedHelpers.callMethod(bgObj, "setImageDrawable", drawable);
                    } catch (Throwable ignored) {
                    }
                }
                if (sLastImageSwitcher != null) {
                    ImageSwitcher is = sLastImageSwitcher.get();
                    if (is != null) {
                        is.setImageDrawable(drawable);
                    }
                }
                if (sLastBgView != null) {
                    View v = sLastBgView.get();
                    if (v != null) {
                        applyBitmapToView(v, blurred);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] reloadBackground failed: " + t);
            }
        });
    }

    private static Context getContextFromBgObject(Object bgObj) {
        if (bgObj == null) return sLastContext != null ? sLastContext.get() : null;
        try {
            Field f = bgObj.getClass().getDeclaredField("mContext");
            f.setAccessible(true);
            Context c = (Context) f.get(bgObj);
            if (c != null) return c;
        } catch (Throwable ignored) {
        }
        try {
            Field f = bgObj.getClass().getDeclaredField("mImageSwitcher");
            f.setAccessible(true);
            View v = (View) f.get(bgObj);
            if (v != null) return v.getContext();
        } catch (Throwable ignored) {
        }
        return sLastContext != null ? sLastContext.get() : null;
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
            if (maskBottom != null && maskBottom.getVisibility() != View.GONE) {
                maskBottom.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
        try {
            View maskTop = (View) XposedHelpers.getObjectField(view, "maskTopView");
            if (maskTop != null && maskTop.getVisibility() != View.GONE) {
                maskTop.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
        try {
            ImageView iv = (ImageView) XposedHelpers.getObjectField(view, "imageView");
            if (iv != null) {
                ViewGroup.LayoutParams lp = iv.getLayoutParams();
                if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    iv.setLayoutParams(lp);
                }
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
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
                    // 若无明确ID，取尺寸较小者或最内层元素作为封面
                    ImageView first = imageViews.get(0);
                    ImageView second = imageViews.get(1);
                    if (first.getWidth() > second.getWidth() && second.getWidth() > 0) {
                        albumImage = second;
                    } else {
                        albumImage = first;
                    }
                }

                // 将除封面外的外圈底盘/光斑/遮罩设为不可见
                for (ImageView iv : imageViews) {
                    if (iv != albumImage) {
                        if (iv.getVisibility() != View.INVISIBLE) {
                            iv.setVisibility(View.INVISIBLE);
                        }
                    }
                }

                ViewGroup.LayoutParams lp = albumImage.getLayoutParams();
                if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    albumImage.setLayoutParams(lp);
                }
                albumImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
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

    private static void applyKsongHideFromDecorView(View root) {
        if (root == null || !SettingHelper.getInstance().isEnable(SettingHelper.beauty_ksong_hide_key)) {
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyKsongHideFromDecorView(vg.getChildAt(i));
            }
        }
        CharSequence desc = root.getContentDescription();
        if (desc != null) {
            String d = desc.toString();
            if (d.contains("K歌") || d.contains("音街") || d.contains("铃声") || d.contains("伴奏") || d.contains("唱这首歌") || d.equals("唱")) {
                root.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = root.getLayoutParams();
                if (lp != null) {
                    lp.width = 0;
                    lp.height = 0;
                    root.setLayoutParams(lp);
                }
            }
        }
        try {
            if (root.getId() != View.NO_ID && root.getResources() != null) {
                String entryName = root.getResources().getResourceEntryName(root.getId());
                if (entryName != null) {
                    String lower = entryName.toLowerCase();
                    if (lower.contains("ksong") || lower.contains("karaoke") || lower.contains("sing_song") || lower.contains("ring_tone")) {
                        root.setVisibility(View.GONE);
                        ViewGroup.LayoutParams lp = root.getLayoutParams();
                        if (lp != null) {
                            lp.width = 0;
                            lp.height = 0;
                            root.setLayoutParams(lp);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyBitmapToView(View targetView, Bitmap blurred) {
        if (targetView == null || blurred == null) {
            return;
        }
        Runnable apply = () -> {
            try {
                if (targetView instanceof ImageView) {
                    ImageView iv = (ImageView) targetView;
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageDrawable(new BitmapDrawable(targetView.getResources(), blurred));
                } else {
                    try {
                        Method m = targetView.getClass().getMethod("setImageDrawable", Drawable.class);
                        m.invoke(targetView, new BitmapDrawable(targetView.getResources(), blurred));
                    } catch (Throwable ignored) {
                        if (targetView instanceof ViewGroup) {
                            ViewGroup vg = (ViewGroup) targetView;
                            for (int i = 0; i < vg.getChildCount(); i++) {
                                View child = vg.getChildAt(i);
                                if (child instanceof ImageView) {
                                    ((ImageView) child).setScaleType(ImageView.ScaleType.CENTER_CROP);
                                    ((ImageView) child).setImageDrawable(new BitmapDrawable(targetView.getResources(), blurred));
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] applyBitmapToView error: " + t);
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run();
        } else {
            targetView.post(apply);
        }
    }

    private static synchronized Bitmap getOrLoadBlurredBitmap(File file, int blurRadius, Context context) {
        String cacheKey = file.getAbsolutePath() + "_" + file.lastModified() + "_r" + blurRadius;
        if (cacheKey.equals(sCachedKey) && sCachedBitmap != null && !sCachedBitmap.isRecycled()) {
            return sCachedBitmap;
        }

        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null;
            }

            int targetW = 480;
            int targetH = 800;
            if (context != null && context.getResources() != null) {
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                if (dm != null && dm.widthPixels > 0) {
                    targetW = Math.max(360, dm.widthPixels / 2);
                    targetH = Math.max(640, dm.heightPixels / 2);
                }
            }

            options.inSampleSize = calculateInSampleSize(options, targetW, targetH);
            options.inJustDecodeBounds = false;
            options.inMutable = true;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            Bitmap raw = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (raw == null) {
                return null;
            }

            Bitmap result;
            if (blurRadius <= 0) {
                result = raw;
            } else {
                int radius = Math.max(1, Math.min(50, blurRadius));
                result = FastBlur.doBlur(raw, radius, true);
            }

            if (result != null) {
                if (sCachedBitmap != null && !sCachedBitmap.isRecycled() && sCachedBitmap != result) {
                    sCachedBitmap.recycle();
                }
                sCachedKey = cacheKey;
                sCachedBitmap = result;
                return result;
            }
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] load custom bg bitmap failed: " + t);
        }
        return null;
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }
}
