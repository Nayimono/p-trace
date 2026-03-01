package com.pnones.trace.interceptor.mybatis;

import java.util.*;

/**
 * MyBatis ResultHandler to intercept result processing
 * This captures actual row data at the right moment (before MyBatis consumes it)
 */
public class PTraceResultHandler {
    
    private static final ThreadLocal<List<Map<String, Object>>> capturedRows = new ThreadLocal<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, List<Map<String, Object>>> capturedRowsByThread = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Intercept and capture result row
     */
    public static void captureRow(Object rowObject) {
        try {
            if (rowObject == null) return;
            
            List<Map<String, Object>> rows = capturedRows.get();
            if (rows == null) {
                rows = new ArrayList<>();
                capturedRows.set(rows);
            }
            
            // Convert result object to map
            Map<String, Object> rowMap = objectToMap(rowObject);
            if (!rowMap.isEmpty()) {
                rows.add(rowMap);

                long threadId = Thread.currentThread().getId();
                capturedRowsByThread.compute(threadId, (k, existing) -> {
                    if (existing == null) {
                        existing = new ArrayList<>();
                    }
                    existing.add(new LinkedHashMap<>(rowMap));
                    return existing;
                });
            }
        } catch (Throwable ignored) {}
    }
    
    /**
     * Get captured rows for current thread
     */
    public static List<Map<String, Object>> getCapturedRows() {
        List<Map<String, Object>> rows = capturedRows.get();
        return rows != null ? new ArrayList<>(rows) : new ArrayList<>();
    }
    
    /**
     * Clear captured rows
     */
    public static void clearCapturedRows() {
        capturedRows.remove();
        capturedRowsByThread.remove(Thread.currentThread().getId());
    }

    public static List<Map<String, Object>> getCapturedRowsByThread(long threadId) {
        List<Map<String, Object>> rows = capturedRowsByThread.get(threadId);
        return rows != null ? new ArrayList<>(rows) : new ArrayList<>();
    }

    public static void clearCapturedRowsByThread(long threadId) {
        capturedRowsByThread.remove(threadId);
    }
    
    /**
     * Convert result object to map
     * Handles POJOs, Maps, Lists, primitives
     */
    private static Map<String, Object> objectToMap(Object obj) {
        Map<String, Object> map = new LinkedHashMap<>();
        
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        
        if (obj == null) {
            return map;
        }
        
        try {
            // Try reflection-based field extraction
            Class<?> clazz = obj.getClass();
            
            // Skip built-in types
            String packageName = clazz.getPackage() != null ? clazz.getPackage().getName() : "";
            if (packageName.startsWith("java.") || packageName.startsWith("javax.")) {
                map.put("value", obj.toString());
                return map;
            }
            
            // Extract all fields via reflection
            java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                try {
                    field.setAccessible(true);
                    String fieldName = field.getName();
                    Object value = field.get(obj);
                    map.put(fieldName.toLowerCase(), value);
                } catch (Throwable ignored) {}
            }
            
            if (!map.isEmpty()) {
                return map;
            }
            
            // Fallback: try to use toString()
            map.put("value", obj.toString());
            return map;
        } catch (Throwable e) {
            map.put("error", "Failed to convert: " + e.getMessage());
            return map;
        }
    }
}
