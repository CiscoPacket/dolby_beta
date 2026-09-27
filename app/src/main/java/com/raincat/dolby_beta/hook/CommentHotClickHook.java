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
 *     version: 6.0
 * </pre>
 */
public class CommentHotClickHook {
    private static volatile String sLastSongThreadId = null;
    private static volatile boolean sInitialLoadedForCurrentSong = false;

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

        // 2. 评论界面生命周期感知：新评论界面打开或销毁时重置状态
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
                            sLastSongThreadId = null;
                            sInitialLoadedForCurrentSong = false;
                        }
                    });
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.findAndHookMethod(fCls, "onDestroy", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sLastSongThreadId = null;
                            sInitialLoadedForCurrentSong = false;
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }

        // 3. 9.x+ 评论网络请求构建器 (DexKit 动态解析或 x71.g0) 参数拦截
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
                                    ensureHotOnInitial(crd);
                                }
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
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        ensureHotOnInitial(param.args[0]);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    if (!sInitialLoadedForCurrentSong) return;
                    Object res = param.getResult();
                    if (res != null) {
                        try {
                            Object mapObj = XposedHelpers.callMethod(res, "getParamMap");
                            if (mapObj instanceof Map) {
                                Map map = (Map) mapObj;
                                Object currentSt = map.get("sortType");
                                if (currentSt != null && ("1".equals(currentSt.toString()) || Integer.valueOf(1).equals(currentSt) || "99".equals(currentSt.toString()) || Integer.valueOf(99).equals(currentSt))) {
                                    // 仅当首次加载时确保最热
                                    if (sInitialLoadedForCurrentSong) {
                                        // initial load done, let user requests pass
                                    }
                                }
                            }
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

        // 4. 9.6+ 核心评论数据模型 CommentRequestData (DexKit 动态匹配)
        Class<?> crdClass = ClassHelper.CommentRequestData.getClazz(context);
        if (crdClass != null) {
            XposedBridge.hookAllConstructors(crdClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    // 在构造方法中检查参数是否为首次请求并修正为最热 (sortType=2)
                    if (param.args != null && param.args.length > 4) {
                        if (param.args[4] instanceof Integer) {
                            String tid = param.args[0] != null ? param.args[0].toString() : null;
                            boolean isNewSong = (tid != null && !tid.equals(sLastSongThreadId)) || sLastSongThreadId == null;
                            if (isNewSong) {
                                if (tid != null) sLastSongThreadId = tid;
                                sInitialLoadedForCurrentSong = false;
                            }
                            if (!sInitialLoadedForCurrentSong) {
                                int st = (Integer) param.args[4];
                                if (st == 1 || st == 99 || st == 0) {
                                    param.args[4] = 2;
                                    sInitialLoadedForCurrentSong = true;
                                }
                            }
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    Object crd = param.thisObject;
                    if (crd != null) {
                        ensureHotOnInitial(crd);
                    }
                }
            });
        }

        // 5. 9.6+ 评论网络请求转换核心工具类 CommentRequestUtil (DexKit 动态匹配)
        Class<?> cruClass = ClassHelper.CommentRequestUtil.getClazz(context);
        if (cruClass != null) {
            for (Method m : cruClass.getDeclaredMethods()) {
                if ("k".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                                    return;
                                Object crd = param.args[0];
                                if (crd != null) {
                                    ensureHotOnInitial(crd);
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                                    return;
                                Object jsonAndTraceId = param.getResult();
                                if (jsonAndTraceId != null) {
                                    try {
                                        JSONObject json = (JSONObject) XposedHelpers.getObjectField(jsonAndTraceId, "json");
                                        if (json != null) {
                                            String tid = json.optString("threadId", "");
                                            boolean isNewSong = (!tid.isEmpty() && !tid.equals(sLastSongThreadId)) || sLastSongThreadId == null;
                                            if (isNewSong) {
                                                if (!tid.isEmpty()) sLastSongThreadId = tid;
                                                sInitialLoadedForCurrentSong = false;
                                            }
                                            if (!sInitialLoadedForCurrentSong) {
                                                int st = json.optInt("sortType", 0);
                                                if (st == 1 || st == 99 || st == 0) {
                                                    json.put("sortType", 2);
                                                    sInitialLoadedForCurrentSong = true;
                                                }
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                    break;
                }
            }
        }

        // 6. 评论排序Tab模型 (SortTypeList.parseList) 将最热置于第1位，推荐置于第2位，最新置于第3位
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

    private static void ensureHotOnInitial(Object crd) {
        if (crd == null) return;
        try {
            String threadId = null;
            try {
                Object tid = XposedHelpers.callMethod(crd, "getThreadId");
                if (tid != null) threadId = tid.toString();
            } catch (Throwable ignored) {
            }
            if (threadId == null) {
                try {
                    Object rid = XposedHelpers.callMethod(crd, "getResId");
                    if (rid != null) threadId = rid.toString();
                } catch (Throwable ignored) {
                }
            }

            boolean isNewSong = (threadId != null && !threadId.equals(sLastSongThreadId)) || sLastSongThreadId == null;
            if (isNewSong) {
                if (threadId != null) {
                    sLastSongThreadId = threadId;
                }
                sInitialLoadedForCurrentSong = false;
            }

            if (!sInitialLoadedForCurrentSong) {
                int st = (int) XposedHelpers.callMethod(crd, "getSortType");
                if (st == 1 || st == 99 || st == 0) {
                    XposedHelpers.callMethod(crd, "setSortType", 2);
                    sInitialLoadedForCurrentSong = true;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
