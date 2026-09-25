package com.raincat.dolby_beta.view.beauty;

import android.content.Context;
import android.util.AttributeSet;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.view.BaseDialogItem;

/**
 * <pre>
 *     author : luoxingran
 *     e-mail : szb5845201314@gmail.com
 *     time   : 2023/08/22
 *     desc   : 播放界面背景
 *     version: 1.0
 * </pre>
 */

public class PlayerBackgroundView extends BaseDialogItem {
    public PlayerBackgroundView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public PlayerBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PlayerBackgroundView(Context context) {
        super(context);
    }

    @Override
    public void init(Context context, AttributeSet attrs) {
        super.init(context, attrs);
        title = SettingHelper.background_title;
        key = SettingHelper.background_key;
        updateSub();
        setData(false, false);

        setOnClickListener(view -> {
            sendBroadcast(SettingHelper.background_setting);
        });
    }

    private void updateSub() {
        boolean enabled = SettingHelper.getInstance().getSetting(SettingHelper.beauty_background_key);
        String url = SettingHelper.getInstance().getPictureUrl();
        if (!enabled) {
            sub = "当前：未启用";
        } else if (android.text.TextUtils.isEmpty(url)) {
            sub = "当前：已启用（未设置图片）";
        } else if (url.startsWith("/") || url.startsWith("file://")) {
            String clean = url.startsWith("file://") ? url.substring(7) : url;
            sub = "当前：本地图片 (" + new java.io.File(clean).getName() + ")";
        } else {
            sub = "当前：网络图片 (" + url + ")";
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
