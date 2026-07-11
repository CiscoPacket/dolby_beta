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
import static de.robv.android.xposed.XposedHelpers.findClass;

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
        if (versionCode < 138) {
            XposedBridge.hookAllMethods(findClass("com.netease.cloudmusic.meta.Profile", context.getClassLoader()), "setUserPoint", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    if ((long) XposedHelpers.callMethod(param.thisObject, "getUserId") == Long.parseLong(ExtraHelper.getExtraDate(ExtraHelper.USER_ID))) {
                        XposedHelpers.callMethod(param.thisObject, "setVipType", 100);
                        XposedHelpers.callMethod(param.thisObject, "setVipProExpireTime", System.currentTimeMillis() + 31536000000L);
                        XposedHelpers.callMethod(param.thisObject, "setExpireTime", System.currentTimeMillis() + 31536000000L);
                    }
                }
            });

            //主题
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "i", XC_MethodReplacement.returnConstant(0));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "j", XC_MethodReplacement.returnConstant("免费"));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "o", XC_MethodReplacement.returnConstant(false));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "s", XC_MethodReplacement.returnConstant(false));
        } else {
            findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.UserPrivilege", context.getClassLoader()),
                    "fromJson", JSONObject.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            JSONObject object = (JSONObject) param.args[0];
                            if (object.optInt("code") == 200 && !object.isNull("data") && !object.getJSONObject("data").isNull("userId") &&
                                    object.getJSONObject("data").optLong("userId") == Long.parseLong(ExtraHelper.getExtraDate(ExtraHelper.USER_ID))) {
                                try {
        // 直接获取并修改原生 JSONObject，抛弃脆弱的 Gson
                                    JSONObject data = object.getJSONObject("data");

        // 1. 修改 associator (黑胶)
                                    JSONObject associator = data.optJSONObject("associator");
                                    if (associator == null) {
                                        associator = new JSONObject();
                                        data.put("associator", associator);
                                    }
                                    associator.put("expireTime", System.currentTimeMillis() + 31536000000L);
                                    associator.put("vipCode", 100);

        // 2. 修改 musicPackage (音乐包)
                                    JSONObject musicPackage = data.optJSONObject("musicPackage");
                                    if (musicPackage == null) {
                                        musicPackage = new JSONObject();
                                        data.put("musicPackage", musicPackage);
                                    }
                                    musicPackage.put("expireTime", System.currentTimeMillis() + 31536000000L);
                                    musicPackage.put("vipCode", 220);

        // 3. 修改红黑胶等级
                                    data.put("redVipAnnualCount", 1);
                                    data.put("redVipLevel", 9);

        // 重新赋值回参数
                                    param.args[0] = object;
                                } catch (Exception e) {
        // 防止意外的 JSON 结构变动导致崩溃
                                    e.printStackTrace();
                                }
                            }
                        }
                    });

            //主题
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "getPoints", XC_MethodReplacement.returnConstant(0));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "getPrice", XC_MethodReplacement.returnConstant("免费"));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "isVip", XC_MethodReplacement.returnConstant(false));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "isDigitalAlbum", XC_MethodReplacement.returnConstant(false));
        }

        //音质切换
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "isVipFee", XC_MethodReplacement.returnConstant(false));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "getPlayMaxLevel", XC_MethodReplacement.returnConstant(999000));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "getDownMaxLevel", XC_MethodReplacement.returnConstant(999000));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "getFee", XC_MethodReplacement.returnConstant(0));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "getPayed", XC_MethodReplacement.returnConstant(0));
        XposedBridge.hookAllMethods(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "isFee", XC_MethodReplacement.returnConstant(false));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.SongPrivilege", context.getClassLoader()),
                "canShare", XC_MethodReplacement.returnConstant(true));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.SongPrivilege", context.getClassLoader()),
                "getFreeLevel", XC_MethodReplacement.returnConstant(999000));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "getFlag", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        //云盘歌曲&运算0x8不等于0
                        param.setResult(((int) param.getResult() & 0x8) == 0 ? 0 : param.getResult());
                    }
                });
    }
}
