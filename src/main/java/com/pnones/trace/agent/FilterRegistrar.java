package com.pnones.trace.agent;

public class FilterRegistrar {
    public static void registerOnContext(Object ctx) {
        if (ctx == null) return;
        
        // PRIORITY 1: Register RequestWrapperFilter FIRST to wrap all requests before any app filters
        try {
            java.lang.reflect.Method addFilterByName = findMethod(ctx.getClass(), "addFilter", new Class[]{String.class, String.class});
            if (addFilterByName != null) {
                try {
                    Object reg = addFilterByName.invoke(ctx, "pnones-request-wrapper", "com.pnones.trace.interceptor.web.RequestWrapperFilter");
                    com.pnones.trace.util.DebugLogger.log("✓ Registered RequestWrapperFilter (PRIORITY WRAPPER)");
                    try { mapFilterRegistrationToAllUrls(ctx, reg); } catch (Throwable me) { com.pnones.trace.util.DebugLogger.log("mapFilterRegistrationToAllUrls failed for wrapper", me); }
                } catch (Throwable t) {
                    com.pnones.trace.util.DebugLogger.log("RequestWrapperFilter registration failed", t);
                }
            }
        } catch (Throwable t) {
            com.pnones.trace.util.DebugLogger.log("RequestWrapperFilter addFilter discovery failed", t);
        }
        
        // PRIORITY 2: Register HttpTraceFilter for HTTP tracing
        // Filter registration: Try to register HttpTraceFilter on servlet context
        try {
            // Try addFilter by class name - works for both javax and jakarta (webapp classloader will load correct version)
            try {
                java.lang.reflect.Method addFilterByName = findMethod(ctx.getClass(), "addFilter", new Class[]{String.class, String.class});
                com.pnones.trace.util.DebugLogger.log("FilterRegistrar: addFilter(String,String) method=" + (addFilterByName==null?"null":addFilterByName.toString()));
                if (addFilterByName != null) {
                    try {
                        Object reg = addFilterByName.invoke(ctx, "pnones-http-trace", "com.pnones.trace.interceptor.web.HttpTraceFilter");
                        com.pnones.trace.util.DebugLogger.log("Registered Filter by class name via addFilter(String,String) on " + ctx.getClass().getName());
                        try { mapFilterRegistrationToAllUrls(ctx, reg); } catch (Throwable me) { com.pnones.trace.util.DebugLogger.log("mapFilterRegistrationToAllUrls failed", me); }
                        return;
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("invoke addFilter(String,String) failed", t);
                    }
                }
            } catch (Throwable t) {
                com.pnones.trace.util.DebugLogger.log("FilterRegistrar addFilter discovery failed", t);
            }

            // Try addListener by class name for both javax and jakarta listeners
            try {
                java.lang.reflect.Method addListenerByName = findMethod(ctx.getClass(), "addListener", new Class[]{String.class});
                if (addListenerByName != null) {
                    // Try Jakarta listener first (for Spring Boot 3.x, Tomcat 10+)
                    try {
                        addListenerByName.invoke(ctx, "com.pnones.trace.agent.JakartaAgentContextListener");
                        com.pnones.trace.util.DebugLogger.log("Registered JakartaAgentContextListener by class name via addListener(String) on " + ctx.getClass().getName());
                        return;
                    } catch (Throwable jakartaErr) {
                        com.pnones.trace.util.DebugLogger.log("JakartaAgentContextListener registration failed, trying javax version", jakartaErr);
                    }
                    // Fallback to javax listener (for older Spring Boot, Tomcat 9.x)
                    try {
                        addListenerByName.invoke(ctx, "com.pnones.trace.agent.AgentContextListener");
                        com.pnones.trace.util.DebugLogger.log("Registered AgentContextListener by class name via addListener(String) on " + ctx.getClass().getName());
                        return;
                    } catch (Throwable javaxErr) {
                        com.pnones.trace.util.DebugLogger.log("AgentContextListener registration failed", javaxErr);
                    }
                }
            } catch (Throwable ignored) {}

            // Fallback: if this context has a getServletContext(), try registration on that object too
            try {
                java.lang.reflect.Method getSc = findMethod(ctx.getClass(), "getServletContext", new Class[]{});
                if (getSc != null) {
                    Object sc = null;
                    try { sc = getSc.invoke(ctx); } catch (Throwable t) { com.pnones.trace.util.DebugLogger.log("getServletContext invoke failed", t); }
                    if (sc != null) {
                        com.pnones.trace.util.DebugLogger.log("FilterRegistrar: attempting registration on servletContext object: " + sc.getClass().getName());
                        try {
                            // Try jakarta addFilter on servletContext
                            try {
                                Class<?> jakartaFilter = Class.forName("jakarta.servlet.Filter");
                                java.lang.reflect.Method addFilter = findMethod(sc.getClass(), "addFilter", new Class[]{String.class, jakartaFilter});
                                if (addFilter != null) {
                                    Object filter = new com.pnones.trace.interceptor.web.HttpTraceFilter();
                                    Object reg = addFilter.invoke(sc, "pnones-http-trace", filter);
                                    com.pnones.trace.util.DebugLogger.log("Registered jakarta Filter instance via addFilter on servletContext " + sc.getClass().getName());
                                    try { mapFilterRegistrationToAllUrls(sc, reg); } catch (Throwable ignored) {}
                                    return;
                                }
                            } catch (Throwable ignored) {}
                            // Try addFilter(String,String) on servletContext
                            try {
                                java.lang.reflect.Method addFilterByName = findMethod(sc.getClass(), "addFilter", new Class[]{String.class, String.class});
                                if (addFilterByName != null) {
                                    Object reg = addFilterByName.invoke(sc, "pnones-http-trace", "com.pnones.trace.interceptor.web.HttpTraceFilter");
                                    com.pnones.trace.util.DebugLogger.log("Registered Filter by class name via addFilter(String,String) on servletContext " + sc.getClass().getName());
                                    try { mapFilterRegistrationToAllUrls(sc, reg); } catch (Throwable ignored) {}
                                    return;
                                }
                            } catch (Throwable ignored) {}
                        } catch (Throwable t) {
                            com.pnones.trace.util.DebugLogger.log("Fallback servletContext registration failed", t);
                        }
                    }
                }
            } catch (Throwable t) {
                com.pnones.trace.util.DebugLogger.log("Error invoking getServletContext fallback", t);
            }
        } catch (Throwable t) {
            com.pnones.trace.util.DebugLogger.log("FilterRegistrar.registerOnContext failed", t);
        }
    }

