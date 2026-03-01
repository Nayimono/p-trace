package com.pnones.trace.logger;

public class HttpTraceLogger {
    private final RollingFileLogger jsonWriter;
    private final RollingFileLogger textWriter;
    private final boolean perThread;
    private final boolean partitionByThreadSession;
    private final String rootDir;
    private final String baseName;
    private final String format;
    private final java.util.concurrent.ConcurrentMap<String, RollingFileLogger> partitionJsonWriters = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, RollingFileLogger> partitionTextWriters = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Long, RollingFileLogger> threadJsonWriters = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Long, RollingFileLogger> threadTextWriters = new java.util.concurrent.ConcurrentHashMap<>();
    private final RollingFileLogger requestBodyWriter;
    private final RollingFileLogger responseBodyWriter;
    private final java.util.concurrent.ConcurrentMap<Long, RollingFileLogger> threadRequestBodyWriters = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Long, RollingFileLogger> threadResponseBodyWriters = new java.util.concurrent.ConcurrentHashMap<>();
    private final com.google.gson.Gson prettyGson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

    public HttpTraceLogger() {
        com.pnones.trace.util.DebugLogger.info("HttpTraceLogger initializing...");
        String root = com.pnones.trace.config.TraceConfig.getString("log.dir", "./logs");
        String base = com.pnones.trace.config.TraceConfig.getString("http.log.filename", "access-trace");
        String format = com.pnones.trace.config.TraceConfig.getString("log.format", "BOTH");
        this.perThread = com.pnones.trace.config.TraceConfig.getBoolean("log.per-thread", false);
        this.partitionByThreadSession = com.pnones.trace.config.TraceConfig.getBoolean("http.log.partition.by-thread-session", false);
        this.rootDir = root;
        this.baseName = base;
        this.format = format;
        
        // 반드시 로그 디렉토리 생성 (절대/상대경로 모두 대응)
        try {
            java.io.File logDir = new java.io.File(root);
            if (!logDir.exists()) {
                if (logDir.mkdirs()) {
                    com.pnones.trace.util.DebugLogger.debug("HttpTraceLogger dir", logDir.getAbsolutePath());
                } else {
                    com.pnones.trace.util.DebugLogger.error("HttpTraceLogger mkdir failed", new Exception("Cannot create: " + logDir.getAbsolutePath()));
                }
            }
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.error("HttpTraceLogger init failed", e);
        }
        
        com.pnones.trace.util.DebugLogger.debug("HttpTraceLogger", "dir=" + new java.io.File(root).getAbsolutePath() + ", format=" + format);
        if (!perThread) {
            if (format.equalsIgnoreCase("JSON") || format.equalsIgnoreCase("BOTH")) {
                String path = root + "/" + base + ".jsonl";
                com.pnones.trace.util.DebugLogger.debug("HttpTraceLogger JSON", path);
                jsonWriter = new RollingFileLogger(path);
            } else jsonWriter = null;
            if (format.equalsIgnoreCase("TEXT") || format.equalsIgnoreCase("BOTH")) {
                String path = root + "/" + base + ".log";
                com.pnones.trace.util.DebugLogger.debug("HttpTraceLogger TEXT", path);
                textWriter = new RollingFileLogger(path);
            } else textWriter = null;
            // Request/Response body는 메인 로그에 포함되므로 별도 파일 생성 안함
            requestBodyWriter = null;
            responseBodyWriter = null;
        } else {
            jsonWriter = null;
            textWriter = null;
            requestBodyWriter = null;
            responseBodyWriter = null;
        }
    }

