package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.raincat.dolby_beta.db.CloudDao;
import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.helper.EAPIHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;


/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/04/16
 *     desc   : 网络访问hook
 *     version: 1.0
 * </pre>
 */

public class EAPIHook {
    public EAPIHook(final Context context) {
        Method resultMethod = ClassHelper.HttpResponse.getResultMethod(context);
        if (resultMethod == null) {
            DebugLogger.e("EAPIHook", "HttpResponse.getResultMethod is null, skipping EAPI hook", null);
            return;
        }
        XposedBridge.hookMethod(resultMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    //代理和黑胶都未开启
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)
                            && !SettingHelper.getInstance().isEnable(SettingHelper.proxy_master_key))
                        return;
                    //返回参数不对
                    if ((!(param.getResult() instanceof String) && !(param.getResult() instanceof JSONObject)))
                        return;
                    //返回参数为空
                    String original = param.getResult().toString();
                    if (TextUtils.isEmpty(original)) {
                        return;
                    }
                    String originalBackup = original;
                    ClassHelper.HttpResponse httpResponse = new ClassHelper.HttpResponse(param.thisObject);
                    Object eapi = httpResponse.getEapi(context);
                    Uri uri = ClassHelper.HttpUrl.getUri(context, eapi);
                    if (uri == null || uri.getPath() == null)
                        return;
                    String path = uri.getPath();
                    if (!path.contains("/eapi/") && !path.contains("/xeapi/") && !path.contains("/api/"))
                        return;

