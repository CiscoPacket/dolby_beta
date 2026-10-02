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
import com.raincat.dolby_beta.hook.CommentHotClickHook;

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

    /** batch 子响应 VIP 标记清理/键名诊断的日志计数 (限次, 避免刷屏) */
    private static final java.util.concurrent.atomic.AtomicInteger EFFECT_SUB_LOG =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final Gson gson = new Gson();

    /**
     * 解除下载加密
     */
    public static String modifyPlayer(String original) {
        if (original == null || original.isEmpty()) return original;
        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return original;
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
        if (originalContent == null || originalContent.isEmpty()) return originalContent;
        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return originalContent;
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
        // 9.6.x 音效/样式条目字段已改名: aeVipType/animVipType/aeLimitTime/animLimitTime
        originalContent = Pattern.compile("\"aeVipType\":\\d+").matcher(originalContent).replaceAll("\"aeVipType\":0");
        originalContent = Pattern.compile("\"animVipType\":\\d+").matcher(originalContent).replaceAll("\"animVipType\":0");
        originalContent = Pattern.compile("\"aeLimitTime\":\\d+").matcher(originalContent).replaceAll("\"aeLimitTime\":999999999999");
        originalContent = Pattern.compile("\"animLimitTime\":\\d+").matcher(originalContent).replaceAll("\"animLimitTime\":999999999999");
        originalContent = Pattern.compile("\"fee\":\\d+").matcher(originalContent).replaceAll("\"fee\":0");
        originalContent = Pattern.compile("\"payed\":\\d+").matcher(originalContent).replaceAll("\"payed\":1");
        originalContent = Pattern.compile("\"free\":false").matcher(originalContent).replaceAll("\"free\":true");
        return originalContent;
    }

    /**
     * 纯正则清除 VIP 标记字段 (不做 JSON 解析, 可安全应用于 RN 数据下发通道)。
     * 覆盖: limitTime/vipType/aeVipType/animVipType/fee/payed/free
     */
    public static String clearVipMarkers(String original) {
        if (original == null || original.isEmpty()) return original;
        try {
            String s = original;
            s = Pattern.compile("\"limitTime\":\\d+").matcher(s).replaceAll("\"limitTime\":0");
            s = Pattern.compile("\"vipType\":\\d+").matcher(s).replaceAll("\"vipType\":0");
            s = Pattern.compile("\"aeVipType\":\\d+").matcher(s).replaceAll("\"aeVipType\":0");
            s = Pattern.compile("\"animVipType\":\\d+").matcher(s).replaceAll("\"animVipType\":0");
            s = Pattern.compile("\"aeLimitTime\":\\d+").matcher(s).replaceAll("\"aeLimitTime\":999999999999");
            s = Pattern.compile("\"animLimitTime\":\\d+").matcher(s).replaceAll("\"animLimitTime\":999999999999");
            s = Pattern.compile("\"fee\":\\d+").matcher(s).replaceAll("\"fee\":0");
            s = Pattern.compile("\"payed\":\\d+").matcher(s).replaceAll("\"payed\":1");
            s = Pattern.compile("\"free\":false").matcher(s).replaceAll("\"free\":true");
            return s;
        } catch (Throwable t) {
            return original;
        }
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
        if (original == null || original.isEmpty()) return original;
        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return original;
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
        if (original == null || original.isEmpty()) return original;
        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return original;
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
        if (original == null || original.isEmpty()) return original;
        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return original;
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
        if (original == null || original.isEmpty()) return original;
        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return original;
        try {
            JSONObject jsonObject = new JSONObject(original);
            // cashier/pre-check (music-vip-thoth) 的判定字段是顶层布尔 data:
            // 实测服务端对无权益用户返回 data=true (需要进收银台), 翻转为 false = 跳过收银台
            // (日志实锤: 20:44:43 data=true -> 弹收银台; 翻转后为 false -> 直接使用)
            Object dataObj = jsonObject.opt("data");
            if (Boolean.TRUE.equals(dataObj)) {
                jsonObject.put("data", false);
                DebugLogger.i("EAPIHelper", "cashier pre-check data=true -> false (paywall bypassed)");
            }
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
            if (obj.has("needPay")) obj.put("needPay", false);
            if (obj.has("showCashier")) obj.put("showCashier", false);
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
        if (original == null || original.isEmpty()) return original;
        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return original;
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
        if (original == null || original.isEmpty()) return original;
        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return original;
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
        if (original == null || original.isEmpty()) return original;
        if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)) return original;
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
                } else if (key.contains("playermode") || key.contains("playeruimode") || key.contains("player/mode")
                        || key.contains("vinyl") || key.contains("effect") || key.contains("musiceffect")) {
                    // 播放器样式/音效条目批量接口: 条目的 vipType/limitTime 标记在此清除
                    JSONObject eff = jsonObject.optJSONObject(key);
                    if (eff != null) {
                        jsonObject.put(key, new JSONObject(modifyEffect(eff.toString())));
                    }
                } else if (key.contains("vipauth") || key.contains("auth/query") || key.contains("soundquality")) {
                    JSONObject auth = jsonObject.optJSONObject(key);
                    if (auth != null) {
                        jsonObject.put(key, new JSONObject(modifyVipAuth(auth.toString())));
                    }
                } else {
                    // 兜底: 子响应里出现 VIP/限时标记 (vipType/aeVipType/animVipType/limitTime) 一律清除
                    JSONObject sub = jsonObject.optJSONObject(key);
                    if (sub != null) {
                        String subStr = sub.toString();
                        if (subStr.contains("vipType") || subStr.contains("aeVipType")
                                || subStr.contains("animVipType") || subStr.contains("limitTime")) {
                            String cleaned = clearVipMarkers(subStr);
                            if (!cleaned.equals(subStr)) {
                                jsonObject.put(key, new JSONObject(cleaned));
                                if (EFFECT_SUB_LOG.getAndIncrement() < 3) {
                                    DebugLogger.i("EAPIHelper", "batch sub vip markers cleared: " + key);
                                }
                            }
                        }
                    }
                }
            }
            if (EFFECT_SUB_LOG.getAndIncrement() < 2) {
                StringBuilder kb = new StringBuilder();
                for (String k : keyList) {
                    if (kb.length() > 0) kb.append(", ");
                    kb.append(k);
                }
                XposedBridge.log("[dolby_beta] batch sub keys: " + kb);
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
     * 评论区优先显示最热：保留官方原生 Tab 排序 [推荐] [最热] [最新]，不物理颠倒 Tab 位置
     * 每首歌首屏保证响应标识为最热(2)，歌曲内用户主动点击推荐或最新时完全放行
     */
    public static String modifyCommentHot(String original) {
        if (original == null || original.isEmpty()) return original;
        // 强制窗口外零开销返回, 避免对大响应做无谓的 JSON 解析/序列化
        if (!CommentHotClickHook.isInForceHotWindow()) return original;
        try {
            JSONObject root = new JSONObject(original);
            JSONObject data = root.optJSONObject("data");
            if (data != null) {
                if (CommentHotClickHook.isInForceHotWindow()) {
                    boolean changed = false;
                    // 强制设置 currentSortType = 2 (最热)
                    int curSt = data.optInt("currentSortType", -1);
                    if (curSt != 2) {
                        data.put("currentSortType", 2);
                        changed = true;
                    }
                    // 强制设置 sortType = 2
                    int st = data.optInt("sortType", -1);
                    if (st != 2) {
                        data.put("sortType", 2);
                        changed = true;
                    }
                    // 强制设置 defaultSortType = 2
                    int defSt = data.optInt("defaultSortType", -1);
                    if (defSt != 2) {
                        data.put("defaultSortType", 2);
                        changed = true;
                    }
                    if (changed) {
                        CommentHotClickHook.markResponseModified();
                        DebugLogger.i("EAPIHelper", "modifyCommentHot: forced sortType=2 in response (currentSortType=" + curSt + "->2, sortType=" + st + "->2, defaultSortType=" + defSt + "->2)");
                        return root.toString();
                    }
                }
            }
        } catch (Throwable t) {
            DebugLogger.e("EAPIHelper", "modifyCommentHot error: " + t.getMessage(), t);
        }
        return original;
    }

    /**
     * 侧边栏精简：递归过滤侧边栏响应中的项目
     */
    public static String modifySidebar(String original, String path) {
        if (original == null || original.isEmpty()) return original;
        try {
            HashMap<String, Boolean> settingMap = SettingHelper.getInstance().getSidebarSetting(null);
            if (settingMap == null || settingMap.isEmpty()) return original;
            JSONObject root = new JSONObject(original);
            List<String> removed = new ArrayList<>();
            filterSidebarJsonObject(root, settingMap, removed, 0);
            if (!removed.isEmpty()) {
                DebugLogger.i("EAPIHelper", "modifySidebar[" + path + "] removed " + removed.size()
                        + " items: " + removed.subList(0, Math.min(removed.size(), 8)));
                return root.toString();
            }
        } catch (Throwable t) {
            DebugLogger.e("EAPIHelper", "modifySidebar error: " + t.getMessage(), t);
        }
        return original;
    }

    private static void filterSidebarJsonObject(JSONObject obj, HashMap<String, Boolean> settingMap, List<String> removed, int depth) {
        if (obj == null || depth > 8) return;
        List<String> keys = new ArrayList<>();
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        for (String k : keys) {
            Object val = obj.opt(k);
            if (val instanceof JSONObject) {
                filterSidebarJsonObject((JSONObject) val, settingMap, removed, depth + 1);
            } else if (val instanceof JSONArray) {
                JSONArray arr = (JSONArray) val;
                JSONArray newArr = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    if (item instanceof JSONObject) {
                        JSONObject itemObj = (JSONObject) item;
                        if (!shouldHideSidebarJsonItem(itemObj, settingMap)) {
                            filterSidebarJsonObject(itemObj, settingMap, removed, depth + 1);
                            newArr.put(itemObj);
                        } else {
                            StringBuilder desc = new StringBuilder();
                            collectItemStrings(itemObj, desc, 0);
                            String d = desc.toString().trim();
                            removed.add(d.length() > 40 ? d.substring(0, 40) : d);
                        }
                    } else if (item instanceof String && ((String) item).trim().startsWith("{")) {
                        // 侧边栏动态条目 (vipnewcenter contentList 等) 为"JSON 字符串"数组元素,
                        // 必须解析后按 title/条目名判定, 否则数据级隐藏完全失效
                        String raw = (String) item;
                        try {
                            JSONObject inner = new JSONObject(raw.trim());
                            if (shouldHideSidebarJsonItem(inner, settingMap)) {
                                StringBuilder desc = new StringBuilder();
                                collectItemStrings(inner, desc, 0);
                                String d = desc.toString().trim();
                                removed.add(d.length() > 40 ? d.substring(0, 40) : d);
                            } else {
                                int before = removed.size();
                                filterSidebarJsonObject(inner, settingMap, removed, depth + 1);
                                newArr.put(removed.size() > before ? inner.toString() : raw);
                            }
                        } catch (Throwable ignored) {
                            newArr.put(raw);
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
        // 1) 标题字段精确匹配优先 (键空间统一为条目名)
        String title = item.optString("title", "").trim();
        if (!title.isEmpty() && settingMap.containsKey(title)) {
            return Boolean.TRUE.equals(settingMap.get(title));
        }
        for (String f : new String[]{"title", "name", "subTitle"}) {
            String v = item.optString(f, "");
            if (v != null && !v.isEmpty() && Boolean.TRUE.equals(settingMap.get(v.trim()))) return true;
        }
        // 2) 9.6+ 侧边栏数据为 link-position 动态体系, 文本位于嵌套结构
        // (uiInfo.title / action.actionUrl / resourceType / resourceId 等),
        // 只读顶层扁平字段会全部漏判, 这里递归收集条目子树内所有字符串 (限深)
        StringBuilder sb = new StringBuilder();
        collectItemStrings(item, sb, 0);
        // 2) 兜底: 条目子树内出现任一"已勾选条目名" (键空间统一为条目名)
        String all = sb.toString();
        for (HashMap.Entry<String, Boolean> e : settingMap.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) continue;
            String key = e.getKey();
            if (key == null || key.trim().length() < 2) continue;
            if (all.contains(key.trim())) return true;
        }
        return false;
    }

    private static void collectItemStrings(JSONObject obj, StringBuilder sb, int depth) {
        if (obj == null || depth > 4) return;
        try {
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = obj.opt(k);
                if (v instanceof JSONObject) {
                    collectItemStrings((JSONObject) v, sb, depth + 1);
                } else if (v instanceof JSONArray) {
                    JSONArray arr = (JSONArray) v;
                    for (int i = 0; i < arr.length(); i++) {
                        Object e = arr.opt(i);
                        if (e instanceof JSONObject) {
                            collectItemStrings((JSONObject) e, sb, depth + 1);
                        } else if (e instanceof String) {
                            sb.append((String) e).append(' ');
                        }
                    }
                } else if (v instanceof String) {
                    sb.append((String) v).append(' ');
                }
            }
        } catch (Throwable ignored) {
        }
    }


}
