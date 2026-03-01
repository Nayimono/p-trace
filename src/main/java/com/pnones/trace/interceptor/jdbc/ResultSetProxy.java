package com.pnones.trace.interceptor.jdbc;

import java.sql.*;
import java.util.*;

/**
 * ResultSet proxy that captures data as application reads it.
 * This allows safe data capture without consuming the cursor.
 */
public class ResultSetProxy implements java.lang.reflect.InvocationHandler {
    
    private final ResultSet target;
    private final List<Map<String, Object>> capturedRows = new ArrayList<>();
    private final Map<String, Object> currentRow = new LinkedHashMap<>();
    private final int maxRows;
    private boolean capturingEnabled = true;
    private ResultSetMetaData metadata;
    private int columnCount;
    
    public ResultSetProxy(ResultSet target, int maxRows) {
        this.target = target;
        this.maxRows = maxRows;
        try {
            this.metadata = target.getMetaData();
            this.columnCount = metadata.getColumnCount();
        } catch (SQLException e) {
            // ignore
        }
    }
    
    public List<Map<String, Object>> getCapturedRows() {
        List<Map<String, Object>> result = new ArrayList<>();
        
        // Always include metadata first if we have column info
        if (metadata != null && columnCount > 0) {
            try {
                List<String> columnNames = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    try {
                        columnNames.add(metadata.getColumnLabel(i));
                    } catch (Exception ignored) {}
                }
                
                if (!columnNames.isEmpty()) {
                    Map<String, Object> metadataMap = new LinkedHashMap<>();
                    metadataMap.put("_columns", columnNames);
                    metadataMap.put("_columnCount", columnCount);
                    result.add(metadataMap);
                }
            } catch (Exception ignored) {}
        }
        
        // Add captured data rows
        result.addAll(capturedRows);
        return result;
    }
    
    @Override
    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        
        // Handle Object methods directly
        if ("equals".equals(methodName) && args != null && args.length == 1) {
            return proxy == args[0];
        }
        if ("hashCode".equals(methodName)) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(methodName)) {
            return "ResultSetProxy[" + target.toString() + "]";
        }
        
        try {
            // Call original method
            Object result = method.invoke(target, args);
            
            // Intercept next() to capture row data AFTER it returns true
            if ("next".equals(methodName) && capturingEnabled) {
                if (result instanceof Boolean && ((Boolean) result).booleanValue()) {
                    if (capturedRows.size() < maxRows) {
                        captureCurrentRow();
                    } else {
                        capturingEnabled = false; // Stop capturing after max rows
                    }
                }
            }
            
            // Intercept close() to finalize capture
            else if ("close".equals(methodName)) {
                capturingEnabled = false;
            }
            
            return result;
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        } catch (Exception e) {
            System.err.println("[PTrace] ResultSetProxy.invoke error on " + methodName + ": " + e.getMessage());
            throw e;
        }
    }
    
    private void captureCurrentRow() {
        if (metadata == null || columnCount == 0) return;
        
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                try {
                    String columnLabel = metadata.getColumnLabel(i);
                    Object value = target.getObject(i);
                    row.put(columnLabel, value);
                } catch (SQLException e) {
                    // Column read error - put null
                    try {
                        String columnLabel = metadata.getColumnLabel(i);
                        row.put(columnLabel, null);
                    } catch (Exception ignored2) {}
                } catch (Exception e) {
                    // Ignore other errors
                }
            }
            
            if (!row.isEmpty()) {
                capturedRows.add(row);
            }
        } catch (Exception e) {
            // Failed to capture row - log and continue without throwing
            System.err.println("[PTrace] captureCurrentRow failed: " + e.getMessage());
        }
    }
    
    public static ResultSet wrap(ResultSet rs, int maxRows) {
        if (rs == null) return null;
        
        try {
            // Don't wrap if already wrapped
            if (java.lang.reflect.Proxy.isProxyClass(rs.getClass())) {
                java.lang.reflect.InvocationHandler ih = java.lang.reflect.Proxy.getInvocationHandler(rs);
                if (ih instanceof ResultSetProxy) {
                    return rs; // Already wrapped
                }
            }
            
            // Collect all interfaces that the original ResultSet implements
            // This ensures compatibility with connection pool wrappers
            Set<Class<?>> interfaces = new LinkedHashSet<>();
            interfaces.add(ResultSet.class);
            
            Class<?> clazz = rs.getClass();
            while (clazz != null) {
                for (Class<?> iface : clazz.getInterfaces()) {
                    if (iface.getName().startsWith("java.sql")) {
                        interfaces.add(iface);
                    }
                }
                clazz = clazz.getSuperclass();
            }
            
            ResultSetProxy handler = new ResultSetProxy(rs, maxRows);
            ClassLoader loader = rs.getClass().getClassLoader();
            if (loader == null) {
                loader = ClassLoader.getSystemClassLoader();
            }
            
            return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                loader,
                interfaces.toArray(new Class[0]),
                handler
            );
        } catch (Exception e) {
            // If wrapping fails, return original
            System.err.println("[PTrace] ResultSetProxy.wrap failed: " + e.getMessage());
            return rs;
        }
    }
}
