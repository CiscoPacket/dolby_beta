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
 *     desc   : 黑胶，100黑胶，220音乐包
 *     version: 1.0
 * </pre>
 */

public class BlackHook {
    public BlackHook(Context context, int versionCode) {
        ClassLoader classLoader = context.getClassLoader();

        // 1. Profile: setUserPoint
        Class<?> profileClass = findClassIfExists("com.netease.cloudmusic.meta.Profile", classLoader);
        if (profileClass != null) {
            try {
                XposedBridge.hookAllMethods(profileClass, "setUserPoint", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        try {
                            XposedHelpers.callMethod(param.thisObject, "setVipType", 100);
                            XposedHelpers.callMethod(param.thisObject, "setVipProExpireTime", System.currentTimeMillis() + 31536000000L);
                            XposedHelpers.callMethod(param.thisObject, "setExpireTime", System.currentTimeMillis() + 31536000000L);
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable ignored) {}
        }

        // 2. UserPrivilege: fromJson 解析与会员特权属性
        Class<?> userPrivilegeClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.UserPrivilege", classLoader);
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
                                long expireTime = System.currentTimeMillis() + 31536000000L;
                                userPrivilegeBean.getData().getAssociator().setExpireTime(expireTime);
                                userPrivilegeBean.getData().getAssociator().setVipCode(100);
                                userPrivilegeBean.getData().getMusicPackage().setExpireTime(expireTime);
                                userPrivilegeBean.getData().getMusicPackage().setVipCode(220);
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

            try {
                findAndHookMethod(userPrivilegeClass, "isVip", XC_MethodReplacement.returnConstant(true));
                findAndHookMethod(userPrivilegeClass, "isBlackVip", XC_MethodReplacement.returnConstant(true));
                findAndHookMethod(userPrivilegeClass, "isAnnualVip", XC_MethodReplacement.returnConstant(true));
                findAndHookMethod(userPrivilegeClass, "isWhateverVip", XC_MethodReplacement.returnConstant(true));
                findAndHookMethod(userPrivilegeClass, "isRedPlus", XC_MethodReplacement.returnConstant(true));
                findAndHookMethod(userPrivilegeClass, "isSignBlackVip", XC_MethodReplacement.returnConstant(true));
                findAndHookMethod(userPrivilegeClass, "isSignSVip", XC_MethodReplacement.returnConstant(true));
                findAndHookMethod(userPrivilegeClass, "getRedVipLevel", XC_MethodReplacement.returnConstant(9));
                findAndHookMethod(userPrivilegeClass, "getRedVipAnnualCount", XC_MethodReplacement.returnConstant(1));
                findAndHookMethod(userPrivilegeClass, "getBlackVipType", XC_MethodReplacement.returnConstant(100));
                findAndHookMethod(userPrivilegeClass, "getBlackVipExpireTime", XC_MethodReplacement.returnConstant(System.currentTimeMillis() + 31536000000L));
                findAndHookMethod(userPrivilegeClass, "getMusicPackageType", XC_MethodReplacement.returnConstant(220));
                findAndHookMethod(userPrivilegeClass, "getMusicPackageExpireTime", XC_MethodReplacement.returnConstant(System.currentTimeMillis() + 31536000000L));
            } catch (Throwable ignored) {}
        }

        // 3. 9.x+ Moshi 响应模型 (UserPrivilegeDO & RightsDO)
        Class<?> userPrivilegeDOClass = findClassIfExists("com.netease.cloudmusic.meta.response.UserPrivilegeDO", classLoader);
        if (userPrivilegeDOClass != null) {
            try {
                findAndHookMethod(userPrivilegeDOClass, "getRights", XC_MethodReplacement.returnConstant(true));
                findAndHookMethod(userPrivilegeDOClass, "getVipCode", XC_MethodReplacement.returnConstant(100));
                findAndHookMethod(userPrivilegeDOClass, "getRedVipLevel", XC_MethodReplacement.returnConstant(9));
                findAndHookMethod(userPrivilegeDOClass, "getRedVipAnnualCount", XC_MethodReplacement.returnConstant(1));
            } catch (Throwable ignored) {}
        }

        Class<?> rightsDOClass = findClassIfExists("com.netease.cloudmusic.meta.response.RightsDO", classLoader);
        if (rightsDOClass != null) {
            try {
                findAndHookMethod(rightsDOClass, "getRights", XC_MethodReplacement.returnConstant(true));
                findAndHookMethod(rightsDOClass, "getVipCode", XC_MethodReplacement.returnConstant(100));
            } catch (Throwable ignored) {}
        }

        // 4. 主题与皮肤
        Class<?> themeInfoClass = findClassIfExists("com.netease.cloudmusic.theme.core.ThemeInfo", classLoader);
        if (themeInfoClass != null) {
            try {
                findAndHookMethod(themeInfoClass, "getPoints", XC_MethodReplacement.returnConstant(0));
                findAndHookMethod(themeInfoClass, "getPrice", XC_MethodReplacement.returnConstant("免费"));
                findAndHookMethod(themeInfoClass, "isVip", XC_MethodReplacement.returnConstant(false));
                findAndHookMethod(themeInfoClass, "isDigitalAlbum", XC_MethodReplacement.returnConstant(false));
                findAndHookMethod(themeInfoClass, "isRedPlus", XC_MethodReplacement.returnConstant(false));
                findAndHookMethod(themeInfoClass, "isPaid", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable ignored) {}
            try {
                findAndHookMethod(themeInfoClass, "i", XC_MethodReplacement.returnConstant(0));
                findAndHookMethod(themeInfoClass, "j", XC_MethodReplacement.returnConstant("免费"));
                findAndHookMethod(themeInfoClass, "o", XC_MethodReplacement.returnConstant(false));
                findAndHookMethod(themeInfoClass, "s", XC_MethodReplacement.returnConstant(false));
            } catch (Throwable ignored) {}
        }

        // 5. 音质与歌曲特权 (ResourcePrivilege & SongPrivilege)
        Class<?> resPrivilegeClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", classLoader);
        if (resPrivilegeClass != null) {
            try {
                findAndHookMethod(resPrivilegeClass, "isVipFee", XC_MethodReplacement.returnConstant(false));
                findAndHookMethod(resPrivilegeClass, "getPlayMaxLevel", XC_MethodReplacement.returnConstant(999000));
                findAndHookMethod(resPrivilegeClass, "getDownMaxLevel", XC_MethodReplacement.returnConstant(999000));
                findAndHookMethod(resPrivilegeClass, "getFee", XC_MethodReplacement.returnConstant(0));
                findAndHookMethod(resPrivilegeClass, "getPayed", XC_MethodReplacement.returnConstant(0));
                XposedBridge.hookAllMethods(resPrivilegeClass, "isFee", XC_MethodReplacement.returnConstant(false));
                findAndHookMethod(resPrivilegeClass, "getFlag", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        // 云盘歌曲 & 运算 0x8 不等于 0
                        param.setResult(((int) param.getResult() & 0x8) == 0 ? 0 : param.getResult());
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> songPrivilegeClass = findClassIfExists("com.netease.cloudmusic.meta.virtual.SongPrivilege", classLoader);
        if (songPrivilegeClass != null) {
            try {
                findAndHookMethod(songPrivilegeClass, "canShare", XC_MethodReplacement.returnConstant(true));
                findAndHookMethod(songPrivilegeClass, "getFreeLevel", XC_MethodReplacement.returnConstant(999000));
            } catch (Throwable ignored) {}
        }

        Class<?> songPrivilegeDOClass = findClassIfExists("com.netease.cloudmusic.meta.response.SongPrivilegeDO", classLoader);
        if (songPrivilegeDOClass != null) {
            try {
                findAndHookMethod(songPrivilegeDOClass, "getFee", XC_MethodReplacement.returnConstant(0));
                findAndHookMethod(songPrivilegeDOClass, "getSt", XC_MethodReplacement.returnConstant(0));
                findAndHookMethod(songPrivilegeDOClass, "getCp", XC_MethodReplacement.returnConstant(1));
                findAndHookMethod(songPrivilegeDOClass, "getPlayMaxbr", XC_MethodReplacement.returnConstant(999000));
                findAndHookMethod(songPrivilegeDOClass, "getDownloadMaxbr", XC_MethodReplacement.returnConstant(999000));
                findAndHookMethod(songPrivilegeDOClass, "getMaxbr", XC_MethodReplacement.returnConstant(999000));
            } catch (Throwable ignored) {}
        }
    }
}
