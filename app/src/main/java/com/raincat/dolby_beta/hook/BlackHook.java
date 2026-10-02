package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.view.View;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * <pre>
 *     author : RainCat
 *     time   : 2019/10/26
 *     desc   : 本地黑胶SVIP、音质、音效与播放器样式解锁
 *     version: 2.2
 * </pre>
 */

public class BlackHook {
    private static final java.util.concurrent.atomic.AtomicInteger AEF_GETTER_LOG =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static XC_MethodHook returnIfEnabled(final Object constant) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                    param.setResult(constant);
                }
            }
        };
    }

    private static XC_MethodHook doNothingIfEnabled() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                    param.setResult(null);
                }
            }
        };
    }

    /** setter 写入即清零: 从数据源头消除 VIP 标记, 不依赖调用方是否读 getter */
    private static void hookIntSetter(Class<?> c, String setterName, final int forcedValue) {
        try {
            findAndHookMethod(c, setterName, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                        param.args[0] = forcedValue;
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** long setter 写入即清零 (limitTime/beginTime 等限时字段) */
    private static void hookLongSetter(Class<?> c, String setterName, final long forcedValue) {
        if (c == null) return;
        try {
            findAndHookMethod(c, setterName, long.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                        param.args[0] = Long.valueOf(forcedValue);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** getter 读取侧清零 (long) */
    private static void hookLongGetter(Class<?> c, String getterName, final long forcedValue) {
        if (c == null) return;
        try {
            findAndHookMethod(c, getterName, returnIfEnabled(Long.valueOf(forcedValue)));
        } catch (Throwable ignored) {
        }
    }

    /** getter 读取侧清零 (int) */
    private static void hookIntGetter(Class<?> c, String getterName, final int forcedValue) {
        if (c == null) return;
        try {
            findAndHookMethod(c, getterName, returnIfEnabled(Integer.valueOf(forcedValue)));
        } catch (Throwable ignored) {
        }
    }

    /** (long, String, int, long) — PlayerAudioEffectConfig 的构造/copy 参数形状 */
    private static boolean isConfigSignature(Class<?>[] pts) {
        return pts != null && pts.length == 4 && pts[0] == long.class && pts[1] == String.class
                && pts[2] == int.class && pts[3] == long.class;
    }

    /** 把指定下标参数清零 (Integer/Long 自适应) */
    private static XC_MethodHook zeroArgsHook(final int... idxs) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                for (int i : idxs) {
                    if (i < param.args.length && param.args[i] instanceof Number) {
                        param.args[i] = (param.args[i] instanceof Long) ? Long.valueOf(0L) : Integer.valueOf(0);
                    }
                }
            }
        };
    }

    public BlackHook(Context context, int versionCode) {
        ClassLoader classLoader = context.getClassLoader();
        long expireTime = System.currentTimeMillis() + 31536000000L;

        Class<?> userPrivilegeClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.UserPrivilege", classLoader);
        Class<?> redPlusClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.RedPlus", classLoader);

        // 1. Profile (com.netease.cloudmusic.meta.Profile)
        Class<?> profileClass = findClassIfExists("com.netease.cloudmusic.meta.Profile", classLoader);
        if (profileClass != null) {
            try {
                findAndHookMethod(profileClass, "getUserType", returnIfEnabled(1));
            } catch (Throwable ignored) {}

            try {
                findAndHookMethod(profileClass, "getUserPrivilege", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                        Object up = param.getResult();
                        if (up != null) {
                            try { XposedHelpers.callMethod(up, "setBlackVipRightsForProfileList", true); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setMusicPackageRightsForProfileList", true); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setRedVipLevel", 9); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setRedVipAnnualCount", 1); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setBlackVipType", 100); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setBlackVipExpireTime", expireTime); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setMusicPackageType", 230); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setMusicPackageExpireTime", expireTime); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setAlbumVipCode", 400); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setAlbumVipExpireTime", expireTime); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setSignBlackVip", true); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setSignMusicPackage", true); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setSignSVIP", true); } catch (Throwable ignored) {}

                            Object rp = null;
                            try { rp = XposedHelpers.callMethod(up, "getRedPlus"); } catch (Throwable ignored) {}
                            if (rp == null && redPlusClass != null) {
                                try {
                                    rp = redPlusClass.newInstance();
                                    XposedHelpers.callMethod(up, "setRedPlus", rp);
                                } catch (Throwable ignored) {}
                            }
                            if (rp != null) {
                                try { XposedHelpers.callMethod(rp, "setVipCode", 300); } catch (Throwable t) {
                                    try { XposedHelpers.setIntField(rp, "vipCode", 300); } catch (Throwable ignored) {}
                                }
                                try { XposedHelpers.callMethod(rp, "setVipLevel", 9); } catch (Throwable t) {
                                    try { XposedHelpers.setIntField(rp, "vipLevel", 9); } catch (Throwable ignored) {}
                                }
                                try { XposedHelpers.callMethod(rp, "setExpireTime", expireTime); } catch (Throwable t) {
                                    try { XposedHelpers.setLongField(rp, "expireTime", expireTime); } catch (Throwable ignored) {}
                                }
                                try { XposedHelpers.callMethod(rp, "setSign", true); } catch (Throwable t) {
                                    try { XposedHelpers.setBooleanField(rp, "isSign", true); } catch (Throwable ignored) {}
                                }
                            }

                            Class<?> memberLogoClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.MemberLogo", classLoader);
                            if (memberLogoClass != null) {
                                try {
                                    Object logo = XposedHelpers.callMethod(up, "getMemberLogo");
                                    if (logo == null) {
                                        logo = memberLogoClass.newInstance();
                                        XposedHelpers.callMethod(logo, "setUrl", "https://p1.music.126.net/2zQloRuJIGiguu-ekkVxwQ==/109951166687981504.png");
                                        XposedHelpers.callMethod(logo, "setWidth", 64.0);
                                        XposedHelpers.callMethod(logo, "setHeight", 24.0);
                                        XposedHelpers.callMethod(up, "setMemberLogo", logo);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}

            try {
                findAndHookMethod(profileClass, "getMyVipInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                        Object myVipInfo = param.getResult();
                        if (myVipInfo != null) {
                            try { XposedHelpers.setIntField(myVipInfo, "vipHintStatus", 1); } catch (Throwable ignored) {}
                            try { XposedHelpers.setObjectField(myVipInfo, "buttonText", "黑胶SVIP"); } catch (Throwable ignored) {}
                            try { XposedHelpers.setObjectField(myVipInfo, "jumpUrl", "https://music.163.com"); } catch (Throwable ignored) {}
                            try { XposedHelpers.setObjectField(myVipInfo, "logContext", ""); } catch (Throwable ignored) {}
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        // 2. UserPrivilege (com.netease.cloudmusic.meta.virtual.UserPrivilege)
        if (userPrivilegeClass != null) {
            XC_MethodHook fromJsonHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof JSONObject) {
                        JSONObject object = (JSONObject) param.args[0];
                        if (object.optInt("code", 200) == 200) {
                            JSONObject data = object.optJSONObject("data");
                            if (data == null) {
                                data = object;
                            }
                            long uid = data.optLong("userId", -1);
                            if (uid > 0 && "-1".equals(ExtraHelper.getExtraDate(ExtraHelper.USER_ID))) {
                                ExtraHelper.setExtraDate(ExtraHelper.USER_ID, uid);
                            }
                            long now = data.optLong("now", System.currentTimeMillis());
                            long expTime = now + 31622400000L;

                            data.put("redVipLevel", 9);
                            data.put("redVipAnnualCount", 1);

                            JSONObject associator = data.optJSONObject("associator");
                            if (associator == null) {
                                associator = new JSONObject();
                                data.put("associator", associator);
                            }
                            associator.put("vipCode", 100);
                            associator.put("vipLevel", 9);
                            associator.put("expireTime", expTime);
                            associator.put("isSign", true);

                            JSONObject musicPackage = data.optJSONObject("musicPackage");
                            if (musicPackage == null) {
                                musicPackage = new JSONObject();
                                data.put("musicPackage", musicPackage);
                            }
                            musicPackage.put("vipCode", 230);
                            musicPackage.put("vipLevel", 9);
                            musicPackage.put("expireTime", expTime);
                            musicPackage.put("isSign", true);

                            JSONObject redplus = data.optJSONObject("redplus");
                            if (redplus == null) {
                                redplus = new JSONObject();
                                data.put("redplus", redplus);
                            }
                            redplus.put("vipCode", 300);
                            redplus.put("vipLevel", 9);
                            redplus.put("expireTime", expTime);
                            redplus.put("isSign", true);
                            redplus.put("isSignIap", false);
                            redplus.put("isSignDeduct", false);
                            redplus.put("isSignIapDeduct", false);

                            JSONObject albumVip = data.optJSONObject("albumVip");
                            if (albumVip == null) {
                                albumVip = new JSONObject();
                                data.put("albumVip", albumVip);
                            }
                            albumVip.put("vipCode", 400);
                            albumVip.put("vipLevel", 0);
                            albumVip.put("expireTime", expTime);
                        }
                    }
                }
            };
            try {
                findAndHookMethod(userPrivilegeClass, "fromJson", JSONObject.class, fromJsonHook);
            } catch (Throwable ignored) {}
            try {
                findAndHookMethod(userPrivilegeClass, "fromJson2", JSONObject.class, fromJsonHook);
            } catch (Throwable ignored) {}

            try { findAndHookMethod(userPrivilegeClass, "isRedPlus", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isSignSVip", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isBlackVip", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isWhateverVip", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isAnnualVip", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isSignBlackVip", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isSignMusicPackage", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isLuxuryMusicPackage", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isAlbumVip", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getRedVipLevel", returnIfEnabled(9)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getRedVipAnnualCount", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getBlackVipType", returnIfEnabled(100)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getBlackVipExpireTime", returnIfEnabled(expireTime)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getMusicPackageType", returnIfEnabled(230)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getMusicPackageExpireTime", returnIfEnabled(expireTime)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getAlbumVipCode", returnIfEnabled(400)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getAlbumVipExpireTime", returnIfEnabled(expireTime)); } catch (Throwable ignored) {}

            try {
                findAndHookMethod(userPrivilegeClass, "getRedPlus", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                        Object rp = param.getResult();
                        if (rp == null && redPlusClass != null) {
                            try {
                                rp = redPlusClass.newInstance();
                                param.setResult(rp);
                            } catch (Throwable ignored) {}
                        }
                        if (rp != null) {
                            try { XposedHelpers.setIntField(rp, "vipCode", 300); } catch (Throwable ignored) {}
                            try { XposedHelpers.setIntField(rp, "vipLevel", 9); } catch (Throwable ignored) {}
                            try { XposedHelpers.setLongField(rp, "expireTime", expireTime); } catch (Throwable ignored) {}
                            try { XposedHelpers.setBooleanField(rp, "isSign", true); } catch (Throwable ignored) {}
                        }
                    }
                });
            } catch (Throwable ignored) {}

            Class<?> memberLogoClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.MemberLogo", classLoader);
            if (memberLogoClass != null) {
                try {
                    findAndHookMethod(userPrivilegeClass, "getMemberLogo", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            super.afterHookedMethod(param);
                            if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                            try {
                                Object logo = param.getResult();
                                if (logo == null) {
                                    logo = memberLogoClass.newInstance();
                                    param.setResult(logo);
                                }
                                XposedHelpers.callMethod(logo, "setUrl", "https://p1.music.126.net/2zQloRuJIGiguu-ekkVxwQ==/109951166687981504.png");
                                XposedHelpers.callMethod(logo, "setWidth", 64.0);
                                XposedHelpers.callMethod(logo, "setHeight", 24.0);
                            } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }

        // 2.5 音效/播放器样式数据模型 (AudioEffectBrowseData$Item)
        // 9.6.x 效果/样式条目的 VIP 标记字段为 aeVipType/animVipType (旧版是 vipType),
        // RN 音效页/播放器样式页按这些 getter 显示 SVIP 徽标并拦截 —— 直接 hook getter 清零,
        // 无论数据来自网络响应还是本地缓存均覆盖
        Class<?> aeItemClass = findClassIfExists("com.netease.cloudmusic.music.base.bridge.member.audioeffect.model.AudioEffectBrowseData$Item", classLoader);
        if (aeItemClass != null) {
            try { findAndHookMethod(aeItemClass, "getAeVipType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(aeItemClass, "getAnimVipType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(aeItemClass, "getButtonType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(aeItemClass, "getAeLimitTime", returnIfEnabled(999999999999L)); } catch (Throwable ignored) {}
            try { findAndHookMethod(aeItemClass, "getAnimLimitTime", returnIfEnabled(999999999999L)); } catch (Throwable ignored) {}
            try { findAndHookMethod(aeItemClass, "getThemeLimitTime", returnIfEnabled(999999999999L)); } catch (Throwable ignored) {}

            // 桥模型转换点: ModelParse.parseTheme(新模型 Theme) -> 桥模型 Item,
            // 转换后直接覆盖全部 VIP 标记 (无论映射算出什么都被清), 这是 RN/UI 实际消费的模型
            try {
                Class<?> modelParseClass = findClassIfExists(
                        "com.netease.cloudmusic.music.biz.member.audioeffect.model.ModelParse", classLoader);
                Class<?> themeClass = findClassIfExists(
                        "com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData$Theme", classLoader);
                if (modelParseClass != null && themeClass != null) {
                    findAndHookMethod(modelParseClass, "parseTheme", themeClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                            Object item = param.getResult();
                            if (item == null) return;
                            try { XposedHelpers.callMethod(item, "setAeVipType", 0); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(item, "setAnimVipType", 0); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(item, "setButtonType", 0); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(item, "setAeLimitTime", 9999999999999L); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(item, "setAnimLimitTime", 9999999999999L); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(item, "setThemeLimitTime", 9999999999999L); } catch (Throwable ignored) {}
                            if (AEF_GETTER_LOG.getAndIncrement() < 3) {
                                DebugLogger.i("BlackHook", "parseTheme result markers cleared: " + item.getClass().getSimpleName());
                            }
                        }
                    });
                    DebugLogger.i("BlackHook", "parseTheme conversion hook installed");
                }
            } catch (Throwable ignored) {}
            DebugLogger.i("BlackHook", "AudioEffectItem privilege hooks installed");
        }

        // 2.5c 按钮数据模型 AudioEffectButtonData: 点击效果的判定与按钮文案读取此模型
        // (buttonType/aeVipType/animVipType); setter 写入即清零 + getter 读取兜底
        Class<?> buttonDataClass = findClassIfExists(
                "com.netease.cloudmusic.music.base.bridge.member.audioeffect.model.AudioEffectButtonData", classLoader);
        if (buttonDataClass != null) {
            try { findAndHookMethod(buttonDataClass, "getAeVipType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(buttonDataClass, "getAnimVipType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(buttonDataClass, "getButtonType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(buttonDataClass, "getAeLimitTime", returnIfEnabled(999999999999L)); } catch (Throwable ignored) {}
            try { findAndHookMethod(buttonDataClass, "getAnimLimitTime", returnIfEnabled(999999999999L)); } catch (Throwable ignored) {}
            try { findAndHookMethod(buttonDataClass, "getThemeLimitTime", returnIfEnabled(999999999999L)); } catch (Throwable ignored) {}
            hookIntSetter(buttonDataClass, "setAeVipType", 0);
            hookIntSetter(buttonDataClass, "setAnimVipType", 0);
            hookIntSetter(buttonDataClass, "setButtonType", 0);
            DebugLogger.i("BlackHook", "AudioEffectButtonData hooks installed");
        }

        // 2.7 音效/音质"是否需要VIP"判定 —— 双层解锁 (全部 DexKit 动态特征匹配)
        //
        // 第一层 (最上游, 反编译全链路实锤):
        //   ACTION_AudioActionView_isNeedVip → sb1.k.b2() → td1.k.a.f() → td1.b.m()
        //   td1.i.b(MusicAudioQuality) 按音质类型分发到 5 个判定方法:
        //   DOLBY→td1.b.m() / VIVID_EFFECT→td1.p.l() / IMMERSE_EFFECT→td1.d.l() /
        //   JY_EFFECT→td1.e.l() / JY_MASTER→td1.f.l()
        //   判定语义: 返回 true = 需要VIP (调用方把 true 的音质加入锁定列表)。
        //   结构特征(跨版本稳定): 无参返回 boolean + 方法体引用埋点串 "memberBenefitsInfo==null",
        //   一次命中全部 5 个判定方法, 恒返回 false 即解锁所有音效/音质。
        ClassHelper.runAfterResolve(() -> {
            try {
                List<Method> verdicts = ClassHelper.AudioEffectVip.getVerdictMethods(context);
                int hooked = 0;
                for (Method v : verdicts) {
                    try {
                        XposedBridge.hookMethod(v, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                                if (Boolean.TRUE.equals(param.getResult())) {
                                    param.setResult(Boolean.FALSE);
                                    if (AEF_GETTER_LOG.getAndIncrement() < 8) {
                                        DebugLogger.i("BlackHook", "audio vip verdict(" + param.method.getDeclaringClass().getSimpleName()
                                                + "." + param.method.getName() + ") -> false (unlocked)");
                                    }
                                }
                            }
                        });
                        hooked++;
                    } catch (Throwable t) {
                        DebugLogger.e("BlackHook", "verdict hook failed: " + v, t);
                    }
                }
                DebugLogger.i("BlackHook", "audio vip verdicts hooked: " + hooked + "/" + verdicts.size());
            } catch (Throwable t) {
                DebugLogger.e("BlackHook", "audio vip verdict hook error: " + t.getMessage(), t);
            }

            // 第二层 (总线兜底): 拦截模块总线同步派发, 覆盖 UI 直接走 ACTION 串的路径。
            // 总线类由 ClassHelper.ModuleBus 结构特征匹配 (静态 (String,Object[])->Object 与 (String,Object[])->void 并存)
            try {
                Method busDispatch = ClassHelper.ModuleBus.getDispatchMethod(context);
                if (busDispatch != null) {
                    XposedBridge.hookMethod(busDispatch, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                            if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof String)) return;
                            String action = (String) param.args[0];
                            // 语义化通用拦截 (ACTION 名跨版本稳定, 不依赖混淆名):
                            // 布尔类 VIP/限时判定 -> false; VIP 类型枚举/等级 -> 0
                            if (action.contains("isNeedVip") || action.contains("LimitFree")
                                    || action.contains("isLimitOver") || action.contains("isVipLimit")) {
                                if (Boolean.TRUE.equals(param.getResult())) {
                                    param.setResult(Boolean.FALSE);
                                    if (AEF_GETTER_LOG.getAndIncrement() < 8) {
                                        DebugLogger.i("BlackHook", "bus " + action + " -> false (unlocked)");
                                    }
                                }
                            } else if (action.contains("VipType") && param.getResult() instanceof Number
                                    && ((Number) param.getResult()).intValue() != 0) {
                                if (param.getResult() instanceof Long) param.setResult(Long.valueOf(0L));
                                else if (param.getResult() instanceof Integer) param.setResult(Integer.valueOf(0));
                                else param.setResult(0);
                                if (AEF_GETTER_LOG.getAndIncrement() < 8) {
                                    DebugLogger.i("BlackHook", "bus " + action + " -> 0 (vip type cleared)");
                                }
                            }
                        }
                    });
                    DebugLogger.i("BlackHook", "module bus hook installed: " + busDispatch.getDeclaringClass().getName()
                            + "." + busDispatch.getName());
                } else {
                    DebugLogger.e("BlackHook", "module bus not found (degrade to verdict hooks)", null);
                }
            } catch (Throwable t) {
                DebugLogger.e("BlackHook", "module bus hook failed: " + t.getMessage(), t);
            }
        });

        // 2.6 9.6.x 新版音效/播放器样式模型 (biz.member.audioeffect)
        // 条目可用性由 limitTime/beginTime 标记; 数据来自本地缓存, 网络层无法覆盖,
        // 直接 hook 模型 getter 使其恒为可用 (getLimitTime/getBeginTime -> 0)
        Class<?> tabDataClass = findClassIfExists(
                "com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData", classLoader);
        if (tabDataClass != null) {
            String prefix = "com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData$";
            String[] innerClasses = {"Theme", "TwinkleEffectItem", "AudioBean", "AnimationBean", "AudioEffectListItem"};
            int hooked = 0;
            for (String inner : innerClasses) {
                final Class<?> c = findClassIfExists(prefix + inner, classLoader);
                if (c == null) continue;
                try {
                    findAndHookMethod(c, "getLimitTime", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                                Object orig = param.getResult();
                                if (AEF_GETTER_LOG.getAndIncrement() < 6) {
                                    DebugLogger.i("BlackHook", "getLimitTime called: " + c.getSimpleName() + " orig=" + orig);
                                }
                                param.setResult(0L);
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable ignored) {}
                try {
                    findAndHookMethod(c, "getBeginTime", returnIfEnabled(0L));
                    hooked++;
                } catch (Throwable ignored) {}
                // 写侧清零: JSON 解析器 (gc1.c$c) 经 setter 写入模型, 从数据源头消除限时标记,
                // 无论下游读取 getter 还是直接读字段/转 Map 都已是可用状态
                hookLongSetter(c, "setLimitTime", 0L);
                hookLongSetter(c, "setBeginTime", 0L);
                hooked++;
            }
            Class<?> metaClass = findClassIfExists(
                    "com.netease.cloudmusic.music.biz.member.audioeffect.model.UpdateAudioEffectMeta", classLoader);
            if (metaClass != null) {
                try {
                    findAndHookMethod(metaClass, "getFreeEndTime", returnIfEnabled(9999999999999L));
                    hooked++;
                } catch (Throwable ignored) {}
                try {
                    findAndHookMethod(metaClass, "getFreeBeginTime", returnIfEnabled("0"));
                    hooked++;
                } catch (Throwable ignored) {}
            }
            DebugLogger.i("BlackHook", "new audioeffect model getters hooked=" + hooked);
        }

        // 2.6b 播放器样式/音效配置模型 PlayerAudioEffectConfig (module.playeruimode, Moshi 解析)
        // 字段: id/name/vipType(int)/limitTime(long) —— 样式与音效条目的 VIP 标记与限时来源。
        // 类由 DexKit 结构特征匹配 (long id + String name + int vipType + long limitTime + getVipType/getLimitTime)
        ClassHelper.runAfterResolve(() -> {
            try {
                Class<?> paec = ClassHelper.PlayerAudioEffectConfig.getClazz(context);
                if (paec == null) {
                    DebugLogger.e("BlackHook", "PlayerAudioEffectConfig not found", null);
                    return;
                }
                hookIntGetter(paec, "getVipType", 0);
                hookLongGetter(paec, "getLimitTime", 0L);
                hookIntSetter(paec, "setVipType", 0);
                hookLongSetter(paec, "setLimitTime", 0L);
                int ctors = 0;
                // 构造器/拷贝方法 (Moshi 适配器与 Kotlin copy 都经此写入): (long, String, int, long) -> 后两参清零
                for (java.lang.reflect.Constructor<?> ctor : paec.getDeclaredConstructors()) {
                    if (isConfigSignature(ctor.getParameterTypes())) {
                        try {
                            XposedBridge.hookMethod(ctor, zeroArgsHook(2, 3));
                            ctors++;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                for (Method m : paec.getDeclaredMethods()) {
                    if (m.getReturnType() == paec && isConfigSignature(m.getParameterTypes())) {
                        try {
                            XposedBridge.hookMethod(m, zeroArgsHook(2, 3));
                            ctors++;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                DebugLogger.i("BlackHook", "PlayerAudioEffectConfig hooked (" + paec.getName() + ", writes=" + ctors + ")");
            } catch (Throwable t) {
                DebugLogger.e("BlackHook", "PlayerAudioEffectConfig hook error: " + t.getMessage(), t);
            }
        });

        // 3. RedPlus (com.netease.cloudmusic.meta.virtual.RedPlus)
        if (redPlusClass != null) {
            try { findAndHookMethod(redPlusClass, "getVipCode", returnIfEnabled(300)); } catch (Throwable ignored) {}
            try { findAndHookMethod(redPlusClass, "getVipLevel", returnIfEnabled(9)); } catch (Throwable ignored) {}
            try { findAndHookMethod(redPlusClass, "getExpireTime", returnIfEnabled(expireTime)); } catch (Throwable ignored) {}
            try { findAndHookMethod(redPlusClass, "isIsSign", returnIfEnabled(true)); } catch (Throwable ignored) {}
        }

        // 4. Moshi 响应模型 (UserPrivilegeDO & UserPrivilegeDO$Companion)
        Class<?> userPrivilegeDOClass = findClassIfExists("com.netease.cloudmusic.meta.response.UserPrivilegeDO", classLoader);
        if (userPrivilegeDOClass != null) {
            try { findAndHookMethod(userPrivilegeDOClass, "getRedVipLevel", returnIfEnabled(9)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeDOClass, "getRedVipAnnualCount", returnIfEnabled(1)); } catch (Throwable ignored) {}

            Class<?> memberLogoDOClass = findClassIfExists("com.netease.cloudmusic.meta.response.MemberLogoDO", classLoader);
            if (memberLogoDOClass != null) {
                try {
                    findAndHookMethod(userPrivilegeDOClass, "getMemberLogo", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            super.afterHookedMethod(param);
                            if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                            try {
                                Object logoDO = param.getResult();
                                if (logoDO == null) {
                                    logoDO = XposedHelpers.newInstance(memberLogoDOClass, "https://p1.music.126.net/2zQloRuJIGiguu-ekkVxwQ==/109951166687981504.png", 64.0, 24.0);
                                    param.setResult(logoDO);
                                } else {
                                    XposedHelpers.setObjectField(logoDO, "url", "https://p1.music.126.net/2zQloRuJIGiguu-ekkVxwQ==/109951166687981504.png");
                                    XposedHelpers.setDoubleField(logoDO, "width", 64.0);
                                    XposedHelpers.setDoubleField(logoDO, "height", 24.0);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {}
            }

            Class<?> companionClass = findClassIfExists("com.netease.cloudmusic.meta.response.UserPrivilegeDO$Companion", classLoader);
            if (companionClass != null) {
                try {
                    findAndHookMethod(companionClass, "fromJsonForProfileList", userPrivilegeDOClass, long.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            super.afterHookedMethod(param);
                            if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                            Object up = param.getResult();
                            if (up != null) {
                                try { XposedHelpers.callMethod(up, "setBlackVipRightsForProfileList", true); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setMusicPackageRightsForProfileList", true); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setRedVipLevel", 9); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setRedVipAnnualCount", 1); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setBlackVipType", 100); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setBlackVipExpireTime", expireTime); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setMusicPackageType", 230); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setMusicPackageExpireTime", expireTime); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setAlbumVipCode", 400); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setAlbumVipExpireTime", expireTime); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setSignBlackVip", true); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setSignMusicPackage", true); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setSignSVIP", true); } catch (Throwable ignored) {}

                                Object rp = null;
                                try { rp = XposedHelpers.callMethod(up, "getRedPlus"); } catch (Throwable ignored) {}
                                if (rp == null && redPlusClass != null) {
                                    try {
                                        rp = redPlusClass.newInstance();
                                        XposedHelpers.callMethod(up, "setRedPlus", rp);
                                    } catch (Throwable ignored) {}
                                }
                                if (rp != null) {
                                    try { XposedHelpers.callMethod(rp, "setVipCode", 300); } catch (Throwable t) {
                                        try { XposedHelpers.setIntField(rp, "vipCode", 300); } catch (Throwable ignored) {}
                                    }
                                    try { XposedHelpers.callMethod(rp, "setVipLevel", 9); } catch (Throwable t) {
                                        try { XposedHelpers.setIntField(rp, "vipLevel", 9); } catch (Throwable ignored) {}
                                    }
                                    try { XposedHelpers.callMethod(rp, "setExpireTime", expireTime); } catch (Throwable t) {
                                        try { XposedHelpers.setLongField(rp, "expireTime", expireTime); } catch (Throwable ignored) {}
                                    }
                                    try { XposedHelpers.callMethod(rp, "setSign", true); } catch (Throwable t) {
                                        try { XposedHelpers.setBooleanField(rp, "isSign", true); } catch (Throwable ignored) {}
                                    }
                                }

                                Class<?> memberLogoClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.MemberLogo", classLoader);
                                if (memberLogoClass != null) {
                                    try {
                                        Object logo = XposedHelpers.callMethod(up, "getMemberLogo");
                                        if (logo == null) {
                                            logo = memberLogoClass.newInstance();
                                            XposedHelpers.callMethod(logo, "setUrl", "https://p1.music.126.net/2zQloRuJIGiguu-ekkVxwQ==/109951166687981504.png");
                                            XposedHelpers.callMethod(logo, "setWidth", 64.0);
                                            XposedHelpers.callMethod(logo, "setHeight", 24.0);
                                            XposedHelpers.callMethod(up, "setMemberLogo", logo);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }

        // 5. Look/Live Profile (com.netease.play.commonmeta.Profile)
        Class<?> liveProfileClass = findClassIfExists("com.netease.play.commonmeta.Profile", classLoader);
        if (liveProfileClass != null) {
            try { findAndHookMethod(liveProfileClass, "isVip", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(liveProfileClass, "isVipPro", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(liveProfileClass, "isWhateverVip", returnIfEnabled(true)); } catch (Throwable ignored) {}
        }

        // 6. 音质控制与解锁 (AudioQualityBottomSheet, AudioQualityVO, SongRateChargeInfo, ResourcePrivilege, SongPrivilegeDO)
        Class<?> aqBottomSheetClass = findClassIfExists("com.netease.cloudmusic.ui.bottomsheet.AudioQualityBottomSheet", classLoader);
        if (aqBottomSheetClass != null) {
            try { findAndHookMethod(aqBottomSheetClass, "isAlbumPayed", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(aqBottomSheetClass, "isPermanentPayed", returnIfEnabled(true)); } catch (Throwable ignored) {}
        }

        Class<?> audioQualityVOClass = findClassIfExists("com.netease.cloudmusic.meta.audio.AudioQualityVO", classLoader);
        if (audioQualityVOClass != null) {
            try { findAndHookMethod(audioQualityVOClass, "isVip", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioQualityVOClass, "isVipAudioQuality", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioQualityVOClass, "getToCashier", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioQualityVOClass, "getDisable", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioQualityVOClass, "getCornerType", returnIfEnabled(0)); } catch (Throwable ignored) {}

            try {
                findAndHookMethod(audioQualityVOClass, "setDisable", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) param.args[0] = false;
                    }
                });
            } catch (Throwable ignored) {}
            try {
                findAndHookMethod(audioQualityVOClass, "setToCashier", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) param.args[0] = false;
                    }
                });
            } catch (Throwable ignored) {}
            try {
                XposedBridge.hookAllConstructors(audioQualityVOClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                        try { XposedHelpers.setBooleanField(param.thisObject, "disable", false); } catch (Throwable ignored) {}
                        try { XposedHelpers.setBooleanField(param.thisObject, "toCashier", false); } catch (Throwable ignored) {}
                        try { XposedHelpers.setBooleanField(param.thisObject, "isVip", false); } catch (Throwable ignored) {}
                        try { XposedHelpers.setBooleanField(param.thisObject, "isVipAudioQuality", false); } catch (Throwable ignored) {}
                        try { XposedHelpers.setIntField(param.thisObject, "cornerType", 0); } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> trialInfoClass = findClassIfExists("com.netease.cloudmusic.meta.SoundQualityTrialInfo", classLoader);
        if (trialInfoClass != null) {
            try { findAndHookMethod(trialInfoClass, "getCanTrial", returnIfEnabled(Boolean.TRUE)); } catch (Throwable ignored) {}
        }

        Class<?> spatialPrivilegeClass = findClassIfExists("com.netease.cloudmusic.meta.MemberBenefitsSpatialAudioPrivilege", classLoader);
        if (spatialPrivilegeClass != null) {
            try { findAndHookMethod(spatialPrivilegeClass, "getDolbyOn", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(spatialPrivilegeClass, "getImmersiveOn", returnIfEnabled(true)); } catch (Throwable ignored) {}
        }

        Class<?> simpleLogoInfoClass = findClassIfExists("com.netease.cloudmusic.meta.MemberLogoSimpleInfo", classLoader);
        if (simpleLogoInfoClass != null) {
            try {
                findAndHookMethod(simpleLogoInfoClass, "getUrl", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                        if (param.getResult() == null || "".equals(param.getResult())) {
                            param.setResult("https://p1.music.126.net/2zQloRuJIGiguu-ekkVxwQ==/109951166687981504.png");
                        }
                    }
                });
            } catch (Throwable ignored) {}
            try {
                findAndHookMethod(simpleLogoInfoClass, "getImageWidth", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                        Object res = param.getResult();
                        if (res == null || (res instanceof Number && ((Number) res).intValue() == 0)) {
                            param.setResult(64);
                        }
                    }
                });
            } catch (Throwable ignored) {}
            try {
                findAndHookMethod(simpleLogoInfoClass, "getImageHeight", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                        Object res = param.getResult();
                        if (res == null || (res instanceof Number && ((Number) res).intValue() == 0)) {
                            param.setResult(24);
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> dynamicLogoInfoClass = findClassIfExists("com.netease.cloudmusic.meta.MemberLogoDynamicInfo", classLoader);
        if (dynamicLogoInfoClass != null) {
            try {
                findAndHookMethod(dynamicLogoInfoClass, "getUrl", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                        if (param.getResult() == null || "".equals(param.getResult())) {
                            param.setResult("https://p1.music.126.net/2zQloRuJIGiguu-ekkVxwQ==/109951166687981504.png");
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> mineVipManagerClass = findClassIfExists("com.netease.cloudmusic.ui.MineVipViewManager", classLoader);
        if (mineVipManagerClass != null) {
            try {
                XposedBridge.hookAllMethods(mineVipManagerClass, "initVipIcon", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                        if (param.args != null && param.args.length >= 3 && param.args[2] instanceof Boolean) {
                            param.args[2] = false;
                        }
                        try { XposedHelpers.setBooleanField(param.thisObject, "isVisitors", false); } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> chargeInfoClass = findClassIfExists("com.netease.cloudmusic.meta.SongRateChargeSetting$SongRateChargeInfo", classLoader);
        if (chargeInfoClass != null) {
            try { findAndHookMethod(chargeInfoClass, "isToCashier", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(chargeInfoClass, "getChargeType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(chargeInfoClass, "getChargeUrl", returnIfEnabled("")); } catch (Throwable ignored) {}
        }

        Class<?> aqTagExtraInfoClass = findClassIfExists("com.netease.cloudmusic.meta.AudioQualityTagExtraInfo", classLoader);
        if (aqTagExtraInfoClass != null) {
            try { findAndHookMethod(aqTagExtraInfoClass, "isVipQuality", returnIfEnabled(false)); } catch (Throwable ignored) {}
        }

        Class<?> resPrivilegeClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", classLoader);
        if (resPrivilegeClass != null) {
            try { findAndHookMethod(resPrivilegeClass, "isVipFee", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(resPrivilegeClass, "isVipFeeButNotQQ", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(resPrivilegeClass, "getPlayMaxLevel", returnIfEnabled(999000)); } catch (Throwable ignored) {}
            try { findAndHookMethod(resPrivilegeClass, "getDownMaxLevel", returnIfEnabled(999000)); } catch (Throwable ignored) {}
            try { findAndHookMethod(resPrivilegeClass, "getFee", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(resPrivilegeClass, "getPayed", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(resPrivilegeClass, "isFee", returnIfEnabled(false)); } catch (Throwable ignored) {}
        }

        Class<?> songPrivilegeClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.SongPrivilege", classLoader);
        if (songPrivilegeClass != null) {
            try { findAndHookMethod(songPrivilegeClass, "canShare", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeClass, "getFreeLevel", returnIfEnabled(999000)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeClass, "getPayed", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeClass, "getFee", returnIfEnabled(0)); } catch (Throwable ignored) {}
        }

        Class<?> songPrivilegeDOClass = findClassIfExists("com.netease.cloudmusic.meta.response.SongPrivilegeDO", classLoader);
        if (songPrivilegeDOClass != null) {
            try { findAndHookMethod(songPrivilegeDOClass, "getFee", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getPayed", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getSt", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getCp", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getSp", returnIfEnabled(7)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getSubp", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getPlayMaxbr", returnIfEnabled(999000)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getDownloadMaxbr", returnIfEnabled(999000)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getMaxbr", returnIfEnabled(999000)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getPlLevel", returnIfEnabled("lossless")); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getDlLevel", returnIfEnabled("lossless")); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getFlLevel", returnIfEnabled("lossless")); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getFreeTrialPrivilege", returnIfEnabled(null)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getFreeTimeTrialPrivilege", returnIfEnabled(null)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getFreeTrialInfo", returnIfEnabled(null)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getFreeTrialType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getTrialInfo", returnIfEnabled(null)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getTrialMode", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getTrialType", returnIfEnabled(0)); } catch (Throwable ignored) {}
        }

        Class<?> musicInfoClass = findClassIfExists("com.netease.cloudmusic.meta.MusicInfo", classLoader);
        if (musicInfoClass != null) {
            try { findAndHookMethod(musicInfoClass, "isVipMusic", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(musicInfoClass, "isVipMusicButNotQQ", returnIfEnabled(false)); } catch (Throwable ignored) {}
        }

        Class<?> simpleMusicInfoClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.SimpleMusicInfo", classLoader);
        if (simpleMusicInfoClass != null) {
            try { findAndHookMethod(simpleMusicInfoClass, "isVipSong", returnIfEnabled(false)); } catch (Throwable ignored) {}
        }

        // 7. 鲸云音效与音效素材 (AudioActionView, AudioEffectButtonData & AudioEffectTabData, etc.)
        for (String sub : new String[]{
                "com.netease.cloudmusic.music.biz.member.audioeffect.ui.AudioActionView",
                "com.netease.cloudmusic.music.biz.member.audioeffect.ui.NormalAudioActionView",
                "com.netease.cloudmusic.music.biz.member.audioeffect.ui.DefaultAudioActionView",
                "com.netease.cloudmusic.music.biz.member.audioeffect.ui.ToolbarAudioActionView",
                "com.netease.cloudmusic.music.biz.member.audioeffect.ui.DeviceAeAudioActionView"
        }) {
            Class<?> clazz = findClassIfExists(sub, classLoader);
            if (clazz != null) {
                try { XposedBridge.hookAllMethods(clazz, "p", returnIfEnabled(false)); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(clazz, "l", returnIfEnabled(false)); } catch (Throwable ignored) {}
            }
        }

        Class<?> audioEffectButtonDataClass = findClassIfExists("com.netease.cloudmusic.music.base.bridge.member.audioeffect.model.AudioEffectButtonData", classLoader);
        if (audioEffectButtonDataClass != null) {
            try { findAndHookMethod(audioEffectButtonDataClass, "getAeVipType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getAnimVipType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getAudioType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getType", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getAeLimitTime", returnIfEnabled(0L)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getAnimLimitTime", returnIfEnabled(0L)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getThemeLimitTime", returnIfEnabled(0L)); } catch (Throwable ignored) {}
        }

        Class<?> audioBeanClass = findClassIfExists("com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData$AudioBean", classLoader);
        if (audioBeanClass != null) {
            try { findAndHookMethod(audioBeanClass, "getType", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioBeanClass, "getAudioType", returnIfEnabled(0)); } catch (Throwable ignored) {}
        }

        Class<?> animBeanClass = findClassIfExists("com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData$AnimationBean", classLoader);
        if (animBeanClass != null) {
            try { findAndHookMethod(animBeanClass, "getType", returnIfEnabled(1)); } catch (Throwable ignored) {}
        }

        Class<?> aeThemeClass = findClassIfExists("com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData$Theme", classLoader);
        if (aeThemeClass != null) {
            try { findAndHookMethod(aeThemeClass, "getType", returnIfEnabled(1)); } catch (Throwable ignored) {}
        }

        Class<?> twinkleItemClass = findClassIfExists("com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData$TwinkleEffectItem", classLoader);
        if (twinkleItemClass != null) {
            try { findAndHookMethod(twinkleItemClass, "getType", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(twinkleItemClass, "getSoundType", returnIfEnabled(0)); } catch (Throwable ignored) {}
        }

        Class<?> audioEffectDetailDataClass = findClassIfExists("com.netease.cloudmusic.music.base.bridge.member.audioeffect.model.AudioEffectDetailData", classLoader);
        if (audioEffectDetailDataClass != null) {
            try { findAndHookMethod(audioEffectDetailDataClass, "getType", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectDetailDataClass, "getSoundType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectDetailDataClass, "getLimitTime", returnIfEnabled(0L)); } catch (Throwable ignored) {}
        }

        Class<?> animationDataClass = findClassIfExists("com.netease.cloudmusic.music.base.bridge.member.audioeffect.model.AudioEffectDetailData$AnimationData", classLoader);
        if (animationDataClass != null) {
            try { findAndHookMethod(animationDataClass, "getType", returnIfEnabled(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(animationDataClass, "getLimitTime", returnIfEnabled(0L)); } catch (Throwable ignored) {}
        }

        Class<?> aeDetailEntityClass = findClassIfExists("com.netease.cloudmusic.module.player.audioeffect.core.meta.AudioEffectDetailEntity", classLoader);
        if (aeDetailEntityClass != null) {
            try { findAndHookMethod(aeDetailEntityClass, "getFreeEndTime", returnIfEnabled(expireTime)); } catch (Throwable ignored) {}
        }

        Class<?> animEffectDetailEntityClass = findClassIfExists("com.netease.cloudmusic.module.player.audioeffect.core.meta.AnimEffectDetailEntity", classLoader);
        if (animEffectDetailEntityClass != null) {
            try { findAndHookMethod(animEffectDetailEntityClass, "getFreeEndTime", returnIfEnabled(expireTime)); } catch (Throwable ignored) {}
        }

        Class<?> customEQTrialDataClass = findClassIfExists("com.netease.cloudmusic.music.base.bridge.member.audioeffect.model.CustomEQTrialStrategyData", classLoader);
        if (customEQTrialDataClass != null) {
            try { findAndHookMethod(customEQTrialDataClass, "getExpireTime", returnIfEnabled(expireTime)); } catch (Throwable ignored) {}
            try { findAndHookMethod(customEQTrialDataClass, "getRemainTime", returnIfEnabled(31536000000L)); } catch (Throwable ignored) {}
            try { findAndHookMethod(customEQTrialDataClass, "getTrialCopywriter", returnIfEnabled(null)); } catch (Throwable ignored) {}
            try { findAndHookMethod(customEQTrialDataClass, "getExpiringSoonCopywriter", returnIfEnabled(null)); } catch (Throwable ignored) {}
        }

        Class<?> playerAudioEffectConfigClass = findClassIfExists("com.netease.cloudmusic.module.playeruimode.PlayerAudioEffectConfig", classLoader);
        if (playerAudioEffectConfigClass != null) {
            try { findAndHookMethod(playerAudioEffectConfigClass, "getVipType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(playerAudioEffectConfigClass, "getLimitTime", returnIfEnabled(0L)); } catch (Throwable ignored) {}
        }

        // 8. 播放器样式与动效黑胶 (LunaVipCountDownView, PlayerModeInfo, PlayerModeUseInfo, PetPlayerModelCycleInfo, ThemeInfo)
        Class<?> lunaCountDownClass = findClassIfExists("com.netease.cloudmusic.ui.hint.playermode.LunaVipCountDownView", classLoader);
        if (lunaCountDownClass != null) {
            try { findAndHookMethod(lunaCountDownClass, "checkIsVipLimit", doNothingIfEnabled()); } catch (Throwable ignored) {}
            try {
                findAndHookMethod(lunaCountDownClass, "setVisibility", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                            param.args[0] = View.GONE;
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> playerModeInfoClass = findClassIfExists("com.netease.cloudmusic.module.player.meta.PlayerModeInfo", classLoader);
        if (playerModeInfoClass != null) {
            try { findAndHookMethod(playerModeInfoClass, "getTagCode", returnIfEnabled("")); } catch (Throwable ignored) {}
            try { findAndHookMethod(playerModeInfoClass, "getLimitFree", returnIfEnabled("1")); } catch (Throwable ignored) {}
            try { findAndHookMethod(playerModeInfoClass, "getActivityCode", returnIfEnabled(null)); } catch (Throwable ignored) {}
        }

        Class<?> playerModeUseInfoClass = findClassIfExists("com.netease.cloudmusic.module.player.meta.PlayerModeUseInfo", classLoader);
        if (playerModeUseInfoClass != null) {
            try { findAndHookMethod(playerModeUseInfoClass, "getSuccess", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(playerModeUseInfoClass, "getToast", returnIfEnabled("")); } catch (Throwable ignored) {}
        }

        Class<?> petCycleClass = findClassIfExists("com.netease.cloudmusic.module.playeruimode.petplayermode.PetPlayerModelCycleInfo", classLoader);
        if (petCycleClass != null) {
            try { findAndHookMethod(petCycleClass, "getAvailable", returnIfEnabled(true)); } catch (Throwable ignored) {}
        }

        Class<?> themeInfoClass = findClassIfExists("com.netease.cloudmusic.theme.core.ThemeInfo", classLoader);
        if (themeInfoClass != null) {
            try {
                findAndHookMethod(themeInfoClass, "parseThemeInfo", JSONObject.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return;
                        try {
                            JSONObject json = (JSONObject) param.args[0];
                            if (json != null) {
                                json.put("paid", true);
                                json.put("forVip", false);
                                json.put("paidAlbum", true);
                                json.put("redPlus", false);
                                json.put("pointCost", 0);
                                json.put("rmbCost", "免费");
                                json.remove("periodLimitSkin");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "getPoints", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "getPrice", returnIfEnabled("免费")); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isVip", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isDigitalAlbum", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isRedPlus", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isPaid", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isPaidDigitalAlbum", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "getPeriodLimitSkin", returnIfEnabled(null)); } catch (Throwable ignored) {}
        }

        // 9. 统一权益鉴权拦截 (RightsDO & MemberBenefitsInfo)
        Class<?> rightsDOClass = findClassIfExists("com.netease.cloudmusic.meta.response.RightsDO", classLoader);
        if (rightsDOClass != null) {
            try { findAndHookMethod(rightsDOClass, "getRights", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(rightsDOClass, "getVipCode", returnIfEnabled(100)); } catch (Throwable ignored) {}
        }

        Class<?> memberBenefitsClass = findClassIfExists("com.netease.cloudmusic.meta.MemberBenefitsInfo", classLoader);
        if (memberBenefitsClass != null) {
            try { findAndHookMethod(memberBenefitsClass, "getCanUse", returnIfEnabled(Boolean.TRUE)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "getCanNotUseReasonCode", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "isNormalFreeType", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "fromRedPlus", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "fromVip", returnIfEnabled(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "isVipLimitFreeType", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "showNormalLimitFree", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "showSVipLimitFree", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "showVipLimitFree", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "fromLimitFree", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "getTrialStatus", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "getTrialType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "getUserSrc", returnIfEnabled(1)); } catch (Throwable ignored) {}
        }
    }
}
