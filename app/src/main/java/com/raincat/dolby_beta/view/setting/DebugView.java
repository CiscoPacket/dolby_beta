package com.raincat.dolby_beta.view.setting;

import android.content.Context;
import android.util.AttributeSet;

import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.view.BaseDialogItem;

/**
 * 详细版调试日志设置项
 * 支持点击直接打开应用与模块全量实时日志监控窗口，或点击右侧复选框开关
 */
public class DebugView extends BaseDialogItem {
    public DebugView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public DebugView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DebugView(Context context) {
        super(context);
    }

    @Override
    public void init(Context context, AttributeSet attrs) {
        super.init(context, attrs);
        title = SettingHelper.debug_title;
        key = SettingHelper.debug_key;
        updateSub();
        setData(true, SettingHelper.getInstance().getSetting(key));

        // 右侧复选框可单独点击切换记录状态
        checkBox.setClickable(true);
        checkBox.setOnClickListener(v -> {
            boolean isChecked = checkBox.isChecked();
            SettingHelper.getInstance().setSetting(key, isChecked);
            DebugLogger.onSettingChanged(context);
            updateSub();
            refresh();
            sendBroadcast(SettingHelper.refresh_setting);
        });

        // 点击文字行整体直接打开全屏实时日志监控
        setOnClickListener(view -> {
            LogViewerDialog.show(context);
        });

        // 长按同样打开日志监控窗口
        setOnLongClickListener(view -> {
            LogViewerDialog.show(context);
            return true;
        });
    }

    private void updateSub() {
        boolean enabled = SettingHelper.getInstance().getSetting(key);
        sub = SettingHelper.debug_sub
                + "\n状态：" + (enabled ? "已开启详细记录" : "已关闭")
                + " | 日志大小：" + DebugLogger.getLogFileSize()
                + "\n【点击此处直接查看应用与模块所有实时日志】";
    }

    @Override
    public void refresh() {
        super.refresh();
        updateSub();
        if (subView != null && sub != null) {
            subView.setText(sub);
        }
    }
}