    public void log(String json) {
        com.pnones.trace.util.DebugLogger.debug("HttpTraceLogger.log", json.substring(0, Math.min(120, json.length())) + "...");
        com.pnones.trace.util.DebugLogger.debug("HttpTraceLogger writers", "json=" + (jsonWriter != null ? "OK" : "NULL") + ", text=" + (textWriter != null ? "OK" : "NULL"));
        if (partitionByThreadSession) {
            String partitionKey = buildPartitionKey(json);
            if (format.equalsIgnoreCase("JSON") || format.equalsIgnoreCase("BOTH")) {
                partitionJsonWriters.computeIfAbsent(partitionKey, k -> new RollingFileLogger(rootDir + "/" + k + ".jsonl")).append(json);
            }
            if (format.equalsIgnoreCase("TEXT") || format.equalsIgnoreCase("BOTH")) {
                partitionTextWriters.computeIfAbsent(partitionKey, k -> new RollingFileLogger(rootDir + "/" + k + ".log")).append(toPrettyJson(json));
            }
        } else if (perThread) {
            long tid = Thread.currentThread().getId();
            if (format.equalsIgnoreCase("JSON") || format.equalsIgnoreCase("BOTH")) {
                threadJsonWriters.computeIfAbsent(tid, k -> new RollingFileLogger(rootDir + "/" + baseName + "-thread-" + k + ".jsonl")).append(json);
            }
            if (format.equalsIgnoreCase("TEXT") || format.equalsIgnoreCase("BOTH")) {
                threadTextWriters.computeIfAbsent(tid, k -> new RollingFileLogger(rootDir + "/" + baseName + "-thread-" + k + ".log")).append(toPrettyJson(json));
            }
        } else {
            if (jsonWriter != null) {
                com.pnones.trace.util.DebugLogger.debug("HttpTraceLogger", "writing to jsonWriter");
                jsonWriter.append(json);
            } else {
                com.pnones.trace.util.DebugLogger.error("HttpTraceLogger jsonWriter is null", null);
            }
            if (textWriter != null) {
                textWriter.append(toPrettyJson(json));
            }
        }
        
        // Send to Syslog if enabled
        try {
            SyslogSender.sendHttpLog(json);
        } catch (Throwable ignored) {}
        
        // Send to HTTP API if enabled
        try {
            HttpApiSender.sendHttpLog(json);
        } catch (Throwable ignored) {}
    }

    private String toPrettyJson(String json) {
        try {
            Object parsed = new com.google.gson.JsonParser().parse(json);
            return prettyGson.toJson(parsed);
        } catch (Throwable ignored) {
            return json;
        }
    }

    private String buildPartitionKey(String json) {
        String traceName = "trace";
        String thread = "thread";
        String sessionId = "nosession";
        try {
            com.google.gson.JsonElement rootEl = com.google.gson.JsonParser.parseString(json);
            if (rootEl != null && rootEl.isJsonObject()) {
                com.google.gson.JsonObject obj = rootEl.getAsJsonObject();
                if (obj.has("traceName") && !obj.get("traceName").isJsonNull()) {
                    traceName = obj.get("traceName").getAsString();
                }
                if (obj.has("threadId") && !obj.get("threadId").isJsonNull()) {
                    thread = String.valueOf(obj.get("threadId").getAsLong());
                } else if (obj.has("threadName") && !obj.get("threadName").isJsonNull()) {
                    thread = obj.get("threadName").getAsString();
                }
                if (obj.has("sessionId") && !obj.get("sessionId").isJsonNull()) {
                    String sid = obj.get("sessionId").getAsString();
                    if (sid != null && !sid.trim().isEmpty()) {
                        sessionId = sid;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return sanitizeFilePart(traceName) + "-" + sanitizeFilePart(thread) + "-" + sanitizeFilePart(sessionId);
    }

    private String sanitizeFilePart(String s) {
        if (s == null) return "unknown";
        String v = s.trim();
        if (v.isEmpty()) return "unknown";
        return v.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public void logBodies(String requestBody, String responseBody) {
        // Request/Response body는 메인 로그에 포함되므로 별도 파일 로깅 안함
        return;
        /*
        long tid = Thread.currentThread().getId();
        if (perThread) {
            String root = com.pnones.trace.config.TraceConfig.getString("log.dir", "./logs");
            String base = com.pnones.trace.config.TraceConfig.getString("http.log.filename", "access-trace");
            String reqName = com.pnones.trace.config.TraceConfig.getString("http.request.body.filename", base + "-request-body.log");
            String respName = com.pnones.trace.config.TraceConfig.getString("http.response.body.filename", base + "-response-body.log");
            if (requestBody != null) threadRequestBodyWriters.computeIfAbsent(tid, k -> new RollingFileLogger(root + "/" + reqName.replace(".log","-thread-"+k+".log"))).append(requestBody);
            if (responseBody != null) threadResponseBodyWriters.computeIfAbsent(tid, k -> new RollingFileLogger(root + "/" + respName.replace(".log","-thread-"+k+".log"))).append(responseBody);
        } else {
            if (requestBodyWriter != null && requestBody != null) requestBodyWriter.append(requestBody);
            if (responseBodyWriter != null && responseBody != null) responseBodyWriter.append(responseBody);
        }
        */
    }
}
