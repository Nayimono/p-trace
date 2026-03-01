package com.pnones.trace.agent;

/**
 * ThreadLocal context for passing HTTP trace tokens between method instrumentation points.
 * Used by ApplicationFilterChain.doFilter instrumentation to pass token from before() to after().
 */
public class ApplicationFilterChainTraceContext {
    private static final ThreadLocal<Object> CONTEXT = new ThreadLocal<>();

    public static void set(Object token) {
        CONTEXT.set(token);
    }

    public static Object get() {
        return CONTEXT.get();
    }

    public static void remove() {
        CONTEXT.remove();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
