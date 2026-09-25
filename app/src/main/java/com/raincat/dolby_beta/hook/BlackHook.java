package com.raincat.dolby_beta.hook;

import android.content.Context;

import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONObject;

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

        // 7. 鲸云音效与音效素材 (AudioEffectButtonData & AudioEffectTabData)
        Class<?> audioEffectButtonDataClass = findClassIfExists("com.netease.cloudmusic.music.base.bridge.member.audioeffect.model.AudioEffectButtonData", classLoader);
        if (audioEffectButtonDataClass != null) {
            try { findAndHookMethod(audioEffectButtonDataClass, "getAeVipType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getAnimVipType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getAudioType", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getType", returnIfEnabled(1)); } catch (Throwable ignored) {}
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

        // 8. 播放器样式与动效黑胶 (LunaVipCountDownView, PlayerModeUseInfo, PetPlayerModelCycleInfo, ThemeInfo)
        Class<?> lunaCountDownClass = findClassIfExists("com.netease.cloudmusic.ui.hint.playermode.LunaVipCountDownView", classLoader);
        if (lunaCountDownClass != null) {
            try { findAndHookMethod(lunaCountDownClass, "checkIsVipLimit", doNothingIfEnabled()); } catch (Throwable ignored) {}
        }

        Class<?> playerModeUseInfoClass = findClassIfExists("com.netease.cloudmusic.module.player.meta.PlayerModeUseInfo", classLoader);
        if (playerModeUseInfoClass != null) {
            try { findAndHookMethod(playerModeUseInfoClass, "getSuccess", returnIfEnabled(true)); } catch (Throwable ignored) {}
        }

        Class<?> petCycleClass = findClassIfExists("com.netease.cloudmusic.module.playeruimode.petplayermode.PetPlayerModelCycleInfo", classLoader);
        if (petCycleClass != null) {
            try { findAndHookMethod(petCycleClass, "getAvailable", returnIfEnabled(true)); } catch (Throwable ignored) {}
        }

        Class<?> themeInfoClass = findClassIfExists("com.netease.cloudmusic.theme.core.ThemeInfo", classLoader);
        if (themeInfoClass != null) {
            try { findAndHookMethod(themeInfoClass, "getPoints", returnIfEnabled(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "getPrice", returnIfEnabled("免费")); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isVip", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isDigitalAlbum", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isRedPlus", returnIfEnabled(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isPaid", returnIfEnabled(true)); } catch (Throwable ignored) {}
        }

        // 9. 统一权益鉴权拦截 (com.netease.cloudmusic.meta.MemberBenefitsInfo)
        Class<?> memberBenefitsClass = findClassIfExists("com.netease.cloudmusic.meta.MemberBenefitsInfo", classLoader);
        if (memberBenefitsClass != null) {
            try { findAndHookMethod(memberBenefitsClass, "getCanUse", returnIfEnabled(Boolean.TRUE)); } catch (Throwable ignored) {}
            try { findAndHookMethod(memberBenefitsClass, "getCanNotUseReasonCode", returnIfEnabled(null)); } catch (Throwable ignored) {}
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
