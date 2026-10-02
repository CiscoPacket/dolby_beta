package com.raincat.dolby_beta.helper;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.raincat.dolby_beta.utils.Tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 详细版调试日志与全局崩溃捕获工具类
 * 支持：
 * 1. 模块所有 Hook 日志实时捕获
 * 2. 应用所有原生日志 (Logcat --pid) 后台流式收集
 * 3. 内存环形缓冲区 (3000行) 秒开日志查看器
 * 4. 本地文件持久化 (/sdcard/Android/data/com.netease.cloudmusic/cache/dolby_debug.log)
 * 5. 一键导出至公共下载目录 (/sdcard/Download/dolby_debug.log)
 * 6. 全局未捕获异常堆栈抓取
 */
public class DebugLogger {
    private static final String TAG = "dolby_beta";
    private static File logFile = null;
    private static boolean crashHandlerInstalled = false;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private static final long MAX_LOG_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_BUFFER_LINES = 3000;

    private static final LinkedList<String> sLogBuffer = new LinkedList<>();
    private static volatile boolean sLogcatRunning = false;
    private static int sCaptureSession = 0;
    private static Thread sLogcatThread = null;
    private static Process sLogcatProcess = null;

    public static synchronized void init(Context context) {
        if (context == null) return;
        try {
            // 优先写入外部私有目录（免 Root 可通过文件管理器访问：/sdcard/Android/data/com.netease.cloudmusic/cache/）
            File extCache = context.getExternalCacheDir();
            if (extCache != null && (extCache.exists() || extCache.mkdirs())) {
                logFile = new File(extCache, "dolby_debug.log");
            }
            if (logFile == null || !logFile.getParentFile().canWrite()) {
                File cacheDir = context.getCacheDir();
                if (cacheDir != null) {
                    logFile = new File(cacheDir, "dolby_debug.log");
                }
            }

            installCrashHandler();

            // 根据调试开关状态决定是否开启收集与后台 Logcat
            onSettingChanged(context);
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] DebugLogger init error: " + t);
        }
    }

    public static synchronized void onSettingChanged(Context context) {
        if (isVerboseEnabled()) {
            if (logFile != null && !logFile.exists()) {
                String initMsg = "==================== [DOLBY DEBUG LOGGER INIT] ====================\n"
                        + "Time: " + DATE_FORMAT.format(new Date()) + "\n"
                        + "Package: " + (context != null ? context.getPackageName() : "null") + "\n"
                        + "Process: " + (context != null ? Tools.getCurrentProcessName(context) : "null") + "\n"
                        + "OS: Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                        + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.PRODUCT + ")\n"
                        + "LogPath: " + (logFile != null ? logFile.getAbsolutePath() : "null") + "\n"
                        + "====================================================================";
                writeLog("INFO", "DebugLogger", initMsg, null);
            }
            startLogcatCapture(context);
        } else {
            stopLogcatCapture();
        }
    }

    public static synchronized void startLogcatCapture(Context context) {
        if (!isVerboseEnabled()) return;
        if (sLogcatRunning) return;
        sLogcatRunning = true;
        final int gen = ++sCaptureSession;
        sLogcatThread = new Thread(() -> {
            Process proc = null;
            BufferedReader reader = null;
            try {
                int pid = android.os.Process.myPid();
                try {
                    proc = new ProcessBuilder("logcat", "-v", "time", "--pid=" + pid).start();
                } catch (Throwable t) {
                    proc = new ProcessBuilder("logcat", "-v", "time").start();
                }
                sLogcatProcess = proc;
                reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while (sLogcatRunning && gen == sCaptureSession && (line = reader.readLine()) != null) {
                    if (isVerboseEnabled()) {
                        appendExternalLog(line, false);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Throwable ignored) {}
                }
                if (proc != null) {
                    try { proc.destroy(); } catch (Throwable ignored) {}
                }
                synchronized (DebugLogger.class) {
                    // 仅当仍是本会话时才清理共享状态, 避免旧线程清掉 stop->start 后的新会话
                    if (gen == sCaptureSession) {
                        sLogcatProcess = null;
                        sLogcatRunning = false;
                    }
                }
            }
        }, "DolbyLogcatCapture");
        sLogcatThread.setDaemon(true);
        sLogcatThread.start();
    }

    public static synchronized void stopLogcatCapture() {
        sCaptureSession++;
        sLogcatRunning = false;
        if (sLogcatProcess != null) {
            try {
                sLogcatProcess.destroy();
            } catch (Throwable ignored) {
            }
            sLogcatProcess = null;
        }
        if (sLogcatThread != null) {
            try {
                sLogcatThread.interrupt();
            } catch (Throwable ignored) {
            }
            sLogcatThread = null;
        }
    }

    public static void installCrashHandler() {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        try {
            final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    String time = DATE_FORMAT.format(new Date());
                    String threadName = thread != null ? thread.getName() : "unknown";
                    long threadId = thread != null ? thread.getId() : -1;
                    String crashLog = "\n"
                            + "==================== [DOLBY CRASH CAUGHT] ====================\n"
                            + "Time: " + time + "\n"
                            + "Thread: " + threadName + " (id=" + threadId + ")\n"
                            + "Exception: " + (throwable != null ? throwable.getClass().getName() + ": " + throwable.getMessage() : "null") + "\n"
                            + "Stacktrace:\n" + Log.getStackTraceString(throwable) + "\n"
                            + "==============================================================\n";

                    XposedBridge.log("[dolby_beta][CRASH] " + crashLog);
                    // 崩溃日志无条件落盘, 不受调试开关门禁限制
                    appendExternalLog(crashLog, true);
                } catch (Throwable ignored) {
                } finally {
                    if (defaultHandler != null) {
                        defaultHandler.uncaughtException(thread, throwable);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] installCrashHandler failed: " + t);
        }
    }

    public static void d(String subTag, String msg) {
        writeLog("DEBUG", subTag, msg, null);
    }

    public static void i(String subTag, String msg) {
        writeLog("INFO", subTag, msg, null);
    }

    public static void w(String subTag, String msg) {
        writeLog("WARN", subTag, msg, null);
    }

    public static void e(String subTag, String msg, Throwable t) {
        writeLog("ERROR", subTag, msg, t);
    }

    public static void logEapi(String path, boolean isHit, boolean modified, String detail) {
        if (!isVerboseEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("path=").append(path);
        sb.append(" | hit=").append(isHit);
        sb.append(" | modified=").append(modified);
        if (detail != null && !detail.isEmpty()) {
            sb.append(" | ").append(detail);
        }
        writeLog("EAPI", "Intercept", sb.toString(), null);
    }

    public static boolean isVerboseEnabled() {
        try {
            SettingHelper helper = SettingHelper.getInstance();
            return helper != null && helper.isDebugMode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static synchronized void writeLog(String level, String subTag, String msg, Throwable t) {
        try {
            boolean isCrash = "CRASH".equals(level);
            if (!isCrash && !isVerboseEnabled()) {
                return;
            }

            String time = DATE_FORMAT.format(new Date());
            String formatted = String.format(Locale.getDefault(), "[%s][%s][%s][%s] %s", time, TAG, level, subTag, msg);
            if (t != null) {
                formatted += "\n" + Log.getStackTraceString(t);
            }
            XposedBridge.log(formatted);
            // 不再 println 回 logcat: 捕获线程会抓回本进程日志, 造成同一条日志文件双写与回环

            addToBuffer(formatted);
            writeRawToFile(formatted + "\n");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 记录 Logcat / 外部日志行，不向 Logcat 或 XposedBridge 再次打印以避免死循环。
     * force=true 用于崩溃堆栈等必须落盘的内容（不受调试开关门禁限制）；
     * 含本模块格式化标记的行一律跳过，防止捕获线程把 writeLog 已落盘的行再写一遍。
     */
    private static synchronized void appendExternalLog(String line, boolean force) {
        if (line == null) return;
        if (!force && !isVerboseEnabled()) return;
        if (line.contains("[" + TAG + "][")) return;
        addToBuffer(line);
        writeRawToFile(line + "\n");
    }

    private static void addToBuffer(String line) {
        synchronized (sLogBuffer) {
            if (sLogBuffer.size() >= MAX_BUFFER_LINES) {
                sLogBuffer.removeFirst();
            }
            sLogBuffer.add(line);
        }
    }

    public static List<String> getBufferedLogs() {
        synchronized (sLogBuffer) {
            return new ArrayList<>(sLogBuffer);
        }
    }

    public static List<String> readFullLogFile(int maxLines) {
        List<String> list = new ArrayList<>();
        if (logFile == null || !logFile.exists()) {
            return getBufferedLogs();
        }
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            LinkedList<String> queue = new LinkedList<>();
            while ((line = br.readLine()) != null) {
                if (queue.size() >= maxLines) {
                    queue.removeFirst();
                }
                queue.add(line);
            }
            list.addAll(queue);
        } catch (Throwable t) {
            return getBufferedLogs();
        }
        return list;
    }

    private static synchronized void writeRawToFile(String content) {
        if (logFile == null) return;
        try {
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                File oldFile = new File(logFile.getAbsolutePath() + ".old");
                if (oldFile.exists()) oldFile.delete();
                logFile.renameTo(oldFile);
            }
            try (FileWriter fw = new FileWriter(logFile, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.print(content);
                pw.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized boolean clearLog() {
        synchronized (sLogBuffer) {
            sLogBuffer.clear();
        }
        boolean ok = false;
        if (logFile != null && logFile.exists()) {
            ok = logFile.delete();
        }
        i("DebugLogger", "Logs cleared by user.");
        return ok;
    }

    public static String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "/sdcard/Android/data/com.netease.cloudmusic/cache/dolby_debug.log";
    }

    public static String getLogFileSize() {
        if (logFile == null || !logFile.exists()) return "0 KB";
        long bytes = logFile.length();
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0f);
        return String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0f * 1024.0f));
    }

    /**
     * 导出日志到公共下载目录 (/sdcard/Download/dolby_debug.log)
     */
    public static String exportToDownload(Context context) {
        if (logFile == null || !logFile.exists()) {
            return null;
        }
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs())) {
                File dest = new File(downloadDir, "dolby_debug.log");
                copyFile(logFile, dest);
                return dest.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }

        // Fallback: 外部私有 Cache 根目录
        try {
            if (context != null && context.getExternalCacheDir() != null) {
                File dest = new File(context.getExternalCacheDir().getParentFile(), "dolby_debug_exported.log");
                copyFile(logFile, dest);
                return dest.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return logFile.getAbsolutePath();
    }

    private static void copyFile(File src, File dest) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
    }
}
