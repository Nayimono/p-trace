package com.pnones.trace.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capture HTTP request body without depending on servlet classes.
 * Uses reflection to read InputStream safely.
 */
public class RequestBodyCapture {
    private static final ConcurrentHashMap<Object, byte[]> capturedBodies = new ConcurrentHashMap<>();
    private static final int MAX_SIZE = 10240; // 10KB limit

    /**
 * Wrap request with ContentCachingRequestWrapper if not already wrapped.
 * This ensures body is cached before any application filters read it.
 * Returns the wrapped request (or original if wrapping failed).
 */
public static Object wrapRequestIfNeeded(Object request) {
    if (request == null) return request;
    
    try {
        // Check if already wrapped
        String className = request.getClass().getName();
        if (className.contains("ContentCachingRequestWrapper")) {
            com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: request already wrapped with ContentCachingRequestWrapper");
            return request;
        }
        
        // Try to wrap with Spring ContentCachingRequestWrapper
        try {
            Class<?> wrapperClass = Class.forName("org.springframework.web.util.ContentCachingRequestWrapper");
            java.lang.reflect.Constructor<?> ctor = wrapperClass.getConstructor(Class.forName("javax.servlet.http.HttpServletRequest"));
            Object wrapped = ctor.newInstance(request);
            com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: wrapped request with org.springframework.web.util.ContentCachingRequestWrapper");
            return wrapped;
        } catch (Throwable springErr) {
            com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: Spring wrapper unavailable, trying jakarta", springErr);
        }
        
        // Try jakarta servlet
        try {
            Class<?> wrapperClass = Class.forName("org.springframework.web.util.ContentCachingRequestWrapper");
            java.lang.reflect.Constructor<?> ctor = wrapperClass.getConstructor(Class.forName("jakarta.servlet.http.HttpServletRequest"));
            Object wrapped = ctor.newInstance(request);
            com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: wrapped request with ContentCachingRequestWrapper (jakarta)");
            return wrapped;
        } catch (Throwable jakartaErr) {
            com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: jakarta wrapper failed", jakartaErr);
        }
        
        // Wrapping failed, return original
        return request;
    } catch (Throwable e) {
        com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: wrapRequestIfNeeded error", e);
        return request;
    }
}

/**
     */
    public static void captureFromRequest(Object request) {
        if (request == null) return;
        
        try {
            if (capturedBodies.containsKey(request)) return;

            // 1) Non-destructive: try cached body methods first (wrapper-based)
            byte[] cached = tryGetCachedBytes(request);
            if (cached != null && cached.length > 0) {
                capturedBodies.put(request, cached);
                return;
            }

            // 2) Optional unsafe fallback: direct InputStream read
            // Disabled by default because it can break application body parsing.
            boolean unsafeReadEnabled = com.pnones.trace.config.TraceConfig
                    .getBoolean("http.capture.request-body.unsafe-stream-read", false);
            com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: unsafe-stream-read=" + unsafeReadEnabled);
            if (!unsafeReadEnabled) {
                com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: unsafe stream read disabled");
                return;
            }

            Object inputStream = invokeMethod(request, "getInputStream");
            if (inputStream != null) {
                byte[] bytes = readBytes(inputStream);
                if (bytes != null && bytes.length > 0) {
                    capturedBodies.put(request, bytes);
                }
            }
        } catch (Throwable e) {
            com.pnones.trace.util.DebugLogger.log("Failed to capture request body", e);
        }
    }

    /**
     * Get captured body as String
     */
    public static String getBody(Object request) {
        if (request == null) return null;
        byte[] bytes = capturedBodies.get(request);
        if (bytes == null || bytes.length == 0) return null;
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Clear cached body
     */
    public static void clear(Object request) {
        if (request != null) {
            capturedBodies.remove(request);
        }
    }

    private static byte[] tryGetCachedBytes(Object request) {
        if (request == null) return null;
        try {
            com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: trying cached methods on " + request.getClass().getSimpleName());
            
            String[] byteMethods = new String[]{"getContentAsByteArray", "getCachedBodyAsBytes"};
            for (String methodName : byteMethods) {
                try {
                    java.lang.reflect.Method m = request.getClass().getMethod(methodName);
                    Object v = m.invoke(request);
                    if (v instanceof byte[]) {
                        byte[] bytes = (byte[]) v;
                        if (bytes.length > 0) {
                            com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: got " + bytes.length + " bytes via " + methodName);
                            if (bytes.length > MAX_SIZE) {
                                byte[] truncated = new byte[MAX_SIZE];
                                System.arraycopy(bytes, 0, truncated, 0, MAX_SIZE);
                                return truncated;
                            }
                            return bytes;
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    // Method not available, try next
                } catch (Throwable e) {
                    com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: error calling " + methodName + ": " + e.getClass().getSimpleName());
                }
            }

            String[] stringMethods = new String[]{"getContentAsString", "getBody", "getRequestBody", "getCachedBody"};
            for (String methodName : stringMethods) {
                try {
                    java.lang.reflect.Method m = request.getClass().getMethod(methodName);
                    Object v = m.invoke(request);
                    if (v instanceof String) {
                        String s = (String) v;
                        if (!s.isEmpty()) {
                            com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: got " + s.length() + " chars via " + methodName);
                            if (s.length() > MAX_SIZE) {
                                s = s.substring(0, MAX_SIZE);
                            }
                            return s.getBytes(StandardCharsets.UTF_8);
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    // Method not available, try next
                } catch (Throwable e) {
                    com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: error calling " + methodName + ": " + e.getClass().getSimpleName());
                }
            }
            
            com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: no cached body methods available");
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] readBytes(Object inputStream) throws IOException {
        if (inputStream == null) return null;
        
        try {
            boolean markSupported = false;
            try {
                java.lang.reflect.Method markSupportedMethod = inputStream.getClass().getMethod("markSupported");
                Object result = markSupportedMethod.invoke(inputStream);
                markSupported = (result instanceof Boolean) && (Boolean) result;
            } catch (Throwable ignored) {}

            if (!markSupported) {
                com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: skip stream read (mark/reset unsupported)");
                return null;
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.lang.reflect.Method markMethod = inputStream.getClass().getMethod("mark", int.class);
            markMethod.invoke(inputStream, Integer.MAX_VALUE);

            java.lang.reflect.Method readMethod = inputStream.getClass().getMethod("read", byte[].class);
            byte[] buffer = new byte[4096];
            int bytesRead;
            int totalRead = 0;
            
            while (totalRead < MAX_SIZE) {
                Object result = readMethod.invoke(inputStream, (Object) buffer);
                bytesRead = (result instanceof Integer) ? (Integer) result : -1;
                if (bytesRead == -1) break;
                
                baos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }
            
            try {
                java.lang.reflect.Method resetMethod = inputStream.getClass().getMethod("reset");
                resetMethod.invoke(inputStream);
            } catch (Throwable e) {
                com.pnones.trace.util.DebugLogger.log("RequestBodyCapture: reset failed, skip body capture", e);
                return null;
            }

            return baos.toByteArray();
        } catch (Throwable e) {
            com.pnones.trace.util.DebugLogger.log("Error reading bytes from InputStream", e);
            return null;
        }
    }

    private static Object invokeMethod(Object obj, String methodName, Object... args) {
        if (obj == null) return null;
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i].getClass();
            }
            java.lang.reflect.Method m = obj.getClass().getMethod(methodName, paramTypes);
            return m.invoke(obj, args);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
