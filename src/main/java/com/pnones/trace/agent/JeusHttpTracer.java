package com.pnones.trace.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP tracer for Jeus (TmaxSoft Java Enterprise User Suite).
 * Jeus uses standard Servlet API, delegating to SimpleHttpTracer for consistent tracing.
 */
public class JeusHttpTracer {

    /**
     * Trace Jeus HTTP request.
     * Jeus request/response are standard ServletRequest/ServletResponse objects.
     */
    public static Object before(Object request, Object response) {
        try {
            return SimpleHttpTracer.before(request, response);
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("Jeus HTTP trace error (before)", e);
            return null;
        }
    }

    /**
     * Trace Jeus HTTP response.
     */
    public static void after(Object token, Object response) {
        try {
            SimpleHttpTracer.after(token, response);
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("Jeus HTTP trace error (after)", e);
        }
    }

    /**
     * Trace Jeus default filter chain.
     * Some Jeus versions use org.jeus.servlet.FilterChain or similar.
     */
    public static void traceFilterChain(Object request, Object response, Object filterChain) {
        try {
            before(request, response);
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("Jeus filter chain trace error", e);
        }
    }

    /**
     * Trace Jeus request handler.
     * Delegates to standard SimpleHttpTracer for request/response processing.
     */
    public static void traceRequestHandler(Object request, Object response) {
        try {
            Object token = before(request, response);
            // Token will be retrieved in after(); this is just an entry point
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("Jeus request handler trace error", e);
        }
    }
}
