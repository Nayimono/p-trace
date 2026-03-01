package com.pnones.trace.interceptor.web;

import com.google.gson.Gson;
import com.pnones.trace.logger.HttpTraceLogger;
import com.pnones.trace.agent.SimpleHttpTracer;

/**
 * Reflection-based lightweight filter adapter.
 *
 * This class intentionally avoids compile-time dependency on servlet APIs so
 * it can be loaded in both javax and jakarta environments. It exposes init/doFilter/destroy
 * methods that are invoked reflectively by adapters when running inside a container.
 */
public class HttpTraceFilter {
    private final HttpTraceLogger logger = new HttpTraceLogger();
    private final Gson gson = new Gson();

    // No-arg init called reflectively
    public void init(Object filterConfig) {
        // no-op
    }

    // Generic doFilter expecting (request, response, chain) as Objects
    public void doFilter(Object request, Object response, Object chain) throws Throwable {
        if (!com.pnones.trace.config.TraceConfig.getBoolean("http.enabled", true)) {
            // just forward
            invokeFilterChain(chain, request, response);
            return;
        }

        Object token = null;
        Object requestForChain = request;
        Object responseForChain = response;
        try {
            // Wrap request/response before tracing so body can be captured from wrappers
            try {
                requestForChain = wrapRequestIfPossible(request);
            } catch (Throwable t) {
                com.pnones.trace.util.DebugLogger.log("wrapRequestIfPossible failed", t);
                requestForChain = request;
            }
            try {
                Object wrapper = SimpleHttpTracer.createResponseWrapper(response);
                responseForChain = (wrapper != null) ? wrapper : response;
                com.pnones.trace.util.DebugLogger.log("Response wrapper created: " + (wrapper != null ? wrapper.getClass().getName() : "null, using original"));
            } catch (Throwable t) {
                com.pnones.trace.util.DebugLogger.log("createResponseWrapper failed", t);
                responseForChain = response;
            }

            // Use SimpleHttpTracer to handle extraction and context setup (reflection-safe)
            try {
                token = SimpleHttpTracer.before(requestForChain, responseForChain);
            } catch (Throwable t) {
                // proceed even if tracer fails
                com.pnones.trace.util.DebugLogger.log("SimpleHttpTracer.before failed", t);
            }

            // invoke the chain.doFilter(request, response) reflectively
            com.pnones.trace.util.DebugLogger.log("Invoking filter chain with " + 
                (responseForChain != response ? "wrapped" : "original") + 
                " response: " + responseForChain.getClass().getName());
            invokeFilterChain(chain, requestForChain, responseForChain);
            com.pnones.trace.util.DebugLogger.log("Filter chain completed for " + responseForChain.getClass().getName());

            try {
                SimpleHttpTracer.after(token, responseForChain);
            } catch (Throwable t) {
                com.pnones.trace.util.DebugLogger.log("SimpleHttpTracer.after failed", t);
            }
        } finally {
            try { com.pnones.trace.util.RequestContext.clear(); } catch (Throwable ignored) {}
        }
    }

    public void destroy() {
        // no-op
    }

    private void invokeFilterChain(Object chain, Object req, Object resp) throws Throwable {
        if (chain == null) return;
        try {
            java.lang.reflect.Method m = null;
            Class<?> cls = chain.getClass();
            // find doFilter that accepts two parameters
            for (java.lang.reflect.Method mm : cls.getMethods()) {
                if (mm.getName().equals("doFilter") && mm.getParameterCount() == 2) {
                    m = mm;
                    break;
                }
            }
            if (m != null) {
                try {
                    m.invoke(chain, req, resp);
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    throw ite.getCause();
                }
            } else {
                throw new IllegalStateException("FilterChain#doFilter not found");
            }
        } catch (Throwable t) {
            throw t;
        }
    }

    private Object wrapRequestIfPossible(Object request) {
        if (request == null) return null;
        try {
            // Try Spring ContentCachingRequestWrapper first - auto-caches all request body data
            try {
                Class<?> springWrapper = Class.forName("org.springframework.web.util.ContentCachingRequestWrapper");
                Class<?> servletRequest = Class.forName("javax.servlet.http.HttpServletRequest");
                java.lang.reflect.Constructor<?> springCtor = springWrapper.getConstructor(servletRequest);
                Object wrapped = springCtor.newInstance(request);
                com.pnones.trace.util.DebugLogger.log("Successfully wrapped with Spring ContentCachingRequestWrapper");
                return wrapped;
            } catch (Exception springErr) {
                com.pnones.trace.util.DebugLogger.log("Spring ContentCachingRequestWrapper unavailable, trying custom wrapper");
            }
            
            // Fall back to custom wrapper
            Class<?> wrapperClass = Class.forName("com.pnones.trace.interceptor.web.CachingRequestWrapper");
            java.lang.reflect.Constructor<?> ctor = wrapperClass.getConstructor(request.getClass().getInterfaces().length > 0
                    ? request.getClass().getInterfaces()[0]
                    : request.getClass());
            return ctor.newInstance(request);
        } catch (Throwable ignored) {
            // Fallback: find any 1-arg constructor assignable from request type
            try {
                Class<?> wrapperClass = Class.forName("com.pnones.trace.interceptor.web.CachingRequestWrapper");
                for (java.lang.reflect.Constructor<?> c : wrapperClass.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 1 && p[0].isAssignableFrom(request.getClass())) {
                        return c.newInstance(request);
                    }
                }
            } catch (Throwable ignored2) {}
            return request;
        }
    }
}
