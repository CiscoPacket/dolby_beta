package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.ViewFlipper;

import com.raincat.dolby_beta.helper.FastBlur;
import com.raincat.dolby_beta.helper.SettingHelper;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/11/16
 *     desc   : 播放页hook (黑胶停转、隐藏K歌/音街、自定义本地/网络背景及高斯模糊)
 *     version: 2.0
 * </pre>
 */
public class PlayerActivityHook {
    private static WeakReference<View> sLastBgView;
    private static String sCachedKey = null;
    private static Bitmap sCachedBitmap = null;

    public PlayerActivityHook(Context context, final int versionCode) {
        Class<?> playerActivityClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.activity.PlayerActivity", context.getClassLoader());
        if (playerActivityClass != null) {
            XposedHelpers.findAndHookMethod(playerActivityClass, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    boolean black = SettingHelper.getInstance().isEnable(SettingHelper.beauty_black_hide_key);
                    boolean ksong = SettingHelper.getInstance().isEnable(SettingHelper.beauty_ksong_hide_key);
                    ViewFlipper playerDiscViewFlipper = null;
                    for (Field field : param.thisObject.getClass().getDeclaredFields()) {
                        if (black && field.getType().getName().contains("PlayerDiscViewFlipper")) {
                            field.setAccessible(true);
                            playerDiscViewFlipper = (ViewFlipper) field.get(param.thisObject);
                        }
                        if (ksong && field.getType().getName().contains("ImageView")) {
                            field.setAccessible(true);
                            ImageView imageView = (ImageView) field.get(param.thisObject);
                            if (imageView != null && imageView.getContentDescription() != null) {
                                String desc = imageView.getContentDescription().toString();
                                if (desc.contains("音街") || desc.contains("铃声")) {
                                    ViewGroup.LayoutParams layoutParams = imageView.getLayoutParams();
                                    if (layoutParams != null) {
                                        layoutParams.width = 0;
                                        layoutParams.height = 0;
                                        imageView.setLayoutParams(layoutParams);
                                    }
                                    if (imageView.getParent() instanceof View) {
                                        View parent = (View) imageView.getParent();
                                        layoutParams = parent.getLayoutParams();
                                        if (layoutParams != null) {
                                            layoutParams.width = 0;
                                            layoutParams.height = 0;
                                            parent.setLayoutParams(layoutParams);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (playerDiscViewFlipper == null) {
                        return;
                    }
                    for (int i = 0; i < playerDiscViewFlipper.getChildCount(); i++) {
                        View coverView = null, imageView = null;
                        RelativeLayout rotationRelativeLayout = (RelativeLayout) playerDiscViewFlipper.getChildAt(i);
                        for (int j = 0; j < rotationRelativeLayout.getChildCount(); j++) {
                            View child = rotationRelativeLayout.getChildAt(j);
                            if (child.getClass().getName().contains("ImageView")
                                    && child.getClass().getName().contains("android")) {
                                coverView = child;
                            } else {
                                imageView = child;
                            }
                        }
                        if (coverView != null && imageView != null) {
                            final View coverViewF = coverView;
                            final View imageViewF = imageView;
                            coverView.post(() -> {
                                RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) imageViewF.getLayoutParams();
                                if (layoutParams != null) {
                                    layoutParams.height = coverViewF.getHeight();
                                    layoutParams.width = coverViewF.getWidth();
                                    imageViewF.setLayoutParams(layoutParams);
                                }
                                coverViewF.setVisibility(View.INVISIBLE);
                            });
                        }
                    }
                }
            });

            try {
                XposedHelpers.findAndHookMethod(playerActivityClass, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        reloadBackground();
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        XC_MethodHook stopRotationHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_rotation_key)) {
                    param.setResult(null);
                }
            }
        };

        Class<?> animHolderClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.RotationRelativeLayout$AnimationHolder", context.getClassLoader());
        if (animHolderClass != null) {
            try {
                XposedHelpers.findAndHookMethod(animHolderClass, "prepareAnimation", stopRotationHook);
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] hook prepareAnimation failed: " + t);
            }
        }
        Class<?> rotSubClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.RotationRelativeLayout$a", context.getClassLoader());
        if (rotSubClass != null) {
            try {
                XposedHelpers.findAndHookMethod(rotSubClass, "b", stopRotationHook);
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] hook RotationRelativeLayout$a.b failed: " + t);
            }
        }

        XC_MethodHook bgHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_background_key)) {
                    return;
                }
                String customPath = SettingHelper.getInstance().getPictureUrl();
                if (TextUtils.isEmpty(customPath)) {
                    return;
                }
                int blurRadius = SettingHelper.getInstance().getBackgroundBlur();

                if (param.thisObject instanceof View) {
                    sLastBgView = new WeakReference<>((View) param.thisObject);
                }

                String cleanPath = customPath.startsWith("file://") ? customPath.substring(7) : customPath;
                if (cleanPath.startsWith("/")) {
                    File file = new File(cleanPath);
                    if (!file.exists() || !file.isFile() || file.length() == 0) {
                        return;
                    }
                    if (param.thisObject instanceof View) {
                        View targetView = (View) param.thisObject;
                        Bitmap blurred = getOrLoadBlurredBitmap(file, blurRadius, targetView.getContext());
                        if (blurred != null) {
                            applyBitmapToView(targetView, blurred);
                            param.setResult(null);
                            return;
                        }
                    }
                } else if (customPath.startsWith("http://") || customPath.startsWith("https://")) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof String) {
                        param.args[0] = customPath;
                    }
                    if (param.args != null && param.args.length >= 3 && param.args[2] instanceof Integer) {
                        param.args[2] = blurRadius;
                    }
                }
            }
        };

        Class<?> playerBgClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.PlayerBackgroundImage", context.getClassLoader());
        if (playerBgClass != null) {
            for (Method m : playerBgClass.getDeclaredMethods()) {
                if ("setBlurCover".equals(m.getName())) {
                    try {
                        XposedBridge.hookMethod(m, bgHook);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        Class<?> rClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.ui.r", context.getClassLoader());
        if (rClass != null) {
            try {
                XposedHelpers.findAndHookMethod(rClass, "a", String.class, String.class, int.class, bgHook);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void reloadBackground() {
        if (sLastBgView != null) {
            View view = sLastBgView.get();
            if (view != null) {
                view.post(() -> {
                    try {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_background_key)) {
                            return;
                        }
                        String customPath = SettingHelper.getInstance().getPictureUrl();
                        if (TextUtils.isEmpty(customPath)) {
                            return;
                        }
                        String cleanPath = customPath.startsWith("file://") ? customPath.substring(7) : customPath;
                        if (cleanPath.startsWith("/")) {
                            File file = new File(cleanPath);
                            if (file.exists() && file.isFile()) {
                                int blurRadius = SettingHelper.getInstance().getBackgroundBlur();
                                Bitmap blurred = getOrLoadBlurredBitmap(file, blurRadius, view.getContext());
                                if (blurred != null) {
                                    applyBitmapToView(view, blurred);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[dolby_beta] reloadBackground failed: " + t);
                    }
                });
            }
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