    private static java.lang.reflect.Method findMethod(Class<?> cls, String name, Class<?>[] params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            // try declared
            try { return cls.getDeclaredMethod(name, params); } catch (NoSuchMethodException ex) { return null; }
        }
    }

    private static void mapFilterRegistrationToAllUrls(Object ctx, Object reg) {
        if (reg == null) return;
        try {
            // Set filter order to execute FIRST (highest priority) before all Spring filters
            try {
                java.lang.reflect.Method setOrderMethod = reg.getClass().getMethod("setOrder", int.class);
                setOrderMethod.invoke(reg, -1000);  // Highest priority (lowest order number)
                com.pnones.trace.util.DebugLogger.log("Set pnones-http-trace filter order to -1000 (first execution)");
            } catch (Throwable t) {
                com.pnones.trace.util.DebugLogger.log("setOrder() not available or failed", t);
            }
            
            ClassLoader loader = ctx.getClass().getClassLoader();
            Class<?> dispatcherType = null;
            // Try jakarta.servlet.DispatcherType first (for newer containers)
            try { dispatcherType = loader.loadClass("jakarta.servlet.DispatcherType"); } catch (Throwable ignored) {}
            // Fallback to javax.servlet.DispatcherType (for older containers)
            if (dispatcherType == null) {
                try { dispatcherType = loader.loadClass("javax.servlet.DispatcherType"); } catch (Throwable ignored) {}
            }
            Object enumSet = null;
            if (dispatcherType != null) {
                // get REQUEST enum
                Object req = null;
                try { req = dispatcherType.getField("REQUEST").get(null); } catch (Throwable ignored) {}
                if (req != null) {
                    try {
                        java.lang.reflect.Method ofm = java.util.EnumSet.class.getMethod("of", java.lang.Enum.class);
                        enumSet = ofm.invoke(null, (Enum) req);
                    } catch (Throwable ignored) {}
                }
            }
            // Call addMappingForUrlPatterns on FilterRegistration
            try {
                Class<?> regClass = reg.getClass();
                java.lang.reflect.Method m = null;
                try { m = regClass.getMethod("addMappingForUrlPatterns", java.util.EnumSet.class, boolean.class, String[].class); } catch (NoSuchMethodException ignored) {}
                if (m == null) {
                    try { m = regClass.getMethod("addMappingForUrlPatterns", java.util.Set.class, boolean.class, String[].class); } catch (NoSuchMethodException ignored) {}
                }
                if (m != null) {
                    if (enumSet != null) {
                        m.invoke(reg, enumSet, Boolean.FALSE, new String[]{"/*"});
                    } else {
                        // pass null for dispatcher set (some containers accept this)
                        m.invoke(reg, null, Boolean.FALSE, new String[]{"/*"});
                    }
                    com.pnones.trace.util.DebugLogger.log("Mapped pnones-http-trace to /* via FilterRegistration on " + ctx.getClass().getName());
                }
            } catch (Throwable t) {
                com.pnones.trace.util.DebugLogger.log("Failed to addMappingForUrlPatterns", t);
            }
        } catch (Throwable t) {
            com.pnones.trace.util.DebugLogger.log("mapFilterRegistrationToAllUrls failed", t);
        }
    }

    public static Object wrapRequestForTracing(Object request) {
        return wrapRequestIfPossible(request);
    }

    private static Object wrapRequestIfPossible(Object request) {
        if (request == null) return null;
        try {
            String reqClassName = request.getClass().getName();
            if (reqClassName.equals("com.pnones.trace.interceptor.web.CachingRequestWrapper") ||
                reqClassName.equals("com.pnones.trace.interceptor.web.CachingRequestWrapperJavax")) {
                com.pnones.trace.util.DebugLogger.log("Request already wrapped: " + reqClassName);
                return request;
            }

            // Try jakarta.servlet wrapper first (Spring Boot 3.x)
            try {
                Class<?> wrapperClass = Class.forName("com.pnones.trace.interceptor.web.CachingRequestWrapper");
                java.lang.reflect.Constructor<?> ctor = wrapperClass.getConstructor(request.getClass().getInterfaces().length > 0
                        ? request.getClass().getInterfaces()[0]
                        : request.getClass());
                Object wrapped = ctor.newInstance(request);
                com.pnones.trace.util.DebugLogger.log("Wrapped request with jakarta CachingRequestWrapper");
                return wrapped;
            } catch (Throwable jakartaErr) {
                com.pnones.trace.util.DebugLogger.log("jakarta CachingRequestWrapper failed, trying javax version", jakartaErr);
            }
            
            // Try javax.servlet wrapper (Spring Boot 2.x)
            try {
                Class<?> wrapperClass = Class.forName("com.pnones.trace.interceptor.web.CachingRequestWrapperJavax");
                java.lang.reflect.Constructor<?> ctor = wrapperClass.getConstructor(request.getClass().getInterfaces().length > 0
                        ? request.getClass().getInterfaces()[0]
                        : request.getClass());
                Object wrapped = ctor.newInstance(request);
                com.pnones.trace.util.DebugLogger.log("Wrapped request with javax CachingRequestWrapperJavax");
                return wrapped;
            } catch (Throwable javaxErr) {
                com.pnones.trace.util.DebugLogger.log("javax CachingRequestWrapperJavax failed, trying fallback", javaxErr);
            }
            
            // Fallback: find any 1-arg constructor assignable from request type
            try {
                try {
                    Class<?> wrapperClass = Class.forName("com.pnones.trace.interceptor.web.CachingRequestWrapper");
                    for (java.lang.reflect.Constructor<?> c : wrapperClass.getConstructors()) {
                        Class<?>[] p = c.getParameterTypes();
                        if (p.length == 1 && p[0].isAssignableFrom(request.getClass())) {
                            Object wrapped = c.newInstance(request);
                            com.pnones.trace.util.DebugLogger.log("Wrapped request using jakarta fallback constructor");
                            return wrapped;
                        }
                    }
                } catch (Throwable ignored) {}
                
                Class<?> wrapperClass = Class.forName("com.pnones.trace.interceptor.web.CachingRequestWrapperJavax");
                for (java.lang.reflect.Constructor<?> c : wrapperClass.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 1 && p[0].isAssignableFrom(request.getClass())) {
                        Object wrapped = c.newInstance(request);
                        com.pnones.trace.util.DebugLogger.log("Wrapped request using javax fallback constructor");
                        return wrapped;
                    }
                }
            } catch (Throwable ignored) {}
            
            com.pnones.trace.util.DebugLogger.log("No suitable CachingRequestWrapper found, returning original request");
            return request;
        } catch (Throwable t) {
            com.pnones.trace.util.DebugLogger.log("wrapRequestIfPossible failed, returning original request", t);
            return request;
        }
    }
}
