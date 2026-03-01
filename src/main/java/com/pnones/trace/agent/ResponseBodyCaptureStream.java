package com.pnones.trace.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * ServletOutputStream wrapper that captures response body content while forwarding to original stream.
 * Works across all WAS types (Tomcat, Jetty, Undertow, Vert.x).
 * 
 * IMPORTANT: This extends the base OutputStream class and uses reflection to wrap the actual ServletOutputStream.
 * We cannot directly extend ServletOutputStream because it's abstract and has WAS-specific implementations.
 */
public class ResponseBodyCaptureStream extends java.io.OutputStream {
    private final Object delegateServletOutputStream;
    private final java.lang.reflect.Method writeIntMethod;
    private final java.lang.reflect.Method writeArrayMethod;
    private final java.lang.reflect.Method writeArrayOffsetMethod;
    private final java.lang.reflect.Method flushMethod;
    private final java.lang.reflect.Method closeMethod;
    
    private final ByteArrayOutputStream capture;
    private final int maxSize;
    private boolean limitReached = false;
    
    public ResponseBodyCaptureStream(Object delegateServletOutputStream, int maxSize) throws Exception {
        this.delegateServletOutputStream = delegateServletOutputStream;
        this.capture = new ByteArrayOutputStream();
        this.maxSize = maxSize;
        
        // Cache reflection methods for performance
        Class<?> streamClass = delegateServletOutputStream.getClass();
        this.writeIntMethod = findMethod(streamClass, "write", int.class);
        this.writeArrayMethod = findMethod(streamClass, "write", byte[].class);
        this.writeArrayOffsetMethod = findMethod(streamClass, "write", byte[].class, int.class, int.class);
        this.flushMethod = findMethod(streamClass, "flush");
        this.closeMethod = findMethod(streamClass, "close");
    }
    
    private java.lang.reflect.Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            java.lang.reflect.Method m = clazz.getMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (Exception e) {
            return null;
        }
    }
    
    @Override
    public void write(int b) throws IOException {
        try {
            if (writeIntMethod != null) {
                writeIntMethod.invoke(delegateServletOutputStream, b);
            }
        } catch (Exception e) {
            throw new IOException("Failed to write to delegate stream", e);
        }
        
        if (!limitReached && capture.size() < maxSize) {
            capture.write(b);
        } else {
            limitReached = true;
        }
    }
    
    @Override
    public void write(byte[] b) throws IOException {
        try {
            if (writeArrayMethod != null) {
                writeArrayMethod.invoke(delegateServletOutputStream, b);
            }
        } catch (Exception e) {
            throw new IOException("Failed to write to delegate stream", e);
        }
        captureBytes(b, 0, b.length);
    }
    
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        try {
            if (writeArrayOffsetMethod != null) {
                writeArrayOffsetMethod.invoke(delegateServletOutputStream, b, off, len);
            }
        } catch (Exception e) {
            throw new IOException("Failed to write to delegate stream", e);
        }
        captureBytes(b, off, len);
    }
    
    private void captureBytes(byte[] b, int off, int len) {
        if (!limitReached) {
            int remaining = maxSize - capture.size();
            if (remaining > 0) {
                int toWrite = Math.min(len, remaining);
                capture.write(b, off, toWrite);
                if (toWrite < len) {
                    limitReached = true;
                }
            } else {
                limitReached = true;
            }
        }
    }
    
    @Override
    public void flush() throws IOException {
        try {
            if (flushMethod != null) {
                flushMethod.invoke(delegateServletOutputStream);
            }
        } catch (Exception e) {
            throw new IOException("Failed to flush delegate stream", e);
        }
    }
    
    @Override
    public void close() throws IOException {
        try {
            if (closeMethod != null) {
                closeMethod.invoke(delegateServletOutputStream);
            }
        } catch (Exception e) {
            throw new IOException("Failed to close delegate stream", e);
        }
    }
    
    public String getCapturedContent() {
        try {
            byte[] bytes = capture.toByteArray();
            if (bytes.length == 0) return null;
            // Try UTF-8 decoding
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
    
    public boolean isLimitReached() {
        return limitReached;
    }
    
    public int getCapturedSize() {
        return capture.size();
    }
}
