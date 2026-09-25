package com.raincat.dolby_beta.helper;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 调试日志与全局崩溃捕获工具类
 */
public class DebugLogger {
    private static final String TAG = "dolby_beta";
    private static File logFile = null;
    private static boolean crashHandlerInstalled = false;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5MB

    public static synchronized void init(Context context) {
        if (context == null) return;
        try {
            File cacheDir = context.getCacheDir();
            if (cacheDir != null) {
                logFile = new File(cacheDir, "dolby_debug.log");
            }
            if (logFile == null || !logFile.getParentFile().canWrite()) {
                File extCache = context.getExternalCacheDir();
                if (extCache != null) {
                    logFile = new File(extCache, "dolby_debug.log");
                }
            }
            installCrashHandler();
            i("DebugLogger", "Initialized. Log path: " + (logFile != null ? logFile.getAbsolutePath() : "null"));
        } catch (Throwable t) {
            XposedBridge.log("[dolby_beta] DebugLogger init error: " + t);
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
                    writeRawToFile(crashLog);
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
        if (isVerboseEnabled()) {
            writeLog("DEBUG", subTag, msg, null);
        }
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

    private static boolean isVerboseEnabled() {
        try {
            SettingHelper helper = SettingHelper.getInstance();
            return helper != null && helper.isDebugMode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static synchronized void writeLog(String level, String subTag, String msg, Throwable t) {
        try {
            String time = DATE_FORMAT.format(new Date());
            String formatted = String.format(Locale.getDefault(), "[%s][%s][%s][%s] %s", time, TAG, level, subTag, msg);
            if (t != null) {
                formatted += "\n" + Log.getStackTraceString(t);
            }
            XposedBridge.log(formatted);
            writeRawToFile(formatted + "\n");
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void writeRawToFile(String content) {
        if (logFile == null) return;
        try {
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                // Rotate / truncate
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
        if (logFile != null && logFile.exists()) {
            return logFile.delete();
        }
        return false;
    }

    public static String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "/data/data/com.netease.cloudmusic/cache/dolby_debug.log";
    }
}
