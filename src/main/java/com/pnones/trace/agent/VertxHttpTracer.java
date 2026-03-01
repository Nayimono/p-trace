package com.pnones.trace.agent;

import com.pnones.trace.logger.HttpTraceLogger;
import com.pnones.trace.util.DebugLogger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracer for Vert.x HTTP requests
 */
public class VertxHttpTracer {
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
    
    public static void traceRequest(Object request) {
        try {
            TraceContext ctx = new TraceContext();
            
            // Extract request info using reflection
            Method getMethod = request.getClass().getMethod("method");
            Object methodObj = getMethod.invoke(request);
            ctx.method = methodObj.toString();
            
            Method getPath = request.getClass().getMethod("path");
            ctx.path = (String) getPath.invoke(request);
            
            Method getQuery = request.getClass().getMethod("query");
            ctx.query = (String) getQuery.invoke(request);
            
            contextMap.put(request, ctx);
            
            // Set RequestContext for SQL correlation
            RequestContext.set(ctx.requestId);
            
            DebugLogger.log("Vert.x HTTP TRACE START: " + ctx.method + " " + ctx.path);
            
            // Add response end handler using reflection and dynamic proxy
            try {
                Method response = request.getClass().getMethod("response");
                Object resp = response.invoke(request);
                
                // Create a Handler using dynamic proxy
                ClassLoader cl = request.getClass().getClassLoader();
                Class<?> handlerClass = cl.loadClass("io.vertx.core.Handler");
                
                Object handler = java.lang.reflect.Proxy.newProxyInstance(
                    cl,
                    new Class<?>[] { handlerClass },
                    new java.lang.reflect.InvocationHandler() {
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                            if ("handle".equals(method.getName())) {
                                // Response ended, trace it
                                afterRequest(request, resp);
                            }
                            return null;
                        }
                    }
                );
                
                // Invoke endHandler with our proxy
                Method endHandler = resp.getClass().getMethod("endHandler", handlerClass);
                endHandler.invoke(resp, handler);
                
            } catch (Throwable t) {
                DebugLogger.log("Failed to setup Vert.x response handler", t);
            }
            
        } catch (Throwable t) {
            DebugLogger.log("Failed to trace Vert.x request", t);
        }
    }
    
    public static void afterRequest(Object request, Object response) {
        try {
            TraceContext ctx = contextMap.remove(request);
            if (ctx == null) return;
            
            long elapsed = System.currentTimeMillis() - ctx.startTime;
            
            // Get status code
            int status = 200;
            try {
                Method getStatusCode = response.getClass().getMethod("getStatusCode");
                status = (Integer) getStatusCode.invoke(response);
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
            json.append(",\"server\":\"vertx\"");
            
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
            
            DebugLogger.log("Vert.x HTTP TRACE END: " + ctx.method + " " + ctx.path + " status=" + status + " SQL count=" + sqlQueries.size());
            
        } catch (Throwable t) {
            DebugLogger.log("Failed to complete Vert.x trace", t);
        }
    }
    
    public static boolean shouldTrace(Object routingContext) {
        try {
            // Check if this is the first handler in the chain
            Method request = routingContext.getClass().getMethod("request");
            Object req = request.invoke(routingContext);
            return !contextMap.containsKey(req);
        } catch (Throwable ignored) {
            return false;
        }
    }
    
    public static void beforeRouting(Object routingContext) {
        try {
            Method request = routingContext.getClass().getMethod("request");
            Object req = request.invoke(routingContext);
            traceRequest(req);
            
            // Add end handler to routing context using dynamic proxy
            try {
                ClassLoader cl = routingContext.getClass().getClassLoader();
                Class<?> handlerClass = cl.loadClass("io.vertx.core.Handler");
                
                Method getResponse = routingContext.getClass().getMethod("response");
                Object response = getResponse.invoke(routingContext);
                
                Object handler = java.lang.reflect.Proxy.newProxyInstance(
                    cl,
                    new Class<?>[] { handlerClass },
                    new java.lang.reflect.InvocationHandler() {
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                            if ("handle".equals(method.getName())) {
                                afterRequest(req, response);
                            }
                            return null;
                        }
                    }
                );
                
                Method addEndHandler = routingContext.getClass().getMethod("addEndHandler", handlerClass);
                addEndHandler.invoke(routingContext, handler);
            } catch (Throwable t) {
                DebugLogger.log("Failed to add Vert.x routing end handler", t);
            }
            
        } catch (Throwable t) {
            DebugLogger.log("Failed to setup Vert.x routing trace", t);
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
