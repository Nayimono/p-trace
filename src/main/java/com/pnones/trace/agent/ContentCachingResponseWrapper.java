package com.pnones.trace.agent;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * HttpServletResponseWrapper that caches the response body for later retrieval
 */
public class ContentCachingResponseWrapper extends HttpServletResponseWrapper {
    private final ByteArrayOutputStream cachedContent;
    private final int maxCacheSize;
    private ServletOutputStream outputStream;
    private PrintWriter writer;
    private int contentLength = 0;
    
    public ContentCachingResponseWrapper(HttpServletResponse response, int maxCacheSize) {
        super(response);
        this.maxCacheSize = maxCacheSize;
        this.cachedContent = new ByteArrayOutputStream(Math.min(1024, maxCacheSize));
    }
    
    // For reflection-based construction
    public ContentCachingResponseWrapper(Object response, int maxCacheSize) {
        this((HttpServletResponse) response, maxCacheSize);
    }
    
    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        com.pnones.trace.util.DebugLogger.log("ContentCachingResponseWrapper.getOutputStream() called");
        if (this.outputStream == null) {
            this.outputStream = new ContentCachingOutputStream(super.getOutputStream());
        }
        return this.outputStream;
    }
    
    @Override
    public PrintWriter getWriter() throws IOException {
        com.pnones.trace.util.DebugLogger.log("ContentCachingResponseWrapper.getWriter() called");
        if (this.writer == null) {
            String characterEncoding = getCharacterEncoding();
            this.writer = new PrintWriter(new OutputStreamWriter(
                    getOutputStream(), characterEncoding != null ? characterEncoding : "UTF-8"));
        }
        return this.writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (this.writer != null) {
            this.writer.flush();
        }
        if (this.outputStream != null) {
            this.outputStream.flush();
        }
        super.flushBuffer();
    }
    
    @Override
    public void setContentLength(int len) {
        this.contentLength = len;
        super.setContentLength(len);
    }
    
    @Override
    public void setContentLengthLong(long len) {
        this.contentLength = (int) Math.min(len, Integer.MAX_VALUE);
        super.setContentLengthLong(len);
    }
    
    /**
     * Get the cached content as a byte array
     */
    public byte[] getContentAsByteArray() {
        return this.cachedContent.toByteArray();
    }
    
    /**
     * Get the cached content as a string
     */
    public String getContentAsString() {
        try {
            String characterEncoding = getCharacterEncoding();
            return this.cachedContent.toString(characterEncoding != null ? characterEncoding : "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get the size of the cached content
     */
    public int getContentSize() {
        return this.cachedContent.size();
    }
    
    private class ContentCachingOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;
        private boolean overflowed = false;
        
        public ContentCachingOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public void write(int b) throws IOException {
            this.delegate.write(b);
            if (!overflowed && cachedContent.size() < maxCacheSize) {
                cachedContent.write(b);
            } else if (!overflowed) {
                overflowed = true;
            }
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            this.delegate.write(b, off, len);
            if (!overflowed) {
                int remaining = maxCacheSize - cachedContent.size();
                if (remaining > 0) {
                    int toCache = Math.min(len, remaining);
                    cachedContent.write(b, off, toCache);
                    if (toCache < len) {
                        overflowed = true;
                    }
                } else {
                    overflowed = true;
                }
            }
        }
        
        @Override
        public boolean isReady() {
            return this.delegate.isReady();
        }
        
        @Override
        public void setWriteListener(WriteListener writeListener) {
            this.delegate.setWriteListener(writeListener);
        }
    }
}
