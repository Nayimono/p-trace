package com.pnones.trace.agent;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.ByteArrayOutputStream;

/**
 * HttpServletResponse wrapper that captures response body.
 * Works universally across all WAS implementations by wrapping ServletOutputStream and Writer.
 */
public class ResponseWrapper {
    private final Object originalResponse;
    private final ByteArrayOutputStream captureStream = new ByteArrayOutputStream();
    private ServletOutputStreamWrapper outputStreamWrapper;
    private PrintWriter writerWrapper;
    private final int maxCaptureSize;
    private boolean captured = false;
    
    public ResponseWrapper(Object response, int maxCaptureSize) {
        this.originalResponse = response;
        this.maxCaptureSize = maxCaptureSize;
    }
    
    /**
     * Wrap the getOutputStream() call
     */
    public Object wrapGetOutputStream(Object originalOutputStream) throws IOException {
        if (outputStreamWrapper == null && originalOutputStream != null) {
            outputStreamWrapper = new ServletOutputStreamWrapper(originalOutputStream, captureStream, maxCaptureSize);
            captured = true;
        }
        return outputStreamWrapper != null ? outputStreamWrapper : originalOutputStream;
    }
    
    /**
     * Wrap the getWriter() call
     */
    public Object wrapGetWriter(Object originalWriter) throws IOException {
        if (writerWrapper == null && originalWriter != null) {
            // Create writer that writes to both original and capture
            TeeWriter teeWriter = new TeeWriter(originalWriter, captureStream, maxCaptureSize);
            writerWrapper = new PrintWriter(teeWriter);
            captured = true;
        }
        return writerWrapper != null ? writerWrapper : originalWriter;
    }
    
