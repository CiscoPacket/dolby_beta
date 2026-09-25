package com.raincat.dolby_beta.hook;

import android.content.Context;

import com.google.gson.Gson;
import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.model.UserPrivilegeBean;

import org.json.JSONObject;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * <pre>
 *     author : RainCat
 *     time   : 2019/10/26
 *     desc   : 本地黑胶SVIP、音质、音效与播放器样式解锁
 *     version: 2.1
 * </pre>
 */

public class BlackHook {
    public BlackHook(Context context, int versionCode) {
        ClassLoader classLoader = context.getClassLoader();
        long expireTime = System.currentTimeMillis() + 31536000000L;

        Class<?> userPrivilegeClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.UserPrivilege", classLoader);
        Class<?> redPlusClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.RedPlus", classLoader);

        // 1. Profile (com.netease.cloudmusic.meta.Profile)
        Class<?> profileClass = findClassIfExists("com.netease.cloudmusic.meta.Profile", classLoader);
        if (profileClass != null) {
            try {
                findAndHookMethod(profileClass, "getUserType", XC_MethodReplacement.returnConstant(1));
            } catch (Throwable ignored) {}

            try {
                findAndHookMethod(profileClass, "getUserPrivilege", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        Object up = param.getResult();
                        if (up == null && userPrivilegeClass != null) {
                            try {
                                up = XposedHelpers.callStaticMethod(userPrivilegeClass, "createDefaultRights", 0L);
                            } catch (Throwable t) {
                                try { up = userPrivilegeClass.newInstance(); } catch (Throwable ignored) {}
                            }
                            param.setResult(up);
                        }
                        if (up != null) {
                            try { XposedHelpers.callMethod(up, "setBlackVipRightsForProfileList", true); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setMusicPackageRightsForProfileList", true); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setRedVipLevel", 9); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setRedVipAnnualCount", 1); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setBlackVipType", 100); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setBlackVipExpireTime", expireTime); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setMusicPackageType", 220); } catch (Throwable ignored) {}
                            try { XposedHelpers.callMethod(up, "setMusicPackageExpireTime", expireTime); } catch (Throwable ignored) {}

                            Object rp = null;
                            try { rp = XposedHelpers.callMethod(up, "getRedPlus"); } catch (Throwable ignored) {}
                            if (rp == null && redPlusClass != null) {
                                try {
                                    rp = redPlusClass.newInstance();
                                    XposedHelpers.callMethod(up, "setRedPlus", rp);
                                } catch (Throwable ignored) {}
                            }
                            if (rp != null) {
                                try { XposedHelpers.setIntField(rp, "vipCode", 100); } catch (Throwable ignored) {}
                                try { XposedHelpers.setIntField(rp, "vipLevel", 9); } catch (Throwable ignored) {}
                                try { XposedHelpers.setLongField(rp, "expireTime", expireTime); } catch (Throwable ignored) {}
                                try { XposedHelpers.setBooleanField(rp, "isSign", true); } catch (Throwable ignored) {}
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
                        Object myVipInfo = param.getResult();
                        if (myVipInfo == null) {
                            Class<?> vipInfoClz = findClassIfExists("com.netease.cloudmusic.music.base.bridge.mymusic.meta.MyVipInfo", classLoader);
                            if (vipInfoClz != null) {
                                try {
                                    myVipInfo = vipInfoClz.newInstance();
                                    param.setResult(myVipInfo);
                                } catch (Throwable ignored) {}
                            }
                        }
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
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof JSONObject) {
                        JSONObject object = (JSONObject) param.args[0];
                        if (object.optInt("code") == 200 && !object.isNull("data")) {
                            JSONObject data = object.optJSONObject("data");
                            if (data != null) {
                                long uid = data.optLong("userId", -1);
                                if (uid > 0 && "-1".equals(ExtraHelper.getExtraDate(ExtraHelper.USER_ID))) {
                                    ExtraHelper.setExtraDate(ExtraHelper.USER_ID, uid);
                                }
                            }
                            Gson gson = new Gson();
                            UserPrivilegeBean userPrivilegeBean = gson.fromJson(object.toString(), UserPrivilegeBean.class);
                            if (userPrivilegeBean != null && userPrivilegeBean.getData() != null) {
                                userPrivilegeBean.getData().getAssociator().setExpireTime(expireTime);
                                userPrivilegeBean.getData().getAssociator().setVipCode(100);
                                userPrivilegeBean.getData().getAssociator().setIsSign(true);
                                userPrivilegeBean.getData().getMusicPackage().setExpireTime(expireTime);
                                userPrivilegeBean.getData().getMusicPackage().setVipCode(220);
                                userPrivilegeBean.getData().getMusicPackage().setIsSign(true);
                                userPrivilegeBean.getData().getRedplus().setExpireTime(expireTime);
                                userPrivilegeBean.getData().getRedplus().setVipCode(100);
                                userPrivilegeBean.getData().getRedplus().setVipLevel(9);
                                userPrivilegeBean.getData().getRedplus().setIsSign(true);
                                userPrivilegeBean.getData().setRedVipAnnualCount(1);
                                userPrivilegeBean.getData().setRedVipLevel(9);
                                param.args[0] = new JSONObject(gson.toJson(userPrivilegeBean));
                            }
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

            try { findAndHookMethod(userPrivilegeClass, "isRedPlus", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isSignSVip", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isBlackVip", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isWhateverVip", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isAnnualVip", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isSignBlackVip", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "isSignMusicPackage", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getRedVipLevel", XC_MethodReplacement.returnConstant(9)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getRedVipAnnualCount", XC_MethodReplacement.returnConstant(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getBlackVipType", XC_MethodReplacement.returnConstant(100)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getBlackVipExpireTime", XC_MethodReplacement.returnConstant(expireTime)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getMusicPackageType", XC_MethodReplacement.returnConstant(220)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeClass, "getMusicPackageExpireTime", XC_MethodReplacement.returnConstant(expireTime)); } catch (Throwable ignored) {}

            try {
                findAndHookMethod(userPrivilegeClass, "getRedPlus", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        Object rp = param.getResult();
                        if (rp == null && redPlusClass != null) {
                            try {
                                rp = redPlusClass.newInstance();
                                param.setResult(rp);
                            } catch (Throwable ignored) {}
                        }
                        if (rp != null) {
                            try { XposedHelpers.setIntField(rp, "vipCode", 100); } catch (Throwable ignored) {}
                            try { XposedHelpers.setIntField(rp, "vipLevel", 9); } catch (Throwable ignored) {}
                            try { XposedHelpers.setLongField(rp, "expireTime", expireTime); } catch (Throwable ignored) {}
                            try { XposedHelpers.setBooleanField(rp, "isSign", true); } catch (Throwable ignored) {}
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        // 3. RedPlus (com.netease.cloudmusic.meta.virtual.RedPlus)
        if (redPlusClass != null) {
            try { findAndHookMethod(redPlusClass, "getVipCode", XC_MethodReplacement.returnConstant(100)); } catch (Throwable ignored) {}
            try { findAndHookMethod(redPlusClass, "getVipLevel", XC_MethodReplacement.returnConstant(9)); } catch (Throwable ignored) {}
            try { findAndHookMethod(redPlusClass, "getExpireTime", XC_MethodReplacement.returnConstant(expireTime)); } catch (Throwable ignored) {}
            try { findAndHookMethod(redPlusClass, "isIsSign", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
        }

        // 4. Moshi 响应模型 (UserPrivilegeDO & UserPrivilegeDO$Companion)
        Class<?> userPrivilegeDOClass = findClassIfExists("com.netease.cloudmusic.meta.response.UserPrivilegeDO", classLoader);
        if (userPrivilegeDOClass != null) {
            try { findAndHookMethod(userPrivilegeDOClass, "getRedVipLevel", XC_MethodReplacement.returnConstant(9)); } catch (Throwable ignored) {}
            try { findAndHookMethod(userPrivilegeDOClass, "getRedVipAnnualCount", XC_MethodReplacement.returnConstant(1)); } catch (Throwable ignored) {}

            Class<?> companionClass = findClassIfExists("com.netease.cloudmusic.meta.response.UserPrivilegeDO$Companion", classLoader);
            if (companionClass != null) {
                try {
                    findAndHookMethod(companionClass, "fromJsonForProfileList", userPrivilegeDOClass, long.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            super.afterHookedMethod(param);
                            Object up = param.getResult();
                            if (up != null) {
                                try { XposedHelpers.callMethod(up, "setBlackVipRightsForProfileList", true); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setMusicPackageRightsForProfileList", true); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setRedVipLevel", 9); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setRedVipAnnualCount", 1); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setBlackVipType", 100); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setBlackVipExpireTime", expireTime); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setMusicPackageType", 220); } catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(up, "setMusicPackageExpireTime", expireTime); } catch (Throwable ignored) {}

                                Object rp = null;
                                try { rp = XposedHelpers.callMethod(up, "getRedPlus"); } catch (Throwable ignored) {}
                                if (rp == null && redPlusClass != null) {
                                    try {
                                        rp = redPlusClass.newInstance();
                                        XposedHelpers.callMethod(up, "setRedPlus", rp);
                                    } catch (Throwable ignored) {}
                                }
                                if (rp != null) {
                                    try { XposedHelpers.setIntField(rp, "vipCode", 100); } catch (Throwable ignored) {}
                                    try { XposedHelpers.setIntField(rp, "vipLevel", 9); } catch (Throwable ignored) {}
                                    try { XposedHelpers.setLongField(rp, "expireTime", expireTime); } catch (Throwable ignored) {}
                                    try { XposedHelpers.setBooleanField(rp, "isSign", true); } catch (Throwable ignored) {}
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
            try { findAndHookMethod(liveProfileClass, "isVip", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(liveProfileClass, "isVipPro", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(liveProfileClass, "isWhateverVip", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
        }

        // 6. 音质控制与解锁 (AudioQualityVO, SongRateChargeInfo, ResourcePrivilege, SongPrivilegeDO)
        Class<?> audioQualityVOClass = findClassIfExists("com.netease.cloudmusic.meta.audio.AudioQualityVO", classLoader);
        if (audioQualityVOClass != null) {
            try { findAndHookMethod(audioQualityVOClass, "isVip", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioQualityVOClass, "isVipAudioQuality", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioQualityVOClass, "getToCashier", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioQualityVOClass, "getDisable", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
        }

        Class<?> chargeInfoClass = findClassIfExists("com.netease.cloudmusic.meta.SongRateChargeSetting$SongRateChargeInfo", classLoader);
        if (chargeInfoClass != null) {
            try { findAndHookMethod(chargeInfoClass, "isToCashier", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(chargeInfoClass, "getChargeType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(chargeInfoClass, "getChargeUrl", XC_MethodReplacement.returnConstant("")); } catch (Throwable ignored) {}
        }

        Class<?> aqTagExtraInfoClass = findClassIfExists("com.netease.cloudmusic.meta.AudioQualityTagExtraInfo", classLoader);
        if (aqTagExtraInfoClass != null) {
            try { findAndHookMethod(aqTagExtraInfoClass, "isVipQuality", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
        }

        Class<?> resPrivilegeClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", classLoader);
        if (resPrivilegeClass != null) {
            try { findAndHookMethod(resPrivilegeClass, "isVipFee", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(resPrivilegeClass, "isVipFeeButNotQQ", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(resPrivilegeClass, "getPlayMaxLevel", XC_MethodReplacement.returnConstant(999000)); } catch (Throwable ignored) {}
            try { findAndHookMethod(resPrivilegeClass, "getDownMaxLevel", XC_MethodReplacement.returnConstant(999000)); } catch (Throwable ignored) {}
            try { findAndHookMethod(resPrivilegeClass, "getFee", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(resPrivilegeClass, "getPayed", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(resPrivilegeClass, "isFee", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
        }

        Class<?> songPrivilegeClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.SongPrivilege", classLoader);
        if (songPrivilegeClass != null) {
            try { findAndHookMethod(songPrivilegeClass, "canShare", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeClass, "getFreeLevel", XC_MethodReplacement.returnConstant(999000)); } catch (Throwable ignored) {}
        }

        Class<?> songPrivilegeDOClass = findClassIfExists("com.netease.cloudmusic.meta.response.SongPrivilegeDO", classLoader);
        if (songPrivilegeDOClass != null) {
            try { findAndHookMethod(songPrivilegeDOClass, "getFee", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getSt", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getCp", XC_MethodReplacement.returnConstant(1)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getPlayMaxbr", XC_MethodReplacement.returnConstant(999000)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getDownloadMaxbr", XC_MethodReplacement.returnConstant(999000)); } catch (Throwable ignored) {}
            try { findAndHookMethod(songPrivilegeDOClass, "getMaxbr", XC_MethodReplacement.returnConstant(999000)); } catch (Throwable ignored) {}
        }

        Class<?> musicInfoClass = findClassIfExists("com.netease.cloudmusic.meta.MusicInfo", classLoader);
        if (musicInfoClass != null) {
            try { findAndHookMethod(musicInfoClass, "isVipMusic", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(musicInfoClass, "isVipMusicButNotQQ", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
        }

        Class<?> simpleMusicInfoClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.SimpleMusicInfo", classLoader);
        if (simpleMusicInfoClass != null) {
            try { findAndHookMethod(simpleMusicInfoClass, "isVipSong", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
        }

        // 7. 鲸云音效与音效素材 (AudioEffectButtonData & AudioEffectTabData)
        Class<?> audioEffectButtonDataClass = findClassIfExists("com.netease.cloudmusic.music.base.bridge.member.audioeffect.model.AudioEffectButtonData", classLoader);
        if (audioEffectButtonDataClass != null) {
            try { findAndHookMethod(audioEffectButtonDataClass, "getAeVipType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getAnimVipType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getAudioType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioEffectButtonDataClass, "getType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
        }

        Class<?> audioBeanClass = findClassIfExists("com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData$AudioBean", classLoader);
        if (audioBeanClass != null) {
            try { findAndHookMethod(audioBeanClass, "getType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(audioBeanClass, "getAudioType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
        }

        Class<?> animBeanClass = findClassIfExists("com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData$AnimationBean", classLoader);
        if (animBeanClass != null) {
            try { findAndHookMethod(animBeanClass, "getType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
        }

        Class<?> aeThemeClass = findClassIfExists("com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData$Theme", classLoader);
        if (aeThemeClass != null) {
            try { findAndHookMethod(aeThemeClass, "getType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
        }

        Class<?> twinkleItemClass = findClassIfExists("com.netease.cloudmusic.music.biz.member.audioeffect.model.AudioEffectTabData$TwinkleEffectItem", classLoader);
        if (twinkleItemClass != null) {
            try { findAndHookMethod(twinkleItemClass, "getType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(twinkleItemClass, "getSoundType", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
        }

        // 8. 播放器样式与动效黑胶 (LunaVipCountDownView, PlayerModeUseInfo, ThemeInfo)
        Class<?> lunaCountDownClass = findClassIfExists("com.netease.cloudmusic.ui.hint.playermode.LunaVipCountDownView", classLoader);
        if (lunaCountDownClass != null) {
            try { findAndHookMethod(lunaCountDownClass, "checkIsVipLimit", XC_MethodReplacement.DO_NOTHING); } catch (Throwable ignored) {}
        }

        Class<?> playerModeUseInfoClass = findClassIfExists("com.netease.cloudmusic.module.player.meta.PlayerModeUseInfo", classLoader);
        if (playerModeUseInfoClass != null) {
            try { findAndHookMethod(playerModeUseInfoClass, "getSuccess", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
        }

        Class<?> themeInfoClass = findClassIfExists("com.netease.cloudmusic.theme.core.ThemeInfo", classLoader);
        if (themeInfoClass != null) {
            try { findAndHookMethod(themeInfoClass, "getPoints", XC_MethodReplacement.returnConstant(0)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "getPrice", XC_MethodReplacement.returnConstant("免费")); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isVip", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isDigitalAlbum", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isRedPlus", XC_MethodReplacement.returnConstant(false)); } catch (Throwable ignored) {}
            try { findAndHookMethod(themeInfoClass, "isPaid", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
        }
    }
}
