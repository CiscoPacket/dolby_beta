package com.raincat.dolby_beta.helper;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.raincat.dolby_beta.model.UserInfoBean;
import com.raincat.dolby_beta.net.Http;

import java.util.HashMap;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/04/15
 *     desc   : 用户状态帮助类
 *     version: 1.0
 * </pre>
 */

public class UserHelper {
    /**
     * 通过cookie获取用户信息
     */
    public static void getUserInfo() {
        try {
            String cookie = ExtraHelper.getExtraDate(ExtraHelper.COOKIE);
            if (TextUtils.isEmpty(cookie) || "-1".equals(cookie)) {
                return;
            }
            HashMap<String, Object> headers = new HashMap<>();
            headers.put("cookie", cookie);
            String userInfo = new Http("GET", "https://music.163.com/api/nuser/account/get", headers, (String) null).getResult();
            if (TextUtils.isEmpty(userInfo)) {
                return;
            }
            Gson gson = new Gson();
            UserInfoBean userInfoBean = gson.fromJson(userInfo, UserInfoBean.class);
            if (userInfoBean != null && userInfoBean.getProfile() != null) {
                long uid = userInfoBean.getProfile().getUserId();
                if (uid > 0) {
                    ExtraHelper.setExtraDate(ExtraHelper.USER_ID, uid);
                    DebugLogger.d("UserHelper", "getUserInfo success: uid=" + uid);
                }
            }
        } catch (Throwable t) {
            DebugLogger.e("UserHelper", "getUserInfo error: " + t.getMessage(), t);
        }
    }
}
