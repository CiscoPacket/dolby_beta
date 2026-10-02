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
import java.util.Iterator;

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
    private static final java.util.concurrent.atomic.AtomicInteger SIDEBAR_SAMPLE_LOG =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger EFFECT_SAMPLE_LOG =
            new java.util.concurrent.atomic.AtomicInteger(0);
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
                    // 检查是否有任何相关功能开启
                    boolean needProcess = SettingHelper.getInstance().isEnable(SettingHelper.black_key)
                            || SettingHelper.getInstance().isEnable(SettingHelper.proxy_master_key)
                            || SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key)
                            || SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key)
                            || SettingHelper.getInstance().isSidebarHideEnable();
                    if (!needProcess)
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
                        if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                            original = EAPIHelper.modifyPlayer(original);
                            original = EAPIHelper.injectUniversalPrivilege(original);
                        }
                    } else if (path.contains("song/enhance/download/url")) {
                        if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
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
                                original = EAPIHelper.injectUniversalPrivilege(original);
                            } catch (Throwable t) {
                                DebugLogger.e("EAPIHook", "download/url modify error: " + t.getMessage(), t);
                            }
                        }
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_tab_hide_key)
                            && (path.contains("link/home/framework/tab") || path.contains("link/home/framework/top/tab"))) {
                        original = EAPIHelper.modifyTab(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)
                            && (path.contains("link/position/show") || path.contains("link/scene/show") || path.contains("delivery/deliver"))) {
                        // RN 页面(音效/装扮/会员等)数据经 link-position / delivery 通道下发,
                        // 其中的 vipType 类标记字段在此清除 (纯正则, 不做 JSON 解析)
                        String before = original;
                        original = EAPIHelper.clearVipMarkers(original);
                        if (before != null && !before.equals(original)) {
                            DebugLogger.i("EAPIHook", "cleared vip markers in [" + path + "]");
                        }
                        // 内容命中音效相关时采样一次, 便于核对真实字段
                        if (original != null && (original.contains("aeVip") || original.contains("音效") || original.contains("effect"))) {
                            if (EFFECT_SAMPLE_LOG.getAndIncrement() < 2) {
                                DebugLogger.i("EAPIHook", "effect-like resp[" + path + "]: "
                                        + (original.length() > 1500 ? original.substring(0, 1500) : original));
                            }
                        }
                        if (SettingHelper.getInstance().isSidebarHideEnable() && path.contains("link/position/show")) {
                            if (SIDEBAR_SAMPLE_LOG.getAndIncrement() < 3) {
                                DebugLogger.d("EAPIHook", "sidebar resp[" + path + "]: "
                                        + (original != null && original.length() > 500 ? original.substring(0, 500) : original));
                            }
                            original = EAPIHelper.modifySidebar(original, path);
                        }
                    } else if (SettingHelper.getInstance().isSidebarHideEnable()
                            && (path.contains("side-bar") || path.contains("yunbei/account"))) {
                        original = EAPIHelper.modifySidebar(original, path);
                    } else if (path.contains("v1/playlist/manipulate/tracks")) {
                        original = EAPIHelper.modifyManipulate(ClassHelper.HttpParams.getParams(context, eapi), original);
                    } else if (path.contains("song/like")) {
                        original = EAPIHelper.modifyLike(ClassHelper.HttpParams.getParams(context, eapi), original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("usertool/sound") || path.contains("sound/mobile") || path.contains("sound/twinkle") || path.contains("sound/material") || path.contains("page=audio_effect") || path.contains("audio/effect") || path.contains("sound/effect") || path.contains("sound/info") || path.contains("/effect") || path.contains("effect/list"))) {
                        // 音效列表走本地缓存, 清缓存重新拉取时由此分支翻转 vipType/fee 等标记
                        if (EFFECT_SAMPLE_LOG.getAndIncrement() < 2) {
                            DebugLogger.d("EAPIHook", "effect resp[" + path + "]: "
                                    + (original != null && original.length() > 500 ? original.substring(0, 500) : original));
                        }
                        original = EAPIHelper.modifyEffect(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)
                            && (path.contains("vipnewcenter/app/resource/common/list") || path.contains("vipnewcenter/app/vipplus"))) {
                        // 音效/播放器样式数据源 (vipnewcenter 资源列表): 清除 aeVipType/animVipType 等标记
                        if (EFFECT_SAMPLE_LOG.getAndIncrement() < 2) {
                            DebugLogger.d("EAPIHook", "vipnewcenter resource resp[" + path + "]: "
                                    + (original != null && original.length() > 600 ? original.substring(0, 600) : original));
                        }
                        original = EAPIHelper.modifyEffect(original);
                        // 该列表同时承载侧边栏动态条目 (title/subTitle), 精简侧边栏开启时在数据源头移除,
                        // 让 RN 侧根本不渲染 (无残留空洞/不可点击残留)
                        if (SettingHelper.getInstance().isSidebarHideEnable()) {
                            original = EAPIHelper.modifySidebar(original, path);
                        }
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && path.contains("vipnewcenter/app/cashier/privilege/show")) {
                        // 收银台特权清单: 注入已拥有状态, 防止点击功能后弹购买页
                        original = EAPIHelper.modifyVipAuth(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("memberlogo") || path.contains("vip/logo") || path.contains("vipnewcenter"))) {
                        original = EAPIHelper.modifyMemberLogo(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("vip/info") || path.contains("vip-membership"))) {
                        original = EAPIHelper.modifyVipInfo(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("account/get") || path.contains("user/info") || path.contains("user/detail") || path.contains("user/profile"))) {
                        original = EAPIHelper.modifyAccount(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("vipauth") || path.contains("auth/query") || path.contains("soundquality") || path.contains("vip-thoth") || path.contains("music-vip-configuration"))) {
                        // vip-thoth 为新版 VIP 决策服务 (cashier/pre/check 等), 漏掉会导致部分 SVIP 功能直接跳收银台
                        // music-vip-configuration 为功能 VIP 配置查询 (音效/播放器样式页加载后立即请求)
                        if (path.contains("pre/check") || path.contains("config/query") || path.contains("auth/set")) {
                            DebugLogger.d("EAPIHook", "vip-decision resp[" + path + "]: "
                                    + (original != null && original.length() > 600 ? original.substring(0, 600) : original));
                        }
                        original = EAPIHelper.modifyVipAuth(original);
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.black_key) && (path.contains("playermode") || path.contains("player/mode") || path.contains("vinyl"))) {
                        original = EAPIHelper.modifyPlayerMode(original);
                    } else if (path.contains("batch")) {
                        if (SettingHelper.getInstance().isEnable(SettingHelper.black_key)) {
                            original = EAPIHelper.modifyBatchVip(original);
                        }
                        // 窗口短路: 非强制窗口期不做全量 JSON 解析 (评论响应可达数百 KB)
                        if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key)
                                && CommentHotClickHook.isInForceHotWindow()
                                && (original.contains("resource/comments") || original.contains("resource\\/comments")
                                    || original.contains("commentInfo/list") || original.contains("commentInfo\\/list"))) {
                            try {
                                JSONObject batchObj = new JSONObject(original);
                                Iterator<String> it = batchObj.keys();
                                boolean batchModified = false;
                                while (it.hasNext()) {
                                    String k = it.next();
                                    if (k.contains("resource/comments") || k.contains("resource\\/comments")
                                            || k.contains("commentInfo/list") || k.contains("commentInfo\\/list")) {
                                        Object subVal = batchObj.opt(k);
                                        if (subVal instanceof JSONObject) {
                                            String subStr = EAPIHelper.modifyCommentHot(subVal.toString());
                                            batchObj.put(k, new JSONObject(subStr));
                                            batchModified = true;
                                        }
                                    }
                                }
                                if (batchModified) {
                                    original = batchObj.toString();
                                }
                            } catch (Throwable t) {
                                DebugLogger.e("EAPIHook", "batch comment modify error: " + t.getMessage(), t);
                            }
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
                    } else if (SettingHelper.getInstance().isEnable(SettingHelper.beauty_comment_hot_key)
                            && (path.contains("resource/comments") || path.contains("commentInfo/list") || path.contains("comment/floor/get") || path.contains("comments/v2") || path.contains("comment/v2"))) {
                        original = EAPIHelper.modifyCommentHot(original);
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
