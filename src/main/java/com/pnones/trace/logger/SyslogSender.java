package com.pnones.trace.logger;

import com.pnones.trace.config.TraceConfig;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * Syslog 메시지 전송 클래스
 * RFC3164 (BSD Syslog) 및 RFC5424 (IETF Syslog) 지원
 */
public class SyslogSender {
    private static final boolean ENABLED = TraceConfig.getBoolean("syslog.enabled", false);
    private static final String HOST = TraceConfig.getString("syslog.host", "localhost");
    private static final int PORT = TraceConfig.getInt("syslog.port", 514);
    private static final String PROTOCOL = TraceConfig.getString("syslog.protocol", "UDP");
    private static final int FACILITY = TraceConfig.getInt("syslog.facility", 16); // LOCAL0
    private static final String FORMAT = TraceConfig.getString("syslog.format", "RFC5424");
    private static final String APP_NAME = getAppName();
    private static final String LOG_TYPES = TraceConfig.getString("syslog.log-types", "BOTH");
    private static final int RETRY_COUNT = TraceConfig.getInt("syslog.retry-count", 3);
    private static final int TIMEOUT_MS = TraceConfig.getInt("syslog.timeout-ms", 5000);
    
    // trace.name을 먼저 참고, 없으면 syslog.app-name, 둘 다 없으면 기본값
    private static String getAppName() {
        String traceName = TraceConfig.getString("trace.name", null);
        if (traceName != null && !traceName.trim().isEmpty()) {
            return traceName.trim();
        }
        return TraceConfig.getString("syslog.app-name", "pnones-trace-agent");
    }
    
    private static DatagramSocket udpSocket;
    private static Socket tcpSocket;
    private static InetAddress serverAddress;
    private static final Object lock = new Object();
    
    // Syslog severity levels
    public static final int SEVERITY_EMERGENCY = 0;
    public static final int SEVERITY_ALERT = 1;
    public static final int SEVERITY_CRITICAL = 2;
    public static final int SEVERITY_ERROR = 3;
    public static final int SEVERITY_WARNING = 4;
    public static final int SEVERITY_NOTICE = 5;
    public static final int SEVERITY_INFO = 6;
    public static final int SEVERITY_DEBUG = 7;
    
    static {
        if (ENABLED) {
            try {
                serverAddress = InetAddress.getByName(HOST);
                if ("UDP".equalsIgnoreCase(PROTOCOL)) {
                    udpSocket = new DatagramSocket();
                    udpSocket.setSoTimeout(TIMEOUT_MS);
                }
            } catch (Exception e) {
                com.pnones.trace.util.DebugLogger.error("SyslogSender init failed", e);
            }
        }
    }
    
    /**
     * HTTP 로그를 Syslog로 전송
     */
    public static void sendHttpLog(String message) {
        if (!ENABLED) return;
        if (!"HTTP".equalsIgnoreCase(LOG_TYPES) && !"BOTH".equalsIgnoreCase(LOG_TYPES)) return;
        send(message, SEVERITY_INFO, "HTTP");
    }
    
    /**
     * SQL 로그를 Syslog로 전송
     */
    public static void sendSqlLog(String message) {
        if (!ENABLED) return;
        if (!"SQL".equalsIgnoreCase(LOG_TYPES) && !"BOTH".equalsIgnoreCase(LOG_TYPES)) return;
        send(message, SEVERITY_INFO, "SQL");
    }
    
