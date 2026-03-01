package com.pnones.trace.agent;

public class JakartaAgentContextListener implements jakarta.servlet.ServletContextListener {
    @Override
    public void contextInitialized(jakarta.servlet.ServletContextEvent sce) {
        try {
            jakarta.servlet.ServletContext ctx = sce.getServletContext();
            try { FilterRegistrar.registerOnContext(ctx); } catch (Throwable t) { com.pnones.trace.util.DebugLogger.log("JakartaAgentContextListener failed to register on context", t); }
        } catch (Throwable ignored) {}
    }

    @Override
    public void contextDestroyed(jakarta.servlet.ServletContextEvent sce) {}
}
