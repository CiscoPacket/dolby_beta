package com.raincat.dolby_beta.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.model.SidebarEnum;
import com.raincat.dolby_beta.utils.Tools;
import com.raincat.dolby_beta.view.BaseDialogInputItem;
import com.raincat.dolby_beta.view.BaseDialogItem;
import com.raincat.dolby_beta.view.beauty.BeautyBlackHideView;
import com.raincat.dolby_beta.view.beauty.BeautyCommentHotView;
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
import com.raincat.dolby_beta.view.beauty.background.BackgroundLocalPictureView;
import com.raincat.dolby_beta.view.proxy.*;
import com.raincat.dolby_beta.view.proxy.configuration.*;
import com.raincat.dolby_beta.view.setting.AboutView;
import com.raincat.dolby_beta.view.setting.BeautyView;
import com.raincat.dolby_beta.view.setting.BlackView;
import com.raincat.dolby_beta.view.setting.DebugView;
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

import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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
 *     author : RainCat
 *     time   : 2019/10/26
 *     desc   : 设置
 *     version: 1.0
 * </pre>
 */
public class SettingHook {
    private static final String ENTRY_TAG = "dolby_beta_setting_entry";
    private static final int TAG_EXTRA_SPACE = 0x60D01B01;
    private static final int TAG_CARD_BG = 0x60D01B03;
    private static final int TAG_THEME_SAMPLED = 0x60D01B04;
    private static final int TAG_PIXEL_TRIES = 0x60D01B05;
    private static final int COLOR_ORANGE = 0xFFE25D24;
    private static final int COLOR_SUB = 0xFF888888;
    private static final int COLOR_DARK_CARD = 0xFF2C2C2C;
    private static Boolean cachedNightTheme;
    private static WeakReference<View> rnCardRef;
    private static WeakReference<Activity> rnActivityRef;
    private static SharedPreferences.OnSharedPreferenceChangeListener themePrefListener;
    public static final int REQUEST_CODE_PICK_BG = 0x8823;
    private TextView titleView, subView;
    private LinearLayout dialogRoot, dialogProxyRoot, dialogProxyConfigRoot, dialogBeautyRoot, dialogPlayerBgRoot, dialogSidebarRoot;

