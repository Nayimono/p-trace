package com.pnones.trace.logger;

import com.pnones.trace.model.SqlTraceRecord;

public class SqlTraceLogger {
    public SqlTraceLogger() {
        System.err.println("[PTrace] Initializing SqlTraceLogger...");
        com.pnones.trace.util.DebugLogger.log("[INIT] SqlTraceLogger initializing...");
        com.pnones.trace.util.DebugLogger.log("[INIT] SqlTraceLogger initialized in unified-only mode (no standalone sql-trace files)");
    }

    public void log(SqlTraceRecord rec) {
        // SQL은 HTTP 로그에 통합 기록되므로 별도 sql-trace 파일은 생성하지 않음
        return;
    }
}
