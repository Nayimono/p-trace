package com.pnones.trace.agent;

import com.pnones.trace.logger.HttpTraceLogger;
import com.pnones.trace.util.DebugLogger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracer for Undertow HTTP server
 * Works with HttpServerExchange
 */
public class UndertowHttpTracer {
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
     * @param exchange HttpServerExchange object
     */
    public static Object before(Object exchange) {
        try {
            TraceContext ctx = new TraceContext();
            
            // Get HTTP method
            Method getRequestMethod = exchange.getClass().getMethod("getRequestMethod");
            Object methodObj = getRequestMethod.invoke(exchange);
            ctx.method = methodObj.toString();
            
            // Get request path
            Method getRequestPath = exchange.getClass().getMethod("getRequestPath");
            ctx.path = (String) getRequestPath.invoke(exchange);
            
            // Get query string
            Method getQueryString = exchange.getClass().getMethod("getQueryString");
            ctx.query = (String) getQueryString.invoke(exchange);
            
            contextMap.put(exchange, ctx);
            
            // Set RequestContext for SQL correlation
            RequestContext.set(ctx.requestId);
            
            DebugLogger.log("Undertow HTTP TRACE START: " + ctx.method + " " + ctx.path);
            
            // Add completion listener using reflection and dynamic proxy
            try {
                ClassLoader cl = exchange.getClass().getClassLoader();
                Class<?> listenerClass = cl.loadClass("io.undertow.server.ExchangeCompletionListener");
                
                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    cl,
                    new Class<?>[] { listenerClass },
                    new java.lang.reflect.InvocationHandler() {
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                            if ("exchangeEvent".equals(method.getName())) {
                                after(exchange, ctx);
                            }
                            return null;
                        }
                    }
                );
                
                Method addListener = exchange.getClass().getMethod("addExchangeCompleteListener", listenerClass);
                addListener.invoke(exchange, listener);
                
            } catch (Throwable t) {
                DebugLogger.log("Failed to add Undertow completion listener", t);
            }
            
            return ctx;
            
        } catch (Throwable t) {
            DebugLogger.log("Failed to trace Undertow request", t);
            return null;
        }
    }
    
    /**
     * Called after exchange completes
     */
    public static void after(Object exchange, Object token) {
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
                Method getStatusCode = exchange.getClass().getMethod("getStatusCode");
                status = (Integer) getStatusCode.invoke(exchange);
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
            json.append(",\"server\":\"undertow\"");
            
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
            
            DebugLogger.log("Undertow HTTP TRACE END: " + ctx.method + " " + ctx.path + " status=" + status + " SQL count=" + sqlQueries.size());
            
        } catch (Throwable t) {
            DebugLogger.log("Failed to complete Undertow trace", t);
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
