package com.pnones.trace.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP tracer for GlassFish/Payara containers.
 * GlassFish uses Servlet API similar to Tomcat, delegating to SimpleHttpTracer.
 */
public class GlassFishHttpTracer {
    
    /**
     * Delegate to SimpleHttpTracer.before() for consistent HTTP tracing.
     * GlassFish request/response objects are Servlet API compatible.
     */
    public static Object before(Object request, Object response) {
        try {
            return SimpleHttpTracer.before(request, response);
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("GlassFish HTTP trace error (before)", e);
            return null;
        }
    }

    /**
     * Delegate to SimpleHttpTracer.after() for consistent HTTP tracing.
     */
    public static void after(Object token, Object response) {
        try {
            SimpleHttpTracer.after(token, response);
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("GlassFish HTTP trace error (after)", e);
        }
    }

    /**
     * Special handler for GlassFish servlet request wrapper.
     * Some GlassFish versions wrap Servlet requests in PERequest.
     */
    public static void traceGlassFishRequest(Object request, Object response) {
        try {
            before(request, response);
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("GlassFish request trace error", e);
        }
    }
}
