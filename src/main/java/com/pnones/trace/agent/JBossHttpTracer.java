package com.pnones.trace.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP tracer for JBoss/WildFly containers.
 * - JBoss 7+ (WildFly): Uses Undertow, delegates to UndertowHttpTracer
 * - JBoss 6 and earlier: Uses Tomcat or custom request processing
 */
public class JBossHttpTracer {

    /**
     * Trace JBoss HTTP request.
     * JBoss may wrap request/response in custom handlers.
     * This method extracts ServletRequest/ServletResponse and delegates to SimpleHttpTracer.
     */
    public static Object before(Object jbossRequest, Object jbossResponse) {
        try {
            // JBoss request is typically a ServletRequest compatible object
            return SimpleHttpTracer.before(jbossRequest, jbossResponse);
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("JBoss HTTP trace error (before)", e);
            return null;
        }
    }

    /**
     * Trace JBoss HTTP response.
     */
    public static void after(Object token, Object jbossResponse) {
        try {
            SimpleHttpTracer.after(token, jbossResponse);
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("JBoss HTTP trace error (after)", e);
        }
    }

    /**
     * Trace JBoss request through InvokerServlet (legacy JBoss versions).
     * Some older JBoss versions use custom servlet invokers.
     */
    public static void traceInvokerServlet(Object request, Object response) {
        try {
            Class<?> servletRequestClass = Class.forName("javax.servlet.ServletRequest");
            if (servletRequestClass.isInstance(request)) {
                before(request, response);
            } else {
                com.pnones.trace.util.DebugLogger.log("JBoss request is not javax.servlet.ServletRequest: " + 
                    (request != null ? request.getClass().getName() : "null"));
            }
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("JBoss InvokerServlet trace error", e);
        }
    }

    /**
     * Trace JBoss Servlet request context.
     * Some WildFly versions may use JBoss-specific request context.
     */
    public static void traceJBossServletRequest(Object request, Object response) {
        try {
            // Attempt to extract underlying Servlet request if wrapped
            Object actualRequest = request;
            Object actualResponse = response;
            
            // Try to unwrap if it's a wrapped request
            try {
                Class<?> reqClass = request.getClass();
                java.lang.reflect.Method getRequest = reqClass.getMethod("getRequest");
                if (getRequest != null && getRequest.getReturnType() != void.class) {
                    actualRequest = getRequest.invoke(request);
                }
            } catch (Exception ignored) {}
            
            try {
                Class<?> respClass = response.getClass();
                java.lang.reflect.Method getResponse = respClass.getMethod("getResponse");
                if (getResponse != null && getResponse.getReturnType() != void.class) {
                    actualResponse = getResponse.invoke(response);
                }
            } catch (Exception ignored) {}
            
            before(actualRequest, actualResponse);
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("JBoss Servlet request trace error", e);
        }
    }
}
