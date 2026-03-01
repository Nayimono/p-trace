package com.pnones.trace.interceptor.jdbc;

import com.google.gson.Gson;
import com.pnones.trace.logger.SqlTraceLogger;
import com.pnones.trace.model.SqlTraceRecord;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class JdbcUtils {
    private static final Map<Statement, String> preparedSql = Collections.synchronizedMap(new WeakHashMap<Statement, String>());
    private static final Map<Statement, Map<Integer, Object>> preparedParams = Collections.synchronizedMap(new WeakHashMap<Statement, Map<Integer, Object>>());
    // Changed to WeakHashMap to prevent memory leak - ConcurrentHashMap keeps strong references
    private static final Map<Statement, Long> startTime = Collections.synchronizedMap(new WeakHashMap<Statement, Long>());
    private static final Map<ResultSet, ResultSetProxy> resultSetProxies = Collections.synchronizedMap(new WeakHashMap<ResultSet, ResultSetProxy>());
    private static final SqlTraceLogger logger = new SqlTraceLogger();
    private static final Gson gson = new Gson();
    
    // ThreadLocal approach: Store SQL for current thread
    // This works regardless of wrapper layers (log4jdbc, HikariCP, etc.)
    private static final ThreadLocal<String> threadLocalSql = new ThreadLocal<>();
    private static final ThreadLocal<Map<Integer, Object>> threadLocalParams = new ThreadLocal<>();
    private static final ThreadLocal<Long> resultSetProxyStartTime = new ThreadLocal<>();
    private static final Map<Statement, Map<String, Object>> pendingSqlInfoByStmt = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Statement, SqlTraceRecord> pendingSqlRecordByStmt = Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean sqlEnabled() { return com.pnones.trace.config.TraceConfig.getBoolean("sql.enabled", true); }
    private static long sqlSlowThreshold() { return com.pnones.trace.config.TraceConfig.getLong("sql.slow-threshold-ms", 1000); }
    private static int sqlStackDepth() { return com.pnones.trace.config.TraceConfig.getInt("sql.stack-depth", 10); }
    private static int sqlResultMaxRows() { return com.pnones.trace.config.TraceConfig.getInt("sql.result.max-rows", 100); }
    private static boolean sqlResultCaptureData() { return com.pnones.trace.config.TraceConfig.getBoolean("sql.result.capture-data", true); }
    private static String sqlResultCaptureMode() { return com.pnones.trace.config.TraceConfig.getString("sql.result.capture-mode", "FULL"); }
    private static int sqlResultCaptureWaitMs() { return com.pnones.trace.config.TraceConfig.getInt("sql.result.capture-wait-ms", 500); }
    private static String sqlResultCaptureStrategy() { return com.pnones.trace.config.TraceConfig.getString("sql.result.capture-strategy", "AUTO"); }

    public static void registerPreparedStatement(Object stmt, String sql) {
        try {
            // Store in ThreadLocal (works with all wrappers)
            if (sql != null && !sql.isEmpty()) {
                threadLocalSql.set(sql);
            }
            
            // Also store in map as fallback
            if (!(stmt instanceof Statement)) return;
            Statement s = (Statement) stmt;
            
            preparedSql.put(s, sql);
        } catch (Throwable ignored) {}
    }

    public static void recordParam(Object stmt, int idx, Object val) {
        try {
            // Store in ThreadLocal (works with all wrappers)
            Map<Integer, Object> params = threadLocalParams.get();
            if (params == null) {
                params = new java.util.HashMap<>();
                threadLocalParams.set(params);
            }
            params.put(idx, val);
            
            // Also store in map as fallback
            if (!(stmt instanceof Statement)) return;
            Statement s = (Statement) stmt;
            Map<Integer, Object> m = preparedParams.get(s);
            if (m == null) {
                m = new java.util.HashMap<>();
                preparedParams.put(s, m);
            }
            m.put(idx, val);
        } catch (Throwable ignored) {}
    }

    private static Map<Integer,Object> takeParams(Statement s) {
        try {
            // Try ThreadLocal first
            Map<Integer,Object> threadParams = threadLocalParams.get();
            if (threadParams != null && !threadParams.isEmpty()) {
                // Clean up and return copy
                threadLocalParams.remove();
                return new java.util.LinkedHashMap<>(threadParams);
            }
            
            // Fallback to map
            Map<Integer,Object> m = preparedParams.remove(s);
            return m == null ? null : new java.util.LinkedHashMap<>(m);
        } catch (Throwable ignored) { 
            return null; 
        }
    }

    public static void beforeExecute(Object stmt) {
        if (stmt instanceof Statement) {
            Statement s = (Statement) stmt;
            startTime.put(s, System.currentTimeMillis());
        }
    }
    
    public static Long getStartTime(Object stmt) {
        if (stmt instanceof Statement) {
            Long t = startTime.get((Statement) stmt);
            return t != null ? t : System.currentTimeMillis();
        }
        return System.currentTimeMillis();
    }
    
    /**
     * Wrap ResultSet with proxy to capture data transparently as MyBatis reads it.
     * This is called by bytecode instrumentation right after executeQuery() returns.
     */
    public static ResultSet wrapResultSetOnly(Object stmt, ResultSet rs) {
        try {
            if (!sqlEnabled() || !sqlResultCaptureData()) {
                return rs;
            }
            if (rs == null) {
                return null;
            }
            
            // Wrap ResultSet with improved Proxy that captures data on each rs.next() call
            ResultSet wrappedRs = ResultSetProxy.wrap(rs, sqlResultMaxRows());
            
            // Extract proxy handler and store for later retrieval in afterExecute()
            if (wrappedRs != rs && java.lang.reflect.Proxy.isProxyClass(wrappedRs.getClass())) {
                java.lang.reflect.InvocationHandler handler = java.lang.reflect.Proxy.getInvocationHandler(wrappedRs);
                if (handler instanceof ResultSetProxy) {
                    resultSetProxies.put(wrappedRs, (ResultSetProxy) handler);
                    System.err.println("[PTrace] JDBC: ResultSet wrapped with proxy");
                }
            }
            
            return wrappedRs;
        } catch (Exception e) {
            System.err.println("[PTrace] JDBC: Failed to wrap ResultSet: " + e.getMessage());
            return rs; // Return original on error
        }
    }

    public static void afterExecute(Object stmt, Object result, long elapsedMs) {
        Object wrappedResult = afterExecuteInternal(stmt, result, elapsedMs);
        // Note: wrappedResult may be a proxied ResultSet, but we can't return it here
        // because this method is void. Use afterExecuteAndWrap if you need the wrapped result.
    }
    
    private static Object afterExecuteInternal(Object stmt, Object result, long elapsedMs) {
        try {
            if (!sqlEnabled()) return result;
            if (!(stmt instanceof Statement)) return result;
            Statement s = (Statement) stmt;
            
            // Try to get SQL from ThreadLocal first (most reliable)
            String sql = threadLocalSql.get();
            
            // Fallback to map lookup
            if (sql == null || sql.isEmpty()) {
                sql = preparedSql.get(s);
            }
            
            // Last resort: extract from wrapper
            if (sql == null || sql.isEmpty()) {
                sql = extractSqlFromWrapper(s);
            }
            
            if (sql == null) sql = "";
            
            // Clean up ThreadLocal to prevent memory leak
            threadLocalSql.remove();
            
            // Clean up maps to prevent Connection leak
            try {
                startTime.remove(s);
                preparedSql.remove(s);
                preparedParams.remove(s);
            } catch (Throwable ignored) {}
            
            // exclude patterns
            String[] excludes = com.pnones.trace.config.TraceConfig.getList("sql.exclude-pattern", ";");
            for (String ex : excludes) {
                if (ex == null || ex.isEmpty()) continue;
                try {
                    if (sql.toUpperCase().matches(ex.toUpperCase())) return result;
                } catch (Exception ignored) {}
            }

            SqlTraceRecord rec = new SqlTraceRecord();
            rec.setSql(sql);
            rec.setElapsedMs(elapsedMs);
            rec.setCaller(Thread.currentThread().getName());
            rec.setThreadId(Thread.currentThread().getId());
            try { rec.setRequestId(com.pnones.trace.util.RequestContext.getRequestId()); } catch (Throwable ignored) {}
            // stack
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            List<String> frames = new java.util.ArrayList<>();
            int depth = Math.min(sqlStackDepth(), st.length);
            for (int i = 0; i < depth; i++) frames.add(st[i].toString());
            rec.setCallerStack(frames);

            // Extract ResultSet from various return types
            // If result is ResultSet, wrap it with proxy for data capture
            ResultSet resultSet = null;
            Object wrappedResult = result;
            try {
                if (result instanceof ResultSet) {
                    // executeQuery() returns ResultSet directly
                    resultSet = (ResultSet) result;
                    
                    // Wrap with proxy if not already wrapped
                    ResultSet proxiedRs = wrapResultSetOnly(stmt, resultSet);
                    if (proxiedRs != resultSet) {
                        wrappedResult = proxiedRs;
                        resultSet = proxiedRs;
                    }
                } else if (result instanceof Boolean) {
                    // execute() returns boolean - need to call getResultSet()
                    if (((Boolean) result).booleanValue()) {
                        resultSet = s.getResultSet();
                    }
                }
            } catch (Throwable ignored) {}

            ResultSetProxy pendingProxyForAsync = null;
            boolean pendingAsyncCapture = false;
            if (resultSet != null && sqlResultCaptureData()) {
                try {
                    // Check if ResultSet was wrapped with proxy
                    ResultSetProxy proxy = resultSetProxies.get(resultSet);
                    
                    if (proxy != null) {
                        // Data was captured by proxy as MyBatis read it
                        List<Map<String, Object>> capturedData = proxy.getCapturedRows();
                        long dataRowCount = capturedData.stream()
                            .filter(m -> !m.containsKey("_columns"))
                            .count();
                        
                        if (dataRowCount > 0) {
                            rec.setResult(capturedData);
                            rec.setResultRowCount((int) dataRowCount);
                            System.err.println("[PTrace] JDBC: Captured " + dataRowCount + " rows from proxy");
                        } else {
                            // No rows read yet by application: defer final logging for async capture
                            rec.setResult(capturedData);
                            rec.setResultRowCount(0);
                            pendingProxyForAsync = proxy;
                            pendingAsyncCapture = true;
                            System.err.println("[PTrace] JDBC: Deferring log until rows are consumed by application");
                        }

                        // Clean up immediately only when no async capture is needed
                        if (pendingProxyForAsync == null) {
                            resultSetProxies.remove(resultSet);
                        }
                    } else {
                        // Fallback: capture metadata now and defer async capture from MyBatis handler
                        captureMetadataFromResultSet(resultSet, rec);
                        pendingAsyncCapture = true;
                    }
                } catch (Throwable e) {
                    System.err.println("[PTrace] JDBC: ResultSet processing error: " + e.getMessage());
                }
            }
            
            // include prepared statement parameters if any
            try {
                Map<Integer,Object> params = takeParams(s);
                if (params != null) rec.setParams(params);
            } catch (Throwable ignored) {}
            // slow tag
            if (elapsedMs >= sqlSlowThreshold()) {
                // optionally we could add a flag in record; writing SQL as-is for now
            }
            
            Map<String, Object> sqlInfoRef = null;
            // Add SQL to RequestContext so it can be linked to HTTP request
            try {
                com.pnones.trace.agent.RequestContext ctx = com.pnones.trace.agent.RequestContext.get();
                if (ctx != null) {
                    Map<String, Object> sqlInfo = new java.util.concurrent.ConcurrentHashMap<>();
                    sqlInfoRef = sqlInfo;
                    sqlInfo.put("sql", sql);
                    sqlInfo.put("elapsedMs", elapsedMs);
                    if (rec.getParams() != null) {
                        sqlInfo.put("params", rec.getParams());
                    }
                    // Add result data for SELECT queries
                    if (sqlResultCaptureData() && rec.getResult() != null) {
                        sqlInfo.put("resultData", rec.getResult());
                        sqlInfo.put("resultRows", rec.getResultRowCount());
                    } else if (rec.getResultRowCount() > 0) {
                        // Only row count available (no data captured)
                        sqlInfo.put("resultRows", rec.getResultRowCount());
                    }
                    if (pendingAsyncCapture) {
                        sqlInfo.put("pendingResult", true);
                    }
                    ctx.addSqlQuery(sqlInfo);
                }
            } catch (Throwable ignored) {}
            
            // Defer logging until rows are consumed (proxy or MyBatis handler)
            if (pendingAsyncCapture) {
                scheduleAsyncDataCapture(rec, pendingProxyForAsync, s, sqlInfoRef);
            } else {
                logger.log(rec);
            }
            
            // Return wrapped ResultSet if we wrapped it, otherwise original result
            return wrappedResult;
        } catch (Throwable t) {
            System.err.println("[PTrace] JDBC: afterExecuteInternal error: " + t.getMessage());
        }
        
        return result; // Return original on error
    }
    
    /**
     * Wraps ResultSet for data capture and performs tracing.
     * Returns wrapped ResultSet that captures data as application reads it.
     */
    public static Object wrapAndTrace(Object stmt, Object result, long elapsedMs) {
        try {
            return afterExecuteInternal(stmt, result, elapsedMs);
        } catch (Throwable t) {
            // ignore
        }
        
        return result; // Return original if wrapping failed or not applicable
    }
    
    /**
     * Try to get rows captured by MyBatis ResultHandler
     */
    private static List<Map<String, Object>> tryGetCapturedRowsFromHandler() {
        try {
            Class<?> handlerClass = Class.forName("com.pnones.trace.interceptor.mybatis.PTraceResultHandler");
            java.lang.reflect.Method getCapturedRows = handlerClass.getMethod("getCapturedRows");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) getCapturedRows.invoke(null);
            
            if (rows != null && !rows.isEmpty()) {
                // Clear for next query
                java.lang.reflect.Method clearRows = handlerClass.getMethod("clearCapturedRows");
                clearRows.invoke(null);
                return rows;
            }
        } catch (Throwable ignored) {
            // Handler not available or no rows captured
        }
        return new ArrayList<>();
    }

    private static List<Map<String, Object>> tryGetCapturedRowsFromHandler(long threadId) {
        try {
            Class<?> handlerClass = Class.forName("com.pnones.trace.interceptor.mybatis.PTraceResultHandler");
            try {
                java.lang.reflect.Method m = handlerClass.getMethod("getCapturedRowsByThread", long.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) m.invoke(null, threadId);
                return rows != null ? rows : new ArrayList<>();
            } catch (NoSuchMethodException ignore) {
                return tryGetCapturedRowsFromHandler();
            }
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static void clearCapturedRowsFromHandler(long threadId) {
        try {
            Class<?> handlerClass = Class.forName("com.pnones.trace.interceptor.mybatis.PTraceResultHandler");
            try {
                java.lang.reflect.Method m = handlerClass.getMethod("clearCapturedRowsByThread", long.class);
                m.invoke(null, threadId);
                return;
            } catch (NoSuchMethodException ignore) {
                // fallback below
            }
            java.lang.reflect.Method clearRows = handlerClass.getMethod("clearCapturedRows");
            clearRows.invoke(null);
        } catch (Throwable ignored) {}
    }
    
    /**
     * Capture metadata (column names, types) from ResultSet
     * Works for all DBMS and ResultSet types
     */
    private static void captureMetadataFromResultSet(ResultSet rs, SqlTraceRecord rec) {
        try {
            ResultSetMetaData md = rs.getMetaData();
            List<String> columnNames = new ArrayList<>();
            List<String> columnTypes = new ArrayList<>();
            int colCount = md.getColumnCount();
            
            for (int i = 1; i <= colCount; i++) {
                columnNames.add(md.getColumnLabel(i));
                try {
                    columnTypes.add(md.getColumnTypeName(i));
                } catch (Throwable ignored) {
                    columnTypes.add("?");
                }
            }
            
            if (!columnNames.isEmpty()) {
                Map<String, Object> metadata = new java.util.HashMap<>();
                metadata.put("_columns", columnNames);
                metadata.put("_columnCount", columnNames.size());
                metadata.put("_columnTypes", columnTypes);
                rec.setResult(java.util.Collections.singletonList(metadata));
                rec.setResultRowCount(0);
            }
        } catch (Throwable ignored) {}
    }
    
    /**
     * Capture data from any ResultSet (both scrollable and forward-only)
     * Works for all DBMS and connection pool types
     */
    private static void captureDataFromScrollableResultSet(ResultSet rs, SqlTraceRecord rec) {
        try {
            boolean isScrollable = false;
            try {
                int type = rs.getType();
                isScrollable = (type == ResultSet.TYPE_SCROLL_INSENSITIVE || type == ResultSet.TYPE_SCROLL_SENSITIVE);
            } catch (Exception ignored) {}
            
            // Read data - extractResultSetData() handles cursor position preservation for scrollable ResultSets
            List<Map<String, Object>> extracted = extractResultSetData(rs, sqlResultMaxRows());
            if (!extracted.isEmpty()) {
                rec.setResult(extracted);
                long dataRowCount = extracted.stream()
                    .filter(m -> !m.containsKey("_columns"))
                    .count();
                rec.setResultRowCount((int) dataRowCount);
                System.err.println("[PTrace] JDBC: Captured " + dataRowCount + " rows (Scrollable=" + isScrollable + ")");
            }
        } catch (Throwable e) {
            System.err.println("[PTrace] JDBC: Data extraction failed: " + e.getMessage());
        }
    }
    
    /**
     * Detect database product name from ResultSet
     * Supports: Oracle, PostgreSQL, MySQL, MariaDB, MSSQL, H2, Derby, etc.
     */
    private static String detectDatabase(ResultSet rs) {
        try {
            java.sql.Connection conn = rs.getStatement().getConnection();
            java.sql.DatabaseMetaData dbMeta = conn.getMetaData();
            String productName = dbMeta.getDatabaseProductName();
            String version = dbMeta.getDatabaseProductVersion();
            return productName + " v" + version;
        } catch (Throwable ignored) {
            return "UNKNOWN";
        }
    }
    
    /**
     * Schedule async data capture from Proxy ResultSet
     * Periodically checks for captured data and re-logs when complete
     */
    private static void scheduleAsyncDataCapture(SqlTraceRecord rec, ResultSetProxy handler, Statement stmt, Map<String, Object> sqlInfoRef) {
        Thread asyncThread = new Thread(() -> {
            try {
                int maxWaitMs = sqlResultCaptureWaitMs();
                int intervalMs = 50;
                int maxIter = maxWaitMs / intervalMs;
                long threadId = rec.getThreadId();
                
                // Poll for data collection to complete
                for (int i = 0; i < maxIter; i++) {
                    Thread.sleep(intervalMs);

                    List<Map<String, Object>> capturedData = new ArrayList<>();
                    if (handler != null) {
                        try {
                            capturedData = handler.getCapturedRows();
                        } catch (Throwable ignored) {}
                    }

                    long dataRowCount = capturedData.stream()
                        .filter(m -> !m.containsKey("_columns"))
                        .count();

                    if (dataRowCount == 0) {
                        List<Map<String, Object>> mybatisRows = tryGetCapturedRowsFromHandler(threadId);
                        if (mybatisRows != null && !mybatisRows.isEmpty()) {
                            dataRowCount = mybatisRows.size();
                            List<Map<String, Object>> merged = new ArrayList<>();
                            if (!capturedData.isEmpty()) {
                                for (Map<String, Object> m : capturedData) {
                                    if (m != null && m.containsKey("_columns")) {
                                        merged.add(m);
                                    }
                                }
                            }
                            merged.addAll(mybatisRows);
                            capturedData = merged;
                        }
                    }
                    
                    // If data captured or max wait time reached, update and log
                    if (dataRowCount > 0 || i >= maxIter - 1) {
                        if (dataRowCount > 0) {
                            rec.setResult(capturedData);
                            rec.setResultRowCount((int) dataRowCount);
                            if (sqlInfoRef != null) {
                                sqlInfoRef.put("resultData", capturedData);
                                sqlInfoRef.put("resultRows", (int) dataRowCount);
                                sqlInfoRef.remove("pendingResult");
                            }
                            System.err.println("[PTrace] JDBC: Async collected " + dataRowCount + " rows after " + (i * intervalMs) + "ms");
                        } else if (sqlInfoRef != null) {
                            sqlInfoRef.remove("pendingResult");
                        }
                        // Re-log with captured data
                        logger.log(rec);
                        clearCapturedRowsFromHandler(threadId);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                System.err.println("[PTrace] JDBC: Async capture error: " + e.getMessage());
                // Log original record even if async collection fails
                logger.log(rec);
            } finally {
                // Cleanup proxy
                try {
                    // Find and cleanup wrapped ResultSet from registry
                    if (handler != null) {
                        resultSetProxies.values().removeIf(h -> h == handler);
                    }
                } catch (Throwable ignored) {}
            }
        }, "PTrace-AsyncCapture-" + System.currentTimeMillis());
        asyncThread.setDaemon(true);
        asyncThread.start();
    }
    
    /**
     * Legacy method - kept for compatibility
     */
    public static Object afterExecuteAndWrap(Object stmt, Object result, long elapsedMs) {
        return afterExecuteInternal(stmt, result, elapsedMs);
    }

    /**
     * Collects data from ResultSet proxy before it's closed
     */
    public static void collectResultSetData(Object rs) {
        try {
            if (rs instanceof ResultSet) {
                ResultSetProxy proxy = resultSetProxies.remove((ResultSet) rs);
                if (proxy != null) {
                    // Data already collected in afterExecute
                    // Just cleanup
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Extract all rows from current cursor position
     * Respects max-rows limit
     */
    private static List<Map<String, Object>> extractResultSetData(ResultSet rs, int maxRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            
            // Extract metadata ONLY - do not touch cursor to avoid breaking MyBatis
            List<String> columnNames = new ArrayList<>();
            List<String> columnTypes = new ArrayList<>();
            for (int c = 1; c <= cols; c++) {
                columnNames.add(md.getColumnLabel(c));
                try {
                    columnTypes.add(md.getColumnTypeName(c));
                } catch (Exception ignored) {
                    columnTypes.add("?");
                }
            }
            
            Map<String, Object> metadataMap = new LinkedHashMap<>();
            metadataMap.put("_columns", columnNames);
            metadataMap.put("_columnCount", cols);
            metadataMap.put("_columnTypes", columnTypes);
            metadataMap.put("_note", "Data rows not captured to avoid interfering with ORM framework");
            rows.add(metadataMap);
            
            // CRITICAL: Do NOT call rs.next() here!
            // Reason: By the time afterExecute() is called, MyBatis/Hibernate is already
            // consuming the ResultSet. Any cursor movement will cause NullPointerException
            // in the application because data becomes unavailable.
            //
            // To capture actual row data safely, we would need to:
            // 1. Intercept at Statement.executeQuery() BEFORE returning ResultSet
            // 2. Wrap ResultSet with a Proxy that captures data as MyBatis reads it
            // 3. Or implement MyBatis Plugin to intercept at ORM level
            //
            // Current approach: Log metadata only (columns, types) which is safe.
            
        } catch (Exception e) {
            System.err.println("[PTrace] JDBC: Metadata extraction failed: " + e.getMessage());
        }
        return rows;
    }

    /**
     * Legacy method for backward compatibility
     */
    public static List<Map<String, Object>> extract(ResultSet rs) {
        return extractResultSetData(rs, sqlResultMaxRows());
    }
    
    /**
     * Extract SQL from various JDBC wrapper implementations (log4jdbc, HikariCP, etc.)
     * Uses reflection to access internal fields
     */
    private static String extractSqlFromWrapper(Statement stmt) {
        if (stmt == null) return null;
        
        String className = stmt.getClass().getName();
        
        try {
            // ============================================================
            // 1. log4jdbc PreparedStatementSpy
            // ============================================================
            if (className.contains("log4jdbc") || className.contains("PreparedStatementSpy")) {
                // Try method: getSqlWithValues() or getQuery()
                for (String methodName : new String[]{"getSqlWithValues", "getQuery", "getSql"}) {
                    try {
                        java.lang.reflect.Method m = stmt.getClass().getMethod(methodName);
                        Object result = m.invoke(stmt);
                        if (result != null && !result.toString().isEmpty()) {
                            return result.toString();
                        }
                    } catch (Throwable ignored) {}
                }
                
                // Try accessing all possible SQL field names
                for (String fieldName : new String[]{"sql", "originalSql", "query", "sqlTemplate", "preparedQuery"}) {
                    try {
                        java.lang.reflect.Field f = findField(stmt.getClass(), fieldName);
                        if (f != null) {
                            f.setAccessible(true);
                            Object result = f.get(stmt);
                            if (result != null && !result.toString().isEmpty()) {
                                return result.toString();
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                
                // Try getting delegate and extracting from it
                Statement delegate = extractDelegateFromLog4jdbc(stmt);
                if (delegate != null && delegate != stmt) {
                    String delegateSql = extractSqlFromWrapper(delegate);
                    if (delegateSql != null) return delegateSql;
                }
            }
            
            // ============================================================
            // 2. HikariCP Proxy
            // ============================================================
            if (className.contains("Hikari") || className.contains("Proxy")) {
                Statement delegate = getDelegate(stmt);
                if (delegate != null && delegate != stmt) {
                    // Check if we have SQL for the delegate
                    String sql = preparedSql.get(delegate);
                    if (sql != null && !sql.isEmpty()) return sql;
                    
                    // Recursively try to extract from delegate
                    return extractSqlFromWrapper(delegate);
                }
            }
            
            // ============================================================
            // 3. PostgreSQL, MySQL, Oracle native statements
            // ============================================================
            // Try toString() - many JDBC drivers include SQL in toString()
            try {
                String str = stmt.toString();
                if (str != null && !str.isEmpty()) {
                    // PostgreSQL: "org.postgresql.jdbc.PgPreparedStatement@hash: SELECT ..."
                    if (str.contains(":") && str.contains(" ")) {
                        int colonIdx = str.indexOf(":");
                        if (colonIdx > 0 && colonIdx < str.length() - 2) {
                            String sqlPart = str.substring(colonIdx + 1).trim();
                            if (sqlPart.length() > 5) {  // At least "SELECT"
                                return sqlPart;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            
            // ============================================================
            // 4. Generic reflection on common field names
            // ============================================================
            String[] fieldNames = {"sql", "originalSql", "query", "sqlString", "_sql", "preparedQuery", "command"};
            for (String fieldName : fieldNames) {
                try {
                    java.lang.reflect.Field f = findField(stmt.getClass(), fieldName);
                    if (f != null) {
                        f.setAccessible(true);
                        Object result = f.get(stmt);
                        if (result != null && !result.toString().isEmpty()) {
                            return result.toString();
                        }
                    }
                } catch (Throwable ignored) {}
            }
            
            // ============================================================
            // 5. Try unwrapping if JDBC 4.0+
            // ============================================================
            try {
                if (stmt.isWrapperFor(java.sql.PreparedStatement.class)) {
                    Statement unwrapped = stmt.unwrap(java.sql.PreparedStatement.class);
                    if (unwrapped != null && unwrapped != stmt) {
                        String unwrappedSql = extractSqlFromWrapper(unwrapped);
                        if (unwrappedSql != null) return unwrappedSql;
                    }
                }
            } catch (Throwable ignored) {}
            
        } catch (Throwable ignored) {}
        
        return null;
    }
    
    /**
     * Extract delegate from log4jdbc PreparedStatementSpy
     */
    private static Statement extractDelegateFromLog4jdbc(Statement stmt) {
        // log4jdbc uses "passthru" or "realStatement" for delegate
        String[] delegateFields = {"passthru", "realStatement", "statement", "delegate"};
        for (String fieldName : delegateFields) {
            try {
                java.lang.reflect.Field f = findField(stmt.getClass(), fieldName);
                if (f != null) {
                    f.setAccessible(true);
                    Object result = f.get(stmt);
                    if (result instanceof Statement) {
                        return (Statement) result;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
    
    private static Statement getDelegate(Statement stmt) {
        try {
            // Try unwrap() method first (JDBC 4.0+)
            if (stmt.isWrapperFor(java.sql.PreparedStatement.class)) {
                Statement unwrapped = stmt.unwrap(java.sql.PreparedStatement.class);
                if (unwrapped != stmt) return unwrapped;
            }
        } catch (Throwable ignored) {}
        
        // Try reflection - common delegate field names
        String[] delegateFields = {"delegate", "statement", "innerStatement", "_stmt", "realStatement"};
        for (String fieldName : delegateFields) {
            try {
                java.lang.reflect.Field f = findField(stmt.getClass(), fieldName);
                if (f != null) {
                    f.setAccessible(true);
                    Object result = f.get(stmt);
                    if (result instanceof Statement) {
                        return (Statement) result;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
    
    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
