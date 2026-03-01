package com.pnones.trace.agent;

import com.pnones.trace.config.TraceConfig;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLDecoder;
import java.security.ProtectionDomain;

public class TraceAgent {
    private static int instrumentedClasses = 0;
    
    public static void premain(String agentArgs, Instrumentation inst) {
        System.err.println("\n\n=====================================================");
        System.err.println("[PTrace Agent] AGENT LOADING - START");
        System.err.println("[PTrace Agent] Args: " + agentArgs);
        System.err.println("=====================================================");
        System.err.flush();
        
        try {
            TraceConfig.load(agentArgs);
            String logDir = TraceConfig.getString("log.dir", "./logs");
            System.err.println("[PTrace Agent] ✓ TraceConfig loaded");
            System.err.println("[PTrace Agent] ✓ Log directory: " + new java.io.File(logDir).getAbsolutePath());
            
            // Register shutdown hook to archive logs when Tomcat shuts down
            com.pnones.trace.util.ShutdownLogHandler.registerShutdownHook();
        } catch (Throwable t) {
            System.err.println("[PTrace Agent] ✗ TraceConfig load failed: " + t.getMessage());
            t.printStackTrace(System.err);
        }
        System.err.flush();
        // Log already-loaded container/servlet classes to help diagnose why filters may not register
        try {
            Class[] loaded = inst.getAllLoadedClasses();
            String[] prefixes = new String[]{"org.apache.catalina.", "org.eclipse.jetty.", "io.undertow.", "org.glassfish.", "org.jboss.", "org.springframework.boot.web."};
            for (Class c : loaded) {
                if (c == null) continue;
                String n = c.getName();
                for (String p : prefixes) {
                    if (n.startsWith(p)) {
                        com.pnones.trace.util.DebugLogger.log("Loaded container class: " + n);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        final ClassPool defaultPool = ClassPool.getDefault();
        // ensure agent classloader is available on default pool
        defaultPool.insertClassPath(new LoaderClassPath(TraceAgent.class.getClassLoader()));

        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (className == null) return null;
                String dotted = className.replace('/', '.');
                // Skip transforming JDBC driver classes (they cause VerifyErrors)
                // Allow connection pool wrappers (HikariCP, DBCP2, etc.) as they're safe to instrument
                try {
                    String skipPackages = TraceConfig.getString("agent.skip.packages",
                            "com.mysql.cj.jdbc.driver;com.mysql.jdbc.driver;org.postgresql.jdbc.PgConnection;oracle.jdbc.driver;com.microsoft.sqlserver.jdbc.SQLServerDriver;com.ibm.db2.jcc.DB2Driver;org.sqlite.jdbc.JDBC;net.sourceforge.jtds.jdbc.Driver;org.mariadb.jdbc.Driver;io.r2dbc.;io.agroal.;org.jkiss.dbeaver.;");
                    if (skipPackages != null && !skipPackages.isEmpty()) {
                        for (String p : skipPackages.split(";")) {
                            p = p.trim();
                            if (p.length() == 0) continue;
                            if (dotted.startsWith(p)) return null;
                        }
                    }
                } catch (Throwable ignored) {}
                // Skip only JDK dynamic proxies that can't be instrumented
                if (dotted.contains("$Proxy") && !dotted.contains("HikariProxy")) {
                    return null;
                }
                // Use a per-transform ClassPool that includes the transforming classloader
                ClassPool pool = null;
                try {
                    pool = new ClassPool(true);
                    pool.appendSystemPath();
                    // ensure agent classes available
                    pool.insertClassPath(new LoaderClassPath(TraceAgent.class.getClassLoader()));
                    if (loader != null) {
                        try {
                            pool.insertClassPath(new LoaderClassPath(loader));
                        } catch (Throwable ignore) {}
                    }
                    CtClass ct = pool.get(dotted);
                    if (ct.isInterface() || ct.isAnnotation()) return null;
                    boolean _pn_modified = false;
                    // instrument Connection.prepareStatement to register SQL
                    try {
                        CtClass conn = pool.get("java.sql.Connection");
                        if (ct.subtypeOf(conn)) {
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("prepareStatement")) {
                                    // after returning PreparedStatement, register mapping
                                    m.insertAfter("{ com.pnones.trace.interceptor.jdbc.JdbcUtils.registerPreparedStatement($_, $1); }");
                                    _pn_modified = true;
                                    instrumentedClasses++;
                                    System.err.println("[PTrace] ✓ JDBC Connection.prepareStatement: " + dotted);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    
                    // instrument PreparedStatement execute methods to capture SQL execution
                    try {
                        CtClass pstmt = pool.get("java.sql.PreparedStatement");
                        if (ct.subtypeOf(pstmt) && !ct.isInterface()) {
                            // Use getMethods() to include inherited methods, not just declared ones
                            for (CtMethod m : ct.getMethods()) {
                                // Only instrument methods declared in this class or its JDBC superclasses
                                // Skip Object methods and other non-JDBC methods
                                String declClass = m.getDeclaringClass().getName();
                                if (!declClass.startsWith("java.sql") && !declClass.startsWith("org.h2") && 
                                    !declClass.startsWith("com.mysql") && !declClass.startsWith("org.postgresql")) {
                                    continue;
                                }
                                
                                String n = m.getName();
                                if (n.equals("execute") || n.equals("executeQuery") || n.equals("executeUpdate") || n.equals("executeBatch")) {
                                    try {
                                        m.insertBefore("{ com.pnones.trace.interceptor.jdbc.JdbcUtils.beforeExecute(this); }");
                                        // Wrap return value for ResultSet capture
                                        if (n.equals("executeQuery")) {
                                            // executeQuery always returns ResultSet - wrap it
                                            m.insertAfter("{ " +
                                                "long _pn_elapsed = System.currentTimeMillis() - ((Long)com.pnones.trace.interceptor.jdbc.JdbcUtils.getStartTime(this)).longValue(); " +
                                                "$_ = (java.sql.ResultSet)com.pnones.trace.interceptor.jdbc.JdbcUtils.wrapAndTrace(this, $_, _pn_elapsed); " +
                                            "}", false);
                                        } else {
                                            // execute/executeUpdate - use standard tracing
                                            m.insertAfter("{ " +
                                                "long _pn_elapsed = System.currentTimeMillis() - ((Long)com.pnones.trace.interceptor.jdbc.JdbcUtils.getStartTime(this)).longValue(); " +
                                                "com.pnones.trace.interceptor.jdbc.JdbcUtils.afterExecute(this, ($w)$_, _pn_elapsed); " +
                                            "}", false);
                                        }
                                        _pn_modified = true;
                                        instrumentedClasses++;
                                        System.err.println("[PTrace] ✓ JDBC PreparedStatement." + n + ": " + dotted);
                                        com.pnones.trace.util.DebugLogger.log("Instrumented PreparedStatement." + n + " in " + dotted);
                                    } catch (Throwable ex) {
                                        // may fail on some implementations, that's ok
                                        com.pnones.trace.util.DebugLogger.log("Failed to instrument " + dotted + "." + n, ex);
                                    }
                                }
                                if (n.equals("getResultSet") && m.getParameterTypes().length == 0) {
                                    try {
                                        m.insertAfter("{ $_ = (java.sql.ResultSet)com.pnones.trace.interceptor.jdbc.JdbcUtils.wrapResultSetOnly(this, (java.sql.ResultSet)$_); }", false);
                                        _pn_modified = true;
                                        com.pnones.trace.util.DebugLogger.log("Instrumented PreparedStatement.getResultSet in " + dotted);
                                    } catch (Throwable ex) {
                                        com.pnones.trace.util.DebugLogger.log("Failed to instrument " + dotted + ".getResultSet", ex);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    // SQL tracing is done via Connection.prepareStatement registration (see above)
                    // Direct Statement instrumentation causes VerifyError with various connection pools
                    // JdbcUtils.registerPreparedStatement + JdbcUtils.afterExecute handles SQL tracking
                    boolean isJdbcStatement = false;
                    // Statement instrumentation disabled to avoid bytecode verification errors
                    
                    if (isJdbcStatement) {
                        com.pnones.trace.util.DebugLogger.log("Instrumenting JDBC Statement: " + dotted);
                        try {
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                String n = m.getName();
                                if (n.equals("executeQuery") || n.equals("execute") || n.equals("executeUpdate") || n.equals("executeBatch")) {
                                    try {
                                        // Extract SQL from the statement object or method parameter
                                        m.addLocalVariable("_pn_token", pool.get("java.lang.Object"));
                                        m.addLocalVariable("_pn_sql", pool.get("java.lang.String"));
                                        
                                        // For Statement.execute(String sql), use $1
                                        // For PreparedStatement.execute(), try to get from toString()
                                        String sqlExtraction = "{ " +
                                                "try { " +
                                                "if ($sig.length > 0 && $sig[0].equals(\"java.lang.String\")) { " +
                                                "_pn_sql = $1; " +
                                                "} else { " +
                                                "try { " +
                                                "_pn_sql = this.toString(); " +
                                                "} catch (Throwable ex) { " +
                                                "_pn_sql = \"<PreparedStatement>\"; " +
                                                "} " +
                                                "} " +
                                                "} catch (Throwable e) { " +
                                                "_pn_sql = \"\"; " +
                                                "} " +
                                                "_pn_token = com.pnones.trace.agent.SimpleSqlTracer.beforeExecute(this, _pn_sql); " +
                                                "}";
                                        
                                        m.insertBefore(sqlExtraction);
                                        m.insertAfter("{ " +
                                                "try { " +
                                                "com.pnones.trace.agent.SimpleSqlTracer.afterExecute(_pn_token, this, $_, null); " +
                                                "} catch (Throwable ignored) {} " +
                                                "}", true); // asFinally=true to handle exceptions
                                        _pn_modified = true;
                                        com.pnones.trace.util.DebugLogger.log("Instrumented SQL method: " + dotted + "." + n);
                                    } catch (Throwable ex) {
                                        com.pnones.trace.util.DebugLogger.log("Failed to instrument SQL method: " + dotted + "." + n, ex);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }

                    // instrument PreparedStatement setX methods to capture parameter values
                    try {
                        CtClass pstmt = pool.get("java.sql.PreparedStatement");
                        if (ct.subtypeOf(pstmt)) {
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                String n = m.getName();
                                if (n.startsWith("set") && m.getParameterTypes().length >= 2) {
                                    try {
                                        // assume first param is int index, second is value (may be primitive) - record boxed value
                                        m.insertAfter("try{ com.pnones.trace.interceptor.jdbc.JdbcUtils.recordParam(this, $1, ($w)$2); }catch(Throwable ignored){};", true);
                                        _pn_modified = true;
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    // MyBatis result capture fallback:
                    // Intercept ResultHandler.handleResult(ResultContext) and capture row object.
                    // This works even when JDBC ResultSet proxy cannot capture rows reliably.
                    try {
                        CtClass rh = pool.get("org.apache.ibatis.session.ResultHandler");
                        if (ct.subtypeOf(rh) && !ct.isInterface()) {
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("handleResult") && m.getParameterTypes().length == 1) {
                                    try {
                                        m.insertBefore(
                                            "{ " +
                                            "  try { " +
                                            "    Object _pn_obj = null; " +
                                            "    try { _pn_obj = $1.getResultObject(); } catch (Throwable ignored) {} " +
                                            "    if (_pn_obj != null) { " +
                                            "      com.pnones.trace.interceptor.mybatis.PTraceResultHandler.captureRow(_pn_obj); " +
                                            "    } " +
                                            "  } catch (Throwable ignored) {} " +
                                            "}"
                                        );
                                        _pn_modified = true;
                                        instrumentedClasses++;
                                        System.err.println("[PTrace] ✓ MyBatis ResultHandler.handleResult: " + dotted);
                                        com.pnones.trace.util.DebugLogger.log("Instrumented MyBatis ResultHandler.handleResult in " + dotted);
                                    } catch (Throwable ex) {
                                        com.pnones.trace.util.DebugLogger.log("Failed to instrument MyBatis handler in " + dotted, ex);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    // MyBatis default internal handlers (strong fallback)
                    // 1) DefaultResultHandler.handleResult(ResultContext)
                    try {
                        if (dotted.equals("org.apache.ibatis.executor.result.DefaultResultHandler")) {
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("handleResult") && m.getParameterTypes().length == 1) {
                                    try {
                                        m.insertBefore(
                                            "{ " +
                                            "  try { " +
                                            "    Object _pn_obj = null; " +
                                            "    try { _pn_obj = $1.getResultObject(); } catch (Throwable ignored) {} " +
                                            "    if (_pn_obj != null) { " +
                                            "      com.pnones.trace.interceptor.mybatis.PTraceResultHandler.captureRow(_pn_obj); " +
                                            "    } " +
                                            "  } catch (Throwable ignored) {} " +
                                            "}"
                                        );
                                        _pn_modified = true;
                                        instrumentedClasses++;
                                        System.err.println("[PTrace] ✓ MyBatis DefaultResultHandler.handleResult: " + dotted);
                                        com.pnones.trace.util.DebugLogger.log("Instrumented MyBatis DefaultResultHandler.handleResult");
                                    } catch (Throwable ex) {
                                        com.pnones.trace.util.DebugLogger.log("Failed to instrument DefaultResultHandler.handleResult", ex);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    // 2) DefaultResultSetHandler.getRowValue(...): capture returned row object
                    try {
                        if (dotted.equals("org.apache.ibatis.executor.resultset.DefaultResultSetHandler")) {
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("getRowValue")) {
                                    try {
                                        m.insertAfter(
                                            "{ " +
                                            "  try { " +
                                            "    if ($_ != null) { " +
                                            "      com.pnones.trace.interceptor.mybatis.PTraceResultHandler.captureRow($_); " +
                                            "    } " +
                                            "  } catch (Throwable ignored) {} " +
                                            "}", false
                                        );
                                        _pn_modified = true;
                                        instrumentedClasses++;
                                        System.err.println("[PTrace] ✓ MyBatis DefaultResultSetHandler.getRowValue: " + dotted);
                                        com.pnones.trace.util.DebugLogger.log("Instrumented MyBatis DefaultResultSetHandler.getRowValue");
                                    } catch (Throwable ex) {
                                        com.pnones.trace.util.DebugLogger.log("Failed to instrument DefaultResultSetHandler.getRowValue", ex);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    // HTTP tracing: Tomcat ApplicationFilterChain.doFilter - PROXY APPROACH
                    try {
                        if (dotted.equals("org.apache.catalina.core.ApplicationFilterChain")) {
                            CtMethod doFilter = ct.getDeclaredMethod("doFilter");
                            
                            // Replace entire doFilter method with wrapped version
                            String wrappedCode = 
                                "{ " +
                                "  javax.servlet.ServletRequest _pn_req = $1; " +
                                "  javax.servlet.ServletResponse _pn_resp = $2; " +
                                "  " +
                                "  try { " +
                                "    if (_pn_req instanceof javax.servlet.http.HttpServletRequest) { " +
                                "      javax.servlet.ServletRequest _pn_wrapped = (javax.servlet.ServletRequest) com.pnones.trace.agent.RequestBodyCapture.wrapRequestIfNeeded(_pn_req); " +
                                "      if (_pn_wrapped != _pn_req) { " +
                                "        com.pnones.trace.util.DebugLogger.log(\"[PTrace] ✓ ApplicationFilterChain.doFilter: request wrapped\"); " +
                                "        _pn_req = _pn_wrapped; " +
                                "      } " +
                                "    } " +
                                "  } catch (Throwable _pn_t) { " +
                                "    com.pnones.trace.util.DebugLogger.log(\"[PTrace] wrap failed\", _pn_t); " +
                                "  } " +
                                "  " +
                                "  Object _pn_token = null; " +
                                "  try { " +
                                "    _pn_token = com.pnones.trace.agent.SimpleHttpTracer.before(_pn_req, _pn_resp); " +
                                "    com.pnones.trace.agent.ApplicationFilterChainTraceContext.set(_pn_token); " +
                                "  } catch (Throwable _pn_t) { " +
                                "    com.pnones.trace.util.DebugLogger.log(\"before hook error\", _pn_t); " +
                                "  } " +
                                "  " +
                                "  try { " +
                                "    internalDoFilter(_pn_req, _pn_resp); " +
                                "  } finally { " +
                                "    try { " +
                                "      Object _pn_token2 = com.pnones.trace.agent.ApplicationFilterChainTraceContext.get(); " +
                                "      com.pnones.trace.agent.SimpleHttpTracer.after(_pn_token2, _pn_resp); " +
                                "    } catch (Throwable _pn_t) { " +
                                "      com.pnones.trace.util.DebugLogger.log(\"after hook error\", _pn_t); " +
                                "    } finally { " +
                                "      com.pnones.trace.agent.ApplicationFilterChainTraceContext.remove(); " +
                                "    } " +
                                "  } " +
                                "} ";
                            
                            doFilter.setBody(wrappedCode);
                            
                            _pn_modified = true;
                            instrumentedClasses++;
                            System.err.println("[PTrace] ✓ ApplicationFilterChain.doFilter replaced");
                            com.pnones.trace.util.DebugLogger.log("Replaced ApplicationFilterChain.doFilter with wrapper");
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to replace ApplicationFilterChain.doFilter", t);
                    }

                    // HTTP tracing: Spring DelegatingFilterProxy - wrap request before application filters
                    try {
                        if (dotted.equals("org.springframework.web.filter.DelegatingFilterProxy")) {
                            // Hook invokeDelegate instead of doFilter to intercept actual filter invocation
                            try {
                                CtMethod invokeDelegate = ct.getDeclaredMethod("invokeDelegate");
                                
                                invokeDelegate.insertBefore(
                                    "{ " +
                                    "  try { " +
                                    "    if ($2 instanceof javax.servlet.http.HttpServletRequest) { " +
                                    "      javax.servlet.ServletRequest _pn_wrapped = (javax.servlet.ServletRequest) com.pnones.trace.agent.RequestBodyCapture.wrapRequestIfNeeded($2); " +
                                    "      if (_pn_wrapped != $2) { " +
                                    "        $2 = _pn_wrapped; " +
                                    "        com.pnones.trace.util.DebugLogger.log(\"[PTrace] Request wrapped in invokeDelegate before: \" + $1.getClass().getName()); " +
                                    "      } " +
                                    "    } " +
                                    "  } catch (Throwable _pn_t) { " +
                                    "    com.pnones.trace.util.DebugLogger.log(\"[PTrace] invokeDelegate wrap error\", _pn_t); " +
                                    "  } " +
                                    "}"
                                );
                                
                                _pn_modified = true;
                                instrumentedClasses++;
                                System.err.println("[PTrace] ✓ Spring DelegatingFilterProxy.invokeDelegate instrumented");
                                com.pnones.trace.util.DebugLogger.log("Instrumented DelegatingFilterProxy.invokeDelegate");
                            } catch (Throwable invokeDelegateErr) {
                                com.pnones.trace.util.DebugLogger.log("invokeDelegate not found, trying doFilter", invokeDelegateErr);
                                
                                // Fallback to doFilter
                                CtMethod doFilter = ct.getDeclaredMethod("doFilter");
                                
                                doFilter.insertBefore(
                                    "{ " +
                                    "  try { " +
                                    "    javax.servlet.ServletRequest _pn_wrapped = (javax.servlet.ServletRequest) com.pnones.trace.agent.RequestBodyCapture.wrapRequestIfNeeded($1); " +
                                    "    if (_pn_wrapped != $1) { " +
                                    "      $1 = _pn_wrapped; " +
                                    "      com.pnones.trace.util.DebugLogger.log(\"[PTrace] Request wrapped in DelegatingFilterProxy.doFilter\"); " +
                                    "    } " +
                                    "  } catch (Throwable _pn_t) { " +
                                    "    com.pnones.trace.util.DebugLogger.log(\"[PTrace] DelegatingFilterProxy wrap error\", _pn_t); " +
                                    "  } " +
                                    "}"
                                );
                                
                                _pn_modified = true;
                                instrumentedClasses++;
                                System.err.println("[PTrace] ✓ Spring DelegatingFilterProxy.doFilter instrumented");
                                com.pnones.trace.util.DebugLogger.log("Instrumented DelegatingFilterProxy.doFilter");
                            }
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to instrument DelegatingFilterProxy", t);
                    }

                    // HTTP tracing: Wrap request in ALL ServletFilter implementations
                    // Universal approach: works for ALL customers, ALL filter implementations
                    // Ensures @RequestBody can read request body regardless of filter type
                    try {
                        // Check if this class implements javax.servlet.Filter
                        boolean isFilter = false;
                        try {
                            for (CtClass iface : ct.getInterfaces()) {
                                if (iface.getName().equals("javax.servlet.Filter") || 
                                    iface.getName().equals("jakarta.servlet.Filter")) {
                                    isFilter = true;
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                        
                        // Apply to ALL Filter implementations (app-specific + framework filters)
                        boolean wrapAllFilters = TraceConfig.getBoolean("agent.wrap-request-in-all-filters", true);
                        if (isFilter && wrapAllFilters) {
                            try {
                                CtMethod doFilter = ct.getDeclaredMethod("doFilter");
                                
                                doFilter.insertBefore(
                                    "{ " +
                                    "  try { " +
                                    "    if ($1 instanceof javax.servlet.http.HttpServletRequest) { " +
                                    "      javax.servlet.ServletRequest _pn_wrapped = (javax.servlet.ServletRequest) com.pnones.trace.agent.RequestBodyCapture.wrapRequestIfNeeded($1); " +
                                    "      if (_pn_wrapped != $1) { " +
                                    "        $1 = _pn_wrapped; " +
                                    "        com.pnones.trace.util.DebugLogger.log(\"[PTrace] Request wrapped in: \" + this.getClass().getName()); " +
                                    "      } " +
                                    "    } " +
                                    "  } catch (Throwable _pn_t) { " +
                                    "    com.pnones.trace.util.DebugLogger.log(\"[PTrace] Filter wrap error in \" + this.getClass().getName(), _pn_t); " +
                                    "  } " +
                                    "}"
                                );
                                
                                _pn_modified = true;
                                instrumentedClasses++;
                                System.err.println("[PTrace] ✓ Filter instrumented: " + dotted);
                                com.pnones.trace.util.DebugLogger.log("Instrumented servlet filter: " + dotted);
                            } catch (Throwable filterErr) {
                                // doFilter method might not exist or already instrumented
                            }
                        }
                    } catch (Throwable t) {
                        // Ignore filter detection errors
                    }

                    // HTTP tracing: Vert.x HTTP server
                    try {
                        if (dotted.equals("io.vertx.core.http.impl.Http1xServerRequest") ||
                            dotted.equals("io.vertx.core.http.impl.Http2ServerRequest")) {
                            // Instrument handleEnd or similar method to capture request/response
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("handleEnd")) {
                                    m.insertBefore("{ " +
                                        "try { " +
                                        "com.pnones.trace.agent.VertxHttpTracer.traceRequest(this); " +
                                        "} catch (Throwable ignored) {} " +
                                        "}");
                                    _pn_modified = true;
                                    com.pnones.trace.util.DebugLogger.log("Instrumented Vert.x HTTP: " + dotted);
                                    break;
                                }
                            }
                        }
                        // Vert.x Web Router handler
                        else if (dotted.equals("io.vertx.ext.web.impl.RoutingContextImpl")) {
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("next")) {
                                    m.insertBefore("{ " +
                                        "try { " +
                                        "if (com.pnones.trace.agent.VertxHttpTracer.shouldTrace(this)) { " +
                                        "com.pnones.trace.agent.VertxHttpTracer.beforeRouting(this); " +
                                        "} " +
                                        "} catch (Throwable ignored) {} " +
                                        "}");
                                    _pn_modified = true;
                                    com.pnones.trace.util.DebugLogger.log("Instrumented Vert.x Router: " + dotted);
                                    break;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to instrument Vert.x HTTP", t);
                    }

                    // Servlet Container Initializer Error Handler (handles Spring 4.x, Logback, old servlet libs)
                    // STRATEGY: Wrap Class.forName() calls to catch and skip jakarta-referencing initializers
                    try {
                        if (dotted.equals("org.apache.catalina.startup.WebappServiceLoader")) {
                            CtMethod loadServicesMethod = null;
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("loadServices")) {
                                    loadServicesMethod = m;
                                    break;
                                }
                            }
                            
                            if (loadServicesMethod != null) {
                                // Prepend code to wrap all operations in try-catch
                                // This catches jakarta errors at the source
                                loadServicesMethod.insertBefore(
                                    "{ java.lang.System.err.println(\"[PTrace] loadServices() called for \" + $1); }"
                                );
                                
                                // Wrap the entire method body in try-catch
                                loadServicesMethod.addCatch(
                                    "{" +
                                    "  if ($e.getMessage() != null && $e.getMessage().contains(\"jakarta\")) {" +
                                    "    java.lang.System.err.println(\"[PTrace] Skipping jakarta initializer: \" + $e.getMessage());" +
                                    "    return new java.util.HashSet();" +
                                    "  }" +
                                    "  throw $e;" +
                                    "}", 
                                    defaultPool.get("java.lang.NoClassDefFoundError")
                                );
                                
                                loadServicesMethod.addCatch(
                                    "{" +
                                    "  if ($e.getMessage() != null && $e.getMessage().contains(\"jakarta\")) {" +
                                    "    java.lang.System.err.println(\"[PTrace] Skipping jakarta initializer: \" + $e.getMessage());" +
                                    "    return new java.util.HashSet();" +
                                    "  }" +
                                    "  throw $e;" +
                                    "}", 
                                    defaultPool.get("java.lang.ClassNotFoundException")
                                );
                                
                                _pn_modified = true;
                                com.pnones.trace.util.DebugLogger.log("Instrumented WebappServiceLoader.loadServices() for jakarta protection");
                            }
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to protect WebappServiceLoader.loadServices()", t);
                    }
                    
                    try {
                        if (dotted.equals("org.springframework.web.server.adapter.HttpWebHandlerAdapter")) {
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("handle")) {
                                    m.insertBefore("{ " +
                                        "Object _pn_token = null; " +
                                        "try { " +
                                        "_pn_token = com.pnones.trace.agent.WebFluxHttpTracer.beforeExchange($1); " +
                                        "} catch (Throwable ignored) {} " +
                                        "}");
                                    
                                    // Return type is Mono<Void>, need to add doOnTerminate/doFinally
                                    m.insertAfter("{ " +
                                        "try { " +
                                        "Object _finalToken = _pn_token; " +
                                        "Object _exchange = $1; " +
                                        "if ($_ != null) { " +
                                        "java.lang.reflect.Method doFinally = $_.getClass().getMethod(\"doFinally\", java.util.function.Consumer.class); " +
                                        "if (doFinally != null) { " +
                                        "$_ = (reactor.core.publisher.Mono) doFinally.invoke($_, new java.util.function.Consumer() { " +
                                        "public void accept(Object signal) { " +
                                        "com.pnones.trace.agent.WebFluxHttpTracer.afterExchange(_exchange, _finalToken); " +
                                        "} " +
                                        "}); " +
                                        "} " +
                                        "} " +
                                        "} catch (Throwable ignored) {} " +
                                        "}", false);
                                    
                                    _pn_modified = true;
                                    com.pnones.trace.util.DebugLogger.log("Instrumented WebFlux HTTP: " + dotted);
                                    break;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to instrument WebFlux HTTP", t);
                    }

                    // HTTP tracing: Undertow (HttpServerExchange through HttpHandler)
                    try {
                        if (dotted.equals("io.undertow.server.HttpServerExchange")) {
                            // Add instrumentation to dispatch method
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("dispatch") && m.getParameterTypes().length == 0) {
                                    m.insertBefore("{ " +
                                        "try { " +
                                        "com.pnones.trace.agent.UndertowHttpTracer.before(this); " +
                                        "} catch (Throwable ignored) {} " +
                                        "}");
                                    _pn_modified = true;
                                    com.pnones.trace.util.DebugLogger.log("Instrumented Undertow HTTP: " + dotted);
                                    break;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to instrument Undertow HTTP", t);
                    }

                    // HTTP tracing: Jetty (HttpChannel)
                    try {
                        if (dotted.equals("org.eclipse.jetty.server.HttpChannel") ||
                            dotted.equals("org.eclipse.jetty.server.Request")) {
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("handle")) {
                                    m.insertBefore("{ " +
                                        "Object _pn_token = null; " +
                                        "try { " +
                                        "Object _request = this; " +
                                        "Object _response = null; " +
                                        "try { " +
                                        "java.lang.reflect.Method getResponse = this.getClass().getMethod(\"getResponse\"); " +
                                        "_response = getResponse.invoke(this); " +
                                        "} catch (Throwable ignored) {} " +
                                        "_pn_token = com.pnones.trace.agent.JettyHttpTracer.before(_request, _response); " +
                                        "} catch (Throwable ignored) {} " +
                                        "}");
                                    m.insertAfter("{ " +
                                        "try { " +
                                        "Object _request = this; " +
                                        "Object _response = null; " +
                                        "try { " +
                                        "java.lang.reflect.Method getResponse = this.getClass().getMethod(\"getResponse\"); " +
                                        "_response = getResponse.invoke(this); " +
                                        "} catch (Throwable ignored) {} " +
                                        "com.pnones.trace.agent.JettyHttpTracer.after(_request, _response, _pn_token); " +
                                        "} catch (Throwable ignored) {} " +
                                        "}", true);
                                    _pn_modified = true;
                                    com.pnones.trace.util.DebugLogger.log("Instrumented Jetty HTTP: " + dotted);
                                    break;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to instrument Jetty HTTP", t);
                    }

                    // HTTP tracing: GlassFish/Payara (PEApplicationFilterChain)
                    try {
                        if (dotted.equals("com.sun.enterprise.web.connector.coyote.PEApplicationFilterChain")) {
                            CtMethod doFilter = ct.getDeclaredMethod("doFilter");

                            doFilter.insertBefore(
                                "{ " +
                                "  Object _pn_token = null; " +
                                "  try { " +
                                "    _pn_token = com.pnones.trace.agent.GlassFishHttpTracer.before($1, $2); " +
                                "  } catch (Throwable ignored) {} " +
                                "}"
                            );

                            doFilter.insertAfter(
                                "{ " +
                                "  try { " +
                                "    Object _pn_token = $1; " +
                                "    com.pnones.trace.agent.GlassFishHttpTracer.after(_pn_token, $2); " +
                                "  } catch (Throwable ignored) {} " +
                                "}",
                                true
                            );
                            
                            _pn_modified = true;
                            com.pnones.trace.util.DebugLogger.log("Instrumented GlassFish PEApplicationFilterChain.doFilter");
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to instrument GlassFish HTTP", t);
                    }

                    // HTTP tracing: JBoss/WildFly (RequestProcessor or custom servlet chain)
                    try {
                        if (dotted.equals("org.jboss.web.tomcat.service.request.ActiveRequest") ||
                            dotted.equals("org.jboss.as.web.WebContainer")) {
                            for (CtMethod m : ct.getDeclaredMethods()) {
                                if (m.getName().equals("invoke") || m.getName().equals("service")) {
                                    m.insertBefore("{ " +
                                        "try { " +
                                        "com.pnones.trace.agent.JBossHttpTracer.traceJBossServletRequest($1, $2); " +
                                        "} catch (Throwable ignored) {} " +
                                        "}");
                                    _pn_modified = true;
                                    com.pnones.trace.util.DebugLogger.log("Instrumented JBoss HTTP: " + dotted);
                                    break;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to instrument JBoss HTTP", t);
                    }

                    // HTTP tracing: JBoss Undertow handler (WildFly 8+)
                    // Note: Undertow instrumentation above already covers this, but adding JBoss-specific path
                    try {
                        if (dotted.equals("org.wildfly.extension.undertow.deployment.UndertowDeploymentInfoService") ||
                            dotted.contains("undertow")) {
                            // Already covered by Undertow instrumentation above
                            // This is a safety check for WildFly-specific Undertow wrappers
                        }
                    } catch (Throwable ignored) {}

                    // HTTP tracing: Jeus (TmaxSoft Java Enterprise User Suite)
                    try {
                        if (dotted.equals("jeus.servlet.DefaultFilterChain") ||
                            dotted.equals("org.jeus.servlet.FilterChain") ||
                            dotted.equals("jeus.servlet.FilterChain")) {
                            CtMethod doFilter = ct.getDeclaredMethod("doFilter");

                            doFilter.insertBefore(
                                "{ " +
                                "  Object _pn_token = null; " +
                                "  try { " +
                                "    _pn_token = com.pnones.trace.agent.JeusHttpTracer.before($1, $2); " +
                                "  } catch (Throwable ignored) {} " +
                                "}"
                            );

                            doFilter.insertAfter(
                                "{ " +
                                "  try { " +
                                "    Object _pn_token = $1; " +
                                "    com.pnones.trace.agent.JeusHttpTracer.after(_pn_token, $2); " +
                                "  } catch (Throwable ignored) {} " +
                                "}",
                                true
                            );
                            
                            _pn_modified = true;
                            com.pnones.trace.util.DebugLogger.log("Instrumented Jeus FilterChain.doFilter");
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to instrument Jeus HTTP", t);
                    }

                    // Note: Response body capture via direct OutputStream instrumentation causes VerifyError
                    // because return type must match ServletOutputStream, not OutputStream.
                    // For universal WAS support, response body capture should be implemented via:
                    // 1. Servlet Filter with response wrapper (recommended)
                    // 2. Response buffer reading after commit (limited to buffered responses)
                    // 3. WAS-specific implementations
                    //
                    // SQL tracing is working through PreparedStatement execute() instrumentation.

                    
                    byte[] b = ct.toBytecode();
                    if (_pn_modified) {
                        com.pnones.trace.util.DebugLogger.log("Instrumented class: " + dotted);
                    }
                    ct.detach();
                    return b;
                } catch (javassist.NotFoundException nf) {
                    // Common: class not available in this transformer's classpath — skip quietly
                    return null;
                } catch (Throwable t) {
                    com.pnones.trace.util.DebugLogger.log("Transformer error for class " + dotted, t);
                    return null;
                }
            }
        };

        inst.addTransformer(transformer, true);
        // Attempt selective retransformation for common container/servlet classes already loaded.
        try {
                if (inst.isRetransformClassesSupported()) {
                    String[] toRetransform = new String[]{
                            "org.apache.catalina.core.ApplicationFilterChain",
                            "org.apache.catalina.core.StandardContext",
                            "org.springframework.web.servlet.DispatcherServlet",
                            "org.springframework.web.servlet.FrameworkServlet",
                            "org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext",
                            "org.springframework.web.server.adapter.HttpWebHandlerAdapter",
                            "org.eclipse.jetty.servlet.ServletContextHandler",
                            "org.eclipse.jetty.server.HttpChannel",
                            "org.eclipse.jetty.server.Request",
                            "io.undertow.servlet.core.DeploymentManager",
                            "io.undertow.server.HttpServerExchange",
                            "io.vertx.core.http.impl.Http1xServerRequest",
                            "io.vertx.core.http.impl.Http2ServerRequest",
                            "io.vertx.ext.web.impl.RoutingContextImpl"
                    };
                    // build simple name set for endsWith matching
                    java.util.Set<String> simpleNames = new java.util.HashSet<>();
                    for (String s : toRetransform) {
                        if (s == null) continue;
                        int idx = s.lastIndexOf('.');
                        if (idx >= 0 && idx < s.length()-1) simpleNames.add(s.substring(idx+1));
                        simpleNames.add(s);
                    }
                    try {
                        Class<?>[] loaded = inst.getAllLoadedClasses();
                        for (Class<?> lc : loaded) {
                            if (lc == null) continue;
                            String name = lc.getName();
                            boolean matches = false;
                            if (simpleNames.contains(name)) matches = true;
                            for (String sn : simpleNames) {
                                if (name.endsWith(sn)) { matches = true; break; }
                                if (name.contains(sn) && name.length() - sn.length() > 0 && name.charAt(name.length()-sn.length()-1)=='.') { matches = true; break; }
                            }
                            if (!matches) continue;
                            try {
                                if (inst.isModifiableClass(lc)) {
                                    com.pnones.trace.util.DebugLogger.log("Retransforming detected loaded class: " + name);
                                    try { inst.retransformClasses(lc); } catch (Throwable t) { com.pnones.trace.util.DebugLogger.log("Retransform failed for " + name, t); }
                                } else {
                                    com.pnones.trace.util.DebugLogger.log("Detected but not modifiable: " + name);
                                }
                            } catch (Throwable t) {
                                com.pnones.trace.util.DebugLogger.log("Error while attempting retransform on " + name, t);
                            }
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Error scanning loaded classes for retransformation", t);
                    }
                } else {
                    com.pnones.trace.util.DebugLogger.log("Retransform not supported by JVM");
                }
        } catch (Throwable ignored) {}
        // print ASCII banner on startup to make agent presence obvious
        try {
            System.err.println("\n=====================================================");
            System.err.println("  ____  _                ");
            System.err.println(" |  _ \\| | ___  ___ _ __ ");
            System.err.println(" | |_) | |/ _ \\/ _ \\` '__|");
            System.err.println(" |  __/| |  __/  __/ |   ");
            System.err.println(" |_|   |_|\\___|\\____|_|   ");
            System.err.println(" :: PTrace Agent :: (v0.1.0)");
            System.err.println("=====================================================");
            System.err.println("[PTrace Agent] ✓ AGENT LOADED SUCCESSFULLY");
            System.err.println("[PTrace Agent] ✓ Instrumented " + instrumentedClasses + " classes");
            System.err.println("[PTrace Agent] ✓ Transformer registered");
            System.err.println("=====================================================");
            System.err.println("[PTrace Agent] Waiting for HTTP requests and SQL queries...");
            System.err.println("=====================================================");
            System.err.flush();
        } catch (Throwable ignored) {}
        // Note: retransformation of already-loaded application classes is intentionally omitted
        // to avoid triggering classloader/service-loader interactions during JVM startup.
        // write agent started marker
        try {
            String root = com.pnones.trace.config.TraceConfig.getString("log.dir", "./logs");
            java.io.File d = new java.io.File(root);
            if (!d.exists()) d.mkdirs();
            java.io.File f = new java.io.File(d, "agent-started.txt");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(f, true))) {
                pw.println(System.currentTimeMillis() + " - Agent premain loaded. args=" + agentArgs);
            }
        } catch (Exception ignored) {}
    }
}