    private final XC_MethodHook activityResultHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            int requestCode = (int) param.args[0];
            int resultCode = (int) param.args[1];
            Intent data = (Intent) param.args[2];
            if (requestCode == REQUEST_CODE_PICK_BG) {
                param.setResult(null);
                if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                    Activity activity = (Activity) param.thisObject;
                    handleSelectedImageUri(activity, data.getData());
                }
            }
        }
    };

    private BroadcastReceiver broadcastReceiver;

    public SettingHook(Context context, int versionCode) {
        try {
            findAndHookMethod(Activity.class, "onActivityResult", int.class, int.class, Intent.class, activityResultHook);
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] hook Activity.onActivityResult failed: " + t);
        }

        Class<?> fragActivityClz = findClassIfExists("androidx.fragment.app.FragmentActivity", context.getClassLoader());
        if (fragActivityClz != null) {
            try {
                findAndHookMethod(fragActivityClz, "onActivityResult", int.class, int.class, Intent.class, activityResultHook);
            } catch (Throwable ignored) {
            }
        }

        hookRnSettingPage(context.getClassLoader());
        Class<?> settingActivityClass = resolveSettingActivity(context.getClassLoader(), versionCode);
        if (settingActivityClass == null) {
            XposedBridge.log("[dolby_beta] SettingActivity not found, versionCode=" + versionCode);
            return;
        }
        XposedBridge.log("[dolby_beta] hook SettingActivity=" + settingActivityClass.getName() + " versionCode=" + versionCode);

        try {
            findAndHookMethod(settingActivityClass, "onActivityResult", int.class, int.class, Intent.class, activityResultHook);
        } catch (Throwable ignored) {
        }

        findAndHookMethod(settingActivityClass, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Activity activity = (Activity) param.thisObject;
                try {
                    registerBroadcastReceiver(activity);
                } catch (Throwable t) {
                    XposedBridge.log("[dolby_beta] register setting receiver failed: " + t);
                }
                Runnable inject = () -> {
                    try {
                        injectSettingEntry(activity);
                    } catch (Throwable t) {
                        XposedBridge.log("[dolby_beta] inject setting entry failed: " + t);
                    }
                };
                View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
                if (decor != null) {
                    decor.post(inject);
                    decor.postDelayed(inject, 400);
                } else {
                    inject.run();
                }
            }
        });

        try {
            findAndHookMethod(settingActivityClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        injectSettingEntry((Activity) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log("[dolby_beta] inject setting entry onResume failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] hook onResume failed: " + t);
        }

        findAndHookMethod(settingActivityClass, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (broadcastReceiver != null) {
                    try {
                        LocalBroadcastManager.getInstance((Context) param.thisObject).unregisterReceiver(broadcastReceiver);
                    } catch (Throwable ignored) {
                    }
                    try {
                        ((Context) param.thisObject).unregisterReceiver(broadcastReceiver);
                    } catch (Throwable ignored) {
                    }
                    broadcastReceiver = null;
                }
            }
        });
    }

    private void hookRnSettingPage(ClassLoader classLoader) {
        hookNeteaseThemeSwitch(classLoader);
        XC_MethodHook injectHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Activity activity = (Activity) param.thisObject;
                if (!isRnSettingPage(activity)) {
                    return;
                }
                try {
                    registerBroadcastReceiver(activity);
                } catch (Throwable ignored) {
                }
                scheduleRnInject(activity);
            }
        };

        String[] hosts = {
                "com.netease.cloudmusic.music.biz.rn.activity.MainProcessRNActivity",
                "com.netease.cloudmusic.music.biz.rn.activity.CloudMusicRNActivity",
                "com.netease.cloudmusic.music.biz.rn.activity.CloudMusicAutoOrientationRNActivity",
                "com.netease.cloudmusic.music.biz.rn.activity.BgMainProcessRNActivity",
                "com.netease.cloudmusic.activity.ReactNativeActivity"
        };
        for (String name : hosts) {
            Class<?> clazz = findClassIfExists(name, classLoader);
            if (clazz == null) {
                continue;
            }
            try {
                findAndHookMethod(clazz, "onResume", injectHook);
                try {
                    findAndHookMethod(clazz, "onActivityResult", int.class, int.class, Intent.class, activityResultHook);
                } catch (Throwable ignored) {
                }
                XposedBridge.log("[dolby_beta] hooked RN host " + name);
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] hook RN host failed " + name + ": " + t);
            }
        }

        try {
            Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", classLoader);
            findAndHookMethod(activityClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!isRnSettingPage(activity)) {
                        return;
                    }
                    try {
                        registerBroadcastReceiver(activity);
                        scheduleRnInject(activity);
                    } catch (Throwable t) {
                        XposedBridge.log("[dolby_beta] generic rn overlay failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] hook Activity.onResume failed: " + t);
        }
    }

    private boolean isRnSettingPage(Activity activity) {
        if (activity == null) {
            return false;
        }
        Intent intent = activity.getIntent();
        if (intent == null) {
            return false;
        }
        StringBuilder dump = new StringBuilder();
        if (intent.getDataString() != null) {
            dump.append(intent.getDataString());
        }
        String sourceUrl = intent.getStringExtra("extra_source_url");
        if (sourceUrl != null) {
            dump.append(' ').append(sourceUrl);
        }
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value != null) {
                    dump.append(' ').append(value);
                }
            }
        }
        String text = dump.toString();
        return text.contains("rn-setting") || text.contains("page_setting") || text.contains("/account/settings");
    }

    private void scheduleRnInject(final Activity activity) {
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        Runnable tryList = () -> {
            try {
                injectRnOverlay(activity, false);
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] rn overlay failed: " + t);
            }
        };
        Runnable fallback = () -> {
            try {
                injectRnOverlay(activity, true);
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] rn overlay fallback failed: " + t);
            }
        };
        if (decor != null) {
            decor.post(tryList);
            decor.postDelayed(tryList, 400);
            decor.postDelayed(tryList, 1000);
            decor.postDelayed(tryList, 2000);
            decor.postDelayed(fallback, 3500);
        } else {
            fallback.run();
        }
    }

    private void injectRnOverlay(Activity activity, boolean allowBottomFallback) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        View existing = content.findViewWithTag(ENTRY_TAG);
        if (existing != null) {
            rnCardRef = new WeakReference<>(existing);
            rnActivityRef = new WeakReference<>(activity);
            cachedNightTheme = null;
            refreshRnCardTheme(activity, existing);
            return;
        }
        if (injectRnStandaloneCard(activity, content)) {
            return;
        }
        if (allowBottomFallback) {
            injectRnBottomBar(activity, content);
        }
    }

    private boolean injectRnStandaloneCard(Activity activity, ViewGroup content) {
        TextView play = firstText(content, "播放与下载", "播放與下載");
        if (play == null) {
            XposedBridge.log("[dolby_beta] rn play text not found");
            return false;
        }
        TextView account = firstText(content, "账号与安全", "帳號與安全");
        View playCard = findSettingGroupCard(play, account);
        View accountCard = account != null ? findSettingGroupCard(account, play) : null;
        ViewGroup host = accountCard != null ? findCommonHost(accountCard, playCard) : null;
        if (host == null && playCard.getParent() instanceof ViewGroup) {
            host = (ViewGroup) playCard.getParent();
        }
        if (host == null || isRecyclerLike(host)) {
            XposedBridge.log("[dolby_beta] rn standalone host not found");
            return false;
        }
        TextView style = account != null ? account : play;
        LinearLayout card = createOrangeWhiteCard(activity, style);
        rnCardRef = new WeakReference<>(card);
        rnActivityRef = new WeakReference<>(activity);
        listenThemePrefs(activity);
        applyRnCardTheme(card, accountCard, playCard, account, play, activity);
        try {
            host.addView(card);
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] rn standalone insert failed: " + t);
            return false;
        }
        host.setClipChildren(false);
        host.setClipToPadding(false);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            host.setClipToOutline(false);
        }
        int rowH = resolveStandaloneRowHeight(activity, accountCard, style);
        bindStandaloneCardLayout(host, card, accountCard, playCard, play, account, rowH);
        ensureScrollExtraSpace(host, rowH + Tools.dp2px(activity, 8));
        XposedBridge.log("[dolby_beta] rn standalone card injected into " + host.getClass().getName());
        return true;
    }

    private LinearLayout createOrangeWhiteCard(Activity activity, TextView playStyle) {
        LinearLayout card = new LinearLayout(activity);
        card.setTag(ENTRY_TAG);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Tools.dp2px(activity, 16), 0, Tools.dp2px(activity, 16), 0);
        card.setMinimumHeight(Tools.dp2px(activity, 52));
        card.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(Tools.dp2px(activity, 12));
        card.setBackground(background);
        card.setClickable(true);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            card.setElevation(0);
        }

        float titlePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16,
                activity.getResources().getDisplayMetrics());
        if (playStyle != null && playStyle.getPaint() != null && playStyle.getPaint().getTextSize() > 0) {
            titlePx = Math.max(titlePx, playStyle.getPaint().getTextSize());
        }

        titleView = new TextView(activity);
        titleView.setTextColor(COLOR_ORANGE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, titlePx);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setClickable(false);
        titleView.setFocusable(false);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (playStyle != null) {
            titleView.setTypeface(playStyle.getTypeface());
            titleView.setIncludeFontPadding(playStyle.getIncludeFontPadding());
        }

        subView = new TextView(activity);
        subView.setTextColor(COLOR_SUB);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, titlePx * 0.875f);
        subView.setMaxLines(1);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        subView.setClickable(false);
        subView.setFocusable(false);
        subView.setPadding(Tools.dp2px(activity, 8), 0, Tools.dp2px(activity, 4), 0);

        TextView chevron = new TextView(activity);
        chevron.setText(">");
        chevron.setTextColor(COLOR_SUB);
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_PX, titlePx * 0.875f);
        chevron.setClickable(false);
        chevron.setFocusable(false);

        card.addView(titleView);
        card.addView(subView);
        card.addView(chevron);
        refresh();
        card.setOnClickListener(view -> showSettingDialog(activity));
        card.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP
                    && event.getX() >= 0 && event.getY() >= 0
                    && event.getX() <= v.getWidth() && event.getY() <= v.getHeight()) {
                v.performClick();
            }
            return true;
        });
        return card;
    }

    private void bindStandaloneCardLayout(ViewGroup host, View card, View accountCard,
                                          View playCard, TextView play, TextView account, int rowH) {
        Runnable relayout = () -> applyStandaloneCardLayout(host, card, accountCard, playCard, play, account, rowH);
        host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> relayout.run());
        if (accountCard != null) {
            accountCard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> relayout.run());
        }
        if (playCard != null) {
            playCard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> relayout.run());
        }
        card.post(relayout);
        card.postDelayed(relayout, 50);
        card.postDelayed(relayout, 300);
    }

    private void applyStandaloneCardLayout(ViewGroup host, View card, View accountCard,
                                           View playCard, TextView play, TextView account, int rowH) {
        if (card.getParent() != host || rowH <= 0) {
            return;
        }
        applyRnCardTheme(card, accountCard, playCard, account, play, host.getContext());
        TextView style = account != null ? account : play;
        if (style != null && titleView != null && style.getPaint() != null && style.getPaint().getTextSize() > 0) {
            float titlePx = Math.max(style.getPaint().getTextSize(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16,
                            host.getResources().getDisplayMetrics()));
            if (Math.abs(titleView.getTextSize() - titlePx) > 0.5f) {
                titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, titlePx);
                titleView.setTypeface(style.getTypeface());
                titleView.setIncludeFontPadding(style.getIncludeFontPadding());
            }
        }
        View playBranch = playCard != null ? findDirectChildContaining(host, playCard) : null;
        if (playBranch == null && playCard != null && playCard.getParent() == host) {
            playBranch = playCard;
        }
        int[] hostLoc = new int[2];
        host.getLocationOnScreen(hostLoc);
        int spacing = resolveGroupSpacing(host, accountCard, playCard, playBranch);
        int left = Tools.dp2px(host.getContext(), 16);
        int width = Math.max(host.getWidth() - left * 2, 0);
        View widthRef = accountCard != null ? accountCard : playCard;
        if (widthRef != null && widthRef.getWidth() > host.getWidth() * 0.5f) {
            int[] refLoc = new int[2];
            widthRef.getLocationOnScreen(refLoc);
            left = refLoc[0] - hostLoc[0];
            width = widthRef.getWidth();
        }
        if (width <= 0) {
            return;
        }
        int top;
        if (accountCard != null && accountCard.getHeight() > 0) {
            int[] accLoc = new int[2];
            accountCard.getLocationOnScreen(accLoc);
            top = accLoc[1] - hostLoc[1] + accountCard.getHeight() + spacing;
        } else if (playCard != null) {
            int[] playLoc = new int[2];
            playCard.getLocationOnScreen(playLoc);
            int playShift = playBranch != null ? (int) playBranch.getTranslationY() : (int) playCard.getTranslationY();
            top = playLoc[1] - hostLoc[1] - playShift - rowH - spacing;
        } else {
            top = spacing;
        }
        top = Math.max(0, top);
        if (card.getLeft() != left || card.getTop() != top
                || card.getWidth() != width || card.getHeight() != rowH) {
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(rowH, View.MeasureSpec.EXACTLY));
            card.layout(left, top, left + width, top + rowH);
        }
        if (play != null && play.getWidth() > 0) {
            int[] playLoc = new int[2];
            int[] cardLoc = new int[2];
            play.getLocationOnScreen(playLoc);
            card.getLocationOnScreen(cardLoc);
            int padL = playLoc[0] + play.getTotalPaddingLeft() - cardLoc[0];
            int min = Tools.dp2px(host.getContext(), 8);
            int max = Math.max(width / 2, min);
            int padR = Tools.dp2px(host.getContext(), 16);
            if (padL >= min && padL <= max && (card.getPaddingLeft() != padL || card.getPaddingRight() != padR)) {
                card.setPadding(padL, 0, padR, 0);
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(rowH, View.MeasureSpec.EXACTLY));
                card.layout(left, top, left + width, top + rowH);
            }
        }
        card.bringToFront();

        if (playBranch == null) {
            return;
        }
        int shift = rowH + spacing;
        int threshold = playBranch.getTop() - 4;
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if (child == card) {
                continue;
            }
            if (child.getTop() >= threshold) {
                if (Math.abs(child.getTranslationY() - shift) > 0.5f) {
                    child.setTranslationY(shift);
                }
            }
        }
    }

    private int resolveStandaloneRowHeight(Activity activity, View accountCard, TextView play) {
        int min = Tools.dp2px(activity, 52);
        if (accountCard != null && accountCard.getHeight() >= min
                && accountCard.getHeight() <= Tools.dp2px(activity, 84)) {
            return accountCard.getHeight();
        }
        if (play != null && play.getHeight() > 0) {
            return Math.max(min, play.getHeight() + Tools.dp2px(activity, 24));
        }
        return min;
    }

    private View findSettingGroupCard(View text, View otherGroupText) {
        View current = text;
        View best = text;
        int screenW = text.getResources().getDisplayMetrics().widthPixels;
        int screenH = text.getResources().getDisplayMetrics().heightPixels;
        while (current != null && current.getId() != android.R.id.content) {
            int width = current.getWidth();
            int height = current.getHeight();
            if (width >= screenW * 0.72f && width <= screenW * 0.96f
                    && height > Tools.dp2px(text.getContext(), 36)
                    && height < screenH * 0.75f) {
                if (otherGroupText != null && isDescendant(current, otherGroupText)) {
                    break;
                }
                best = current;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return best;
    }

    private int resolveGroupSpacing(ViewGroup host, View accountCard, View playCard, View playBranch) {
        int fallback = Tools.dp2px(host.getContext(), 8);
        if (accountCard == null || playCard == null || accountCard.getHeight() <= 0) {
            return fallback;
        }
        int[] accLoc = new int[2];
        int[] playLoc = new int[2];
        accountCard.getLocationOnScreen(accLoc);
        playCard.getLocationOnScreen(playLoc);
        int playShift = playBranch != null ? (int) playBranch.getTranslationY() : (int) playCard.getTranslationY();
        int gap = (playLoc[1] - playShift) - (accLoc[1] + accountCard.getHeight());
        int min = Tools.dp2px(host.getContext(), 4);
        int max = Tools.dp2px(host.getContext(), 24);
        if (gap >= min && gap <= max) {
            return gap;
        }
        return fallback;
    }

    private void refreshRnCardTheme(Activity activity, View card) {
        if (activity == null || card == null || activity.isFinishing()) {
            return;
        }
        card.setTag(TAG_THEME_SAMPLED, null);
        card.setTag(TAG_PIXEL_TRIES, 0);
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            applyRnCardTheme(card, null, null, null, null, activity);
            return;
        }
        TextView play = firstText(content, "播放与下载", "播放與下載");
        TextView account = firstText(content, "账号与安全", "帳號與安全");
        View playCard = play != null ? findSettingGroupCard(play, account) : null;
        View accountCard = account != null ? findSettingGroupCard(account, play) : null;
        applyRnCardTheme(card, accountCard, playCard, account, play, activity);
    }

    private void refreshVisibleRnCard() {
        Activity activity = rnActivityRef != null ? rnActivityRef.get() : null;
        View card = rnCardRef != null ? rnCardRef.get() : null;
        if (activity == null || card == null || card.getParent() == null) {
            return;
        }
        activity.runOnUiThread(() -> refreshRnCardTheme(activity, card));
    }

    private void applyRnCardTheme(View card, View accountCard, View playCard,
                                  TextView account, TextView play, Context context) {
        Integer sampled = sampleCardDrawable(accountCard);
        if (sampled == null) {
            sampled = sampleCardDrawable(playCard);
        }
        boolean nightApi = isNeteaseNightTheme(context);
        boolean nightSample = sampled != null && luminance(sampled) < 160;
        boolean night = nightApi || nightSample;
        int bg;
        if (nightSample) {
            bg = sampled | 0xFF000000;
        } else if (night) {
            bg = COLOR_DARK_CARD;
        } else {
            bg = Color.WHITE;
        }
        applyCardBackground(card, context, bg);
        TextView sampleText = account != null ? account : play;
        requestNativeCardPixel(card, sampleText, context);
    }

    private void applyCardBackground(View card, Context context, int bg) {
        Object old = card.getTag(TAG_CARD_BG);
        if (old instanceof Integer && (Integer) old == bg) {
            return;
        }
        card.setTag(TAG_CARD_BG, bg);
        GradientDrawable background = new GradientDrawable();
        background.setColor(bg);
        background.setCornerRadius(Tools.dp2px(context, 12));
        card.setBackground(background);
    }

    private void requestNativeCardPixel(View card, TextView text, Context context) {
        if (android.os.Build.VERSION.SDK_INT < 24 || !(context instanceof Activity) || text == null) {
            return;
        }
        if (text.getWidth() <= 0 || text.getHeight() <= 0) {
            return;
        }
        Object current = card.getTag(TAG_CARD_BG);
        boolean currentDark = current instanceof Integer && luminance((Integer) current) < 160;
        boolean nightApi = isNeteaseNightTheme(context);
        if (Boolean.TRUE.equals(card.getTag(TAG_THEME_SAMPLED)) && !(nightApi && !currentDark)) {
            return;
        }
        Object tryTag = card.getTag(TAG_PIXEL_TRIES);
        int tries = tryTag instanceof Integer ? (Integer) tryTag : 0;
        if (tries >= 8) {
            return;
        }
        card.setTag(TAG_PIXEL_TRIES, tries + 1);
        Activity activity = (Activity) context;
        if (activity.getWindow() == null) {
            return;
        }
        int[] loc = new int[2];
        text.getLocationInWindow(loc);
        int x = loc[0] - Tools.dp2px(context, 8);
        int y = loc[1] + text.getHeight() / 2;
        if (x < 0 || y < 0) {
            return;
        }
        final Bitmap bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(activity.getWindow(), new Rect(x, y, x + 1, y + 1), bmp, result -> {
                try {
                    if (result != PixelCopy.SUCCESS) {
                        return;
                    }
                    int pixel = bmp.getPixel(0, 0);
                    if (Color.alpha(pixel) < 180) {
                        return;
                    }
                    int lum = luminance(pixel);
                    Object now = card.getTag(TAG_CARD_BG);
                    boolean alreadyDark = now instanceof Integer && luminance((Integer) now) < 160;
                    if (lum < 160) {
                        card.setTag(TAG_THEME_SAMPLED, true);
                        applyCardBackground(card, context, pixel | 0xFF000000);
                    } else if (!alreadyDark && !isNeteaseNightTheme(context)) {
                        Object tryCount = card.getTag(TAG_PIXEL_TRIES);
                        if (tryCount instanceof Integer && (Integer) tryCount >= 7) {
                            card.setTag(TAG_THEME_SAMPLED, true);
                            applyCardBackground(card, context, Color.WHITE);
                        }
                    }
                } finally {
                    bmp.recycle();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable ignored) {
            bmp.recycle();
        }
    }

    private Integer sampleCardDrawable(View start) {
        if (start == null) {
            return null;
        }
        int screenW = start.getResources().getDisplayMetrics().widthPixels;
        int screenH = start.getResources().getDisplayMetrics().heightPixels;
        View current = start;
        for (int i = 0; i < 8 && current != null; i++) {
            Integer color = extractDrawableColor(current.getBackground());
            if (color != null && Color.alpha(color) > 180
                    && current.getWidth() > screenW * 0.55f
                    && current.getWidth() < screenW * 0.98f
                    && current.getHeight() > Tools.dp2px(start.getContext(), 36)
                    && current.getHeight() < screenH * 0.75f) {
                return color;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return null;
    }

    private Integer extractDrawableColor(Drawable drawable) {
        if (drawable instanceof ColorDrawable) {
            return ((ColorDrawable) drawable).getColor();
        }
        if (drawable instanceof GradientDrawable && android.os.Build.VERSION.SDK_INT >= 24) {
            android.content.res.ColorStateList list = ((GradientDrawable) drawable).getColor();
            if (list != null) {
                return list.getDefaultColor();
            }
        }
        return null;
    }

    private int luminance(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
    }

    private void hookNeteaseThemeSwitch(ClassLoader classLoader) {
        String[] agents = {
                "com.netease.cloudmusic.theme.core.ThemeAgent",
                "com.netease.cloudmusic.theme.core.c"
        };
        XC_MethodHook afterSwitch = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Boolean night = null;
                if (param.args != null) {
                    for (Object arg : param.args) {
                        if (arg == null) {
                            continue;
                        }
                        String name = arg.getClass().getName();
                        if (!name.contains("ThemeInfo") && !name.contains("theme")) {
                            continue;
                        }
                        night = probeNightFromObject(arg);
                        if (night == null) {
                            night = themeIdIsNight(arg);
                        }
                        if (night != null) {
                            break;
                        }
                    }
                }
                if (night == null && param.thisObject instanceof Context) {
                    night = readNightFromPrefs((Context) param.thisObject);
                }
                if (night != null) {
                    cachedNightTheme = night;
                    XposedBridge.log("[dolby_beta] theme switched night=" + night);
                }
                refreshVisibleRnCard();
            }
        };
        for (String name : agents) {
            Class<?> clazz = findClassIfExists(name, classLoader);
            if (clazz == null) {
                continue;
            }
            try {
                XposedBridge.hookAllMethods(clazz, "switchTheme", afterSwitch);
                XposedBridge.log("[dolby_beta] hooked theme switch " + name + ".switchTheme");
            } catch (Throwable ignored) {
            }
            for (Method method : clazz.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 2) {
                    continue;
                }
                boolean hasThemeInfo = false;
                for (Class<?> type : params) {
                    if (type.getName().contains("ThemeInfo")) {
                        hasThemeInfo = true;
                        break;
                    }
                }
                if (!hasThemeInfo) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, afterSwitch);
                    XposedBridge.log("[dolby_beta] hooked theme method " + name + "." + method.getName());
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            findAndHookMethod(Activity.class, "onConfigurationChanged",
                    android.content.res.Configuration.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!isRnSettingPage(activity)) {
                                return;
                            }
                            cachedNightTheme = null;
                            refreshVisibleRnCard();
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private boolean isNeteaseNightTheme(Context context) {
        if (cachedNightTheme != null) {
            return cachedNightTheme;
        }
        Boolean prefs = readNightFromPrefs(context);
        if (prefs != null) {
            cachedNightTheme = prefs;
            return prefs;
        }
        try {
            ClassLoader cl = context.getClassLoader();
            String[] classNames = {
                    "com.netease.cloudmusic.theme.core.ResourceRouter",
                    "com.netease.cloudmusic.theme.core.ThemeConfig",
                    "com.netease.cloudmusic.theme.core.ThemeAgent",
                    "com.netease.cloudmusic.theme.core.b",
                    "com.netease.cloudmusic.theme.core.f",
                    "com.netease.cloudmusic.theme.core.c"
            };
            for (String className : classNames) {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
                Boolean night = probeNightFromClass(clazz, context);
                if (night != null) {
                    cachedNightTheme = night;
                    return night;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void listenThemePrefs(Context context) {
        if (themePrefListener != null) {
            return;
        }
        themePrefListener = (sp, key) -> {
            if (key == null) {
                return;
            }
            String lower = key.toLowerCase();
            if (!lower.contains("theme") && !lower.contains("night") && !lower.contains("dark")) {
                return;
            }
            cachedNightTheme = null;
            Boolean night = readNightFromPrefs(context.getApplicationContext());
            if (night != null) {
                cachedNightTheme = night;
            }
            XposedBridge.log("[dolby_beta] theme pref changed " + key + " night=" + cachedNightTheme);
            refreshVisibleRnCard();
        };
        try {
            File dir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(".xml")) {
                    continue;
                }
                context.getSharedPreferences(name.substring(0, name.length() - 4), Context.MODE_PRIVATE)
                        .registerOnSharedPreferenceChangeListener(themePrefListener);
            }
        } catch (Throwable ignored) {
        }
    }

    private Boolean readNightFromPrefs(Context context) {
        try {
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            names.add("Night_Mode");
            names.add("theme");
            names.add("ThemeConfig");
            names.add("com.netease.cloudmusic_preferences");
            names.add(context.getPackageName() + "_preferences");
            File dir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (name.endsWith(".xml")) {
                        names.add(name.substring(0, name.length() - 4));
                    }
                }
            }
            for (String name : names) {
                SharedPreferences sp = context.getSharedPreferences(name, Context.MODE_PRIVATE);
                Map<String, ?> all = sp.getAll();
                if (all == null || all.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    String key = entry.getKey();
                    if (key == null) {
                        continue;
                    }
                    String lower = key.toLowerCase();
                    Object value = entry.getValue();
                    if (value instanceof Boolean
                            && (lower.contains("night") || lower.contains("dark"))) {
                        return (Boolean) value;
                    }
                    if (value instanceof Integer
                            && (lower.contains("theme") || lower.equals("id"))
                            && (Integer) value == -3) {
                        return true;
                    }
                    if (value instanceof Integer && lower.contains("themeid") && (Integer) value == -3) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Boolean probeNightFromClass(Class<?> clazz, Context context) {
        if (clazz == null) {
            return null;
        }
        Object instance = resolveSingleton(clazz, context);
        Boolean night = probeNightFromObject(instance);
        if (night != null) {
            return night;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length > 1) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object result;
                if (params.length == 0) {
                    result = method.invoke(null);
                } else if (Context.class.isAssignableFrom(params[0])) {
                    result = method.invoke(null, context);
                } else {
                    continue;
                }
                night = probeNightFromObject(result);
                if (night != null) {
                    return night;
                }
                night = themeIdIsNight(result);
                if (night != null) {
                    return night;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object resolveSingleton(Class<?> clazz, Context context) {
        String[] names = {"getInstance", "a", "b", "c", "d", "e"};
        for (String name : names) {
            try {
                Object value = XposedHelpers.callStaticMethod(clazz, name, context);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
            try {
                Object value = XposedHelpers.callStaticMethod(clazz, name);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        for (Field field : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null && clazz.isInstance(value)) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Boolean themeIdIsNight(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Integer) {
            return (Integer) obj == -3 ? true : null;
        }
        for (String name : new String[]{"getId", "getThemeId", "getCurrentThemeId", "c", "d", "e"}) {
            try {
                Object value = XposedHelpers.callMethod(obj, name);
                if (value instanceof Integer && (Integer) value == -3) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return probeNightFromObject(obj);
    }

    private Boolean probeNightFromObject(Object obj) {
        if (obj == null) {
            return null;
        }
        for (Method method : obj.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            String name = method.getName().toLowerCase();
            Class<?> type = method.getReturnType();
            try {
                if ((type == boolean.class || type == Boolean.class)
                        && (name.contains("night") || name.contains("dark"))) {
                    Object result = method.invoke(obj);
                    if (result instanceof Boolean) {
                        return (Boolean) result;
                    }
                }
                if ((type == int.class || type == Integer.class)
                        && (name.contains("theme") || name.equals("getid") || name.equals("id"))) {
                    Object result = method.invoke(obj);
                    if (result instanceof Integer && (Integer) result == -3) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private ViewGroup findCommonHost(View a, View b) {
        View current = a;
        while (current.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) current.getParent();
            if (isDescendant(parent, b)) {
                return parent;
            }
            current = parent;
        }
        return null;
    }

    private TextView firstText(View root, String... labels) {
        for (String label : labels) {
            TextView found = findTextViewByText(root, label);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private View findDirectChildContaining(ViewGroup parent, View descendant) {
        View current = descendant;
        while (current != null && current.getParent() != parent) {
            if (!(current.getParent() instanceof View)) {
                return null;
            }
            current = (View) current.getParent();
        }
        return current;
    }

    private boolean isDescendant(View parent, View child) {
        View current = child;
        while (current != null) {
            if (current == parent) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private void ensureScrollExtraSpace(ViewGroup list, int extra) {
        ViewGroup host = findScrollAncestor(list);
        if (host == null) {
            host = list;
        }
        if (Boolean.TRUE.equals(host.getTag(TAG_EXTRA_SPACE))) {
            return;
        }
        host.setTag(TAG_EXTRA_SPACE, true);
        host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
                host.getPaddingRight(), host.getPaddingBottom() + extra);
        host.setClipToPadding(false);
    }

    private ViewGroup findScrollAncestor(View view) {
        View current = view;
        while (current.getParent() instanceof View) {
            current = (View) current.getParent();
            String name = current.getClass().getName();
            if (current instanceof ScrollView || name.contains("ScrollView") || name.contains("NestedScrollView")) {
                return (ViewGroup) current;
            }
            if (current.getId() == android.R.id.content) {
                break;
            }
        }
        return null;
    }

    private void injectRnBottomBar(Activity activity, ViewGroup content) {
        LinearLayout bar = createOrangeWhiteCard(activity, null);
        bar.setBackgroundColor(COLOR_ORANGE);
        titleView.setTextColor(Color.WHITE);
        subView.setTextColor(Color.WHITE);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            bar.setElevation(Tools.dp2px(activity, 10));
        }
        if (content instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM;
            lp.bottomMargin = Tools.dp2px(activity, 24);
            content.addView(bar, lp);
        } else {
            content.addView(bar, new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        XposedBridge.log("[dolby_beta] rn setting bar moved to bottom on " + activity.getClass().getName());
    }

    private TextView findTextViewByText(View root, String text) {
        List<TextView> exact = new ArrayList<>();
        collectTextViews(root, text, true, exact);
        if (!exact.isEmpty()) {
            return pickBestTextView(exact);
        }
        List<TextView> fuzzy = new ArrayList<>();
        collectTextViews(root, text, false, fuzzy);
        return fuzzy.isEmpty() ? null : pickBestTextView(fuzzy);
    }

    private void collectTextViews(View root, String text, boolean exact, List<TextView> out) {
        if (root instanceof TextView) {
            CharSequence value = ((TextView) root).getText();
            if (value != null) {
                String s = value.toString().replace('\u00A0', ' ').trim();
                if (exact ? s.equals(text) : s.contains(text)) {
                    out.add((TextView) root);
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTextViews(group.getChildAt(i), text, exact, out);
            }
        }
    }

    private TextView pickBestTextView(List<TextView> views) {
        TextView best = null;
        float bestScore = -1f;
        for (TextView tv : views) {
            float score = tv.getTextSize();
            if (tv.getVisibility() == View.VISIBLE) {
                score += 1000f;
            }
            if (tv.isShown()) {
                score += 500f;
            }
            if (tv.getWidth() > 0 && tv.getHeight() > 0) {
                score += tv.getWidth();
            }
            if (score > bestScore) {
                bestScore = score;
                best = tv;
            }
        }
        return best;
    }

    private boolean isRecyclerLike(ViewGroup list) {
        String name = list.getClass().getName();
        return name.contains("RecyclerView")
                || name.contains("ListView")
                || list instanceof android.widget.AdapterView;
    }

    private Class<?> resolveSettingActivity(ClassLoader classLoader, int versionCode) {
        String[] candidates = versionCode >= 8007000
                ? new String[]{
                    "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity",
                    "com.netease.cloudmusic.activity.SettingActivity",
                    "com.netease.cloudmusic.music.biz.setting.SettingActivity"
                }
                : new String[]{
                    "com.netease.cloudmusic.activity.SettingActivity",
                    "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity"
                };
        for (String name : candidates) {
            Class<?> clazz = findClassIfExists(name, classLoader);
            if (clazz != null) {
                return clazz;
            }
        }
        return null;
    }

    private void injectSettingEntry(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor != null && decor.findViewWithTag(ENTRY_TAG) != null) {
            return;
        }

        ViewGroup host = findScrollContent(activity);
        ViewGroup styleSource = null;
        if (host == null) {
            View switchView = findAttachedSwitch(activity);
            if (switchView != null && switchView.getParent() instanceof ViewGroup
                    && switchView.getParent().getParent() instanceof ViewGroup) {
                styleSource = (ViewGroup) switchView.getParent();
                host = (ViewGroup) switchView.getParent().getParent();
            }
        }
        if (host == null) {
            host = activity.findViewById(android.R.id.content);
            if (host != null && host.getChildCount() > 0 && host.getChildAt(0) instanceof ViewGroup) {
                host = (ViewGroup) host.getChildAt(0);
            }
        }
        if (host == null) {
            XposedBridge.log("[dolby_beta] no host view for setting entry");
            return;
        }
        addEntryTo(activity, host, 0, styleSource);
    }

    private ViewGroup findScrollContent(Activity activity) {
        ScrollView scrollView = findScrollViewField(activity);
        if (scrollView == null) {
            scrollView = findScrollViewInTree(activity.findViewById(android.R.id.content));
        }
        if (scrollView == null) {
            return null;
        }
        if (scrollView.getChildCount() > 0 && scrollView.getChildAt(0) instanceof ViewGroup) {
            return (ViewGroup) scrollView.getChildAt(0);
        }
        return scrollView;
    }

    private ScrollView findScrollViewField(Activity activity) {
        for (Class<?> clazz = activity.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            Field[] fields;
            try {
                fields = clazz.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                if (!ScrollView.class.isAssignableFrom(field.getType()) && !field.getType().getName().contains("ScrollView")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(activity);
                    if (value instanceof ScrollView) {
                        return (ScrollView) value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private ScrollView findScrollViewInTree(View root) {
        if (root instanceof ScrollView) {
            return (ScrollView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = findScrollViewInTree(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private View findAttachedSwitch(Activity activity) {
        for (Class<?> clazz = activity.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            Field[] fields;
            try {
                fields = clazz.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                String typeName = field.getType().getName();
                if (!typeName.contains("Switch") && !typeName.contains("CompoundButton")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(activity);
                    if (value instanceof View) {
                        View view = (View) value;
                        if (view.getParent() instanceof ViewGroup) {
                            return view;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return findSwitchInTree(activity.findViewById(android.R.id.content));
    }

    private void addEntryTo(Activity activity, ViewGroup host, int index, ViewGroup styleSource) {
        if (host.findViewWithTag(ENTRY_TAG) != null) {
            return;
        }
        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setTag(ENTRY_TAG);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setGravity(Gravity.CENTER_VERTICAL);
        linearLayout.setPadding(Tools.dp2px(activity, 16), Tools.dp2px(activity, 14),
                Tools.dp2px(activity, 16), Tools.dp2px(activity, 14));
        linearLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        linearLayout.setBackgroundColor(0x33FF5722);
        linearLayout.setClickable(true);
        linearLayout.setFocusable(true);

        titleView = new TextView(activity);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTextColor(COLOR_ORANGE);
        subView = new TextView(activity);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subView.setTextColor(COLOR_SUB);

        ViewGroup colorSource = styleSource != null ? styleSource : host;
        TextView originalText = findFirstTextView(colorSource);
        if (originalText != null) {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalText.getTextSize());
            subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalText.getTextSize() * 0.75f);
        }

        linearLayout.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        linearLayout.addView(subView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refresh();
        linearLayout.setOnClickListener(view -> showSettingDialog(activity));

        int safeIndex = Math.min(Math.max(index, 0), host.getChildCount());
        host.addView(linearLayout, safeIndex);
        XposedBridge.log("[dolby_beta] setting entry injected into " + host.getClass().getName());
    }

    private View findSwitchInTree(View root) {
        if (root == null) {
            return null;
        }
        String name = root.getClass().getName();
        if (name.contains("Switch")) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findSwitchInTree(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private TextView findFirstTextView(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView) {
                return (TextView) child;
            }
            if (child instanceof ViewGroup) {
                TextView nested = findFirstTextView((ViewGroup) child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @SuppressLint("SetTextI18n")
    private void refresh() {
        if (titleView == null || subView == null) {
            return;
        }
        titleView.setText("杜比大喇叭设置");
        if (ExtraHelper.getExtraDate(ExtraHelper.USER_ID).equals("-1")) {
            subView.setText("USERID失败");
        } else if (!SettingHelper.getInstance().getSetting(SettingHelper.master_key))
            subView.setText("已关闭");
        else if (ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS).equals("1"))
            subView.setText("运行中");
        else
            subView.setText("未运行");
    }

    private void registerBroadcastReceiver(final Context context) {
        if (broadcastReceiver != null) {
            return;
        }
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
                String action = intent.getAction();
                if (SettingHelper.refresh_setting.equals(action)) {
                    SettingHelper.getInstance().refreshSetting(context);
                    refreshDialogItems(dialogRoot);
                    refreshDialogItems(dialogProxyRoot);
                    refreshDialogItems(dialogProxyConfigRoot);
                    refreshDialogItems(dialogBeautyRoot);
                    refreshDialogItems(dialogPlayerBgRoot);
                    refreshDialogItems(dialogSidebarRoot);
                    try {
                        PlayerActivityHook.reloadBackground();
                    } catch (Throwable ignored) {
                    }
                } else if (SettingHelper.proxy_setting.equals(action)) {
                    showProxyDialog(context);
                } else if (SettingHelper.beauty_setting.equals(action)) {
                    showBeautyDialog(context);
                } else if (SettingHelper.sidebar_setting.equals(action)) {
                    showSidebarDialog(context);
                } else if (SettingHelper.background_setting.equals(action)) {
                    showPlayerBackgroundDialog(context);
                } else if (SettingHelper.proxy_configuration_setting.equals(action)) {
                    showProxyConfigurationDialog(context);
                }
            }
        };
        LocalBroadcastManager.getInstance(context).registerReceiver(broadcastReceiver, intentFilter);
        try {
            context.registerReceiver(broadcastReceiver, intentFilter);
        } catch (Throwable ignored) {
        }
    }

    private void refreshDialogItems(LinearLayout root) {
        if (root == null) {
            return;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof BaseDialogItem) {
                ((BaseDialogItem) child).refresh();
            } else if (child instanceof BaseDialogInputItem) {
                ((BaseDialogInputItem) child).refresh();
            }
        }
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
        DebugView debugView = new DebugView(context);
        debugView.setBaseOnView(masterView);
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


        proxyView.setOnClickListener(view -> showProxyDialog(context));
        beautyView.setOnClickListener(view -> showBeautyDialog(context));

        dialogRoot.addView(new TitleView(context));
        dialogRoot.addView(masterView);
        dialogRoot.addView(dexView);
        dialogRoot.addView(warnView);
        dialogRoot.addView(debugView);
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
        showLightDialog(context, scrollView, false,
                "确定", (dialogInterface, i) -> refresh(),
                "重启网易云", (dialogInterface, i) -> restartApplication(context));
    }

    private void showProxyDialog(final Context context) {
        dialogProxyRoot = new BaseDialogItem(context);
        dialogProxyRoot.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(dialogProxyRoot);

        ProxyMasterView proxyMasterView = new ProxyMasterView(context);
        ProxyCoverView proxyCoverView = new ProxyCoverView(context);
        proxyCoverView.setBaseOnView(proxyMasterView);
        ProxyServerView proxyServerView = new ProxyServerView(context);
        proxyServerView.setBaseOnView(proxyMasterView);
        ProxyPriorityView proxyPriorityView = new ProxyPriorityView(context);
        proxyPriorityView.setBaseOnView(proxyMasterView);
        ProxyFlacView proxyFlacView = new ProxyFlacView(context);
        proxyFlacView.setBaseOnView(proxyMasterView);
        ProxyLocalVipView proxyLocalVipView = new ProxyLocalVipView(context);
        proxyLocalVipView.setBaseOnView(proxyMasterView);
        ProxyGrayView proxyGrayView = new ProxyGrayView(context);
        proxyGrayView.setBaseOnView(proxyMasterView);
        ProxyConfigurationView proxyConfigurationView = new ProxyConfigurationView(context);
        proxyConfigurationView.setBaseOnView(proxyMasterView);

        proxyConfigurationView.setOnClickListener(view -> showProxyConfigurationDialog(context));

        dialogProxyRoot.addView(new ProxyTitleView(context));
        dialogProxyRoot.addView(proxyMasterView);
        dialogProxyRoot.addView(proxyCoverView);
        dialogProxyRoot.addView(proxyServerView);
        dialogProxyRoot.addView(proxyPriorityView);
        dialogProxyRoot.addView(proxyFlacView);
        dialogProxyRoot.addView(proxyLocalVipView);
        dialogProxyRoot.addView(proxyGrayView);
        dialogProxyRoot.addView(proxyConfigurationView);

        showLightDialog(context, scrollView, true,
                "仅保存", (dialogInterface, i) -> refresh(),
                "保存并重启", (dialogInterface, i) -> restartApplication(context));
    }

    private void showProxyConfigurationDialog(final Context context) {
        dialogProxyConfigRoot = new BaseDialogItem(context);
        dialogProxyConfigRoot.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(dialogProxyConfigRoot);

        dialogProxyConfigRoot.addView(new ProxyConfigurationTitleView(context));
        dialogProxyConfigRoot.addView(new ProxyHttpView(context));
        dialogProxyConfigRoot.addView(new ProxyPortView(context));
        dialogProxyConfigRoot.addView(new ProxyOriginalView(context));
        dialogProxyConfigRoot.addView(new ProxyKuwoView(context));
        dialogProxyConfigRoot.addView(new ProxyQqView(context));
        dialogProxyConfigRoot.addView(new ProxyMiguView(context));
        showLightDialog(context, scrollView, true,
                "仅保存", (dialogInterface, i) -> refresh(),
                "保存并重启", (dialogInterface, i) -> restartApplication(context));
    }

    private void showPlayerBackgroundDialog(final Context context) {
        dialogPlayerBgRoot = new BaseDialogItem(context);
        dialogPlayerBgRoot.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(dialogPlayerBgRoot);

        BackgroundMasterView backgroundMasterView = new BackgroundMasterView(context);
        BackgroundLocalPictureView backgroundLocalPictureView = new BackgroundLocalPictureView(context);
        backgroundLocalPictureView.setBaseOnView(backgroundMasterView);
        BackgroundPictureUrlView backgroundPictureUrlView = new BackgroundPictureUrlView(context);
        backgroundPictureUrlView.setBaseOnView(backgroundMasterView);
        BackgroundBlurRadiusView backgroundBlurRadiusView = new BackgroundBlurRadiusView(context);
        backgroundBlurRadiusView.setBaseOnView(backgroundMasterView);

        dialogPlayerBgRoot.addView(new BackgroundTitleView(context));
        dialogPlayerBgRoot.addView(backgroundMasterView);
        dialogPlayerBgRoot.addView(backgroundLocalPictureView);
        dialogPlayerBgRoot.addView(backgroundPictureUrlView);
        dialogPlayerBgRoot.addView(backgroundBlurRadiusView);

        showLightDialog(context, scrollView, true,
                "仅保存", (dialogInterface, i) -> refresh(),
                "保存并重启", (dialogInterface, i) -> restartApplication(context));
    }

    private void showBeautyDialog(final Context context) {
        dialogBeautyRoot = new BaseDialogItem(context);
        dialogBeautyRoot.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(dialogBeautyRoot);

        PlayerBackgroundView playerBackgroundView = new PlayerBackgroundView(context);
        BeautySidebarHideView beautySidebarHideView = new BeautySidebarHideView(context);
        playerBackgroundView.setOnClickListener(view -> showPlayerBackgroundDialog(context));
        beautySidebarHideView.setOnClickListener(view -> showSidebarDialog(context));

        dialogBeautyRoot.addView(new BeautyTitleView(context));
        dialogBeautyRoot.addView(new BeautyNightModeView(context));
        dialogBeautyRoot.addView(new BeautyTabHideView(context));
        dialogBeautyRoot.addView(beautySidebarHideView);
        dialogBeautyRoot.addView(new BeautyBlackHideView(context));
        dialogBeautyRoot.addView(new BeautyRotationView(context));
        dialogBeautyRoot.addView(new BeautyCommentHotView(context));
        dialogBeautyRoot.addView(playerBackgroundView);
        showLightDialog(context, scrollView, true,
                "仅保存", (dialogInterface, i) -> refresh(),
                "保存并重启", (dialogInterface, i) -> restartApplication(context));
    }

    public static void startImagePicker(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            activity.startActivityForResult(Intent.createChooser(intent, "选择背景图片"), REQUEST_CODE_PICK_BG);
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(Intent.ACTION_PICK);
                intent.setType("image/*");
                activity.startActivityForResult(intent, REQUEST_CODE_PICK_BG);
            } catch (Throwable t2) {
                Toast.makeText(activity, "无法启动图片选择器: " + t2.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static void handleSelectedImageUri(final Activity activity, final Uri uri) {
        if (activity == null || uri == null) {
            return;
        }
        new Thread(() -> {
            try {
                File dir = new File(activity.getFilesDir(), "dolby_background");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File destFile = new File(dir, "player_bg.png");
                InputStream is = activity.getContentResolver().openInputStream(uri);
                if (is == null) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, "无法读取选中的图片", Toast.LENGTH_SHORT).show());
                    return;
                }
                FileOutputStream fos = new FileOutputStream(destFile);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.flush();
                fos.close();
                is.close();

                destFile.setLastModified(System.currentTimeMillis());

                SettingHelper.getInstance().setPictureUrl(destFile.getAbsolutePath());

                Intent intent = new Intent(SettingHelper.refresh_setting);
                LocalBroadcastManager.getInstance(activity).sendBroadcast(intent);
                try {
                    activity.sendBroadcast(intent);
                } catch (Throwable ignored) {
                }

                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, "背景图片选择成功！", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable t) {
                XposedBridge.log("[dolby_beta] handleSelectedImageUri failed: " + t);
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, "保存背景图片失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
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
        if (sidebarMap != null) {
            for (Map.Entry<String, String> entry : sidebarMap.entrySet()) {
                BeautySidebarHideItem item = new BeautySidebarHideItem(context);
                item.initData(sidebarMap, sidebarSettingMap, entry.getKey());
                dialogSidebarRoot.addView(item);
            }
        }

        showLightDialog(context, scrollView, true,
                "确定", (dialogInterface, i) -> refresh(),
                null, null);
    }

    private void showLightDialog(Context context, View content, boolean cancelable,
                                 String positive, DialogInterface.OnClickListener positiveClick,
                                 String negative, DialogInterface.OnClickListener negativeClick) {
        content.setBackgroundColor(Color.WHITE);
        AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setView(content)
                .setCancelable(cancelable)
                .setPositiveButton(positive, positiveClick);
        if (!TextUtils.isEmpty(negative)) {
            builder.setNegativeButton(negative, negativeClick);
        }
        builder.show();
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
