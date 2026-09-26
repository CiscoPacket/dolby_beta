package com.raincat.dolby_beta.helper;

import com.google.gson.Gson;
import com.ndktools.javamd5.core.MD5;
import com.raincat.dolby_beta.model.CloudHeader;
import com.raincat.dolby_beta.net.Http;
import com.raincat.dolby_beta.utils.NeteaseAES2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedBridge;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/04/16
 *     desc   : 接口处理
 *     version: 1.0
 * </pre>
 */

public class EAPIHelper {
    private static final Gson gson = new Gson();

    /**
     * 解除下载加密
     */
    public static String modifyPlayer(String original) {
        if (original == null || original.isEmpty()) return original;
        try {
            JSONObject root = new JSONObject(original);
            root.put("code", 200);
            JSONArray data = root.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    // flag与8非0为云盘歌曲
                    int flag = item.optInt("flag", 0);
                    if ((flag & 0x8) == 0) {
                        item.put("fee", 0);
                        item.put("flag", 0);
                        item.put("payed", 1);
                        item.remove("freeTrialInfo");
                        item.remove("freeTrialPrivilege");
                        item.remove("freeTimeTrialPrivilege");
                        item.remove("freeTrialType");
                        item.remove("cannotListenReason");
                        item.remove("playReason");
                        item.remove("trialMode");
                        item.put("canExtend", true);
                        String url = item.optString("url", null);
                        if (url != null && !url.isEmpty()) {
                            item.put("code", 200);
                            if (url.contains("126.net") && url.contains("?")) {
                                String query = url.substring(url.indexOf("?") + 1).toLowerCase();
                                if (!query.contains("wssecret") && !query.contains("token") && !query.contains("sign") && !query.contains("auth")) {
                                    item.put("url", url.substring(0, url.indexOf("?")));
                                }
                            }
                        }
                    }
                }
            }
            return root.toString();
        } catch (Throwable t) {
            DebugLogger.e("EAPIHelper", "modifyPlayer error: " + t.getMessage(), t);
            return original;
        }
    }

    /**
     * 收藏
     */
    public static String modifyManipulate(HashMap<String, String> data, String original) throws Exception {
        if (original.contains("\"code\":200") && original.contains("\"offlineIds\":[]") && !original.contains("\"trackIds\":\"[]\""))
            return original;

        String cookie = ExtraHelper.getExtraDate(ExtraHelper.COOKIE);
        if (cookie.equals("-1")) {
            return original;
        }
        HashMap<String, Object> header = new HashMap<>();
        header.put("Cookie", cookie);

        JSONObject paramJSON = decrypt(data.get("params"));
        HashMap<String, Object> param = new HashMap<>();
        String trackIds = paramJSON.getString("trackIds");
        param.put("op", paramJSON.getString("op"));
        param.put("pid", paramJSON.getString("pid"));

        String newTrackIds = trackIds.replace("]", "") + trackIds.replace("[", ",");
        param.put("trackIds", newTrackIds);
        String result = new Http("POST", "http://music.163.com/api/playlist/manipulate/tracks", param, header).getResult();
        if (result.contains("502") || result.contains("200"))
            result = "{\"trackIds\":" + trackIds + ",\"code\":200,\"privateCloudStored\":false}";
        return result;
    }

    /**
     * 喜欢
     */
    public static String modifyLike(HashMap<String, String> data, String original) throws Exception {
        String cookie = ExtraHelper.getExtraDate(ExtraHelper.COOKIE);
        String pid = ExtraHelper.getExtraDate(ExtraHelper.LOVE_PLAY_LIST);
        if (original.contains("\"code\":200") || cookie.equals("-1") || pid.equals("-1"))
            return original;

        HashMap<String, Object> header = new HashMap<>();
        header.put("Cookie", cookie);

        //获取我喜欢的音乐列表
        JSONObject paramJSON = decrypt(data.get("params"));
        String trackId = paramJSON.getString("trackId");

        HashMap<String, Object> param = new HashMap<>();
        param.put("trackIds", "[\"" + trackId + "\",\"" + trackId + "\"]");
        param.put("op", "add");
        param.put("pid", pid);

        String result = new Http("POST", "http://music.163.com/api/playlist/manipulate/tracks", param, header).getResult();
        if (result.contains("502") || result.contains("200"))
            result = "{\"playlistId\":" + pid + ",\"code\":200}";
        return result;
    }

    public static void uploadCloud(String data) {
        String paramString = "{\"songid\":\"" + data + "\",\"e_r\":true,\"header\":\"%s\"}";
        CloudHeader cloudHeader = new CloudHeader();
        cloudHeader.setOs("pc");
        cloudHeader.setAppver("2.7.1.198242");
//        cloudHeader.setDeviceId(ExtraDao.getInstance(context).getExtra("deviceId"));
        Random random = new Random();
        cloudHeader.setRequestId(String.valueOf(random.nextInt() * (1000000 - 10000 + 1) + 10000));
        cloudHeader.setClientSign("60:45:CB:9A:C3:5E@@@WD-WCC2E6LCUS2U@@@@@@39cda0b9-b0aa-4e38-a7d5-e5e9b2f430176d0b275515819c796da324b0129703e2");
        cloudHeader.setOsver("Microsoft-Windows-10-Professional-build-18363-64bit");
        cloudHeader.setBatchmethod("POST");
        cloudHeader.setMUSIC_U(ExtraHelper.getExtraDate(ExtraHelper.COOKIE).replace("MUSIC_U=", ""));

        Gson gson = new Gson();
        String headerParam = gson.toJson(cloudHeader);
        headerParam = headerParam.replace("\"", "\\\"");
        paramString = String.format(paramString, headerParam);
        MD5 md5 = new MD5();
        String md5String = md5.getMD5ofStr("nobody" + "/api/cloud/pub/v2" + "use" + paramString + "md5forencrypt");
        paramString = "/api/cloud/pub/v2-36cd479b6b5-" + paramString + "-36cd479b6b5-" + md5String.toLowerCase();

        HashMap<String, Object> header = new HashMap<>();
        header.put("Host", "interface3.music.163.com");
        header.put("Connection", "keep-alive");
        header.put("Accept", "*/*");
        header.put("Content-Type", "application/x-www-form-urlencoded");
        header.put("Origin", "orpheus://orpheus");
        header.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/35.0.1916.157 NeteaseMusicDesktop/2.7.1.198242 Safari/537.36");
        header.put("Accept-Encoding", "gzip,deflate");
        header.put("Accept-Language", "en-us,en;q=0.8");

        paramString = NeteaseAES2.Encrypt(paramString);
        HashMap<String, Object> param = new HashMap<>();
        param.put("params", paramString);

        new Http("POST", "http://interface3.music.163.com/eapi/cloud/pub/v2", param, header).getResult();
    }

    /**
     * 音效 (依据 UNM unblockSoundEffects 机制解除锁定)
     */
    public static String modifyEffect(String originalContent) {
        try {
            JSONObject jsonObject = new JSONObject(originalContent);
            if (jsonObject.optInt("code", 0) == 200) {
                Object dataObj = jsonObject.opt("data");
                if (dataObj instanceof JSONArray) {
                    JSONArray dataArr = (JSONArray) dataObj;
                    for (int i = 0; i < dataArr.length(); i++) {
                        JSONObject item = dataArr.optJSONObject(i);
                        if (item != null) {
                            unblockSingleEffect(item);
                        }
                    }
                } else if (dataObj instanceof JSONObject) {
                    unblockSingleEffect((JSONObject) dataObj);
                }
            }
            originalContent = jsonObject.toString();
        } catch (Throwable ignored) {}

        originalContent = Pattern.compile("\"limitTime\":\\d+").matcher(originalContent).replaceAll("\"limitTime\":0");
        originalContent = Pattern.compile("\"vipType\":\\d+").matcher(originalContent).replaceAll("\"vipType\":0");
        originalContent = Pattern.compile("\"fee\":\\d+").matcher(originalContent).replaceAll("\"fee\":0");
        originalContent = Pattern.compile("\"payed\":\\d+").matcher(originalContent).replaceAll("\"payed\":1");
        originalContent = Pattern.compile("\"free\":false").matcher(originalContent).replaceAll("\"free\":true");
        return originalContent;
    }

    private static void unblockSingleEffect(JSONObject item) {
        try {
            if (item.has("type") && item.optInt("type", 0) != 0) {
                item.put("type", 1);
            }
            item.put("fee", 0);
            item.put("payed", 1);
            item.put("free", true);
            item.put("vipType", 0);
            item.put("limitTime", 0);
            item.put("canUse", true);
            item.put("canNotUseReasonCode", 200);
        } catch (Throwable ignored) {}
    }

    public static JSONObject decrypt(String params) throws Exception {
        params = NeteaseAES2.Decrypt(params);
        if (params != null && params.length() != 0) {
            params = params.substring(params.indexOf("{"), params.lastIndexOf("}") + 1);
            JSONObject jsonObject = new JSONObject(params);
            if (jsonObject.isNull("params"))
                return new JSONObject(params);
            else
                return decrypt(jsonObject.getString("params"));
        } else
            return new JSONObject();
    }

    /**
     * VIP 会员信息 (对标 UNM ENABLE_LOCAL_VIP=svip 规范)
     */
    public static String modifyVipInfo(String original) {
        try {
            JSONObject jsonObject = new JSONObject(original);
            JSONObject data = jsonObject.optJSONObject("data");
            if (data == null) {
                data = jsonObject;
            }
            long now = data.optLong("now", System.currentTimeMillis());
            long expireTime = now + 31622400000L;
            data.put("redVipLevel", 9);
            data.put("redVipAnnualCount", 1);
            data.put("isVip", true);
            data.put("isRedPlus", true);
            data.put("userType", 1);
            data.put("vipType", 100);

            JSONObject associator = data.optJSONObject("associator");
            if (associator == null) associator = new JSONObject();
            associator.put("vipCode", 100);
            associator.put("vipLevel", 9);
            associator.put("expireTime", expireTime);
            associator.put("rights", true);
            associator.put("isSign", true);
            associator.put("isSignIap", false);
            associator.put("isSignDeduct", false);
            associator.put("isSignIapDeduct", false);
            data.put("associator", associator);

            JSONObject musicPackage = data.optJSONObject("musicPackage");
            if (musicPackage == null) musicPackage = new JSONObject();
            musicPackage.put("vipCode", 230);
            musicPackage.put("vipLevel", 9);
            musicPackage.put("expireTime", expireTime);
            musicPackage.put("rights", true);
            musicPackage.put("isSign", true);
            musicPackage.put("isSignIap", false);
            musicPackage.put("isSignDeduct", false);
            musicPackage.put("isSignIapDeduct", false);
            data.put("musicPackage", musicPackage);

            JSONObject redplus = data.optJSONObject("redplus");
            if (redplus == null) redplus = new JSONObject();
            redplus.put("vipCode", 300);
            redplus.put("vipLevel", 9);
            redplus.put("expireTime", expireTime);
            redplus.put("rights", true);
            redplus.put("isSign", true);
            redplus.put("isSignIap", false);
            redplus.put("isSignDeduct", false);
            redplus.put("isSignIapDeduct", false);
            data.put("redplus", redplus);

            JSONObject albumVip = data.optJSONObject("albumVip");
            if (albumVip == null) albumVip = new JSONObject();
            albumVip.put("vipCode", 400);
            albumVip.put("vipLevel", 0);
            albumVip.put("expireTime", expireTime);
            data.put("albumVip", albumVip);

            JSONObject memberLogo = new JSONObject();
            memberLogo.put("url", "https://p1.music.126.net/2zQloRuJIGiguu-ekkVxwQ==/109951166687981504.png");
            memberLogo.put("width", 64.0);
            memberLogo.put("height", 24.0);
            data.put("memberLogo", memberLogo);

            return jsonObject.toString();
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] modifyVipInfo error: " + t.getMessage());
        }
        return original;
    }

    /**
     * 账号信息（VIP 角标显示与 Moshi ProfileDO 支持）
     */
    public static String modifyAccount(String original) {
        try {
            JSONObject jsonObject = new JSONObject(original);
            long now = System.currentTimeMillis();
            long expireTime = now + 31622400000L;

            JSONObject memberLogo = new JSONObject();
            memberLogo.put("url", "https://p1.music.126.net/2zQloRuJIGiguu-ekkVxwQ==/109951166687981504.png");
            memberLogo.put("width", 64.0);
            memberLogo.put("height", 24.0);

            JSONObject profile = jsonObject.optJSONObject("profile");
            if (profile != null) {
                profile.put("vipType", 100);
                profile.put("redVipLevel", 9);
                profile.put("redVipAnnualCount", 1);
                profile.put("userType", 1);
                profile.put("memberLogo", memberLogo);

                JSONObject vipRights = profile.optJSONObject("vipRights");
                if (vipRights == null) vipRights = new JSONObject();
                vipRights.put("redVipLevel", 9);
                vipRights.put("redVipAnnualCount", 1);
                vipRights.put("now", now);
                vipRights.put("memberLogo", memberLogo);

                JSONObject redplus = vipRights.optJSONObject("redplus");
                if (redplus == null) redplus = new JSONObject();
                redplus.put("rights", true);
                redplus.put("vipCode", 300);
                redplus.put("vipLevel", 9);
                redplus.put("expireTime", expireTime);
                redplus.put("isSign", true);
                vipRights.put("redplus", redplus);

                JSONObject associator = vipRights.optJSONObject("associator");
                if (associator == null) associator = new JSONObject();
                associator.put("rights", true);
                associator.put("vipCode", 100);
                associator.put("vipLevel", 9);
                associator.put("expireTime", expireTime);
                associator.put("isSign", true);
                vipRights.put("associator", associator);

                JSONObject musicPackage = vipRights.optJSONObject("musicPackage");
                if (musicPackage == null) musicPackage = new JSONObject();
                musicPackage.put("rights", true);
                musicPackage.put("vipCode", 230);
                musicPackage.put("vipLevel", 9);
                musicPackage.put("expireTime", expireTime);
                musicPackage.put("isSign", true);
                vipRights.put("musicPackage", musicPackage);

                JSONObject albumVip = vipRights.optJSONObject("albumVip");
                if (albumVip == null) albumVip = new JSONObject();
                albumVip.put("vipCode", 400);
                albumVip.put("vipLevel", 0);
                albumVip.put("expireTime", expireTime);
                vipRights.put("albumVip", albumVip);

                profile.put("vipRights", vipRights);
            }
            JSONObject account = jsonObject.optJSONObject("account");
            if (account != null) {
                account.put("vipType", 100);
                account.put("userType", 1);
                account.put("memberLogo", memberLogo);
            }
            return jsonObject.toString();
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] modifyAccount error: " + t.getMessage());
        }
        return original;
    }

    /**
     * VIP 会员图标与 Logo (支持 SVIP 动态图标)
     */
    public static String modifyMemberLogo(String original) {
        try {
            JSONObject jsonObject = new JSONObject(original);
            jsonObject.put("code", 200);
            JSONObject data = jsonObject.optJSONObject("data");
            if (data == null) {
                data = new JSONObject();
                jsonObject.put("data", data);
            }
            data.put("doubleRelationLogo", false);

            JSONObject userLogo = data.optJSONObject("userLogo");
            if (userLogo == null) {
                userLogo = new JSONObject();
                data.put("userLogo", userLogo);
            }

            String svipImgUrl = "https://p1.music.126.net/2zQloRuJIGiguu-ekkVxwQ==/109951166687981504.png";
            JSONObject logoInfo = new JSONObject();
            logoInfo.put("url", svipImgUrl);
            logoInfo.put("width", 64);
            logoInfo.put("height", 24);

            JSONObject levelMap = new JSONObject();
            for (int i = 0; i <= 9; i++) {
                levelMap.put("v" + i, logoInfo);
                levelMap.put("V" + i, logoInfo);
            }

            JSONObject styleMap = new JSONObject();
            styleMap.put("normal", levelMap);
            styleMap.put("flash", levelMap);
            styleMap.put("renew", levelMap);
            styleMap.put("open", levelMap);
            styleMap.put("annul", levelMap);
            styleMap.put("expired", levelMap);
            styleMap.put("expiring", levelMap);
            styleMap.put("limitFree", levelMap);

            userLogo.put("svipLogo", styleMap);
            userLogo.put("svipIcon", styleMap);
            userLogo.put("vipLogo", styleMap);
            userLogo.put("vipIcon", styleMap);
            userLogo.put("friendSVipIcon", styleMap);
            userLogo.put("familySVipIcon", styleMap);
            userLogo.put("loverSVipIcon", styleMap);
            userLogo.put("undefinedSVipIcon", styleMap);
            userLogo.put("friendVipIcon", styleMap);
            userLogo.put("familyVipIcon", styleMap);
            userLogo.put("loverVipIcon", styleMap);
            userLogo.put("undefinedVipIcon", styleMap);

            JSONObject userLogoResource = data.optJSONObject("userLogoResource");
            if (userLogoResource == null) {
                userLogoResource = new JSONObject();
                data.put("userLogoResource", userLogoResource);
            }
            userLogoResource.put("userLogo", userLogo);

            jsonObject.put("userLogo", userLogo);
            return jsonObject.toString();
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] modifyMemberLogo error: " + t.getMessage());
        }
        return original;
    }

    /**
     * 动效歌词、特效与音质鉴权 (递归遍历所有层级特权)
     */
    public static String modifyVipAuth(String original) {
        try {
            JSONObject jsonObject = new JSONObject(original);
            jsonObject.put("code", 200);
            jsonObject.put("canUse", true);
            jsonObject.put("auth", true);
            jsonObject.put("hasAuth", true);
            jsonObject.put("vip", true);
            injectAuthPrivilege(jsonObject);
            return jsonObject.toString();
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] modifyVipAuth error: " + t.getMessage());
        }
        return original;
    }

    private static void injectAuthPrivilege(JSONObject obj) {
        if (obj == null) return;
        try {
            if (obj.has("canUse")) obj.put("canUse", true);
            if (obj.has("canNotUseReasonCode")) obj.put("canNotUseReasonCode", 200);
            if (obj.has("auth")) obj.put("auth", true);
            if (obj.has("hasAuth")) obj.put("hasAuth", true);
            if (obj.has("vip")) obj.put("vip", true);
            if (obj.has("hasPrivilege")) obj.put("hasPrivilege", true);
            if (obj.has("success")) obj.put("success", true);
            if (obj.has("toCashier")) obj.put("toCashier", false);
            if (obj.has("disable")) obj.put("disable", false);
            if (obj.has("vipLimit")) obj.put("vipLimit", false);
            if (obj.has("canTrial")) obj.put("canTrial", true);
            if (obj.has("chargeType")) obj.put("chargeType", 0);
            if (obj.has("fee")) obj.put("fee", 0);
            if (obj.has("payed")) obj.put("payed", 1);
            if (obj.has("isVip")) obj.put("isVip", true);
        } catch (Throwable ignored) {}

        List<String> keyList = new ArrayList<>();
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            keyList.add(it.next());
        }
        for (String key : keyList) {
            Object child = obj.opt(key);
            if (child instanceof JSONObject) {
                injectAuthPrivilege((JSONObject) child);
            } else if (child instanceof JSONArray) {
                injectAuthPrivilegeArray((JSONArray) child);
            }
        }
    }

    private static void injectAuthPrivilegeArray(JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            if (item instanceof JSONObject) {
                injectAuthPrivilege((JSONObject) item);
            } else if (item instanceof JSONArray) {
                injectAuthPrivilegeArray((JSONArray) item);
            }
        }
    }

    /**
     * 播放器样式解锁
     */
    public static String modifyPlayerMode(String original) {
        try {
            JSONObject jsonObject = new JSONObject(original);
            jsonObject.put("code", 200);
            jsonObject.put("success", true);
            injectAuthPrivilege(jsonObject);
            JSONObject data = jsonObject.optJSONObject("data");
            if (data != null) {
                data.put("success", true);
                data.put("hasPrivilege", true);
                data.put("isVip", false);
            }
            return jsonObject.toString();
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] modifyPlayerMode error: " + t.getMessage());
        }
        return original;
    }

    /**
     * UNM 全局特权注入 (通用递归注入)
     * 解锁歌曲 VIP 限制、音质限制、播放下载权限
     */
    public static String injectUniversalPrivilege(String original) {
        try {
            JSONObject jsonObject = new JSONObject(original);
            injectPrivilege(jsonObject);
            return jsonObject.toString();
        } catch (Throwable t) {
            return original;
        }
    }

    public static void injectPrivilege(JSONObject obj) {
        if (obj == null) return;
        try {
            if (obj.has("cp") && obj.opt("cp") instanceof Number) {
                if (obj.has("subp") || obj.has("maxbr") || obj.has("downloadMaxbr")) {
                    obj.put("cp", 1);
                }
            }
            if (obj.has("fee") && obj.opt("fee") instanceof Number) {
                obj.put("fee", 0);
            }
            if (obj.has("payed") && obj.opt("payed") instanceof Number && obj.optInt("payed", 0) == 0) {
                obj.put("payed", 1);
            }
            if (obj.has("st") && obj.opt("st") instanceof Number) {
                obj.put("st", 0);
            }
            if (obj.has("sp") && obj.has("subp")) {
                if (obj.opt("sp") instanceof Number) obj.put("sp", 7);
                if (obj.opt("subp") instanceof Number) obj.put("subp", 1);
            }
            if (obj.has("playable")) {
                obj.put("playable", true);
                obj.put("unplayableType", "unknown");
            }
            int dlMax = obj.optInt("downloadMaxbr", 0);
            if (dlMax == 0 && obj.has("downloadMaxbr") && obj.opt("downloadMaxbr") instanceof Number) {
                dlMax = 999000;
                obj.put("downloadMaxbr", 999000);
            }
            if (obj.has("dl") && dlMax > 0 && obj.opt("dl") instanceof Number && obj.optInt("dl", 0) < dlMax) {
                obj.put("dl", dlMax);
            }
            int plMax = obj.optInt("playMaxbr", 0);
            if (plMax == 0 && obj.has("playMaxbr") && obj.opt("playMaxbr") instanceof Number) {
                plMax = 999000;
                obj.put("playMaxbr", 999000);
            }
            if (obj.has("pl") && plMax > 0 && obj.opt("pl") instanceof Number && obj.optInt("pl", 0) < plMax) {
                obj.put("pl", plMax);
            }
            if (obj.has("maxbr") && obj.opt("maxbr") instanceof Number && obj.optInt("maxbr", 0) < 999000) {
                obj.put("maxbr", 999000);
            }
            if (obj.has("plLevel") && "none".equals(obj.optString("plLevel"))) {
                obj.put("plLevel", "lossless");
            }
            if (obj.has("dlLevel") && "none".equals(obj.optString("dlLevel"))) {
                obj.put("dlLevel", "lossless");
            }
            if (obj.has("flLevel") && "none".equals(obj.optString("flLevel"))) {
                obj.put("flLevel", "lossless");
            }
        } catch (Throwable ignored) {}

        List<String> keyList = new ArrayList<>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            keyList.add(keys.next());
        }
        for (String key : keyList) {
            Object child = obj.opt(key);
            if (child instanceof JSONObject) {
                injectPrivilege((JSONObject) child);
            } else if (child instanceof JSONArray) {
                injectPrivilegeArray((JSONArray) child);
            }
        }
    }

    public static void injectPrivilegeArray(JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            if (item instanceof JSONObject) {
                injectPrivilege((JSONObject) item);
            } else if (item instanceof JSONArray) {
                injectPrivilegeArray((JSONArray) item);
            }
        }
    }

    /**
     * batch 接口中的 VIP 信息修改与全局注入
     */
    public static String modifyBatchVip(String original) {
        try {
            JSONObject jsonObject = new JSONObject(original);
            List<String> keyList = new ArrayList<>();
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                keyList.add(keys.next());
            }
            for (String key : keyList) {
                if (key.contains("memberlogo") || key.contains("vip/logo") || key.contains("vipnewcenter")) {
                    JSONObject logo = jsonObject.optJSONObject(key);
                    if (logo != null) {
                        jsonObject.put(key, new JSONObject(modifyMemberLogo(logo.toString())));
                    }
                } else if (key.contains("vip/info") || key.contains("vip-membership")) {
                    JSONObject info = jsonObject.optJSONObject(key);
                    if (info != null) {
                        jsonObject.put(key, new JSONObject(modifyVipInfo(info.toString())));
                    }
                } else if (key.contains("account/get") || key.contains("user/info") || key.contains("user/detail") || key.contains("user/profile")) {
                    JSONObject acc = jsonObject.optJSONObject(key);
                    if (acc != null) {
                        jsonObject.put(key, new JSONObject(modifyAccount(acc.toString())));
                    }
                } else if (key.contains("playermode") || key.contains("player/mode") || key.contains("vinyl")) {
                    JSONObject pm = jsonObject.optJSONObject(key);
                    if (pm != null) {
                        jsonObject.put(key, new JSONObject(modifyPlayerMode(pm.toString())));
                    }
                } else if (key.contains("usertool/sound") || key.contains("sound")) {
                    JSONObject snd = jsonObject.optJSONObject(key);
                    if (snd != null) {
                        jsonObject.put(key, new JSONObject(modifyEffect(snd.toString())));
                    }
                } else if (key.contains("vipauth") || key.contains("auth/query") || key.contains("soundquality")) {
                    JSONObject auth = jsonObject.optJSONObject(key);
                    if (auth != null) {
                        jsonObject.put(key, new JSONObject(modifyVipAuth(auth.toString())));
                    }
                }
            }
        injectPrivilege(jsonObject);
            return jsonObject.toString();
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] modifyBatchVip error: " + t.getMessage());
        }
        return original;
    }

    /**
     * 精简Tab接口数据截断（只保留“我的”与“发现/首页”，排除搜索、漫游等）
     */
    public static String modifyTab(String original) {
        if (original == null || original.isEmpty()) return original;
        try {
            JSONObject root = new JSONObject(original);
            Object dataObj = root.opt("data");
            if (dataObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) dataObj;
                if (arr.length() > 2) {
                    root.put("data", filterTabJsonArray(arr));
                    return root.toString();
                }
            } else if (dataObj instanceof JSONObject) {
                JSONObject data = (JSONObject) dataObj;
                for (String key : new String[]{"tabList", "tabs", "topTabList", "subTabs", "topTabs"}) {
                    JSONArray arr = data.optJSONArray(key);
                    if (arr != null && arr.length() > 2) {
                        data.put(key, filterTabJsonArray(arr));
                    }
                }
                return root.toString();
            }
        } catch (Throwable t) {
            DebugLogger.e("EAPIHelper", "modifyTab error: " + t.getMessage(), t);
        }
        return original;
    }

    private static JSONArray filterTabJsonArray(JSONArray arr) {
        if (arr == null || arr.length() <= 2) return arr;
        JSONObject homeObj = null;
        JSONObject mineObj = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String text = item.toString().toLowerCase();
            if (isJsonTabMine(text)) {
                mineObj = item;
            } else if (isJsonTabHome(text)) {
                if (homeObj == null) homeObj = item;
            }
        }
        try {
            if (homeObj == null && arr.length() > 0) homeObj = arr.optJSONObject(0);
            if (mineObj == null && arr.length() > 1) mineObj = arr.optJSONObject(arr.length() - 1);

            JSONArray newArr = new JSONArray();
            if (homeObj != null) newArr.put(homeObj);
            if (mineObj != null && mineObj != homeObj) newArr.put(mineObj);

            if (newArr.length() >= 2) return newArr;
        } catch (Throwable ignored) {
        }
        return arr;
    }

    private static boolean isJsonTabHome(String s) {
        if (s == null) return false;
        if (s.contains("search") || s.contains("搜索") || s.contains("roam") || s.contains("漫游") || s.contains("dynamic") || s.contains("动态")) {
            return false;
        }
        return s.contains("find") || s.contains("发现") || s.contains("home") || s.contains("首页") || s.contains("main");
    }

    private static boolean isJsonTabMine(String s) {
        if (s == null) return false;
        return s.contains("mine") || s.contains("我的") || s.contains("profile") || s.contains("user");
    }

    /**
     * 评论区优先显示最热：保证数据中 sortType=2 并将最热排在首位
     */
    public static String modifyCommentHot(String original) {
        if (original == null || original.isEmpty()) return original;
        try {
            JSONObject root = new JSONObject(original);
            JSONObject data = root.optJSONObject("data");
            if (data != null) {
                if (data.has("sortType")) {
                    data.put("sortType", 2);
                }
                JSONArray sortTypeList = data.optJSONArray("sortTypeList");
                if (sortTypeList != null && sortTypeList.length() > 1) {
                    JSONObject hotItem = null;
                    JSONArray newSortTypeList = new JSONArray();
                    for (int i = 0; i < sortTypeList.length(); i++) {
                        JSONObject item = sortTypeList.optJSONObject(i);
                        if (item == null) continue;
                        if (item.optInt("sortType", -1) == 2 || item.optString("sortTypeName").contains("热")) {
                            hotItem = item;
                        }
                    }
                    if (hotItem != null) {
                        newSortTypeList.put(hotItem);
                        for (int i = 0; i < sortTypeList.length(); i++) {
                            JSONObject item = sortTypeList.optJSONObject(i);
                            if (item != null && item != hotItem) {
                                newSortTypeList.put(item);
                            }
                        }
                        data.put("sortTypeList", newSortTypeList);
                    }
                }
                return root.toString();
            }
        } catch (Throwable t) {
            DebugLogger.e("EAPIHelper", "modifyCommentHot error: " + t.getMessage(), t);
        }
        return original;
    }

    /**
     * 侧边栏精简：递归过滤侧边栏响应中的项目
     */
    public static String modifySidebar(String original) {
        if (original == null || original.isEmpty()) return original;
        try {
            HashMap<String, Boolean> settingMap = SettingHelper.getInstance().getSidebarSetting(null);
            if (settingMap == null || settingMap.isEmpty()) return original;
            JSONObject root = new JSONObject(original);
            filterSidebarJsonObject(root, settingMap);
            return root.toString();
        } catch (Throwable t) {
            DebugLogger.e("EAPIHelper", "modifySidebar error: " + t.getMessage(), t);
        }
        return original;
    }

    private static void filterSidebarJsonObject(JSONObject obj, HashMap<String, Boolean> settingMap) {
        if (obj == null) return;
        List<String> keys = new ArrayList<>();
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        for (String k : keys) {
            Object val = obj.opt(k);
            if (val instanceof JSONObject) {
                filterSidebarJsonObject((JSONObject) val, settingMap);
            } else if (val instanceof JSONArray) {
                JSONArray arr = (JSONArray) val;
                JSONArray newArr = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    if (item instanceof JSONObject) {
                        JSONObject itemObj = (JSONObject) item;
                        if (!shouldHideSidebarJsonItem(itemObj, settingMap)) {
                            filterSidebarJsonObject(itemObj, settingMap);
                            newArr.put(itemObj);
                        }
                    } else {
                        newArr.put(item);
                    }
                }
                try {
                    obj.put(k, newArr);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean shouldHideSidebarJsonItem(JSONObject item, HashMap<String, Boolean> settingMap) {
        if (item == null || settingMap == null) return false;
        String allText = (item.optString("name", "") + " "
                + item.optString("title", "") + " "
                + item.optString("text", "") + " "
                + item.optString("header", "") + " "
                + item.optString("actionUrl", "") + " "
                + item.optString("code", "") + " "
                + item.optString("itemType", "")).toLowerCase();
        return shouldHideSidebarString(allText, settingMap);
    }

    public static boolean shouldHideSidebarString(String allText, HashMap<String, Boolean> settingMap) {
        if (allText == null || settingMap == null) return false;
        String lower = allText.toLowerCase();

        if (Boolean.TRUE.equals(settingMap.get("STORE")) && (lower.contains("商城") || lower.contains("store"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("GAME")) && (lower.contains("游戏") || lower.contains("game"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("CLOUD_SHELL_CENTER")) && (lower.contains("云贝") || lower.contains("cloudshell"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("TICKET")) && (lower.contains("有票") || lower.contains("ticket"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("MY_ORDER")) && (lower.contains("订单") || lower.contains("order"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("DISCOUNT_COUPON")) && (lower.contains("优惠券") || lower.contains("coupon"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("COLOR_RING")) && (lower.contains("彩铃") || lower.contains("color_ring"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("PRIVATE_CLOUD")) && (lower.contains("云盘") || lower.contains("private_cloud"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("CACHE_WHILE_LISTEN")) && (lower.contains("边听边存") || lower.contains("cache_while_listen"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("VEHICLE_PLAYER")) && (lower.contains("驾驶") || lower.contains("vehicle"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("YOUTH_MODE")) && (lower.contains("青少年") || lower.contains("youth"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("ALARM_CLOCK")) && (lower.contains("闹钟") || lower.contains("alarm"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("CLOCK_PLAY")) && (lower.contains("定时") || lower.contains("clock_play"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("IDENTIFY")) && (lower.contains("听歌识曲") || lower.contains("identify"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("SCAN")) && (lower.contains("扫一扫") || lower.contains("scan"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("FREE")) && (lower.contains("免流量") || lower.contains("free_traffic"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("MUSIC_BLACKLIST")) && (lower.contains("黑名单") || lower.contains("blacklist"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("MY_FRIEND")) && (lower.contains("好友") || lower.contains("my_friend"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("RED_PACKET")) && (lower.contains("红包") || lower.contains("red_packet"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("PROFIT")) && (lower.contains("赞赏") || lower.contains("profit"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("FEEDBACK_HELP")) && (lower.contains("帮助") || lower.contains("feedback"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("SHARE_APP")) && (lower.contains("分享网易云") || lower.contains("share_app"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("ABOUT")) && (lower.contains("关于") || lower.contains("about"))) return true;
        if ((Boolean.TRUE.equals(settingMap.get("MUSICIAN")) || Boolean.TRUE.equals(settingMap.get("CREATOR_CENTER")) || Boolean.TRUE.equals(settingMap.get("MUSICIAN_CREATOR_CENTER")) || Boolean.TRUE.equals(settingMap.get("MUSICIAN_VIEWER")))
                && (lower.contains("音乐人") || lower.contains("创作者") || lower.contains("musician") || lower.contains("creator"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("BEAT")) && lower.contains("beat")) return true;
        if (Boolean.TRUE.equals(settingMap.get("NEARBY")) && (lower.contains("附近") || lower.contains("nearby"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("VIP")) && (lower.contains("我的会员") || lower.contains("黑胶vip") || lower.contains("vipnewcenter"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("MESSAGE")) && (lower.contains("我的消息") || lower.contains("message"))) return true;
        if (Boolean.TRUE.equals(settingMap.get("THEME")) && (lower.contains("装扮") || lower.contains("theme"))) return true;

        return false;
    }
}
