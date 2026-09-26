package com.raincat.dolby_beta.hook;

import android.content.Context;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 评论区优先显示“最热”内容 (精准最热置顶，修正倒序Bug)
 *     version: 3.0
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

        // 2. 9.x+ 评论请求参数构造 (CommentRequestData) 默认指定最热排序 (sortType = 2)
        Class<?> crdClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.music.biz.comment.meta.CommentRequestData", context.getClassLoader());
        if (crdClass != null) {
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
                        // 默认推荐为99或0，将其重定向至2(最热)
                        if (st == 99 || st == 0) {
                            XposedHelpers.callMethod(obj, "setSortType", 2);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        // 3. 评论排序Tab模型 (SortTypeList.parseList) 语义化将最热排在第1位，绝非盲目倒序
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