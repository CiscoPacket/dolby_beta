package com.raincat.dolby_beta.view.beauty;

import android.content.Context;
import android.util.AttributeSet;

import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.view.BaseDialogItem;

import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/10/22
 *     desc   : 侧边栏精简
 *     version: 1.0
 * </pre>
 */

public class BeautySidebarHideItem extends BaseDialogItem {
    public BeautySidebarHideItem(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public BeautySidebarHideItem(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BeautySidebarHideItem(Context context) {
        super(context);
    }

    public void initData(LinkedHashMap<String, String> sidebarMap, HashMap<String, Boolean> sidebarSettingMap, String sidebarKey) {
        String name = sidebarMap != null ? sidebarMap.get(sidebarKey) : "";
        title = (name != null ? name : "") + "(" + sidebarKey + ")";
        key = sidebarKey;
        boolean isChecked = sidebarSettingMap != null && Boolean.TRUE.equals(sidebarSettingMap.get(sidebarKey));
        setData(true, isChecked);
        setOnClickListener(view -> {
            boolean newChecked = !checkBox.isChecked();
            if (sidebarSettingMap != null) {
                sidebarSettingMap.put(sidebarKey, newChecked);
            }
            SettingHelper.getInstance().setSidebarSetting(key, newChecked);
            setData(true, newChecked);
            sendBroadcast(SettingHelper.refresh_setting);
        });
    }

    @Override
    public void refresh() {
        super.refresh();
        if (key != null && checkBox != null && checkBox.getVisibility() == VISIBLE) {
            boolean isChecked = SettingHelper.getInstance().getSetting(key);
            checkBox.setChecked(isChecked);
        }
    }
}
