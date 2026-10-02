package com.raincat.dolby_beta.view.setting;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.utils.Tools;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 详细版调试日志查看器对话框
 * 支持：
 * - 实时查看应用与模块全量日志
 * - 分类过滤（全部 / 仅模块 / 错误崩溃 / 应用日志）
 * - 实时关键字搜索过滤
 * - 一键复制至剪贴板
 * - 一键导出至公共下载目录 (/sdcard/Download/dolby_debug.log)
 * - 清空日志
 */
public class LogViewerDialog {

    private enum FilterMode {
        ALL, MODULE_ONLY, ERROR_ONLY, APP_ONLY
    }

    public static void show(Context context) {
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1E1E);
        int pad = Tools.dp2px(context, 12);
        root.setPadding(pad, pad, pad, pad);

        // 1. 顶部标题栏与文件状态
        TextView tvTitle = new TextView(context);
        tvTitle.setText("应用与模块 调试日志");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvStatus = new TextView(context);
        tvStatus.setTextColor(0xFFB0B0B0);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvStatus.setPadding(0, Tools.dp2px(context, 2), 0, Tools.dp2px(context, 6));
        root.addView(tvStatus);

        // 2. 快捷过滤类型按钮栏
        HorizontalScrollView filterScroll = new HorizontalScrollView(context);
        LinearLayout filterBar = new LinearLayout(context);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterScroll.addView(filterBar);
        root.addView(filterScroll);

        final FilterMode[] currentFilter = {FilterMode.ALL};
        final String[] searchKeyword = {""};

        Button btnAll = createFilterButton(context, "全部", true);
        Button btnModule = createFilterButton(context, "模块日志", false);
        Button btnError = createFilterButton(context, "错误/崩溃", false);
        Button btnApp = createFilterButton(context, "应用日志", false);

        filterBar.addView(btnAll);
        filterBar.addView(btnModule);
        filterBar.addView(btnError);
        filterBar.addView(btnApp);

        // 3. 关键字搜索输入框
        EditText etSearch = new EditText(context);
        etSearch.setHint("输入关键字实时搜索过滤...");
        etSearch.setHintTextColor(0xFF707070);
        etSearch.setTextColor(Color.WHITE);
        etSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        etSearch.setBackgroundColor(0xFF2D2D2D);
        etSearch.setPadding(Tools.dp2px(context, 10), Tools.dp2px(context, 8), Tools.dp2px(context, 10), Tools.dp2px(context, 8));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = Tools.dp2px(context, 6);
        searchLp.bottomMargin = Tools.dp2px(context, 6);
        etSearch.setLayoutParams(searchLp);
        root.addView(etSearch);

        // 4. 日志文本滚动容器
        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(0xFF141414);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollView.setLayoutParams(scrollLp);

        TextView tvContent = new TextView(context);
        tvContent.setTextColor(0xFFDCDCDC);
        tvContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvContent.setTypeface(Typeface.MONOSPACE);
        tvContent.setTextIsSelectable(true);
        tvContent.setPadding(Tools.dp2px(context, 8), Tools.dp2px(context, 8), Tools.dp2px(context, 8), Tools.dp2px(context, 8));
        scrollView.addView(tvContent);
        root.addView(scrollView);

        // 5. 底部操作按钮栏
        HorizontalScrollView bottomScroll = new HorizontalScrollView(context);
        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomScroll.addView(bottomBar);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.topMargin = Tools.dp2px(context, 8);
        bottomScroll.setLayoutParams(bottomLp);
        root.addView(bottomScroll);

        Button btnRefresh = createActionButton(context, "刷新", 0xFF007ACC);
        Button btnCopy = createActionButton(context, "复制全部", 0xFF388E3C);
        Button btnExport = createActionButton(context, "导出到Download", 0xFFE65100);
        Button btnClear = createActionButton(context, "清空日志", 0xFFD32F2F);
        Button btnClose = createActionButton(context, "关闭", 0xFF616161);

        bottomBar.addView(btnRefresh);
        bottomBar.addView(btnCopy);
        bottomBar.addView(btnExport);
        bottomBar.addView(btnClear);
        bottomBar.addView(btnClose);

        AlertDialog dialog = builder.setView(root).create();

        // 日志读取放后台线程 (文件可达 10MB), UI 线程只做过滤渲染;
        // token 递增作过期标记, 输入防抖 250ms, 关闭时取消未完成任务
        final Handler uiHandler = new Handler(Looper.getMainLooper());
        final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
        final AtomicInteger renderToken = new AtomicInteger(0);

        // 实际渲染: 后台读文件 -> UI 线程过滤渲染; token 过期检查防止旧结果覆盖新结果
        final Runnable doRender = () -> {
            final int token = renderToken.incrementAndGet();
            ioExecutor.execute(() -> {
                List<String> rawLogs = DebugLogger.readFullLogFile(3000);
                uiHandler.post(() -> {
                    if (token != renderToken.get()) return;
                    applyFilteredLogs(tvContent, tvStatus, scrollView, currentFilter[0], searchKeyword[0], rawLogs);
                });
            });
        };
        // 输入防抖: 250ms 内的连续按键只触发一次渲染
        final Runnable[] pendingDebounce = new Runnable[1];
        final Runnable renderDebounced = () -> {
            if (pendingDebounce[0] != null) {
                uiHandler.removeCallbacks(pendingDebounce[0]);
            }
            pendingDebounce[0] = doRender;
            uiHandler.postDelayed(doRender, 250L);
        };
        // 渲染逻辑 (按钮/过滤立即执行, 取消未决的防抖任务)
        final Runnable renderLogs = () -> {
            if (pendingDebounce[0] != null) {
                uiHandler.removeCallbacks(pendingDebounce[0]);
                pendingDebounce[0] = null;
            }
            doRender.run();
        };

