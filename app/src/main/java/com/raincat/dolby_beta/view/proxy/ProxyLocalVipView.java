package com.raincat.dolby_beta.view.proxy;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;

import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.view.BaseDialogItem;

/**
 * UnblockNeteaseMusic ENABLE_LOCAL_VIP
 */
public class ProxyLocalVipView extends BaseDialogItem {
    public ProxyLocalVipView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public ProxyLocalVipView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ProxyLocalVipView(Context context) {
        super(context);
    }

    @Override
    public void init(Context context, AttributeSet attrs) {
        super.init(context, attrs);
        title = SettingHelper.local_vip_title;
        updateSub();
        setData(false, false);

        setOnClickListener(view -> {
            String current = SettingHelper.getInstance().getLocalVip();
            int checked = 0;
            if ("svip".equalsIgnoreCase(current)) {
                checked = 2;
            } else if ("cvip".equalsIgnoreCase(current) || "true".equalsIgnoreCase(current)) {
                checked = 1;
            } else {
                checked = 0;
            }
            final String[] labels = {"关闭", "CVIP（true）", "SVIP"};
            final String[] values = {"off", "cvip", "svip"};
            new AlertDialog.Builder(context, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle(SettingHelper.local_vip_title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        SettingHelper.getInstance().setLocalVip(values[which]);
                        refresh();
                        sendBroadcast(SettingHelper.refresh_setting);
                        dialog.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    @Override
    public void refresh() {
        super.refresh();
        updateSub();
        if (subView != null && !TextUtils.isEmpty(sub)) {
            subView.setText(sub);
            subView.setVisibility(VISIBLE);
        }
    }

    private void updateSub() {
        String value = SettingHelper.getInstance().getLocalVip();
        if (TextUtils.isEmpty(value) || "off".equalsIgnoreCase(value)) {
            sub = SettingHelper.local_vip_sub + "\n当前：关闭";
        } else if ("svip".equalsIgnoreCase(value)) {
            sub = SettingHelper.local_vip_sub + "\n当前：SVIP";
        } else {
            sub = SettingHelper.local_vip_sub + "\n当前：CVIP";
        }
    }
}
