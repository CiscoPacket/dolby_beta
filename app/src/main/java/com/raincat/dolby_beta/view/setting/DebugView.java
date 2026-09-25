package com.raincat.dolby_beta.view.setting;

import android.app.AlertDialog;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;

import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.view.BaseDialogItem;

/**
 * 调试模式设置项
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

        setOnClickListener(view -> {
            SettingHelper.getInstance().setSetting(key, !checkBox.isChecked());
            updateSub();
            refresh();
            sendBroadcast(SettingHelper.refresh_setting);
        });

        setOnLongClickListener(view -> {
            new AlertDialog.Builder(context, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("调试日志选项")
                    .setMessage("日志路径：\n" + DebugLogger.getLogFilePath() + "\n\n是否清空当前日志文件？")
                    .setPositiveButton("清空日志", (dialog, which) -> {
                        boolean ok = DebugLogger.clearLog();
                        Toast.makeText(context, ok ? "已清空调试日志" : "日志文件不存在或已被清空", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
    }

    private void updateSub() {
        boolean enabled = SettingHelper.getInstance().getSetting(key);
        sub = SettingHelper.debug_sub
                + "\n状态：" + (enabled ? "已开启" : "已关闭") + "（长按可管理/清空日志）"
                + "\n路径：" + DebugLogger.getLogFilePath();
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
