package com.raincat.dolby_beta.hook;

import android.content.Context;

import com.raincat.dolby_beta.helper.EAPIHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 * author : Assistant
 * desc   : 精准对接 c1 -> o3(JSONObject) 明文业务层
 * 彻底摒弃底层 okhttp 解密特征的检索，100% 避开 DEX 加密扫描与卡启动、闪退等顽疾
 * </pre>
 */

public class SongUrlHook {
    public SongUrlHook(final Context context) {
        try {
            // 通过 MT 管理器分析得到的最新版网易云核心反序列化音源类
            Class<?> targetClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.c1", context.getClassLoader());

            if (targetClass != null) {
                XposedHelpers.findAndHookMethod(targetClass, "o3", JSONObject.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);

                        // 如果全局音源代理未开启，静默跳过，避免干扰原有逻辑
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.proxy_master_key)) {
                            return;
                        }

                        // 核心：取出已被网易云底层解密完成并无私传入的明文 JSON 音源数据
                        JSONObject responseJson = (JSONObject) param.args[0];
                        if (responseJson == null) {
                            return;
                        }

                        XposedBridge.log("DolbyBeta: [SongUrlHook] 成功捕获到音源解密后明文 JSON!");

                        if (responseJson.has("data")) {
                            Object dataObj = responseJson.opt("data");

                            if (dataObj instanceof JSONArray) {
                                // 处理批量多歌曲音源获取 (如批量歌单缓存、列表试听)
                                JSONArray dataArray = (JSONArray) dataObj;
                                for (int i = 0; i < dataArray.length(); i++) {
                                    JSONObject songObj = dataArray.optJSONObject(i);
                                    if (songObj != null) {
                                        // 回调杜比插件内置的代理和重定向处理
                                        String modifiedStr = EAPIHelper.modifyPlayer(songObj.toString());

                                        // 剔除两侧冗余的数组括号标志（EAPI 转换副产物）
                                        if (modifiedStr.startsWith("[") && modifiedStr.endsWith("]")) {
                                            modifiedStr = modifiedStr.substring(1, modifiedStr.length() - 1);
                                        }

                                        dataArray.put(i, new JSONObject(modifiedStr));
                                    }
                                }
                            } else if (dataObj instanceof JSONObject) {
                                // 单曲流播放解析、或者是试听
                                JSONObject songObj = (JSONObject) dataObj;
                                String modifiedStr = EAPIHelper.modifyPlayer(songObj.toString());
                                if (modifiedStr.startsWith("[") && modifiedStr.endsWith("]")) {
                                    modifiedStr = modifiedStr.substring(1, modifiedStr.length() - 1);
                                }
                                responseJson.put("data", new JSONObject(modifiedStr));
                            }

                            // 将篡改覆写完毕后的明文数据无缝赋值回去
                            param.args[0] = responseJson;
                        }
                    }
                });
                XposedBridge.log("DolbyBeta: 音源代理业务 [com.netease.cloudmusic.c1 -> o3] 硬编码业务 Hook 部署成功！");
            } else {
                XposedBridge.log("DolbyBeta: 警告！未能在当前网易云版本下找到 c1 类，音源硬编码代理暂时挂起。");
            }
        } catch (Throwable e) {
            XposedBridge.log("DolbyBeta: 部署音源代理业务 Hook 时发生严重异常: " + e.getMessage());
        }
    }
}
