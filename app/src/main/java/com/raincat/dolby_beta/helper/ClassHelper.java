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
 * desc   : 利用逆向获取的 com.netease.cloudmusic.c1 -> o3(JSONObject) 拦截音源。
 * 对解密后的明文 JSONObject 直接实施降维打击，杜绝网络解压扫描类的异常崩溃，兼容性爆表！
 * </pre>
 */

public class SongUrlHook {
    public SongUrlHook(final Context context) {
        try {
            // 通过 MT 管理器逆向精准锁定的核心业务反序列化类
            Class<?> targetClass = XposedHelpers.findClassIfExists("com.netease.cloudmusic.c1", context.getClassLoader());
            
            if (targetClass != null) {
                XposedHelpers.findAndHookMethod(targetClass, "o3", JSONObject.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);
                        
                        // 代理未开启则不拦截音源
                        if (!SettingHelper.getInstance().isEnable(SettingHelper.proxy_master_key)) {
                            return;
                        }

                        // 核心：此时 param.args[0] 已经是由网易云底层完全解密并传入的明文 JSONObject 了！
                        JSONObject responseJson = (JSONObject) param.args[0];
                        if (responseJson == null) {
                            return;
                        }

                        // 使用 XposedBridge 在后台悄悄观察明文 JSON，防止黑盒代理调试困难
                        XposedBridge.log("DolbyBeta: 成功捕获到音源解密后明文 JSON!");

                        // 如果包含音源数据块，开始拦截并做重定向 (无缝重路由)
                        if (responseJson.has("data")) {
                            Object dataObj = responseJson.opt("data");
                            
                            if (dataObj instanceof JSONArray) {
                                // 批量歌单音源情况
                                JSONArray dataArray = (JSONArray) dataObj;
                                for (int i = 0; i < dataArray.length(); i++) {
                                    JSONObject songObj = dataArray.optJSONObject(i);
                                    if (songObj != null) {
                                        // 调用 DolbyBeta 插件原本的 EAPIHelper.modifyPlayer 进行代理音源无损/代理重写
                                        // 传入 songObj 的字符串表示并进行覆写
                                        String modifiedStr = EAPIHelper.modifyPlayer(songObj.toString());
                                        
                                        // 剔除可能存在的额外数组层级包裹，将其还原并写回数据节点
                                        if (modifiedStr.startsWith("[") && modifiedStr.endsWith("]")) {
                                            modifiedStr = modifiedStr.substring(1, modifiedStr.length() - 1);
                                        }
                                        
                                        // 替换当前索引下修改过的音源明文 JSONObject
                                        dataArray.put(i, new JSONObject(modifiedStr));
                                    }
                                }
                            } else if (dataObj instanceof JSONObject) {
                                // 单曲音源或下载音源情况
                                JSONObject songObj = (JSONObject) dataObj;
                                String modifiedStr = EAPIHelper.modifyPlayer(songObj.toString());
                                if (modifiedStr.startsWith("[") && modifiedStr.endsWith("]")) {
                                    modifiedStr = modifiedStr.substring(1, modifiedStr.length() - 1);
                                }
                                responseJson.put("data", new JSONObject(modifiedStr));
                            }
                            
                            // 直接将篡改好的明文 JSONObject 传回参数，完美执行音源替换
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