        // 按钮事件绑定
        View.OnClickListener filterClickListener = v -> {
            btnAll.setBackgroundColor(0xFF333333);
            btnModule.setBackgroundColor(0xFF333333);
            btnError.setBackgroundColor(0xFF333333);
            btnApp.setBackgroundColor(0xFF333333);

            if (v == btnAll) {
                currentFilter[0] = FilterMode.ALL;
                btnAll.setBackgroundColor(0xFF007ACC);
            } else if (v == btnModule) {
                currentFilter[0] = FilterMode.MODULE_ONLY;
                btnModule.setBackgroundColor(0xFF007ACC);
            } else if (v == btnError) {
                currentFilter[0] = FilterMode.ERROR_ONLY;
                btnError.setBackgroundColor(0xFF007ACC);
            } else if (v == btnApp) {
                currentFilter[0] = FilterMode.APP_ONLY;
                btnApp.setBackgroundColor(0xFF007ACC);
            }
            renderLogs.run();
        };

        btnAll.setOnClickListener(filterClickListener);
        btnModule.setOnClickListener(filterClickListener);
        btnError.setOnClickListener(filterClickListener);
        btnApp.setOnClickListener(filterClickListener);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchKeyword[0] = s != null ? s.toString().trim() : "";
                // 输入防抖, 避免每个按键都重读文件+全量渲染
                renderDebounced.run();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnRefresh.setOnClickListener(v -> renderLogs.run());

        btnCopy.setOnClickListener(v -> {
            CharSequence text = tvContent.getText();
            if (text != null && text.length() > 0) {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    ClipData clip = ClipData.newPlainText("dolby_log", text);
                    cm.setPrimaryClip(clip);
                    Toast.makeText(context, "已复制 " + text.length() + " 字符日志到剪贴板", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(context, "当前日志为空", Toast.LENGTH_SHORT).show();
            }
        });

        btnExport.setOnClickListener(v -> {
            String path = DebugLogger.exportToDownload(context);
            if (path != null) {
                Toast.makeText(context, "日志已成功导出至：\n" + path, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "导出失败：源日志文件不存在", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            new AlertDialog.Builder(context, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("确认清空日志")
                    .setMessage("确定要清空当前的全部日志记录吗？")
                    .setPositiveButton("清空", (d, w) -> {
                        DebugLogger.clearLog();
                        renderLogs.run();
                        Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnDismissListener(d -> {
            // 取消未完成渲染并释放后台线程
            renderToken.incrementAndGet();
            ioExecutor.shutdown();
        });

        dialog.show();
        renderLogs.run();
    }

    /** UI 线程执行: 过滤并渲染日志行 */
    private static void applyFilteredLogs(TextView tvContent, TextView tvStatus, ScrollView scrollView,
                                          FilterMode filterMode, String keyword, List<String> rawLogs) {
        StringBuilder sb = new StringBuilder();
        int matched = 0;
        String kw = keyword == null ? "" : keyword.toLowerCase(Locale.getDefault());

        for (String line : rawLogs) {
            if (line == null) continue;
            String lower = line.toLowerCase(Locale.getDefault());

            // 模式过滤
            boolean modeMatch = true;
            switch (filterMode) {
                case MODULE_ONLY:
                    modeMatch = line.contains("dolby_beta");
                    break;
                case ERROR_ONLY:
                    modeMatch = lower.contains("error") || lower.contains("crash") || lower.contains("exception") || lower.contains("fatal");
                    break;
                case APP_ONLY:
                    modeMatch = !line.contains("dolby_beta");
                    break;
                case ALL:
                default:
                    modeMatch = true;
                    break;
            }
            if (!modeMatch) continue;

            // 关键字搜索
            if (!kw.isEmpty() && !lower.contains(kw)) {
                continue;
            }

            sb.append(line).append("\n");
            matched++;
        }

        tvContent.setText(sb.toString());
        tvStatus.setText(String.format(Locale.getDefault(), "文件大小: %s | 当前展示: %d 行\n路径: %s",
                DebugLogger.getLogFileSize(), matched, DebugLogger.getLogFilePath()));

        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private static Button createFilterButton(Context context, String text, boolean isSelected) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setBackgroundColor(isSelected ? 0xFF007ACC : 0xFF333333);
        int padH = Tools.dp2px(context, 12);
        int padV = Tools.dp2px(context, 6);
        btn.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Tools.dp2px(context, 34));
        lp.rightMargin = Tools.dp2px(context, 6);
        btn.setLayoutParams(lp);
        return btn;
    }

    private static Button createActionButton(Context context, String text, int color) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setBackgroundColor(color);
        int padH = Tools.dp2px(context, 12);
        int padV = Tools.dp2px(context, 6);
        btn.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Tools.dp2px(context, 36));
        lp.rightMargin = Tools.dp2px(context, 8);
        btn.setLayoutParams(lp);
        return btn;
    }
}
