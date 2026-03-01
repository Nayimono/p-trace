package com.pnones.trace.agent;

import com.pnones.trace.logger.HttpTraceLogger;
import com.pnones.trace.util.DebugLogger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracer for Jetty HTTP server
 * Works with Jetty Request/Response
 */
public class JettyHttpTracer {
    private static final HttpTraceLogger logger = new HttpTraceLogger();
    private static final Map<Object, TraceContext> contextMap = new ConcurrentHashMap<>();
    
    private static class TraceContext {
        String requestId;
        long startTime;
        String method;
        String path;
        String query;
        
        TraceContext() {
            this.requestId = UUID.randomUUID().toString();
            this.startTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Called at the start of request processing
     * @param request Jetty Request object
     * @param response Jetty Response object (optional)
     */
    public static Object before(Object request, Object response) {
        try {
            TraceContext ctx = new TraceContext();
            
            // Get HTTP method
            Method getMethod = request.getClass().getMethod("getMethod");
            ctx.method = (String) getMethod.invoke(request);
            
            // Get request URI
            Method getRequestURI = request.getClass().getMethod("getRequestURI");
            ctx.path = (String) getRequestURI.invoke(request);
            
            // Get query string
            Method getQueryString = request.getClass().getMethod("getQueryString");
            ctx.query = (String) getQueryString.invoke(request);
            
            contextMap.put(request, ctx);
            
            // Set RequestContext for SQL correlation
            RequestContext.set(ctx.requestId);
            
            DebugLogger.log("Jetty HTTP TRACE START: " + ctx.method + " " + ctx.path);
            
            return ctx;
            
        } catch (Throwable t) {
            DebugLogger.log("Failed to trace Jetty request", t);
            return null;
        }
    }
    
    /**
     * Called after request completes
     */
    public static void after(Object request, Object response, Object token) {
        try {
            TraceContext ctx = contextMap.remove(request);
            if (ctx == null && token instanceof TraceContext) {
                ctx = (TraceContext) token;
            }
            if (ctx == null) return;
            
            long elapsed = System.currentTimeMillis() - ctx.startTime;
            
            // Get response status
            int status = 200;
            try {
                if (response != null) {
                    Method getStatus = response.getClass().getMethod("getStatus");
                    status = (Integer) getStatus.invoke(response);
                }
            } catch (Throwable ignored) {}
            
            // Get SQL queries from RequestContext
            List<Map<String, Object>> sqlQueries = RequestContext.getSqlQueries();
            
            // Build JSON log
            StringBuilder json = new StringBuilder("{");
            json.append("\"timestamp\":").append(ctx.startTime).append(",");
            json.append("\"requestId\":\"").append(escapeJson(ctx.requestId)).append("\",");
            json.append("\"threadId\":").append(Thread.currentThread().getId()).append(",");
            json.append("\"threadName\":\"").append(escapeJson(Thread.currentThread().getName())).append("\",");
            json.append("\"method\":\"").append(escapeJson(ctx.method)).append("\",");
            json.append("\"path\":\"").append(escapeJson(ctx.path)).append("\"");
            
            if (ctx.query != null && !ctx.query.isEmpty()) {
                json.append(",\"query\":\"").append(escapeJson(ctx.query)).append("\"");
            }
            
            json.append(",\"status\":").append(status);
            json.append(",\"elapsedMs\":").append(elapsed);
            json.append(",\"server\":\"jetty\"");
            
            // Add SQL queries if any
            if (!sqlQueries.isEmpty()) {
                json.append(",\"sqlQueries\":[");
                boolean first = true;
                for (Map<String, Object> sql : sqlQueries) {
                    if (!first) json.append(",");
                    first = false;
                    
                    json.append("{\"sql\":\"").append(escapeJson(String.valueOf(sql.get("sql")))).append("\"");
                    
                    Object params = sql.get("params");
                    if (params != null) {
                        json.append(",\"params\":\"").append(escapeJson(String.valueOf(params))).append("\"");
                    }
                    
                    Object paramCount = sql.get("paramCount");
                    if (paramCount != null) {
                        json.append(",\"paramCount\":").append(paramCount);
                    }
                    
                    Object execTime = sql.get("executionTimeMs");
                    if (execTime != null) {
                        json.append(",\"executionTimeMs\":").append(execTime);
                    }
                    
                    Object resultRows = sql.get("resultRows");
                    if (resultRows != null) {
                        json.append(",\"resultRows\":").append(resultRows);
                    }
                    
                    Object updateCount = sql.get("updateCount");
                    if (updateCount != null) {
                        json.append(",\"updateCount\":").append(updateCount);
                    }
                    
                    Object success = sql.get("success");
                    if (success != null) {
                        json.append(",\"success\":").append(success);
                    }
                    
                    Object error = sql.get("error");
                    if (error != null) {
                        json.append(",\"error\":\"").append(escapeJson(String.valueOf(error))).append("\"");
                    }
                    
                    json.append("}");
                }
                json.append("]");
            }
            
            json.append("}");
            
            logger.log(json.toString());
            RequestContext.clear();
            
            DebugLogger.log("Jetty HTTP TRACE END: " + ctx.method + " " + ctx.path + " status=" + status + " SQL count=" + sqlQueries.size());
            
        } catch (Throwable t) {
            DebugLogger.log("Failed to complete Jetty trace", t);
        } finally {
            try {
                RequestContext.clear();
            } catch (Throwable ignored) {}
        }
    }
    
    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
