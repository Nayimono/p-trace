package com.pnones.trace.logger;

import com.pnones.trace.config.TraceConfig;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * HTTP API를 통한 로그 전송 클래스
 * 비동기 방식으로 로그를 원격 HTTP 엔드포인트로 전송
 */
public class HttpApiSender {
    private static final boolean ENABLED = TraceConfig.getBoolean("http-api.enabled", false);
    private static final String ENDPOINT = TraceConfig.getString("http-api.endpoint", "http://localhost:8080/logs");
    private static final String LOG_TYPES = TraceConfig.getString("http-api.log-types", "BOTH");
    private static final int TIMEOUT_MS = TraceConfig.getInt("http-api.timeout-ms", 5000);
    private static final int RETRY_COUNT = TraceConfig.getInt("http-api.retry-count", 3);
    private static final int QUEUE_SIZE = TraceConfig.getInt("http-api.queue-size", 1000);
    private static final int BATCH_SIZE = TraceConfig.getInt("http-api.batch-size", 10);
    private static final long BATCH_DELAY_MS = TraceConfig.getLong("http-api.batch-delay-ms", 1000);
    private static final String AUTH_TOKEN = TraceConfig.getString("http-api.auth-token", "");
    private static final boolean ASYNC = TraceConfig.getBoolean("http-api.async", true);
    
    private static final BlockingQueue<LogMessage> queue = new LinkedBlockingQueue<>(QUEUE_SIZE);
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PTrace-HttpApiSender");
        t.setDaemon(true);
        return t;
    });
    
    private static volatile boolean running = false;
    private static final Object lock = new Object();
    
    // 초기화
    static {
        if (ENABLED) {
            running = true;
            if (ASYNC) {
                executor.submit(HttpApiSender::processBatch);
            }
            com.pnones.trace.util.DebugLogger.info("HttpApiSender enabled: " + ENDPOINT);
        }
    }
    
    /**
     * HTTP 로그를 API로 전송
     */
    public static void sendHttpLog(String message) {
        if (!ENABLED) return;
        if (!"HTTP".equalsIgnoreCase(LOG_TYPES) && !"BOTH".equalsIgnoreCase(LOG_TYPES)) return;
        send(message, "HTTP");
    }
    
    /**
     * SQL 로그를 API로 전송
     */
    public static void sendSqlLog(String message) {
        if (!ENABLED) return;
        if (!"SQL".equalsIgnoreCase(LOG_TYPES) && !"BOTH".equalsIgnoreCase(LOG_TYPES)) return;
        send(message, "SQL");
    }
    
    /**
     * 로그 메시지를 큐에 추가
     */
    private static void send(String message, String type) {
        if (!running) return;
        
        try {
            LogMessage logMsg = new LogMessage(message, type, System.currentTimeMillis());
            
            if (ASYNC) {
                // 큐에 추가 (용량 초과 시 오래된 것부터 제거)
                if (!queue.offer(logMsg)) {
                    queue.poll(); // 오래된 항목 제거
                    queue.offer(logMsg);
                }
            } else {
                // 동기 전송
                sendSingle(logMsg);
            }
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.error("HttpApiSender send failed", e);
        }
    }
    
    /**
     * 배치 처리: 여러 로그를 한 번에 전송
     */
    private static void processBatch() {
        java.util.List<LogMessage> batch = new java.util.ArrayList<>(BATCH_SIZE);
        
        while (running) {
            try {
                batch.clear();
                
                // 타임아웃까지 배치 크기만큼 수집
                LogMessage first = queue.poll(BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                    
                    if (!batch.isEmpty()) {
                        sendBatch(batch);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                com.pnones.trace.util.DebugLogger.error("HttpApiSender batch processing failed", e);
            }
        }
    }
    
    /**
     * 배치 전송
     */
    private static void sendBatch(java.util.List<LogMessage> batch) {
        String jsonArray = buildJsonArray(batch);
        
        for (int attempt = 0; attempt <= RETRY_COUNT; attempt++) {
            try {
                sendPost(jsonArray);
                return; // 성공
            } catch (Exception e) {
                if (attempt == RETRY_COUNT) {
                    com.pnones.trace.util.DebugLogger.error("HttpApiSender batch send failed after retries", e);
                }
                try {
                    Thread.sleep(100 * (attempt + 1)); // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    /**
     * 단일 메시지 전송 (동기)
     */
    private static void sendSingle(LogMessage msg) {
        try {
            sendPost(msg.message);
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.error("HttpApiSender single send failed", e);
        }
    }
    
    /**
     * HTTP POST 전송
     */
    private static void sendPost(String jsonPayload) throws Exception {
        URL url = new URL(ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "PTrace-Agent/1.0");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            
            // Authorization 헤더
            if (AUTH_TOKEN != null && !AUTH_TOKEN.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + AUTH_TOKEN);
            }
            
            conn.setDoOutput(true);
            
            // 요청 본문 전송
            byte[] jsonBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(jsonBytes.length));
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }
            
            // 응답 확인
            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                String errorMsg = readResponseBody(conn.getErrorStream());
                throw new Exception("HTTP " + responseCode + ": " + errorMsg);
            }
            
            // 성공 답변 읽기
            readResponseBody(conn.getInputStream());
            
        } finally {
            conn.disconnect();
        }
    }
    
    /**
     * 응답 본문 읽기
     */
    private static String readResponseBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
    
    /**
     * 로그 메시지를 JSON 배열로 변환
     */
    private static String buildJsonArray(java.util.List<LogMessage> batch) {
        StringBuilder sb = new StringBuilder("[");
        
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(",");
            LogMessage msg = batch.get(i);
            sb.append("{\"type\":\"").append(msg.type)
              .append("\",\"timestamp\":").append(msg.timestamp)
              .append(",\"data\":").append(msg.message)
              .append("}");
        }
        
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * 로그 메시지 내부 클래스
     */
    private static class LogMessage {
        String message;
        String type;
        long timestamp;
        
        LogMessage(String message, String type, long timestamp) {
            this.message = message;
            this.type = type;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * Queue 상태 확인
     */
    public static int getQueueSize() {
        return queue.size();
    }
    
    /**
     * 종료
     */
    public static void close() {
        running = false;
        try {
            // 남은 로그 전송
            if (!queue.isEmpty()) {
                java.util.List<LogMessage> remaining = new java.util.ArrayList<>();
                queue.drainTo(remaining);
                if (!remaining.isEmpty()) {
                    sendBatch(remaining);
                }
            }
            
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (Exception ignored) {}
    }
}
