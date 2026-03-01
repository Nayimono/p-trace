package com.pnones.trace.util;

import com.pnones.trace.config.TraceConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * JVM Shutdown 시 로그 파일을 complete 디렉토리로 옮기는 유틸리티
 * Tomcat 종료, 애플리케이션 재시작 등에 사용
 */
public class ShutdownLogHandler {
    private static volatile boolean registered = false;

    /**
     * Shutdown hook 등록 (한 번만)
     */
    public static void registerShutdownHook() {
        // 설정에서 비활성화되었으면 등록 안 함
        boolean enabled = TraceConfig.getBoolean("log.shutdown-archive", true);
        if (!enabled) {
            DebugLogger.info("ShutdownLogHandler disabled by config");
            return;
        }

        if (registered) return;
        registered = true;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                archiveLogsOnShutdown();
            } catch (Exception e) {
                // Best effort - don't fail on shutdown
                e.printStackTrace(System.err);
            }
        }, "PTrace-ShutdownLogHandler"));

        DebugLogger.info("ShutdownLogHandler registered");
    }

    /**
     * Shutdown 시 현재 로그 파일들을 complete 디렉토리로 이동
     */
    private static void archiveLogsOnShutdown() {
        try {
            String logDir = TraceConfig.getString("log.dir", "./logs");
            String archiveDir = TraceConfig.getString("log.rolling.archive-dir", "");
            
            if (archiveDir == null || archiveDir.trim().isEmpty()) {
                archiveDir = logDir + File.separator + "complete";
            }

            File logDirFile = new File(logDir);
            File archiveDirFile = new File(archiveDir);

            if (!logDirFile.exists()) {
                return; // Nothing to archive
            }

            if (!archiveDirFile.exists()) {
                archiveDirFile.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date());
            File[] logFiles = logDirFile.listFiles((dir, name) -> 
                name.endsWith(".log") || name.endsWith(".jsonl") || name.endsWith(".txt")
            );

            if (logFiles == null || logFiles.length == 0) {
                return;
            }

            int archived = 0;
            for (File logFile : logFiles) {
                if (!logFile.isFile()) continue;
                
                try {
                    // 크기가 0인 파일은 건너뜀
                    if (logFile.length() == 0) {
                        continue;
                    }

                    // 파일명에 타임스탬프 추가
                    String baseName = logFile.getName();
                    String archivedName = baseName + "." + timestamp + ".shutdown";
                    File archivedFile = new File(archiveDirFile, archivedName);

                    // 파일 이동
                    Files.move(
                        logFile.toPath(),
                        archivedFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    );

                    archived++;
                    System.err.println("[PTrace] ✓ Archived log on shutdown: " + archivedName);
                } catch (IOException e) {
                    System.err.println("[PTrace] Warning: Failed to archive " + logFile.getName() + ": " + e.getMessage());
                }
            }

            if (archived > 0) {
                System.err.println("[PTrace] ✓ Archived " + archived + " log files on shutdown");
            }

        } catch (Exception e) {
            System.err.println("[PTrace] Error during shutdown log archival: " + e.getMessage());
        }
    }

    /**
     * 즉시 모든 로그를 archive (재시작 전 호출 가능)
     */
    public static void archiveLogsNow() {
        archiveLogsOnShutdown();
    }
}
