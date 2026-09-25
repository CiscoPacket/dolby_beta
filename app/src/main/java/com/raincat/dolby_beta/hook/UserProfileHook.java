package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.UserHelper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/04/17
 *     desc   : 获取账号信息
 *     version: 1.0
 * </pre>
 */

public class UserProfileHook {
    public UserProfileHook(Context context) {
        //获取用户id
        Class<?> userProfileClass = findClassIfExists("com.netease.cloudmusic.meta.Profile", context.getClassLoader());
        if (userProfileClass != null) {
            findAndHookMethod(userProfileClass, "setNickname", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    try {
                        if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                        String nickName = String.valueOf(param.args[0]);
                        if (TextUtils.isEmpty(nickName) || "未登录".equals(nickName))
                            return;
                        Object isMeObj = XposedHelpers.callMethod(param.thisObject, "isMe");
                        if (Boolean.TRUE.equals(isMeObj) && "-1".equals(ExtraHelper.getExtraDate(ExtraHelper.USER_ID))) {
                            Object uid = XposedHelpers.callMethod(param.thisObject, "getUserId");
                            if (uid != null) {
                                ExtraHelper.setExtraDate(ExtraHelper.USER_ID, uid);
                                DebugLogger.d("UserProfileHook", "Detected user id: " + uid);
                            }
                        }
                    } catch (Throwable t) {
                        DebugLogger.e("UserProfileHook", "Profile.setNickname error: " + t.getMessage(), t);
                    }
                }
            });
        }

        Class<?> mainActivityClass = findClassIfExists("com.netease.cloudmusic.activity.MainActivity", context.getClassLoader());
        if (mainActivityClass != null) {
            findAndHookMethod(mainActivityClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    new Thread(() -> {
                        try {
                            if ("-1".equals(ExtraHelper.getExtraDate(ExtraHelper.COOKIE))) {
                                String cookie = ClassHelper.Cookie.getCookie(context);
                                if (!TextUtils.isEmpty(cookie)) {
                                    ExtraHelper.setExtraDate(ExtraHelper.COOKIE, cookie);
                                }
                            }
                            if ("-1".equals(ExtraHelper.getExtraDate(ExtraHelper.USER_ID))) {
                                UserHelper.getUserInfo();
                            }
                        } catch (Throwable t) {
                            DebugLogger.e("UserProfileHook", "onResume background fetch error: " + t.getMessage(), t);
                        }
                    }).start();
                }
            });
        }

        //登录页被创建的时候说明用户数据需要被刷新
        Class<?> loginActivityClass = findClassIfExists("com.netease.cloudmusic.activity.LoginActivity", context.getClassLoader());
        if (loginActivityClass != null) {
            findAndHookMethod(loginActivityClass, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    try {
                        ExtraHelper.cleanUserData();
                    } catch (Throwable t) {
                        DebugLogger.e("UserProfileHook", "LoginActivity onCreate error: " + t.getMessage(), t);
                    }
                }
            });
        }

        //获取我喜欢的歌单id
        Class<?> playListClass = findClassIfExists("com.netease.cloudmusic.meta.PlayList", context.getClassLoader());
        if (playListClass != null) {
            findAndHookMethod(playListClass, "setSpecialType", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    try {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                            int type = (Integer) param.args[0];
                            if (type == 5 && "-1".equals(ExtraHelper.getExtraDate(ExtraHelper.LOVE_PLAY_LIST))) {
                                Object id = XposedHelpers.callMethod(param.thisObject, "getId");
                                if (id != null) {
                                    ExtraHelper.setExtraDate(ExtraHelper.LOVE_PLAY_LIST, id);
                                    DebugLogger.d("UserProfileHook", "Detected love playlist id: " + id);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        DebugLogger.e("UserProfileHook", "PlayList.setSpecialType error: " + t.getMessage(), t);
                    }
                }
            });
        }
    }
}
