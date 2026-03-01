package com.pnones.trace.agent;

import java.util.HashMap;
import java.util.Map;

public class SimpleSqlTracer {
    public static Object beforeExecute(Object stmtObj, String sql) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "execute");
            m.put("sql", sql != null ? sql : "");
            m.put("timestamp", System.currentTimeMillis());
            m.put("threadId", Thread.currentThread().getId());
            m.put("threadName", Thread.currentThread().getName());

            // Extract parameters from PreparedStatement if available
            try {
                Class<?> stmtClass = stmtObj.getClass();
                
                // Check if it's a PreparedStatement by checking for parameter metadata
                try {
                    Object paramMeta = stmtClass.getMethod("getParameterMetaData", (Class<?>[]) null).invoke(stmtObj, (Object[]) null);
                    if (paramMeta != null) {
                        Class<?> paramMetaClass = paramMeta.getClass();
                        Object paramCountObj = paramMetaClass.getMethod("getParameterCount", (Class<?>[]) null).invoke(paramMeta, (Object[]) null);
                        
                        if (paramCountObj instanceof Number) {
                            int paramCount = ((Number) paramCountObj).intValue();
                            if (paramCount > 0) {
                                StringBuilder params = new StringBuilder();
                                for (int i = 1; i <= paramCount; i++) {
                                    try {
                                        // Try to get parameter value from statement (if available)
                                        // Note: Direct parameter access varies by driver, so we use a generic approach
                                        if (params.length() > 0) params.append(",");
                                        params.append("?");
                                    } catch (Throwable ignored) {}
                                }
                                if (params.length() > 0) {
                                    m.put("paramCount", paramCount);
                                    m.put("params", params.toString());
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            } catch (Throwable ex) {
                com.pnones.trace.util.DebugLogger.log("Error extracting SQL parameters", ex);
            }

            com.pnones.trace.util.DebugLogger.log("SQL TRACE START: " + (sql != null ? sql.replace("\n", " ").substring(0, Math.min(100, sql.length())) : ""));

            return m;
        } catch (Throwable t) {
            com.pnones.trace.util.DebugLogger.log("SimpleSqlTracer.beforeExecute error", t);
            return null;
        }
    }

    public static void afterExecute(Object token, Object stmtObj, Object result, Throwable exception) {
        try {
            if (!(token instanceof Map)) return;
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) token;

            long executionTime = System.currentTimeMillis() - ((Number) m.get("timestamp")).longValue();
            m.put("executionTime", executionTime);

            if (exception != null) {
                m.put("error", exception.getClass().getName() + ": " + exception.getMessage());
                m.put("success", false);
            } else {
                m.put("success", true);
                
                // Try to extract result information
                if (result != null) {
                    try {
                        // For ResultSet, try to get row count
                        Class<?> resultClass = result.getClass();
                        String resultClassName = resultClass.getSimpleName();
                        
                        if (resultClassName.contains("ResultSet")) {
                            try {
                                // Try to get last row number
                                Object lastRow = resultClass.getMethod("last", (Class<?>[]) null).invoke(result, (Object[]) null);
                                if (lastRow instanceof Boolean) {
                                    Boolean hasRows = (Boolean) lastRow;
                                    if (hasRows) {
                                        Object rowNum = resultClass.getMethod("getRow", (Class<?>[]) null).invoke(result, (Object[]) null);
                                        if (rowNum instanceof Number) {
                                            m.put("resultRows", ((Number) rowNum).intValue());
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        } else {
                            // For update count
                            m.put("updateCount", result.toString());
                        }
                    } catch (Throwable ignored) {}
                }
            }

            com.pnones.trace.util.DebugLogger.log("SQL TRACE END: executionTime=" + executionTime + "ms, success=" + m.get("success"));

            // Add SQL info to RequestContext (instead of logging to separate file)
            try {
                RequestContext ctx = RequestContext.get();
                if (ctx != null) {
                    ctx.addSqlQuery(m);
                }
            } catch (Throwable ignored) {
                // no-op: standalone sql-trace file writing is disabled
            }

        } catch (Throwable t) {
            com.pnones.trace.util.DebugLogger.log("SimpleSqlTracer.afterExecute error", t);
        }
    }

    public static void clearContext() {
        // no-op: legacy API kept for compatibility
    }
}
