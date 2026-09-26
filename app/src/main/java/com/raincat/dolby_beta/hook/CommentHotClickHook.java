package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.os.Bundle;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 评论区优先显示“最热”内容 (首次进入默认最热，且允许自由切换至推荐或最新)
 *     version: 5.0
 * </pre>
 */
public class CommentHotClickHook {
    // 标记当前歌曲评论区是否处于首次加载阶段
    private static volatile boolean sInitialCommentLoad = true;

    public CommentHotClickHook(Context context) {
        // 1. 旧版评论数据模型兼容
        Class<?> commentDataClass = ClassHelper.CommentDataClass.getClazz();
        if (commentDataClass != null) {
            XposedBridge.hookAllConstructors(commentDataClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    Object object = param.thisObject;
                    if (object == null) return;
                    Field[] fields = object.getClass().getDeclaredFields();
                    for (Field field : fields) {
                        if (Modifier.isPrivate(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()) && field.getType() == int.class) {
                            field.setAccessible(true);
                            Object o = field.get(object);
                            if (o instanceof Integer && (int) o == 0)
                                field.set(object, 2);
                        }
                    }
                }
            });
        }

        // 2. 评论界面生命周期感知：新评论界面打开时重置首次加载标记
        String[] fragmentNames = new String[]{
                "com.netease.cloudmusic.music.biz.comment.fragment.CommentFragment",
                "com.netease.cloudmusic.music.biz.comment.disccomment.container.PlayerCommentFragment"
        };
        for (String fName : fragmentNames) {
            Class<?> fCls = XposedHelpers.findClassIfExists(fName, context.getClassLoader());
            if (fCls != null) {
                try {
                    XposedHelpers.findAndHookMethod(fCls, "onCreate", Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sInitialCommentLoad = true;
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }

        // 3. 用户手动点击切换Tab监听：一旦手动切换Tab，立即解除首次加载拦截，允许自由切换推荐/最新
        Class<?> tabListenerClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.music.biz.comment.fragment.CommentFragment$c", context.getClassLoader());
        if (tabListenerClass != null) {
            try {
                for (Method m : tabListenerClass.getDeclaredMethods()) {
                    if ("onTabSelected".equals(m.getName())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                sInitialCommentLoad = false;
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 4. 9.x+ 评论网络请求构建器 (DexKit 动态解析或 x71.g0) 参数拦截
        Class<?> g0Class = ClassHelper.CommentRequestBuilder.getClazz(context);
        if (g0Class == null) {
            g0Class = XposedHelpers.findClassIfExists("x71.g0", context.getClassLoader());
        }
        if (g0Class != null) {
            // A. Hook 预加载评论请求构造方法 k(MusicInfo) -> CommentRequestData
            try {
                for (Method m : g0Class.getDeclaredMethods()) {
                    if ("k".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                                    return;
                                Object crd = param.getResult();
                                if (crd != null) {
                                    try {
                                        XposedHelpers.callMethod(crd, "setSortType", 2);
                                    } catch (Throwable ignored) {
                                    }
                                }
                                sInitialCommentLoad = false;
                            }
                        });
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }

            // B. Hook 实际网络请求参数构建方法 (h, i, j)
            XC_MethodHook g0Hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    if (sInitialCommentLoad && param.args != null) {
                        for (Object arg : param.args) {
                            if (arg != null) {
                                try {
                                    int st = (int) XposedHelpers.callMethod(arg, "getSortType");
                                    if (st != 2 && st != 3) {
                                        XposedHelpers.callMethod(arg, "setSortType", 2);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    // 仅当为首次进入评论区 (sInitialCommentLoad == true) 时将参数重定向为最热 (sortType = 2)
                    if (!sInitialCommentLoad) {
                        return;
                    }
                    Object res = param.getResult();
                    if (res != null) {
                        try {
                            Object mapObj = XposedHelpers.callMethod(res, "getParamMap");
                            if (mapObj instanceof Map) {
                                Map map = (Map) mapObj;
                                if (map.containsKey("sortType")) {
                                    Object currentSt = map.get("sortType");
                                    if (currentSt != null && !"3".equals(currentSt.toString()) && !Integer.valueOf(3).equals(currentSt)) {
                                        map.put("sortType", 2);
                                    }
                                }
                            }
                            sInitialCommentLoad = false;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            };

            for (Method m : g0Class.getDeclaredMethods()) {
                String mn = m.getName();
                if ("h".equals(mn) || "i".equals(mn) || "j".equals(mn)) {
                    try {
                        XposedBridge.hookMethod(m, g0Hook);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 5. 评论排序Tab模型 (SortTypeList.parseList) 将最热置于第1位，推荐置于第2位，最新置于第3位
        Class<?> sortTypeListClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.music.biz.comment.meta.SortTypeList", context.getClassLoader());
        if (sortTypeListClass == null) {
            sortTypeListClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.module.comment2.meta.SortTypeList", context.getClassLoader());
        }
        if (sortTypeListClass != null) {
            XposedHelpers.findAndHookMethod(sortTypeListClass, "parseList", JSONArray.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    try {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof JSONArray) {
                            JSONArray array = (JSONArray) param.args[0];
                            if (array.length() >= 2) {
                                JSONObject hotObj = null;
                                JSONObject rcmdObj = null;
                                List<JSONObject> otherList = new ArrayList<>();
                                for (int i = 0; i < array.length(); i++) {
                                    JSONObject item = array.optJSONObject(i);
                                    if (item == null) continue;
                                    int st = item.optInt("sortType", -1);
                                    String name = item.optString("sortTypeName", "");
                                    if (st == 2 || name.contains("热")) {
                                        hotObj = item;
                                    } else if (st == 1 || st == 99 || name.contains("推")) {
                                        rcmdObj = item;
                                    } else {
                                        otherList.add(item);
                                    }
                                }
                                if (hotObj != null) {
                                    JSONArray array2 = new JSONArray();
                                    array2.put(hotObj); // 1. 最热排第 1 位
                                    if (rcmdObj != null) {
                                        array2.put(rcmdObj); // 2. 推荐排第 2 位
                                    }
                                    for (JSONObject o : otherList) {
                                        array2.put(o); // 3. 最新及其它排第 3 位及以后
                                    }
                                    param.args[0] = array2;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }
}