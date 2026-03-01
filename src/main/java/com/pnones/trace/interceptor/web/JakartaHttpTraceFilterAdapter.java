package com.pnones.trace.interceptor.web;

/**
 * Adapter placeholder for servlet containers. This class avoids compile-time
 * references to servlet API types and delegates to `HttpTraceFilter` using
 * reflection. It is intentionally generic so it won't trigger classloading
 * errors in either javax or jakarta environments.
 */
public class JakartaHttpTraceFilterAdapter {
    private final Object delegate;

    public JakartaHttpTraceFilterAdapter() {
        this.delegate = new HttpTraceFilter();
    }

    // Reflection-safe init
    public void init(Object filterConfig) {
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("init", Object.class);
            m.invoke(delegate, filterConfig);
        } catch (NoSuchMethodException nsme) {
            // fallback: ignore
        } catch (Throwable ignored) {}
    }

    // Reflection-safe doFilter: accepts generic Objects and delegates
    public void doFilter(Object req, Object resp, Object chain) throws Throwable {
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("doFilter", Object.class, Object.class, Object.class);
            try {
                m.invoke(delegate, req, resp, chain);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                throw ite.getCause();
            }
        } catch (NoSuchMethodException nsme) {
            // fallback: try to call chain.doFilter
            try {
                java.lang.reflect.Method m2 = chain.getClass().getMethod("doFilter", Object.class, Object.class);
                try { m2.invoke(chain, req, resp); } catch (java.lang.reflect.InvocationTargetException ite) { throw ite.getCause(); }
            } catch (Throwable ignored) { }
        }
    }

    public void destroy() {
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("destroy");
            m.invoke(delegate);
        } catch (NoSuchMethodException nsme) {
        } catch (Throwable ignored) {}
    }
}
