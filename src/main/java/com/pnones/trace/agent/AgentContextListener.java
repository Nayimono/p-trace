package com.pnones.trace.agent;

public class AgentContextListener implements javax.servlet.ServletContextListener {
    @Override
    public void contextInitialized(javax.servlet.ServletContextEvent sce) {
        try {
            javax.servlet.ServletContext ctx = sce.getServletContext();
            // register filter directly if possible
            try { FilterRegistrar.registerOnContext(ctx); } catch (Throwable t) { com.pnones.trace.util.DebugLogger.log("AgentContextListener failed to register on context", t); }
        } catch (Throwable ignored) {}
    }

    @Override
    public void contextDestroyed(javax.servlet.ServletContextEvent sce) {}
}
