package com.pnones.trace.agent;

/**
 * Compatibility registrar placeholder.
 *
 * We avoid compile-time dependency on either `javax.servlet` or
 * `jakarta.servlet` here. The agent will perform any container integration
 * (filter registration) using reflection or instrumentation so this class is
 * intentionally empty to prevent accidental classloading of servlet API types
 * by containers that don't provide them.
 */
public final class TraceFilterRegistrar {
    private TraceFilterRegistrar() { }
}
