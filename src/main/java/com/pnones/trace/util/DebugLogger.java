package com.pnones.trace.util;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DebugLogger {
    private static final Object lock = new Object();
    private static long lastConfigCheck = 0;
    private static boolean debugEnabled = false;
    private static boolean printToStderr = false;

    private static void checkConfig() {
        long now = System.currentTimeMillis();
        // Check config every 5 seconds to allow hot-reload
        if (now - lastConfigCheck > 5000) {
            lastConfigCheck = now;
            debugEnabled = Boolean.parseBoolean(
                com.pnones.trace.config.TraceConfig.getString("trace.debug.enabled", "false")
            );
            printToStderr = Boolean.parseBoolean(
                com.pnones.trace.config.TraceConfig.getString("trace.debug.print-stderr", "false")
            );
        }
    }

    public static void log(String msg) {
        log(msg, null);
    }

    public static void log(String msg, Throwable t) {
        checkConfig();
        
        try {
            String root = com.pnones.trace.config.TraceConfig.getString("log.dir", "./logs");
            File dir = new File(root);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "agent-debug.log");
            synchronized (lock) {
                try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                    String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
                    String tn = Thread.currentThread().getName();
                    pw.println(ts + " [" + tn + "] - " + msg);
                    if (t != null) {
                        t.printStackTrace(pw);
                    }
                    pw.flush();
                }
                
                // Print to stderr if enabled
                if (printToStderr && debugEnabled) {
                    System.err.println("[DEBUG] " + msg);
                    if (t != null) {
                        t.printStackTrace(System.err);
                    }
                }
            }
        } catch (Throwable e) {
            // Silently ignore failures to avoid noise
        }
    }

    /**
     * 편의 메소드: 문자열만 기록
     */
    public static void info(String msg) {
        log("[INFO] " + msg);
    }

    /**
     * 편의 메소드: 에러 기록
     */
    public static void error(String msg, Throwable t) {
        log("[ERROR] " + msg, t);
    }

    /**
     * 편의 메소드: 변수/값 기록
     */
    public static void debug(String key, Object value) {
        log("[DEBUG] " + key + "=" + value);
    }

    /**
     * 공개 상태 메소드: 디버깅 활성화 여부
     */
    public static boolean isEnabled() {
        checkConfig();
        return debugEnabled;
    }
}