    /**
     * Syslog 메시지 전송
     */
    private static void send(String message, int severity, String tag) {
        if (!ENABLED || serverAddress == null) return;
        
        try {
            String syslogMessage = formatMessage(message, severity, tag);
            byte[] data = syslogMessage.getBytes(StandardCharsets.UTF_8);
            
            for (int attempt = 0; attempt <= RETRY_COUNT; attempt++) {
                try {
                    if ("UDP".equalsIgnoreCase(PROTOCOL)) {
                        sendUdp(data);
                    } else if ("TCP".equalsIgnoreCase(PROTOCOL)) {
                        sendTcp(data);
                    }
                    break; // Success
                } catch (Exception e) {
                    if (attempt == RETRY_COUNT) {
                        com.pnones.trace.util.DebugLogger.error("SyslogSender send failed after retries", e);
                    }
                    Thread.sleep(100 * (attempt + 1)); // Exponential backoff
                }
            }
        } catch (Exception e) {
            // Ignore - don't let syslog failures affect application
        }
    }
    
    /**
     * UDP로 전송
     */
    private static void sendUdp(byte[] data) throws IOException {
        synchronized (lock) {
            if (udpSocket == null || udpSocket.isClosed()) {
                udpSocket = new DatagramSocket();
                udpSocket.setSoTimeout(TIMEOUT_MS);
            }
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, PORT);
            udpSocket.send(packet);
        }
    }
    
    /**
     * TCP로 전송
     */
    private static void sendTcp(byte[] data) throws IOException {
        synchronized (lock) {
            if (tcpSocket == null || tcpSocket.isClosed() || !tcpSocket.isConnected()) {
                tcpSocket = new Socket();
                tcpSocket.connect(new InetSocketAddress(serverAddress, PORT), TIMEOUT_MS);
                tcpSocket.setSoTimeout(TIMEOUT_MS);
            }
            // RFC6587: octet counting - prepend message length
            String framedMessage = data.length + " " + new String(data, StandardCharsets.UTF_8);
            tcpSocket.getOutputStream().write(framedMessage.getBytes(StandardCharsets.UTF_8));
            tcpSocket.getOutputStream().flush();
        }
    }
    
    /**
     * Syslog 메시지 포맷 생성
     */
    private static String formatMessage(String message, int severity, String tag) {
        int priority = (FACILITY * 8) + severity;
        
        if ("JSON".equalsIgnoreCase(FORMAT)) {
            // JSON 포맷은 메시지를 그대로 전송 (이미 JSON)
            return "<" + priority + ">1 " + getTimestamp() + " " + getHostname() + " " + 
                   APP_NAME + " - - - " + message;
        } else if ("RFC5424".equalsIgnoreCase(FORMAT)) {
            // RFC5424: <PRI>VERSION TIMESTAMP HOSTNAME APP-NAME PROCID MSGID STRUCTURED-DATA MSG
            return "<" + priority + ">1 " + getTimestamp() + " " + getHostname() + " " + 
                   APP_NAME + " " + getProcId() + " " + tag + " - " + escapeMessage(message);
        } else {
            // RFC3164: <PRI>TIMESTAMP HOSTNAME TAG[PROCID]: MSG
            return "<" + priority + ">" + getTimestampRfc3164() + " " + getHostname() + " " + 
                   APP_NAME + "[" + getProcId() + "]: " + escapeMessage(message);
        }
    }
    
    /**
     * RFC5424 타임스탬프: 2023-11-20T15:30:45.123Z
     */
    private static String getTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }
    
    /**
     * RFC3164 타임스탬프: Nov 20 15:30:45
     */
    private static String getTimestampRfc3164() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm:ss");
        return sdf.format(new Date());
    }
    
    /**
     * 호스트명
     */
    private static String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * 프로세스 ID
     */
    private static String getProcId() {
        try {
            String processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return processName.split("@")[0];
        } catch (Exception e) {
            return "-";
        }
    }
    
    /**
     * 메시지 이스케이프 (개행 제거 등)
     */
    private static String escapeMessage(String message) {
        if (message == null) return "";
        return message.replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }
    
    /**
     * 종료 시 리소스 정리
     */
    public static void close() {
        synchronized (lock) {
            try {
                if (udpSocket != null) udpSocket.close();
                if (tcpSocket != null) tcpSocket.close();
            } catch (Exception ignored) {}
        }
    }
}
