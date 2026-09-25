package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.content.SharedPreferences;


import com.raincat.dolby_beta.helper.ExtraHelper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;




import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;


public class ListentogetherHook {

    private final SharedPreferences listening;

    public ListentogetherHook(Context context,int versionCode) {
        //旧版写法
        Class<?> f2 = findClassIfExists("com.netease.cloudmusic.module.listentogether.f2", context.getClassLoader());
        if (f2 != null) {
            findAndHookMethod(f2, "v", XC_MethodReplacement.returnConstant(true));
        } else if (versionCode > 8007075) {
            Class<?> x = findClassIfExists("com.netease.cloudmusic.module.listentogether.x", context.getClassLoader());
            if (x != null) findAndHookMethod(x, "v", XC_MethodReplacement.returnConstant(true));
        } else if (versionCode > 8007070) {
            Class<?> y = findClassIfExists("com.netease.cloudmusic.module.listentogether.y", context.getClassLoader());
            if (y != null) findAndHookMethod(y, "u", XC_MethodReplacement.returnConstant(true));
        } else if (versionCode > 8007055) {
            Class<?> x = findClassIfExists("com.netease.cloudmusic.module.listentogether.x", context.getClassLoader());
            if (x != null) findAndHookMethod(x, "u", XC_MethodReplacement.returnConstant(true));
        } else if (versionCode > 8007026) {
            Class<?> w = findClassIfExists("com.netease.cloudmusic.module.listentogether.w", context.getClassLoader());
            if (w != null) findAndHookMethod(w, "o", XC_MethodReplacement.returnConstant(true));
        } else if (versionCode > 8007004) {
            Class<?> w = findClassIfExists("com.netease.cloudmusic.module.listentogether.w", context.getClassLoader());
            if (w != null) findAndHookMethod(w, "n", XC_MethodReplacement.returnConstant(true));
        } else if (versionCode > 8006076) {
            Class<?> u = findClassIfExists("com.netease.cloudmusic.module.listentogether.u", context.getClassLoader());
            if (u != null) findAndHookMethod(u, "m", XC_MethodReplacement.returnConstant(true));
        } else if (versionCode > 8006045) {
            Class<?> r = findClassIfExists("com.netease.cloudmusic.module.listentogether.r", context.getClassLoader());
            if (r != null) findAndHookMethod(r, "l1", XC_MethodReplacement.returnConstant(true));
        } else if (versionCode > 8006040) {
            Class<?> p = findClassIfExists("com.netease.cloudmusic.module.listentogether.p", context.getClassLoader());
            if (p != null) findAndHookMethod(p, "h1", XC_MethodReplacement.returnConstant(true));
        } else if (versionCode > 8006019) {
            Class<?> x = findClassIfExists("com.netease.cloudmusic.module.listentogether.x", context.getClassLoader());
            if (x != null) findAndHookMethod(x, "n1", XC_MethodReplacement.returnConstant(true));
        } else if (versionCode >= 8006000){
            Class<?> x = findClassIfExists("com.netease.cloudmusic.module.listentogether.x", context.getClassLoader());
            if (x != null) findAndHookMethod(x, "m1", XC_MethodReplacement.returnConstant(true));
        }
        //新版写法
        listening = context.getSharedPreferences("LISTEN_TOGETHER", Context.MODE_MULTI_PROCESS);
        XposedBridge.hookAllMethods(findClass("com.netease.cloudmusic.activity.PlayerActivity", context.getClassLoader()), "onCreate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                findAndHookMethod(findClass("com.netease.cloudmusic.module.listentogether.meta.RoomInfo", context.getClassLoader()),
                        "getUnlockedIdentity", XC_MethodReplacement.returnConstant(true));
                listening.edit().putBoolean("match_unlock_status" + ExtraHelper.USER_ID, true).apply();
            }
        });

    }
}
