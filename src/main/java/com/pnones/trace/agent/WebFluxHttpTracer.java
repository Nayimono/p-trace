package com.pnones.trace.agent;

import com.pnones.trace.logger.HttpTraceLogger;
import com.pnones.trace.util.DebugLogger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracer for Spring WebFlux / Reactor Netty HTTP requests
 * Works with reactive ServerHttpRequest/ServerHttpResponse
 */
public class WebFluxHttpTracer {
    private static final HttpTraceLogger logger = new HttpTraceLogger();
    private static final Map<Object, TraceContext> contextMap = new ConcurrentHashMap<>();
    
    private static class TraceContext {
        String requestId;
        long startTime;
        String method;
        String path;
        String query;
        Object request;
        
        TraceContext() {
            this.requestId = UUID.randomUUID().toString();
            this.startTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Called at the start of request processing
     * @param exchange ServerWebExchange object
     */
    public static Object beforeExchange(Object exchange) {
        try {
            TraceContext ctx = new TraceContext();
            
            // Extract request from exchange
            Method getRequest = exchange.getClass().getMethod("getRequest");
            Object request = getRequest.invoke(exchange);
            ctx.request = request;
            
            // Get HTTP method
            Method getMethod = request.getClass().getMethod("getMethod");
            Object methodObj = getMethod.invoke(request);
            ctx.method = methodObj.toString();
            
            // Get URI
            Method getURI = request.getClass().getMethod("getURI");
            Object uri = getURI.invoke(request);
            
            Method getPath = uri.getClass().getMethod("getPath");
            ctx.path = (String) getPath.invoke(uri);
            
            Method getRawQuery = uri.getClass().getMethod("getRawQuery");
            ctx.query = (String) getRawQuery.invoke(uri);
            
            contextMap.put(exchange, ctx);
            
            // Set RequestContext for SQL correlation
            RequestContext.set(ctx.requestId);
            
            DebugLogger.log("WebFlux HTTP TRACE START: " + ctx.method + " " + ctx.path);
            
            return ctx;
            
        } catch (Throwable t) {
            DebugLogger.log("Failed to trace WebFlux request", t);
            return null;
        }
    }
    
    /**
     * Called after response is sent
     * @param exchange ServerWebExchange object
     * @param token TraceContext returned from beforeExchange
     */
    public static void afterExchange(Object exchange, Object token) {
        try {
            TraceContext ctx = contextMap.remove(exchange);
            if (ctx == null && token instanceof TraceContext) {
                ctx = (TraceContext) token;
            }
            if (ctx == null) return;
            
            long elapsed = System.currentTimeMillis() - ctx.startTime;
            
            // Get response status
            int status = 200;
            try {
                Method getResponse = exchange.getClass().getMethod("getResponse");
                Object response = getResponse.invoke(exchange);
                
                Method getStatusCode = response.getClass().getMethod("getStatusCode");
                Object statusCode = getStatusCode.invoke(response);
                
                if (statusCode != null) {
                    Method getValue = statusCode.getClass().getMethod("value");
                    status = (Integer) getValue.invoke(statusCode);
                }
            } catch (Throwable t) {
                DebugLogger.log("Failed to get WebFlux response status", t);
            }
            
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
            json.append(",\"server\":\"webflux\"");
            
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
            
            DebugLogger.log("WebFlux HTTP TRACE END: " + ctx.method + " " + ctx.path + " status=" + status + " SQL count=" + sqlQueries.size());
            
        } catch (Throwable t) {
            DebugLogger.log("Failed to complete WebFlux trace", t);
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
