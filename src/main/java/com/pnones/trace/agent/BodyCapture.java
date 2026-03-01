package com.pnones.trace.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures request/response body content without interfering with normal operation
 */
public class BodyCapture {
    private static final int MAX_BODY_SIZE = 10240; // 10KB limit
    private static final Map<Object, ByteArrayOutputStream> requestBodies = new ConcurrentHashMap<>();
    private static final Map<Object, ByteArrayOutputStream> responseBodies = new ConcurrentHashMap<>();
    
    public static void captureRequestBody(Object request, byte[] data, int len) {
        try {
            if (data == null || len <= 0) return;
            
            ByteArrayOutputStream baos = requestBodies.get(request);
            if (baos == null) {
                baos = new ByteArrayOutputStream();
                requestBodies.put(request, baos);
            }
            
            if (baos.size() + len <= MAX_BODY_SIZE) {
                baos.write(data, 0, len);
            }
        } catch (Throwable ignored) {}
    }
    
    public static void captureResponseBody(Object response, byte[] data, int len) {
        try {
            if (data == null || len <= 0) return;
            
            ByteArrayOutputStream baos = responseBodies.get(response);
            if (baos == null) {
                baos = new ByteArrayOutputStream();
                responseBodies.put(response, baos);
            }
            
            if (baos.size() + len <= MAX_BODY_SIZE) {
                baos.write(data, 0, len);
            }
        } catch (Throwable ignored) {}
    }
    
    public static String getRequestBody(Object request) {
        try {
            ByteArrayOutputStream baos = requestBodies.remove(request);
            if (baos != null && baos.size() > 0) {
                return new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {}
        return null;
    }
    
    public static String getResponseBody(Object response) {
        try {
            ByteArrayOutputStream baos = responseBodies.remove(response);
            if (baos != null && baos.size() > 0) {
                // Limit output size
                byte[] bytes = baos.toByteArray();
                if (bytes.length > MAX_BODY_SIZE) {
                    return new String(bytes, 0, MAX_BODY_SIZE, StandardCharsets.UTF_8) + "... (truncated)";
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {}
        return null;
    }
    
    public static void clearRequestBody(Object request) {
        requestBodies.remove(request);
    }
    
    public static void clearResponseBody(Object response) {
        responseBodies.remove(response);
    }
    
    /**
     * Check if content type is loggable (text-based)
     */
    public static boolean isLoggableContentType(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.contains("text/") || 
               lower.contains("application/json") || 
               lower.contains("application/xml") ||
               lower.contains("application/x-www-form-urlencoded");
    }
}
