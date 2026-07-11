package com.raincat.dolby_beta.hook;

import android.content.Context;

import com.raincat.dolby_beta.helper.ExtraHelper;

import org.json.JSONObject;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

/**
 * <pre>
 * author : RainCat (Modified by Assistant)
 * time   : 2019/10/26
 * desc   : 黑胶会员、无损音质解锁（规避脆弱的 Gson，采用 100% 兼容的原生 JSONObject 直改）
 * version: 1.1
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

            // 主题破解
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "i", XC_MethodReplacement.returnConstant(0));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "j", XC_MethodReplacement.returnConstant("免费"));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "o", XC_MethodReplacement.returnConstant(false));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "s", XC_MethodReplacement.returnConstant(false));
        } else {
            // 核心修复：Hook 用户特权接口。不再使用 GSON 反序列化来回打包，而是直接利用 JSONObject 原生修改，100% 兼容新版本增删字段。
            findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.UserPrivilege", context.getClassLoader()),
                    "fromJson", JSONObject.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            JSONObject object = (JSONObject) param.args[0];
                            if (object != null && object.optInt("code") == 200 && !object.isNull("data") 
                                    && !object.getJSONObject("data").isNull("userId") 
                                    && object.getJSONObject("data").optLong("userId") == Long.parseLong(ExtraHelper.getExtraDate(ExtraHelper.USER_ID))) {
                                try {
                                    JSONObject data = object.getJSONObject("data");

                                    // 1. 修改黑胶 VIP 状态与过期时间
                                    JSONObject associator = data.optJSONObject("associator");
                                    if (associator == null) {
                                        associator = new JSONObject();
                                        data.put("associator", associator);
                                    }
                                    associator.put("expireTime", System.currentTimeMillis() + 31536000000L);
                                    associator.put("vipCode", 100);

                                    // 2. 修改音乐包状态
                                    JSONObject musicPackage = data.optJSONObject("musicPackage");
                                    if (musicPackage == null) {
                                        musicPackage = new JSONObject();
                                        data.put("musicPackage", musicPackage);
                                    }
                                    musicPackage.put("expireTime", System.currentTimeMillis() + 31536000000L);
                                    musicPackage.put("vipCode", 220);

                                    // 3. 修改年费标识与红黑胶等级
                                    data.put("redVipAnnualCount", 1);
                                    data.put("redVipLevel", 9);

                                    // 写回参数执行，完美避开 Class 结构解析异常
                                    param.args[0] = object;
                                } catch (Exception e) {
                                    XposedBridge.log("DolbyBeta: 篡改 UserPrivilege 会员信息失败: " + e.getMessage());
                                }
                            }
                        }
                    });

            // 主题破解
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "getPoints", XC_MethodReplacement.returnConstant(0));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "getPrice", XC_MethodReplacement.returnConstant("免费"));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "isVip", XC_MethodReplacement.returnConstant(false));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "isDigitalAlbum", XC_MethodReplacement.returnConstant(false));
        }

        // 音质解除锁定、极高音质等本地强制全开
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
                        // 云盘歌曲 & 运算 0x8 不等于 0
                        param.setResult(((int) param.getResult() & 0x8) == 0 ? 0 : param.getResult());
                    }
                });
    }
}
