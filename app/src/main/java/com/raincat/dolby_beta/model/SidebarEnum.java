package com.raincat.dolby_beta.model;

import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/10/22
 *     desc   : 侧边栏条目 (纯动态, 零硬编码)
 *     version: 3.0
 *
 *     条目清单 100% 来自运行时:
 *     - 来源: HideSidebarHook 对侧边栏抽屉视图树的实时扫描 (行文本即条目名);
 *     - 持久化: 经 SettingHelper 写入存储, 重启后由 refreshSetting 恢复;
 *     - 判定: 对话框勾选/行隐藏/数据级过滤全部按条目名精确匹配。
 *     本类不含任何硬编码条目名; 抽屉从未扫描过时清单为空,
 *     设置对话框提示先打开一次抽屉。
 * </pre>
 */

public class SidebarEnum {
    /** 视图扫描识别到的真实条目 (条目名->条目名), 经 SettingHelper 持久化 */
    private static final LinkedHashMap<String, String> dynamicMap = new LinkedHashMap<>();

    public static synchronized void addDynamicItems(Collection<String> labels) {
        for (String label : labels) {
            if (label == null || label.trim().isEmpty()) continue;
            String key = label.trim();
            if (!dynamicMap.containsKey(key)) {
                dynamicMap.put(key, key);
            }
        }
    }

    /** 恢复持久化的动态识别条目 (进程启动时由 SettingHelper 调用) */
    public static synchronized void restoreDynamicItems(Collection<String> labels) {
        if (labels == null) return;
        for (String label : labels) {
            if (label == null || label.trim().isEmpty()) continue;
            String key = label.trim();
            if (!dynamicMap.containsKey(key)) {
                dynamicMap.put(key, key);
            }
        }
    }

    /** 当前动态识别条目名集合 (持久化用) */
    public static synchronized java.util.Set<String> getDynamicLabels() {
        return new java.util.HashSet<>(dynamicMap.keySet());
    }

    public static synchronized void notifyDynamicChanged() {
        // 预留: 动态清单变化时的回调点 (当前设置对话框在打开时重新读取)
    }

    /** 条目清单 (条目名->条目名) —— 纯动态识别结果 */
    public static synchronized LinkedHashMap<String, String> getSidebarEnum() {
        return new LinkedHashMap<>(dynamicMap);
    }
}
