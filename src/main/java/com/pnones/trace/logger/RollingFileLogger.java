package com.pnones.trace.logger;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.GZIPOutputStream;

public class RollingFileLogger {
    private final File file;
    private final Object lock = new Object();
    private final long maxBytes;
    private final int maxFiles;
    private final Charset encoding;
    private final File archiveDir;
    private final boolean useArchive;
    private final boolean compress;
    private final long maxTotalBytes;
    private final String strategy;
    private final SimpleDateFormat timeFormat;
    private String currentTimeKey;
    private int requestCount = 0;
    private final int maxRequestsBeforeRotate;

    public RollingFileLogger(String path) {
        this.file = new File(path);
        File p = file.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        
        // 파일 크기 설정 (MB 단위, 기본 100MB)
        long mb = com.pnones.trace.config.TraceConfig.getLong("log.rolling.max-file-mb", 100);
        this.maxBytes = mb * 1024L * 1024L;
        
        // 보관할 파일 개수 (기본 10개)
        this.maxFiles = com.pnones.trace.config.TraceConfig.getInt("log.rolling.max-files", 10);
        
        // 인코딩 (기본 UTF-8)
        String enc = com.pnones.trace.config.TraceConfig.getString("log.encoding", "UTF-8");
        this.encoding = Charset.forName(enc);
        
        // 아카이브 디렉토리 설정 (완료된 로그 이동)
        String logDir = com.pnones.trace.config.TraceConfig.getString("log.dir", "./logs");
        String archivePath = com.pnones.trace.config.TraceConfig.getString(
            "log.rolling.archive-dir", 
            logDir + File.separator + "complete"  // 기본값: log.dir/complete
        );
        if (archivePath != null && !archivePath.trim().isEmpty()) {
            this.archiveDir = new File(archivePath);
            if (!archiveDir.exists()) archiveDir.mkdirs();
            this.useArchive = true;
        } else {
            this.archiveDir = null;
            this.useArchive = false;
        }
        
        // 압축 여부 (기본 false)
        this.compress = com.pnones.trace.config.TraceConfig.getBoolean("log.rolling.compress", false);
        
        // 전체 로그 크기 제한 (MB 단위, 기본 1000MB = 1GB)
        long totalMb = com.pnones.trace.config.TraceConfig.getLong("log.rolling.max-total-mb", 1000);
        this.maxTotalBytes = totalMb * 1024L * 1024L;
        
        // 로테이션 전략: SIZE (크기 기반) 또는 TIME (시간 기반)
        this.strategy = com.pnones.trace.config.TraceConfig.getString("log.rolling.strategy", "SIZE").toUpperCase();
        
        // 시간 기반 로테이션 패턴 (HOURLY: 시간별, DAILY: 일별)
        String timePattern = com.pnones.trace.config.TraceConfig.getString("log.rolling.time-pattern", "DAILY").toUpperCase();
        if ("HOURLY".equals(timePattern)) {
            this.timeFormat = new SimpleDateFormat("yyyy-MM-dd-HH");
        } else {
            this.timeFormat = new SimpleDateFormat("yyyy-MM-dd");
        }
        this.currentTimeKey = timeFormat.format(new Date());
        
        // REQUEST 전략: N개 요청마다 로테이션
        this.maxRequestsBeforeRotate = com.pnones.trace.config.TraceConfig.getInt("log.rolling.max-requests", 100);
    }

