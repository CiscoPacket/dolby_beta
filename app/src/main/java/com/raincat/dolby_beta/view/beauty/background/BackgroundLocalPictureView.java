package com.raincat.dolby_beta.view.beauty.background;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.Toast;

import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.hook.SettingHook;
import com.raincat.dolby_beta.view.BaseDialogItem;

import java.io.File;

/**
 * 播放界面背景 - 本地图片选择项
 */
public class BackgroundLocalPictureView extends BaseDialogItem {
    public BackgroundLocalPictureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public BackgroundLocalPictureView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BackgroundLocalPictureView(Context context) {
        super(context);
    }

    @Override
    public void init(Context context, AttributeSet attrs) {
        super.init(context, attrs);
        title = "选择本地图片";
        key = "β_background_local_pic_key";
        setData(false, false);
        updateSub();

        setOnClickListener(view -> {
            new AlertDialog.Builder(context, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("本地背景图片")
                    .setItems(new String[]{"从相册 / 文件选择", "清除背景图片"}, (dialog, which) -> {
                        if (which == 0) {
                            if (context instanceof Activity) {
                                SettingHook.startImagePicker((Activity) context);
                            } else {
                                Toast.makeText(context, "当前环境不支持打开系统选择器", Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 1) {
                            SettingHelper.getInstance().setPictureUrl("");
                            updateSub();
                            refresh();
                            sendBroadcast(SettingHelper.refresh_setting);
                            Toast.makeText(context, "已清除背景图片设置", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    private void updateSub() {
        String path = SettingHelper.getInstance().getPictureUrl();
        if (TextUtils.isEmpty(path)) {
            sub = "点击从系统相册或文件选择背景图\n当前：未选择本地图片";
        } else if (path.startsWith("/") || path.startsWith("file://")) {
            String cleanPath = path.startsWith("file://") ? path.substring(7) : path;
            File f = new File(cleanPath);
            if (f.exists()) {
                sub = "点击更换本地图片\n当前：" + f.getName() + " (" + cleanPath + ")";
            } else {
                sub = "本地文件不存在: " + cleanPath;
            }
        } else {
            sub = "当前使用的是网络图片: " + path + "\n（点击可选择本地图片覆盖）";
        }
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