                    if (path.contains("song/enhance/player/url")) {
                        original = EAPIHelper.modifyPlayer(original);
                        if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                            original = EAPIHelper.injectUniversalPrivilege(original);
                        }
                    } else if (path.contains("song/enhance/download/url")) {
                        try {
                            JSONObject jsonObject = new JSONObject(original);
                            Object dataObj = jsonObject.opt("data");
                            if (dataObj instanceof JSONObject) {
                                JSONArray array = new JSONArray();
                                array.put(dataObj);
                                jsonObject.put("data", array);
                                original = EAPIHelper.modifyPlayer(jsonObject.toString());
                                JSONObject modifiedObj = new JSONObject(original);
                                JSONArray modArr = modifiedObj.optJSONArray("data");
                                if (modArr != null && modArr.length() > 0) {
                                    modifiedObj.put("data", modArr.getJSONObject(0));
                                    original = modifiedObj.toString();
                                }
                            } else if (dataObj instanceof JSONArray) {
                                original = EAPIHelper.modifyPlayer(jsonObject.toString());
                            }
                            if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                                original = EAPIHelper.injectUniversalPrivilege(original);
                            }
                        } catch (Throwable t) {
                            DebugLogger.e("EAPIHook", "download/url modify error: " + t.getMessage(), t);
                        }
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key)
                            && (path.contains("link/home/framework/tab") || path.contains("link/home/framework/top/tab"))) {
                        original = EAPIHelper.modifyTab(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_banner_hide_key)
                            && (path.contains("link/page/rcmd/block/resource/multi/refresh") || path.contains("banner/get"))) {
                        original = EAPIHelper.modifyFeedBanners(original);
                    } else if (path.contains("v1/playlist/manipulate/tracks")) {
                        original = EAPIHelper.modifyManipulate(ClassHelper.HttpParams.getParams(context, eapi), original);
                    } else if (path.contains("song/like")) {
                        original = EAPIHelper.modifyLike(ClassHelper.HttpParams.getParams(context, eapi), original);
                    } else if (path.contains("usertool/sound") || path.contains("sound/mobile") || path.contains("sound/twinkle") || path.contains("sound/material") || path.contains("page=audio_effect") || path.contains("audio/effect") || path.contains("sound/effect") || path.contains("sound/info")) {
                        original = EAPIHelper.modifyEffect(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("memberlogo") || path.contains("vip/logo") || path.contains("vipnewcenter"))) {
                        original = EAPIHelper.modifyMemberLogo(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("vip/info") || path.contains("vip-membership"))) {
                        original = EAPIHelper.modifyVipInfo(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("account/get") || path.contains("user/info") || path.contains("user/detail") || path.contains("user/profile"))) {
                        original = EAPIHelper.modifyAccount(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("vipauth") || path.contains("auth/query") || path.contains("soundquality"))) {
                        original = EAPIHelper.modifyVipAuth(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("playermode") || path.contains("player/mode") || path.contains("vinyl"))) {
                        original = EAPIHelper.modifyPlayerMode(original);
                    } else if (path.contains("batch")) {
                        if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                            original = EAPIHelper.modifyBatchVip(original);
                        }
                        if (original.contains("comment\\/banner\\/get")) {
                            JSONObject jsonObject = new JSONObject(original);
                            if (!jsonObject.isNull("/api/content/exposure/comment/banner/get")) {
                                JSONObject object = new JSONObject();
                                object.put("code", 200);
                                object.put("data", new JSONObject());
                                jsonObject.put("/api/content/exposure/comment/banner/get", object);
                            }
                            if (!jsonObject.isNull("/api/v1/content/exposure/comment/banner/get")) {
                                JSONObject object = jsonObject.getJSONObject("/api/v1/content/exposure/comment/banner/get");
                                JSONObject data = object.getJSONObject("data");
                                data.put("count", 0);
                                data.put("offset", 999999999);
                                data.put("records", new JSONArray());
                                data.put("message", "");
                                object.put("data", data);
                                jsonObject.put("/api/v1/content/exposure/comment/banner/get", object);
                            }
                            original = jsonObject.toString();
                        } else if (SettingHelper.getInstance().isEnable(SettingHelper.fix_comment_key) &&
                                original.contains("\\/api\\/resource\\/comment\\/musiciansaid\\/authors")) {
                            JSONObject jsonObject = new JSONObject(original);
                            JSONObject object = jsonObject.getJSONObject("/api/resource/comment/musiciansaid/authors");
                            JSONObject data = object.getJSONObject("data");
                            JSONArray team = data.getJSONArray("team");
                            for (int i = 0; i < team.length(); i++) {
                                JSONObject o = team.getJSONObject(i);
                                String s = o.optString("authorTypeText");
                                if (s != null && s.equals("作者")) {
                                    long uid = o.optLong("uid");
                                    long artistId = o.optLong("artistId");
                                    if (uid > 2147483647) {
                                        JSONObject artistJSONObject = jsonObject.getJSONObject("/api/auth/artist");
                                        JSONObject authJSONObject = artistJSONObject.getJSONObject("auth");
                                        while (uid > 2147483647)
                                            uid = uid / 10;
                                        authJSONObject.put(artistId + "", uid);
                                        artistJSONObject.put("auth", authJSONObject);
                                        jsonObject.put("/api/auth/artist", artistJSONObject);
                                        original = jsonObject.toString();
                                    }
                                }
                            }
                        }
                    } else if (path.contains("upload/cloud/info/v2")) {
                        JSONObject jsonObject = new JSONObject(original);
                        jsonObject = jsonObject.getJSONObject("privateCloud");
                        jsonObject = jsonObject.getJSONObject("simpleSong");
                        original = original.replace("\"waitTime\":60,", "\"waitTime\":5,");
                        CloudDao.getInstance(context).saveSong(Integer.parseInt(jsonObject.getString("id")), original);
                    } else if (path.contains("cloud/pub/v2")) {
                        String songid = EAPIHelper.decrypt(ClassHelper.HttpParams.getParams(context, eapi).get("params")).getString("songid");
                        EAPIHelper.uploadCloud(songid);
                        original = CloudDao.getInstance(context).getSong(Integer.parseInt(songid));
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) &&
                            (path.contains("song/detail") || path.contains("playlist/detail") || path.contains("album") || path.contains("recommend/songs") || path.contains("search") || path.contains("toplist") || path.contains("personalized") || path.contains("discovery"))) {
                        original = EAPIHelper.injectUniversalPrivilege(original);
                    }

                    boolean modified = !original.equals(originalBackup);
                    DebugLogger.logEapi(path, true, modified, null);
                    if (modified) {
                        if (param.getResult() instanceof JSONObject) {
                            try {
                                param.setResult(new JSONObject(original));
                            } catch (Throwable t) {
                                DebugLogger.e("EAPIHook", "Failed to parse modified response as JSONObject: " + path, t);
                            }
                        } else {
                            param.setResult(original);
                        }
                    }
                } catch (Throwable t) {
                    DebugLogger.e("EAPIHook", "EAPIHook error: " + t.getMessage(), t);
                }
            }
        });
    }

    private void logcat(String msg) {
        int max_str_length = 1800;
        //大于4000时
        while (msg.length() > max_str_length) {
            XposedBridge.log(msg.substring(0, max_str_length));
            msg = msg.substring(max_str_length);
        }
        //剩余部分
        XposedBridge.log(msg);
    }
}
