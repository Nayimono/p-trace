package com.pnones.trace.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RequestContext {
    private static final ThreadLocal<RequestContext> context = new ThreadLocal<>();
    
    private String requestId;
    private long threadId;
    private String threadName;
    private List<Map<String, Object>> sqlQueries = new ArrayList<>();
    
    public static RequestContext get() {
        return context.get();
    }
    
    public static void set(RequestContext ctx) {
        context.set(ctx);
    }
    
    public static void clear() {
        context.remove();
    }
    
    // Convenience static methods for easy access
    public static void set(String requestId) {
        RequestContext ctx = new RequestContext(requestId, Thread.currentThread().getId(), Thread.currentThread().getName());
        context.set(ctx);
    }
    
    public static String getRequestId() {
        RequestContext ctx = context.get();
        return ctx != null ? ctx.requestId : null;
    }
    
    public static List<Map<String, Object>> getSqlQueries() {
        RequestContext ctx = context.get();
        return ctx != null ? ctx.sqlQueries : new ArrayList<>();
    }
    
    public static void addSqlQuery(Map<String, Object> sqlData) {
        RequestContext ctx = context.get();
        if (ctx != null) {
            ctx.sqlQueries.add(sqlData);
        }
    }
    
    public RequestContext(String requestId, long threadId, String threadName) {
        this.requestId = requestId;
        this.threadId = threadId;
        this.threadName = threadName;
    }
}
