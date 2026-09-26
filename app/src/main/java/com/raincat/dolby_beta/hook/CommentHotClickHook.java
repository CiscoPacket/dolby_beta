package com.raincat.dolby_beta.hook;

import android.content.Context;

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
 *     desc   : 评论区优先显示“最热”内容 (修正请求参数与Tab对应关系，确保后端返回真实最热评论)
 *     version: 4.0
 * </pre>
 */
public class CommentHotClickHook {
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

        // 2. 9.x+ 评论请求参数构造 (CommentRequestData) 强制指定最热排序 (sortType = 2)
        final Class<?> crdClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.music.biz.comment.meta.CommentRequestData", context.getClassLoader());
        if (crdClass != null) {
            // A. 拦截构造函数与 copy
            XposedBridge.hookAllConstructors(crdClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    Object obj = param.thisObject;
                    if (obj == null) return;
                    try {
                        int st = (int) XposedHelpers.callMethod(obj, "getSortType");
                        if (st != 3) {
                            XposedHelpers.callMethod(obj, "setSortType", 2);
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        XposedHelpers.setIntField(obj, "sortType", 2);
                    } catch (Throwable ignored) {
                    }
                }
            });

            // B. 拦截 getSortType()
            try {
                XposedHelpers.findAndHookMethod(crdClass, "getSortType", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                            return;
                        int current = (int) param.getResult();
                        if (current != 3) {
                            param.setResult(2);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            // C. 拦截 isSortByRcmd() 返回 false
            try {
                XposedHelpers.findAndHookMethod(crdClass, "isSortByRcmd", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                            return;
                        param.setResult(false);
                    }
                });
            } catch (Throwable ignored) {
            }

            // D. 拦截 isDefaultSortType() 返回 false
            try {
                XposedHelpers.findAndHookMethod(crdClass, "isDefaultSortType", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                            return;
                        param.setResult(false);
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 3. 9.x+ 评论网络请求构建器 (x71.g0) 参数拦截
        Class<?> g0Class = XposedHelpers.findClassIfExists("x71.g0", context.getClassLoader());
        if (g0Class != null && crdClass != null) {
            XC_MethodHook g0Hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key))
                        return;
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg != null && crdClass.isInstance(arg)) {
                                try {
                                    int st = (int) XposedHelpers.callMethod(arg, "getSortType");
                                    if (st != 3) {
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
                        } catch (Throwable ignored) {
                        }
                    }
                }
            };

            for (Method m : g0Class.getDeclaredMethods()) {
                String mn = m.getName();
                if ("h".equals(mn) || "i".equals(mn) || "j".equals(mn) || "a".equals(mn) || "k".equals(mn)) {
                    try {
                        XposedBridge.hookMethod(m, g0Hook);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 4. 评论排序Tab模型 (SortTypeList.parseList) 语义化将最热排在第1位
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
                                List<JSONObject> otherList = new ArrayList<>();
                                for (int i = 0; i < array.length(); i++) {
                                    JSONObject item = array.optJSONObject(i);
                                    if (item == null) continue;
                                    int st = item.optInt("sortType", -1);
                                    String name = item.optString("sortTypeName", "");
                                    if (st == 2 || name.contains("热")) {
                                        hotObj = item;
                                    } else {
                                        otherList.add(item);
                                    }
                                }
                                if (hotObj != null) {
                                    JSONArray array2 = new JSONArray();
                                    array2.put(hotObj); // 最热评论置于第1位
                                    for (JSONObject o : otherList) {
                                        array2.put(o);
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