    public void append(String line) {
        com.pnones.trace.util.DebugLogger.debug("RollingLogger append", file.getAbsolutePath());
        synchronized (lock) {
            try {
                // Ensure parent directory exists
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    if (parent.mkdirs()) {
                        com.pnones.trace.util.DebugLogger.info("Created parent directory: " + parent.getAbsolutePath());
                    }
                }
                
                // 로테이션 필요 여부 확인
                boolean needRotate = false;
                
                if ("TIME".equals(strategy)) {
                    // 시간 기반 로테이션
                    String newTimeKey = timeFormat.format(new Date());
                    if (!newTimeKey.equals(currentTimeKey)) {
                        currentTimeKey = newTimeKey;
                        needRotate = true;
                    }
                } else if ("REQUEST".equals(strategy)) {
                    // 요청 기반 로테이션
                    requestCount++;
                    if (requestCount >= maxRequestsBeforeRotate) {
                        needRotate = true;
                        requestCount = 0;
                    }
                } else {
                    // 크기 기반 로테이션 (기본)
                    if (maxBytes > 0 && file.exists() && file.length() > maxBytes) {
                        needRotate = true;
                    }
                }
                
                if (needRotate) {
                    rotate();
                }
                
                // 부모 디렉토리 존재 여부 확인 및 생성 (동적 경로 대응)
                parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    try {
                        parent.mkdirs();
                        com.pnones.trace.util.DebugLogger.debug("RollingLogger dir created", parent.getAbsolutePath());
                    } catch (Exception mkdirsEx) {
                        com.pnones.trace.util.DebugLogger.error("RollingLogger mkdir failed", mkdirsEx);
                    }
                }
                
                // 로그 기록
                try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file, true), encoding);
                     BufferedWriter bw = new BufferedWriter(w)) {
                    com.pnones.trace.util.DebugLogger.debug("RollingLogger write to", file.getAbsolutePath());
                    bw.write(line);
                    bw.write(System.lineSeparator());
                    bw.flush();
                    w.flush();
                    com.pnones.trace.util.DebugLogger.debug("RollingLogger wrote bytes", line.length());
                } catch (Exception writeEx) {
                    com.pnones.trace.util.DebugLogger.error("RollingLogger write failed", writeEx);
                }
            } catch (Exception e) {
                com.pnones.trace.util.DebugLogger.error("RollingFileLogger.append failed", e);
            }
        }
    }

    private void rotate() {
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date());
            String baseName = file.getName();
            
            if (useArchive && archiveDir != null) {
                // 아카이브 디렉토리로 이동
                String archivedName = baseName + "." + timestamp;
                File archivedFile = new File(archiveDir, archivedName);
                
                if (file.exists() && file.length() > 0) {
                    if (compress) {
                        // 압축하여 이동
                        File gzFile = new File(archiveDir, archivedName + ".gz");
                        compressFile(file, gzFile);
                        file.delete();
                    } else {
                        // 그냥 이동
                        Files.move(file.toPath(), archivedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                
                // 오래된 아카이브 파일 정리
                cleanupArchive();
            } else {
                // 기존 방식: 같은 디렉토리에서 번호로 로테이션
                for (int i = maxFiles - 1; i >= 1; i--) {
                    File f = new File(file.getAbsolutePath() + "." + i);
                    if (f.exists()) {
                        if (i >= maxFiles) {
                            // 최대 개수 초과 시 삭제
                            f.delete();
                        } else {
                            File to = new File(file.getAbsolutePath() + "." + (i + 1));
                            if (to.exists()) to.delete();
                            f.renameTo(to);
                        }
                    }
                }
                
                if (file.exists() && file.length() > 0) {
                    File f1 = new File(file.getAbsolutePath() + ".1");
                    if (f1.exists()) f1.delete();
                    
                    if (compress) {
                        File gzFile = new File(file.getAbsolutePath() + ".1.gz");
                        compressFile(file, gzFile);
                        file.delete();
                    } else {
                        file.renameTo(f1);
                    }
                }
            }
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("Log rotation failed", e);
        }
    }
    
    private void compressFile(File source, File target) {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(target);
             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                gzos.write(buffer, 0, len);
            }
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("File compression failed", e);
        }
    }
    
    private void cleanupArchive() {
        if (archiveDir == null || !archiveDir.exists()) return;
        
        try {
            // 아카이브 디렉토리의 모든 파일 크기 계산
            File[] files = archiveDir.listFiles();
            if (files == null || files.length == 0) return;
            
            // 파일을 수정 시간 순으로 정렬 (오래된 것부터)
            java.util.Arrays.sort(files, (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));
            
            long totalSize = 0;
            for (File f : files) {
                totalSize += f.length();
            }
            
            // 전체 크기 초과 시 오래된 파일부터 삭제
            int index = 0;
            while (totalSize > maxTotalBytes && index < files.length) {
                File oldFile = files[index];
                totalSize -= oldFile.length();
                oldFile.delete();
                com.pnones.trace.util.DebugLogger.log("Deleted old archive: " + oldFile.getName());
                index++;
            }
            
            // 파일 개수 제한
            if (files.length > maxFiles) {
                for (int i = 0; i < files.length - maxFiles; i++) {
                    if (files[i].exists()) {
                        files[i].delete();
                        com.pnones.trace.util.DebugLogger.log("Deleted excess archive: " + files[i].getName());
                    }
                }
            }
        } catch (Exception e) {
            com.pnones.trace.util.DebugLogger.log("Archive cleanup failed", e);
        }
    }
    
}
