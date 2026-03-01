package com.pnones.trace.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TraceConfig {
    private static Properties props = new Properties();
    private static String configPath = null;
    private static ScheduledExecutorService scheduler;

    public static void load(String agentArgs) {
        // initial load and start auto-reload with default interval
        detectConfigPath();
        doLoad();
        int interval = getInt("pnones.trace.reload-seconds", 10);
        startAutoReload(interval);
    }

    private static void detectConfigPath() {
        String path = System.getProperty("pnonestrace.config");
        if (path == null) path = System.getProperty("pnones.trace.config");
        if (path != null && !path.trim().isEmpty()) {
            configPath = path.trim();
            return;
        }
        File f = new File(System.getProperty("user.dir"), "pnonestrace.properties");
        if (f.exists()) configPath = f.getAbsolutePath();
        else configPath = null;
    }

    private static synchronized void doLoad() {
        try {
            InputStream in = null;
            if (configPath != null) {
                try { in = new FileInputStream(configPath); } catch (Exception e) { in = null; }
            }
            if (in == null) in = TraceConfig.class.getClassLoader().getResourceAsStream("pnonestrace.properties");
            if (in != null) {
                Properties p2 = new Properties();
                p2.load(in);
                in.close();
                props = p2;
                // ensure log directory and initial files exist
                try {
                    String root = props.getProperty("log.dir", "./logs");
                    java.io.File dir = new java.io.File(root);
                    if (!dir.exists()) dir.mkdirs();
                    String format = props.getProperty("log.format", "BOTH");
                    String httpBase = props.getProperty("http.log.filename", "access-trace");
                    if (format.equalsIgnoreCase("JSON") || format.equalsIgnoreCase("BOTH")) {
                        new java.io.File(dir, httpBase + ".jsonl").createNewFile();
                    }
                    if (format.equalsIgnoreCase("TEXT") || format.equalsIgnoreCase("BOTH")) {
                        new java.io.File(dir, httpBase + ".log").createNewFile();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            // ignore, keep previous props
        }
    }

    public static synchronized void startAutoReload(int seconds) {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pnonestrace");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(TraceConfig::doLoad, seconds, seconds, TimeUnit.SECONDS);
    }

    public static synchronized void stopAutoReload() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public static String getString(String key, String def) { return props.getProperty(key, def); }

    public static int getInt(String key, int def) {
        String v = props.getProperty(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    public static long getLong(String key, long def) {
        String v = props.getProperty(key);
        if (v == null) return def;
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return def; }
    }

    public static boolean getBoolean(String key, boolean def) {
        String v = props.getProperty(key);
        if (v == null) return def;
        return v.trim().equalsIgnoreCase("true") || v.trim().equalsIgnoreCase("yes") || v.trim().equals("1");
    }

    public static String[] getList(String key, String sep) {
        String v = props.getProperty(key);
        if (v == null || v.trim().isEmpty()) return new String[0];
        String[] arr = v.split(sep);
        for (int i = 0; i < arr.length; i++) arr[i] = arr[i].trim();
        return arr;
    }
}