    public String getCapturedContent() {
        if (!captured || captureStream.size() == 0) {
            return null;
        }
        try {
            return captureStream.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
    
    public int getCapturedSize() {
        return captureStream.size();
    }
    
    /**
     * OutputStream wrapper that tees to capture buffer
     */
    static class ServletOutputStreamWrapper extends java.io.OutputStream {
        private final Object delegate;
        private final ByteArrayOutputStream capture;
        private final int maxSize;
        private boolean limitReached = false;
        
        // Cached reflection methods
        private final java.lang.reflect.Method writeIntMethod;
        private final java.lang.reflect.Method writeArrayMethod;
        private final java.lang.reflect.Method writeArrayOffsetMethod;
        private final java.lang.reflect.Method flushMethod;
        private final java.lang.reflect.Method closeMethod;
        
        ServletOutputStreamWrapper(Object delegate, ByteArrayOutputStream capture, int maxSize) {
            this.delegate = delegate;
            this.capture = capture;
            this.maxSize = maxSize;
            
            // Cache reflection methods
            Class<?> clazz = delegate.getClass();
            this.writeIntMethod = getMethod(clazz, "write", int.class);
            this.writeArrayMethod = getMethod(clazz, "write", byte[].class);
            this.writeArrayOffsetMethod = getMethod(clazz, "write", byte[].class, int.class, int.class);
            this.flushMethod = getMethod(clazz, "flush");
            this.closeMethod = getMethod(clazz, "close");
        }
        
        private static java.lang.reflect.Method getMethod(Class<?> clazz, String name, Class<?>... params) {
            try {
                java.lang.reflect.Method m = clazz.getMethod(name, params);
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
                    writeIntMethod.invoke(delegate, b);
                }
            } catch (Exception e) {
                throw new IOException(e);
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
                    writeArrayMethod.invoke(delegate, (Object) b);
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
            captureBytes(b, 0, b.length);
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                if (writeArrayOffsetMethod != null) {
                    writeArrayOffsetMethod.invoke(delegate, b, off, len);
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
            captureBytes(b, off, len);
        }
        
        private void captureBytes(byte[] b, int off, int len) {
            if (!limitReached) {
                int remaining = maxSize - capture.size();
                if (remaining > 0) {
                    int toWrite = Math.min(len, remaining);
                    capture.write(b, off, toWrite);
                    limitReached = (toWrite < len);
                }
            }
        }
        
        @Override
        public void flush() throws IOException {
            try {
                if (flushMethod != null) {
                    flushMethod.invoke(delegate);
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        
        @Override
        public void close() throws IOException {
            try {
                if (closeMethod != null) {
                    closeMethod.invoke(delegate);
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }
    
    /**
     * Writer that tees to capture buffer
     */
    static class TeeWriter extends java.io.Writer {
        private final Object delegate;
        private final ByteArrayOutputStream capture;
        private final int maxSize;
        private boolean limitReached = false;
        
        // Cached reflection methods
        private final java.lang.reflect.Method writeIntMethod;
        private final java.lang.reflect.Method writeArrayMethod;
        private final java.lang.reflect.Method writeArrayOffsetMethod;
        private final java.lang.reflect.Method writeStringMethod;
        private final java.lang.reflect.Method writeStringOffsetMethod;
        private final java.lang.reflect.Method flushMethod;
        private final java.lang.reflect.Method closeMethod;
        
        TeeWriter(Object delegate, ByteArrayOutputStream capture, int maxSize) {
            this.delegate = delegate;
            this.capture = capture;
            this.maxSize = maxSize;
            
            Class<?> clazz = delegate.getClass();
            this.writeIntMethod = getMethod(clazz, "write", int.class);
            this.writeArrayMethod = getMethod(clazz, "write", char[].class);
            this.writeArrayOffsetMethod = getMethod(clazz, "write", char[].class, int.class, int.class);
            this.writeStringMethod = getMethod(clazz, "write", String.class);
            this.writeStringOffsetMethod = getMethod(clazz, "write", String.class, int.class, int.class);
            this.flushMethod = getMethod(clazz, "flush");
            this.closeMethod = getMethod(clazz, "close");
        }
        
        private static java.lang.reflect.Method getMethod(Class<?> clazz, String name, Class<?>... params) {
            try {
                java.lang.reflect.Method m = clazz.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Exception e) {
                return null;
            }
        }
        
        @Override
        public void write(int c) throws IOException {
            try {
                if (writeIntMethod != null) {
                    writeIntMethod.invoke(delegate, c);
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
            if (!limitReached && capture.size() < maxSize) {
                capture.write(c);
            }
        }
        
        @Override
        public void write(char[] cbuf) throws IOException {
            write(cbuf, 0, cbuf.length);
        }
        
        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            try {
                if (writeArrayOffsetMethod != null) {
                    writeArrayOffsetMethod.invoke(delegate, cbuf, off, len);
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
            captureChars(cbuf, off, len);
        }
        
        @Override
        public void write(String str) throws IOException {
            try {
                if (writeStringMethod != null) {
                    writeStringMethod.invoke(delegate, str);
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
            if (!limitReached) {
                try {
                    byte[] bytes = str.getBytes("UTF-8");
                    if (capture.size() + bytes.length <= maxSize) {
                        capture.write(bytes);
                    } else {
                        limitReached = true;
                    }
                } catch (Exception ignored) {}
            }
        }
        
        @Override
        public void write(String str, int off, int len) throws IOException {
            write(str.substring(off, off + len));
        }
        
        private void captureChars(char[] cbuf, int off, int len) {
            if (!limitReached) {
                try {
                    String str = new String(cbuf, off, len);
                    byte[] bytes = str.getBytes("UTF-8");
                    if (capture.size() + bytes.length <= maxSize) {
                        capture.write(bytes);
                    } else {
                        limitReached = true;
                    }
                } catch (Exception ignored) {}
            }
        }
        
        @Override
        public void flush() throws IOException {
            try {
                if (flushMethod != null) {
                    flushMethod.invoke(delegate);
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        
        @Override
        public void close() throws IOException {
            try {
                if (closeMethod != null) {
                    closeMethod.invoke(delegate);
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }
}
