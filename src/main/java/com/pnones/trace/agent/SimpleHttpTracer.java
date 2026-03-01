package com.pnones.trace.agent;

import com.google.gson.Gson;
import com.pnones.trace.config.TraceConfig;
import com.pnones.trace.logger.HttpTraceLogger;
import com.pnones.trace.util.DebugLogger;

import java.util.Base64;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Enumeration;
import java.util.Set;
import java.util.regex.Pattern;

public class SimpleHttpTracer {
    private static final HttpTraceLogger logger = new HttpTraceLogger();
    private static final Gson gson = new Gson();
    
    // ThreadLocal to store wrapped response for this thread's request
    private static final ThreadLocal<Object> wrappedResponseHolder = new ThreadLocal<>();
    
    // ThreadLocal for request tracking
    private static final ThreadLocal<String> requestIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Object>> requestContextHolder = new ThreadLocal<>();
    
    // Configuration access methods - always read latest from TraceConfig
    private static boolean httpEnabled() { return TraceConfig.getBoolean("http.enabled", true); }
    private static boolean captureRequestBody() { return TraceConfig.getBoolean("http.capture.request-body", true); }
    private static boolean captureResponseBody() { return TraceConfig.getBoolean("http.capture.response-body", true); }
    private static boolean captureSession() { return TraceConfig.getBoolean("http.capture.session", true); }
    private static boolean captureRequestHeaders() { return TraceConfig.getBoolean("http.capture.request-headers", true); }
    private static boolean captureResponseHeaders() { return TraceConfig.getBoolean("http.capture.response-headers", true); }
    private static int maxResponseSize() { return TraceConfig.getInt("http.capture.response-body-max-bytes", 10240); }
    
    // Get allowed content types for request/response body capture
    private static Set<String> getAllowedRequestBodyTypes() {
        return parseCsvToLowerSet(TraceConfig.getString("http.capture.request-body-types", "json,xml,html,form,text,plain"), ",");
    }
    
    private static Set<String> getAllowedResponseBodyTypes() {
        return parseCsvToLowerSet(TraceConfig.getString("http.capture.response-body-types", "json,xml,html,text,plain"), ",");
    }
    
    // Check if content-type should be captured
    private static boolean shouldCaptureRequestBody(String contentType) {
        if (contentType == null) return true; // Capture if no content-type specified
        String lower = contentType.toLowerCase();
        for (String allowed : getAllowedRequestBodyTypes()) {
            if (lower.contains(allowed)) return true;
        }
        return false;
    }
    
    private static boolean shouldCaptureResponseBody(String contentType) {
        if (contentType == null) return true; // Capture if no content-type specified
        String lower = contentType.toLowerCase();
        for (String allowed : getAllowedResponseBodyTypes()) {
            if (lower.contains(allowed)) return true;
        }
        return false;
    }
    
    private static String cachedHostname = null;
    private static String getTraceName() {
        String traceName = TraceConfig.getString("trace.name", null);
        if (traceName != null && !traceName.trim().isEmpty()) {
            return traceName.trim();
        }
        // Auto-detect hostname if trace.name not configured
        if (cachedHostname == null) {
            try {
                cachedHostname = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Throwable t) {
                cachedHostname = "unknown-host";
            }
        }
        return cachedHostname;
    }
    private static List<Pattern> getExcludeUrlPatterns() {
        return parseRegexList(TraceConfig.getString("http.exclude-url-pattern", "/actuator/.*;/health;/favicon.ico;/static/.*"));
    }
    private static Set<String> getSessionExcludeKeys() {
        return parseCsvToLowerSet(TraceConfig.getString("http.capture.session.exclude-keys", "password;token;secret"), ";");
    }
    
    /**
     * Create response wrapper to capture body content
     * Tries multiple wrapper types to support different servlet APIs
     */
    public static Object createResponseWrapper(Object response) {
        if (!captureResponseBody() || !httpEnabled() || response == null) {
            return null;
        }

        try {
            if (isReadableResponseWrapper(response)) {
                wrappedResponseHolder.set(response);
                return response;
            }
        } catch (Throwable ignored) {}
        
        // Try our wrapper first
        try {
            ClassLoader cl = SimpleHttpTracer.class.getClassLoader();
            Class<?> wrapperClass = Class.forName("com.pnones.trace.agent.ContentCachingResponseWrapper", true, cl);
            Object wrapper = wrapperClass.getConstructor(Object.class, int.class).newInstance(response, maxResponseSize());
            wrappedResponseHolder.set(wrapper);
            com.pnones.trace.util.DebugLogger.log("Created ContentCachingResponseWrapper");
            return wrapper;
        } catch (ClassNotFoundException | NoClassDefFoundError notFound) {
            com.pnones.trace.util.DebugLogger.log("ContentCachingResponseWrapper not found: " + notFound.getMessage());
        } catch (Throwable t) {
            com.pnones.trace.util.DebugLogger.log("Failed to create ContentCachingResponseWrapper: " + t.getMessage(), t);
        }
        
        // Try Spring's ContentCachingResponseWrapper if available
        try {
            Class<?> springWrapperClass = Class.forName("org.springframework.web.util.ContentCachingResponseWrapper");
            Object wrapper = springWrapperClass.getConstructor(Object.class).newInstance(response);
            wrappedResponseHolder.set(wrapper);
            com.pnones.trace.util.DebugLogger.log("Created Spring ContentCachingResponseWrapper");
            return wrapper;
        } catch (ClassNotFoundException notFound) {
            com.pnones.trace.util.DebugLogger.log("Spring wrapper not available");
        } catch (Throwable t) {
            com.pnones.trace.util.DebugLogger.log("Failed to create Spring wrapper: " + t.getMessage());
        }
        
        // If wrapping fails, store original and return it
        wrappedResponseHolder.set(response);
        com.pnones.trace.util.DebugLogger.log("Using original response (no wrapper available)");
        return response;
    }

    public static Object before(Object reqObj, Object respObj) {
        if (!httpEnabled()) return null;
        
        // CRITICAL: Wrap request with ContentCachingRequestWrapper BEFORE any application filter reads it
        // This ensures the body is cached in memory and available for both application and PTrace to read
        Object wrappedReq = reqObj;
        try {
            wrappedReq = RequestBodyCapture.wrapRequestIfNeeded(reqObj);
            DebugLogger.log("SimpleHttpTracer: wrapped request - " + (wrappedReq != reqObj ? "wrapped" : "already wrapped or wrap failed"));
        } catch (Throwable t) {
            DebugLogger.log("Failed to wrap request", t);
            wrappedReq = reqObj;
        }
        
        // Capture request body via reflection (no servlet dependency)
        try {
            RequestBodyCapture.captureFromRequest(wrappedReq);
        } catch (Throwable t) {
            DebugLogger.log("Failed to capture request body", t);
            // Continue without body capture
        }
        
        // Check if this is a login URL - handle differently for safety
        boolean isLogin = false;
        try {
            isLogin = isLoginUrl(reqObj);
        } catch (Throwable ignored) {}
        
        try {
            Map<String,Object> m = new HashMap<>();
            
            // Extract HTTP info using reflection to avoid classloader issues
            String method = "UNKNOWN";
            String path = "UNKNOWN";
            String query = "";
            String requestId = java.util.UUID.randomUUID().toString();
            String sessionId = "";
            String userAgent = "";
            String contentType = "";
            String sessionUser = "";
            Map<String, Object> sessionAttributes = new HashMap<>();
            Map<String, String> requestHeaders = new HashMap<>();
            
            try {
                // Reflection-based access to HttpServletRequest methods (works with both jakarta and javax)
                if (reqObj != null) {
                    System.err.println("[PTrace] Request object class: " + reqObj.getClass().getName());
                    
                    // Try to unwrap if it's a wrapper
                    Object actualReq = reqObj;
                    try {
                        Class<?> reqClass = reqObj.getClass();
                        if (reqClass.getName().contains("Wrapper") || reqClass.getName().contains("Facade")) {
                            try {
                                java.lang.reflect.Method getRequestMethod = reqClass.getMethod("getRequest");
                                Object unwrapped = getRequestMethod.invoke(reqObj);
                                if (unwrapped != null) {
                                    actualReq = unwrapped;
                                    System.err.println("[PTrace] Unwrapped to: " + actualReq.getClass().getName());
                                }
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    
                    Class<?> reqClass = actualReq.getClass();
                    try {
                        Object methodObj = reqClass.getMethod("getMethod", (Class<?>[])null).invoke(actualReq, (Object[])null);
                        if (methodObj != null) {
                            method = methodObj.toString();
                            System.err.println("[PTrace] HTTP Method: " + method);
                        }
                    } catch (Throwable e) {
                        System.err.println("[PTrace] Failed to get HTTP Method via reflection: " + e.getMessage());
                        com.pnones.trace.util.DebugLogger.log("Failed to get HTTP Method via reflection: " + e.getMessage());
                    }
                    
                    // Try getRequestURI first, then fallbacks
                    try {
                        Object pathObj = reqClass.getMethod("getRequestURI", (Class<?>[])null).invoke(actualReq, (Object[])null);
                        if (pathObj != null) {
                            path = pathObj.toString();
                            System.err.println("[PTrace] HTTP Path: " + path);
                        }
                    } catch (Throwable e) {
                        System.err.println("[PTrace] Failed to get Request URI via reflection: " + e.getMessage());
                        com.pnones.trace.util.DebugLogger.log("Failed to get Request URI via reflection: " + e.getMessage());
                        
                        // Fallback 1: try getServletPath + getPathInfo
                        try {
                            String servletPath = "";
                            String pathInfo = "";
                            try {
                                Object sPathObj = reqClass.getMethod("getServletPath", (Class<?>[])null).invoke(actualReq, (Object[])null);
                                servletPath = (sPathObj != null) ? sPathObj.toString() : "";
                            } catch (Throwable ignored) {}
                            try {
                                Object pInfoObj = reqClass.getMethod("getPathInfo", (Class<?>[])null).invoke(actualReq, (Object[])null);
                                pathInfo = (pInfoObj != null) ? pInfoObj.toString() : "";
                            } catch (Throwable ignored) {}
                            
                            if (!servletPath.isEmpty() || !pathInfo.isEmpty()) {
                                path = servletPath + (pathInfo != null ? pathInfo : "");
                                System.err.println("[PTrace] HTTP Path (from servlet/pathInfo): " + path);
                            }
                        } catch (Throwable ignored) {}
                        
                        // Fallback 2: try getRequestURL and extract path from it
                        if ("UNKNOWN".equals(path)) {
                            try {
                                Object urlObj = reqClass.getMethod("getRequestURL", (Class<?>[])null).invoke(actualReq, (Object[])null);
                                if (urlObj != null) {
                                    String urlStr = urlObj.toString();
                                    int schemeEnd = urlStr.indexOf("://");
                                    if (schemeEnd > 0) {
                                        int hostEnd = urlStr.indexOf("/", schemeEnd + 3);
                                        if (hostEnd > 0) {
                                            path = urlStr.substring(hostEnd);
                                            System.err.println("[PTrace] HTTP Path (from URL): " + path);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                    
                    try {
                        Object queryObj = reqClass.getMethod("getQueryString", (Class<?>[])null).invoke(actualReq, (Object[])null);
                        query = (queryObj != null) ? (String) queryObj : "";
                    } catch (Throwable ignored) {}

                    if (shouldExcludeUrl(path)) {
                        return null;
                    }
                    
                    // Extract session ID and login info (wrapped with outermost exception handler)
                    // Non-login URLs: extract full session info
                    try {
                        try {
                        Object session = null;
                        try {
                            session = reqClass.getMethod("getSession", new Class[]{boolean.class}).invoke(actualReq, false);
                        } catch (Throwable ignored) {}
                        
                        if (session != null) {
                            Class<?> sessionClass = session.getClass();
                            
                            // Get session ID safely
                            try {
                                Object idObj = sessionClass.getMethod("getId").invoke(session);
                                sessionId = (idObj != null) ? idObj.toString() : "";
                            } catch (Throwable ignored) {}
                            
                            // Extract session attributes (login info, user objects, etc)
                            String[] attrNames = {
                                "user", "userId", "username", "principal", "authenticated",
                                "SPRING_SECURITY_CONTEXT", "org.springframework.security.web.authentication.WebAuthenticationDetails",
                                "login", "loginId", "loginUser", "currentUser", "userInfo"
                            };
                            StringBuilder userInfo = new StringBuilder();
                            boolean found = false;
                            for (String attrName : attrNames) {
                                try {
                                    Object attr = sessionClass.getMethod("getAttribute", new Class[]{String.class}).invoke(session, attrName);
                                    if (attr != null) {
                                        String attrStr = attr.toString();
                                        if (!attrStr.isEmpty() && !attrStr.equals("null")) {
                                            if (userInfo.length() > 0) userInfo.append("|");
                                            userInfo.append(attrName).append("=").append(attrStr);
                                            found = true;
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                            sessionUser = userInfo.toString();

                            if (captureSession()) {
                                try {
                                    Object attrNamesObj = null;
                                    try {
                                        attrNamesObj = sessionClass.getMethod("getAttributeNames").invoke(session);
                                    } catch (Throwable ignored) {}
                                    
                                    if (attrNamesObj instanceof Enumeration) {
                                        try {
                                            // Convert enumeration to List immediately to avoid concurrent modification
                                            List<String> attrNameList = new ArrayList<>();
                                            try {
                                                Enumeration<?> en = (Enumeration<?>) attrNamesObj;
                                                while (en.hasMoreElements()) {
                                                    try {
                                                        Object nameObj = en.nextElement();
                                                        if (nameObj != null) {
                                                            attrNameList.add(nameObj.toString());
                                                        }
                                                    } catch (Throwable e) {
                                                        // Continue collecting names despite errors
                                                        break;
                                                    }
                                                }
                                            } catch (Throwable e) {
                                                com.pnones.trace.util.DebugLogger.log("Enumeration collection error", e);
                                            }
                                            
                                            // Now safely process collected names
                                            for (String name : attrNameList) {
                                                if (name == null || name.isEmpty() || name.startsWith("_") || shouldExcludeSessionKey(name)) {
                                                    continue;
                                                }
                                                try {
                                                    Object attr = sessionClass.getMethod("getAttribute", new Class[]{String.class}).invoke(session, name);
                                                    if (attr != null) {
                                                        sessionAttributes.put(name, stringifySessionValue(name, attr));
                                                    }
                                                } catch (Throwable ignored) {}
                                            }
                                        } catch (Throwable e) {
                                            com.pnones.trace.util.DebugLogger.log("Enumeration iteration error", e);
                                        }
                                    }
                                } catch (Throwable e) {
                                    com.pnones.trace.util.DebugLogger.log("Session attribute enumeration error", e);
                                }
                            }
                            
                            // If no specific attributes found, try to get all attributes
                            if (!found) {
                                try {
                                    Object attrNames_obj = null;
                                    try {
                                        attrNames_obj = sessionClass.getMethod("getAttributeNames").invoke(session);
                                    } catch (Throwable ignored) {}
                                    
                                    if (attrNames_obj instanceof Enumeration) {
                                        try {
                                            // Convert enumeration to List immediately to avoid concurrent modification
                                            List<String> attrNameList = new ArrayList<>();
                                            try {
                                                Enumeration<?> en = (Enumeration<?>) attrNames_obj;
                                                while (en.hasMoreElements()) {
                                                    try {
                                                        Object nameObj = en.nextElement();
                                                        if (nameObj != null) {
                                                            attrNameList.add(nameObj.toString());
                                                        }
                                                    } catch (Throwable e) {
                                                        // Continue collecting names despite errors
                                                        break;
                                                    }
                                                }
                                            } catch (Throwable e) {
                                                com.pnones.trace.util.DebugLogger.log("Fallback enumeration collection error", e);
                                            }
                                            
                                            // Now safely process collected names
                                            StringBuilder allAttrs = new StringBuilder();
                                            for (String name : attrNameList) {
                                                if (name == null || name.isEmpty()) continue;
                                                try {
                                                    Object attr = sessionClass.getMethod("getAttribute", new Class[]{String.class}).invoke(session, name);
                                                    if (attr != null && !name.startsWith("_")) {
                                                        if (allAttrs.length() > 0) allAttrs.append("|");
                                                        String attrStr = attr.toString();
                                                        // Truncate very long values
                                                        if (attrStr.length() > 500) {
                                                            attrStr = attrStr.substring(0, 500) + "...";
                                                        }
                                                        allAttrs.append(name).append("=").append(attrStr);
                                                    }
                                                } catch (Throwable ignored) {}
                                            }
                                            if (allAttrs.length() > 0) sessionUser = allAttrs.toString();
                                        } catch (Throwable e) {
                                            com.pnones.trace.util.DebugLogger.log("Fallback enumeration iteration error", e);
                                        }
                                    }
                                } catch (Throwable e) {
                                    com.pnones.trace.util.DebugLogger.log("Session fallback enumeration error", e);
                                }
                            }
                        }
                        } catch (Throwable sessionAccessError) {
                            // Log session access failure but continue - don't break HTTP tracing
                            com.pnones.trace.util.DebugLogger.log("Session access failed", sessionAccessError);
                        }
                    } catch (Throwable ignored) {}
                    
                    // Extract headers
                    try {
                        Object userAgentObj = reqClass.getMethod("getHeader", new Class[]{String.class}).invoke(reqObj, "User-Agent");
                        userAgent = (userAgentObj != null) ? (String) userAgentObj : "";
                        
                        Object contentTypeObj = reqClass.getMethod("getHeader", new Class[]{String.class}).invoke(reqObj, "Content-Type");
                        contentType = (contentTypeObj != null) ? (String) contentTypeObj : "";
                    } catch (Throwable ignored) {}
                    
                    // Extract all request headers if enabled
                    if (captureRequestHeaders()) {
                        try {
                            Object headerNamesObj = reqClass.getMethod("getHeaderNames").invoke(reqObj);
                            if (headerNamesObj instanceof Enumeration) {
                                Enumeration<?> headerNames = (Enumeration<?>) headerNamesObj;
                                while (headerNames.hasMoreElements()) {
                                    try {
                                        String headerName = (String) headerNames.nextElement();
                                        Object headerValue = reqClass.getMethod("getHeader", new Class[]{String.class}).invoke(reqObj, headerName);
                                        if (headerValue != null) {
                                            requestHeaders.put(headerName, headerValue.toString());
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ex) {
                com.pnones.trace.util.DebugLogger.log("Error extracting request info via reflection", ex);
            }
            
            m.put("method", method);
            m.put("path", path);
            m.put("query", query);
            m.put("sessionId", sessionId);
            if (!sessionUser.isEmpty()) m.put("sessionUser", sessionUser);
            if (!sessionAttributes.isEmpty()) m.put("sessionAttributes", sessionAttributes);
            m.put("userAgent", userAgent);
            m.put("contentType", contentType);
            m.put("requestHeaders", requestHeaders);

            // Capture request parameters for all methods (query/form)
            try {
                Object paramMapObj = reqObj.getClass().getMethod("getParameterMap").invoke(reqObj);
                if (paramMapObj instanceof Map) {
                    Map<?, ?> paramMap = (Map<?, ?>) paramMapObj;
                    Map<String, Object> requestParams = new HashMap<>();
                    for (Map.Entry<?, ?> entry : paramMap.entrySet()) {
                        if (entry.getKey() == null) continue;
                        String key = String.valueOf(entry.getKey());
                        Object value = entry.getValue();
                        if (value instanceof String[]) {
                            String[] values = (String[]) value;
                            if (values.length == 1) requestParams.put(key, values[0]);
                            else requestParams.put(key, values);
                        } else {
                            requestParams.put(key, value);
                        }
                    }
                    if (!requestParams.isEmpty()) m.put("requestParams", requestParams);
                }
            } catch (Throwable ignored) {}

            m.put("start", System.currentTimeMillis());
            m.put("requestId", requestId);
            m.put("_pn_resp_obj", respObj);        // Store response object for later use in after()
            m.put("_pn_req_obj", wrappedReq);     // Store WRAPPED request object - enables ContentCachingRequestWrapper caching
            m.put("_pn_original_req", reqObj);    // Also keep original for reference
            
            // Capture request body if enabled
            DebugLogger.log("Request body capture check: captureRequestBody=" + captureRequestBody() + ", contentType=" + contentType);
            if (contentType != null) {
                DebugLogger.log("shouldCaptureRequestBody('" + contentType + "') = " + shouldCaptureRequestBody(contentType));
            }
            if (captureRequestBody() && shouldCaptureRequestBody(contentType)) {
                DebugLogger.log("Attempting to capture request body for content-type: " + contentType);
                try {
                    // Handle form-urlencoded data (safe - doesn't consume InputStream)
                    if (contentType != null && contentType.contains("application/x-www-form-urlencoded")) {
                        Object paramMapObj = reqObj.getClass().getMethod("getParameterMap").invoke(reqObj);
                        if (paramMapObj instanceof Map) {
                            StringBuilder bodyBuilder = new StringBuilder();
                            Map<?, ?> paramMap = (Map<?, ?>) paramMapObj;
                            boolean first = true;
                            for (Map.Entry<?, ?> entry : paramMap.entrySet()) {
                                if (!first) bodyBuilder.append("&");
                                first = false;
                                bodyBuilder.append(entry.getKey()).append("=");
                                Object value = entry.getValue();
                                if (value instanceof String[]) {
                                    String[] values = (String[]) value;
                                    if (values.length > 0) {
                                        bodyBuilder.append(values[0]);
                                    }
                                } else {
                                    bodyBuilder.append(value);
                                }
                            }
                            if (bodyBuilder.length() > 0) {
                                m.put("requestBody", bodyBuilder.toString());
                                m.put("requestBodyType", "form");
                            }
                        }
                    }
                    // For JSON/XML/HTML: try to get from captured body cache via reflection
                    else if (contentType != null) {
                        String wrapperBody = RequestBodyCapture.getBody(reqObj);
                        String bodyType = identifyBodyType(contentType);
                        
                        DebugLogger.log("RequestBodyCapture.getBody() returned: " + (wrapperBody == null ? "null" : "'" + wrapperBody + "'"));
                        
                        if (bodyType != null) {
                            m.put("requestContentType", bodyType);
                            if (wrapperBody != null && !wrapperBody.isEmpty()) {
                                // Limit request body size
                                int maxSize = maxResponseSize();
                                if (wrapperBody.length() > maxSize) {
                                    wrapperBody = wrapperBody.substring(0, maxSize) + "... (truncated)";
                                }
                                m.put("requestBody", wrapperBody);
                                m.put("requestBodyType", bodyType);
                                DebugLogger.log("Added request body to trace: " + wrapperBody);
                            } else {
                                DebugLogger.log("Request body is null or empty after capture");
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            
            // Set RequestContext for SQL tracing
            RequestContext ctx = new RequestContext(requestId, Thread.currentThread().getId(), Thread.currentThread().getName());
            RequestContext.set(ctx);
            m.put("_pn_ctx", ctx);
                    // For login URLs: skip response wrapper to avoid NPE in application code
                    if (!isLogin) {
                        Object wrappedResp = createResponseWrapper(respObj);
                        if (wrappedResp != null && wrappedResp != respObj) {
                            wrappedResponseHolder.set(wrappedResp);
                            com.pnones.trace.util.DebugLogger.log("Response wrapper stored for body capture");
                        }
                    }
            // Add debug logging to confirm tracing is working
            com.pnones.trace.util.DebugLogger.log("HTTP TRACE START: " + method + " " + path);
            
            return m;
        } catch (Throwable t) {
            com.pnones.trace.util.DebugLogger.log("SimpleHttpTracer.before error", t);
            return null;
        }
    }

    private static Object handleLoginRequest(Object reqObj) {
        // Minimal logging for login: only method and path
        try {
            if (reqObj == null) return null;
            
            Class<?> reqClass = reqObj.getClass();
            String method = "UNKNOWN";
            String path = "UNKNOWN";
            
            try {
                method = (String) reqClass.getMethod("getMethod", (Class<?>[]) null).invoke(reqObj, (Object[]) null);
            } catch (Throwable ignored) {}
            
            try {
                path = (String) reqClass.getMethod("getRequestURI", (Class<?>[]) null).invoke(reqObj, (Object[]) null);
            } catch (Throwable ignored) {}
            
            String requestId = java.util.UUID.randomUUID().toString();
            com.pnones.trace.util.DebugLogger.log("LOGIN_REQUEST [" + requestId + "] " + method + " " + path);
            
            // Store minimal context - only for SQL correlation
            Map<String, Object> context = new HashMap<>();
            context.put("requestId", requestId);
            context.put("timestamp", System.currentTimeMillis());
            context.put("isLogin", true);
            context.put("method", method);
            context.put("path", path);
            
            requestIdHolder.set(requestId);
            requestContextHolder.set(context);
            
            return requestId;  // Return token for after()
        } catch (Throwable e) {
            com.pnones.trace.util.DebugLogger.log("Error in handleLoginRequest", e);
            return null;
        }
    }

    public static void after(Object token, Object respObj) {
        if (!httpEnabled()) return;
        
        try {
            // ===== Handle Login Response (minimal processing) =====
            if (token instanceof String) {
                // Login request - minimal response processing
                String requestId = (String) token;
                int status = 0;
                try {
                    if (respObj != null) {
                        Class<?> respClass = respObj.getClass();
                        Object statusObj = respClass.getMethod("getStatus", (Class<?>[]) null).invoke(respObj, (Object[]) null);
                        status = (statusObj instanceof Number) ? ((Number) statusObj).intValue() : 0;
                    }
                } catch (Throwable ignored) {}
                
                com.pnones.trace.util.DebugLogger.log("LOGIN_RESPONSE [" + requestId + "] status=" + status);
                requestIdHolder.remove();
                requestContextHolder.remove();
                    // return;  // Skip all other processing for login
            }
            
            // ===== Handle Normal Request (full processing) =====
            // Token may be null if before() returned early or on exceptions
            Map<String,Object> m = null;
            long start = System.currentTimeMillis();
            
            if (token instanceof Map) {
                @SuppressWarnings("unchecked") Map<String,Object> tokenMap = (Map<String,Object>) token;
                m = tokenMap;
                start = tokenMap.containsKey("start") ? ((Number)tokenMap.get("start")).longValue() : System.currentTimeMillis();
            } else if (token == null) {
                // Skip unknown/duplicate end hooks without request token
                return;
            } else {
                // Token is not a map - skip
                return;
            }
            
            // Extract status using reflection to avoid classloader issues
            int status = 0;
            String contentType = null;
            long contentLength = -1;
            try {
                if (respObj != null) {
                    Class<?> respClass = respObj.getClass();
                    Object statusObj = respClass.getMethod("getStatus", (Class<?>[]) null).invoke(respObj, (Object[]) null);
                    status = (statusObj instanceof Number) ? ((Number) statusObj).intValue() : 0;
                    
                    // Extract Content-Type
                    try {
                        Object ctObj = respClass.getMethod("getContentType", (Class<?>[]) null).invoke(respObj, (Object[]) null);
                        if (ctObj != null) contentType = ctObj.toString();
                    } catch (Throwable ignored) {}
                    
                    // Extract Content-Length
                    try {
                        Object clObj = respClass.getMethod("getContentLength", (Class<?>[]) null).invoke(respObj, (Object[]) null);
                        if (clObj instanceof Number) contentLength = ((Number) clObj).longValue();
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ex) {
                com.pnones.trace.util.DebugLogger.log("Error extracting response status via reflection", ex);
            }
            
            m.put("status", status);
            String responseId = java.util.UUID.randomUUID().toString();
            m.put("responseId", responseId);
            if (contentType != null) m.put("responseContentType", contentType);
            if (contentLength >= 0) m.put("responseContentLength", contentLength);
            
            // Extract all response headers if enabled
            if (captureResponseHeaders()) {
                Map<String, String> responseHeaders = new HashMap<>();
                try {
                    if (respObj != null) {
                        Class<?> respClass = respObj.getClass();
                        Object headerNamesObj = respClass.getMethod("getHeaderNames", (Class<?>[]) null).invoke(respObj, (Object[]) null);
                        if (headerNamesObj instanceof java.util.Collection) {
                            java.util.Collection<?> headerNames = (java.util.Collection<?>) headerNamesObj;
                            for (Object headerNameObj : headerNames) {
                                try {
                                    String headerName = headerNameObj.toString();
                                    Object headerValue = respClass.getMethod("getHeader", new Class[]{String.class}).invoke(respObj, headerName);
                                    if (headerValue != null) {
                                        responseHeaders.put(headerName, headerValue.toString());
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                if (!responseHeaders.isEmpty()) m.put("responseHeaders", responseHeaders);
            }
            
            m.put("elapsedMs", System.currentTimeMillis() - start);
            
            // Capture response body using reflection to avoid classloader issues
            com.pnones.trace.util.DebugLogger.log("Response object type: " + respObj.getClass().getName());
            
            // Try to get response from ThreadLocal wrapper first
            Object responseToCapture = respObj;
            try {
                Object wrappedResp = wrappedResponseHolder.get();
                if (wrappedResp != null) {
                    responseToCapture = wrappedResp;
                    com.pnones.trace.util.DebugLogger.log("Using wrapped response for body capture");
                }
            } catch (Throwable ignored) {}
            
            if (captureResponseBody() && responseToCapture != null && shouldCaptureResponseBody(contentType)) {
                String className = responseToCapture.getClass().getName();
                com.pnones.trace.util.DebugLogger.log("Response capture enabled. Class: " + className);
                
                // Support any wrapper that can expose cached response body
                boolean isReadable = isReadableResponseWrapper(responseToCapture);
                com.pnones.trace.util.DebugLogger.log("Response is readable wrapper: " + isReadable);
                
                if (isReadable) {
                    try {
                        // Use reflection to call methods
                        Class<?> wrapperClass = responseToCapture.getClass();
                        
                        // Try to flush buffer first
                        try {
                            java.lang.reflect.Method flushMethod = wrapperClass.getMethod("flushBuffer");
                            flushMethod.invoke(responseToCapture);
                            com.pnones.trace.util.DebugLogger.log("Flushed response buffer");
                        } catch (Throwable ignored) {}
                        
                        // Try to flush writer if it exists
                        try {
                            java.lang.reflect.Method writerMethod = wrapperClass.getMethod("getWriter");
                            Object writer = writerMethod.invoke(responseToCapture);
                            if (writer != null) {
                                java.lang.reflect.Method flushWriterMethod = writer.getClass().getMethod("flush");
                                flushWriterMethod.invoke(writer);
                                com.pnones.trace.util.DebugLogger.log("Flushed response writer");
                            }
                        } catch (Throwable ignored) {}
                        
                        // Dynamically find and call all no-arg methods that return byte[] or String
                        byte[] cached = null;
                        String stringContent = null;
                        
                        for (java.lang.reflect.Method method : wrapperClass.getMethods()) {
                            if (method.getParameterCount() != 0 || method.getDeclaringClass().equals(Object.class)) {
                                continue; // Skip methods with parameters or from Object
                            }
                            
                            Class<?> returnType = method.getReturnType();
                            
                            try {
                                if (returnType == byte[].class) {
                                    Object result = method.invoke(responseToCapture);
                                    if (result != null) {
                                        cached = (byte[]) result;
                                        com.pnones.trace.util.DebugLogger.log("Got response bytes via " + method.getName() + ": " + cached.length);
                                        break;
                                    }
                                } else if (returnType == String.class) {
                                    Object result = method.invoke(responseToCapture);
                                    if (result != null) {
                                        stringContent = (String) result;
                                        if (cached == null) {
                                            cached = stringContent.getBytes("UTF-8");
                                        }
                                        com.pnones.trace.util.DebugLogger.log("Got response string via " + method.getName() + ": " + stringContent.length());
                                        if (cached.length > 0) break;
                                    }
                                }
                            } catch (Throwable ignored) {
                                // Continue trying other methods
                            }
                        }
                        
                        int cachedLen = cached != null ? cached.length : 0;
                        com.pnones.trace.util.DebugLogger.log("Captured response body bytes: " + cachedLen);
                        
                        if (cachedLen > 0) {
                            boolean textBased = contentType == null || isTextBased(contentType);
                            com.pnones.trace.util.DebugLogger.log("Response is text-based: " + textBased + ", contentType: " + contentType);
                            
                            if (textBased) {
                                String responseBody = stringContent != null ? stringContent : (cached != null ? new String(cached, "UTF-8") : null);
                                
                                if (responseBody != null && !responseBody.isEmpty()) {
                                    // Limit response body size
                                    int maxSize = maxResponseSize();
                                    if (responseBody.length() > maxSize) {
                                        responseBody = responseBody.substring(0, maxSize) + "... (truncated)";
                                    }
                                    m.put("responseBody", responseBody);
                                    m.put("responseBodySize", cachedLen);
                                    
                                    // Identify body type based on content-type
                                    String bodyType = identifyBodyType(contentType);
                                    if (bodyType != null) {
                                        m.put("responseBodyType", bodyType);
                                    }
                                    com.pnones.trace.util.DebugLogger.log("Added response body to trace. Type: " + m.get("responseBodyType") + ", Size: " + cachedLen);
                                } else {
                                    com.pnones.trace.util.DebugLogger.log("Response body is null or empty");
                                }
                            } else {
                                m.put("responseBodyBase64", Base64.getEncoder().encodeToString(cached));
                                m.put("responseBodySize", cachedLen);
                                com.pnones.trace.util.DebugLogger.log("Added binary response body to trace");
                            }
                        } else {
                            com.pnones.trace.util.DebugLogger.log("No response body bytes to capture (cachedLen=0)");
                        }
                    } catch (Throwable t) {
                        com.pnones.trace.util.DebugLogger.log("Failed to get cached response content", t);
                    }
                } else {
                    com.pnones.trace.util.DebugLogger.log("Response is not a readable wrapper. Class: " + className);
                }
            } else if (captureResponseBody()) {
                com.pnones.trace.util.DebugLogger.log("Response body capture disabled or respObj is null");
            }
            
            // Get SQL queries from RequestContext
            RequestContext ctx = (RequestContext) m.get("_pn_ctx");
            java.util.List<Map<String, Object>> sqlQueries = (ctx != null) ? ctx.getSqlQueries() : new java.util.ArrayList<>();
            
            // Add debug logging
            com.pnones.trace.util.DebugLogger.log("HTTP TRACE END: " + m.get("method") + " " + m.get("path") + " status=" + status + " SQL count=" + sqlQueries.size());
            
            // Clear RequestContext
            RequestContext.clear();
            
            // Generate JSON log output
            StringBuilder json = new StringBuilder("{");
            json.append("\"timestamp\":").append(start).append(",");
            json.append("\"traceName\":\"").append(escapeJson(getTraceName())).append("\",");
            json.append("\"requestId\":\"").append(escapeJson(String.valueOf(m.get("requestId")))).append("\",");
            json.append("\"responseId\":\"").append(escapeJson(String.valueOf(m.get("responseId")))).append("\",");
            json.append("\"threadId\":").append(Thread.currentThread().getId()).append(",");
            json.append("\"threadName\":\"").append(escapeJson(Thread.currentThread().getName())).append("\",");
            json.append("\"method\":\"").append(escapeJson(String.valueOf(m.get("method")))).append("\",");
            json.append("\"path\":\"").append(escapeJson(String.valueOf(m.get("path")))).append("\",");
            
            if (m.get("query") != null && !"".equals(m.get("query"))) {
                json.append("\"query\":\"").append(escapeJson(String.valueOf(m.get("query")))).append("\",");
            }
            
            if (m.get("sessionId") != null && !"".equals(m.get("sessionId"))) {
                json.append("\"sessionId\":\"").append(escapeJson(String.valueOf(m.get("sessionId")))).append("\",");
            }
            
            if (m.get("sessionUser") != null && !"".equals(m.get("sessionUser"))) {
                json.append("\"sessionUser\":\"").append(escapeJson(String.valueOf(m.get("sessionUser")))).append("\",");
            }

            if (m.get("sessionAttributes") instanceof Map) {
                json.append("\"sessionAttributes\":").append(gson.toJson(m.get("sessionAttributes"))).append(",");
            }
            
            // Always include request headers (even if empty)
            if (m.get("requestHeaders") instanceof Map) {
                json.append("\"requestHeaders\":").append(gson.toJson(m.get("requestHeaders"))).append(",");
            }

            if (m.get("requestParams") instanceof Map) {
                json.append("\"requestParams\":").append(gson.toJson(m.get("requestParams"))).append(",");
            }
            
            if (m.get("responseHeaders") instanceof Map) {
                json.append("\"responseHeaders\":").append(gson.toJson(m.get("responseHeaders"))).append(",");
            }
            
            if (m.get("userAgent") != null && !"".equals(m.get("userAgent"))) {
                json.append("\"userAgent\":\"").append(escapeJson(String.valueOf(m.get("userAgent")))).append("\",");
            }
            
            if (m.get("contentType") != null && !"".equals(m.get("contentType"))) {
                json.append("\"contentType\":\"").append(escapeJson(String.valueOf(m.get("contentType")))).append("\",");
            }
            
            // Always include request body if capture is enabled (even if empty)
            if (captureRequestBody()) {
                String reqBody = m.get("requestBody") != null ? String.valueOf(m.get("requestBody")) : "";
                // Truncate if too long
                if (reqBody.length() > 1000) {
                    reqBody = reqBody.substring(0, 1000) + "... (truncated)";
                }
                json.append("\"requestBody\":\"").append(escapeJson(reqBody)).append("\",");
            }
            
            // Include request body type if capture is enabled and type was identified
            if (captureRequestBody() && m.get("requestBodyType") != null) {
                json.append("\"requestBodyType\":\"").append(escapeJson(String.valueOf(m.get("requestBodyType")))).append("\",");
            }
            
            if (m.get("requestContentType") != null && !"".equals(m.get("requestContentType"))) {
                json.append("\"requestContentType\":\"").append(escapeJson(String.valueOf(m.get("requestContentType")))).append("\",");
            }
            
            if (m.get("responseBody") != null && !"".equals(m.get("responseBody"))) {
                String respBody = String.valueOf(m.get("responseBody"));
                // Truncate if too long
                if (respBody.length() > 2000) {
                    respBody = respBody.substring(0, 2000) + "... (truncated)";
                }
                json.append("\"responseBody\":\"").append(escapeJson(respBody)).append("\",");
            }
            if (m.get("responseBodyBase64") != null && !"".equals(m.get("responseBodyBase64"))) {
                String respBody64 = String.valueOf(m.get("responseBodyBase64"));
                if (respBody64.length() > 4096) {
                    respBody64 = respBody64.substring(0, 4096) + "... (truncated)";
                }
                json.append("\"responseBodyBase64\":\"").append(escapeJson(respBody64)).append("\",");
            }
            if (m.get("responseBodySize") != null) {
                json.append("\"responseBodySize\":").append(m.get("responseBodySize")).append(",");
            }
            if (m.get("responseBodyType") != null && !"".equals(m.get("responseBodyType"))) {
                json.append("\"responseBodyType\":\"").append(escapeJson(String.valueOf(m.get("responseBodyType")))).append("\",");
            }
            
            json.append("\"status\":").append(status).append(",");
            
            if (m.get("responseContentType") != null && !"".equals(m.get("responseContentType"))) {
                json.append("\"responseContentType\":\"").append(escapeJson(String.valueOf(m.get("responseContentType")))).append("\",");
            }
            
            if (m.get("responseContentLength") != null) {
                long rcl = ((Number) m.get("responseContentLength")).longValue();
                if (rcl >= 0) {
                    json.append("\"responseContentLength\":").append(rcl).append(",");
                }
            }
            
            json.append("\"elapsedMs\":").append(m.get("elapsedMs"));
            
            // Wait for any pending ResultSet proxy data collection to complete
            // This ensures SQL result data is included in the HTTP trace
            try {
                for (int waitCount = 0; waitCount < 50; waitCount++) { // Max 5 seconds wait
                    boolean pendingUpdates = false;
                    for (Map<String, Object> sql : sqlQueries) {
                        if (Boolean.TRUE.equals(sql.get("pendingResult")) || sql.get("resultData") == null) {
                            String sqlStr = (sql.get("sql") != null) ? sql.get("sql").toString() : "";
                            // Check if this is a SELECT query (ignore leading comments)
                            String upperSql = sqlStr.replaceAll("^\\s*/\\*.*?\\*/\\s*", "").toUpperCase().trim();
                            if (upperSql.startsWith("SELECT")) {
                                // Still waiting for SELECT query result data
                                pendingUpdates = true;
                                break;
                            }
                        }
                    }
                    if (!pendingUpdates) break;
                    Thread.sleep(100);
                }
            } catch (Throwable ignored) {}
            
            // SQL Queries as JSON array
            if (!sqlQueries.isEmpty()) {
                json.append(",\"sqlQueries\":[");
                for (int i = 0; i < sqlQueries.size(); i++) {
                    if (i > 0) json.append(",");
                    Map<String, Object> sql = sqlQueries.get(i);
                    json.append("{");
                    json.append("\"requestId\":\"").append(escapeJson(String.valueOf(m.get("requestId")))).append("\"");
                    json.append(",\"responseId\":\"").append(escapeJson(String.valueOf(m.get("responseId")))).append("\"");
                    json.append(",");
                    json.append("\"sql\":\"").append(escapeJson(String.valueOf(sql.get("sql")))).append("\"");
                    
                    if (sql.get("params") != null) {
                        json.append(",\"params\":\"").append(escapeJson(String.valueOf(sql.get("params")))).append("\"");
                    }
                    if (sql.get("paramCount") != null) {
                        json.append(",\"paramCount\":").append(sql.get("paramCount"));
                    }
                    if (sql.get("executionTime") != null) {
                        json.append(",\"executionTimeMs\":").append(sql.get("executionTime"));
                    }
                    if (sql.get("resultRows") != null) {
                        json.append(",\"resultRows\":").append(sql.get("resultRows"));
                    }
                    if (sql.get("resultData") != null) {
                        json.append(",\"resultData\":").append(gson.toJson(sql.get("resultData")));
                    }
                    if (sql.get("updateCount") != null) {
                        json.append(",\"updateCount\":").append(sql.get("updateCount"));
                    }
                    if (sql.get("success") != null) {
                        json.append(",\"success\":").append(sql.get("success"));
                    }
                    if (sql.get("error") != null) {
                        json.append(",\"error\":\"").append(escapeJson(String.valueOf(sql.get("error")))).append("\"");
                    }
                    json.append("}");
                }
                json.append("]");
            }
            
            json.append("}");
            logger.log(json.toString());
        } catch (Throwable t) {
            com.pnones.trace.util.DebugLogger.log("SimpleHttpTracer.after error", t);
        } finally {
            try {
                if (token instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String, Object> _m = (Map<String, Object>) token;
                    Object reqObj = _m.get("_pn_req_obj");
                    RequestBodyCapture.clear(reqObj);
                }
            } catch (Throwable ignored) {}
            // Clear ThreadLocal to prevent memory leaks
            try {
                wrappedResponseHolder.remove();
            } catch (Throwable ignored) {}
        }
    }

    private static String tryExtractRequestBodyFromWrapper(Object reqObj) {
        if (reqObj == null) {
            DebugLogger.log("tryExtractRequestBodyFromWrapper: reqObj is null");
            return null;
        }
        try {
            Class<?> reqClass = reqObj.getClass();
            DebugLogger.log("tryExtractRequestBodyFromWrapper: request class = " + reqClass.getName());

            // Known wrapper method names
            String[] bodyMethods = new String[] {
                "getBody", "getRequestBody", "getCachedBody", "getContentAsString"
            };

            for (String methodName : bodyMethods) {
                try {
                    java.lang.reflect.Method m = reqClass.getMethod(methodName, (Class<?>[]) null);
                    DebugLogger.log("Trying request body extraction method: " + methodName);
                    Object bodyObj = m.invoke(reqObj, (Object[]) null);
                    if (bodyObj instanceof String) {
                        String body = ((String) bodyObj).trim();
                        if (!body.isEmpty()) {
                            DebugLogger.log("SUCCESS: Got body from " + methodName + ", size=" + body.length());
                            return body;
                        } else {
                            DebugLogger.log("Body from " + methodName + " is empty");
                        }
                    } else {
                        DebugLogger.log(methodName + " returned non-String: " + (bodyObj == null ? "null" : bodyObj.getClass().getName()));
                    }
                } catch (NoSuchMethodException e) {
                    DebugLogger.log("Method not found: " + methodName);
                } catch (Throwable e) {
                    DebugLogger.log("Error calling " + methodName + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            }

            // Byte array method name candidates
            String[] bytesMethods = new String[] {
                "getContentAsByteArray", "getCachedBodyAsBytes"
            };
            for (String methodName : bytesMethods) {
                try {
                    java.lang.reflect.Method m = reqClass.getMethod(methodName, (Class<?>[]) null);
                    DebugLogger.log("Trying request body bytes extraction method: " + methodName);
                    Object bytesObj = m.invoke(reqObj, (Object[]) null);
                    if (bytesObj instanceof byte[]) {
                        byte[] bytes = (byte[]) bytesObj;
                        if (bytes.length > 0) {
                            String body = new String(bytes, "UTF-8");
                            DebugLogger.log("SUCCESS: Got body from " + methodName + ", size=" + bytes.length);
                            return body;
                        } else {
                            DebugLogger.log("Byte array from " + methodName + " is empty");
                        }
                    } else {
                        DebugLogger.log(methodName + " returned non-byte[]: " + (bytesObj == null ? "null" : bytesObj.getClass().getName()));
                    }
                } catch (NoSuchMethodException e) {
                    DebugLogger.log("Method not found: " + methodName);
                } catch (Throwable e) {
                    DebugLogger.log("Error calling " + methodName + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            }

            // Common request attributes set by filters/wrappers
            String[] attrNames = new String[] {
                "requestBody", "cachedRequestBody", "REQUEST_BODY", "body"
            };
            for (String attr : attrNames) {
                try {
                    java.lang.reflect.Method getAttr = reqClass.getMethod("getAttribute", String.class);
                    Object attrObj = getAttr.invoke(reqObj, attr);
                    if (attrObj != null) {
                        DebugLogger.log("Attribute '" + attr + "' found: " + attrObj.getClass().getName());
                        if (attrObj instanceof String) {
                            String body = ((String) attrObj).trim();
                            if (!body.isEmpty()) {
                                DebugLogger.log("SUCCESS: Got body from attribute '" + attr + "', size=" + body.length());
                                return body;
                            } else {
                                DebugLogger.log("Attribute '" + attr + "' is empty string");
                            }
                        } else if (attrObj instanceof byte[]) {
                            byte[] bytes = (byte[]) attrObj;
                            if (bytes.length > 0) {
                                String body = new String(bytes, "UTF-8");
                                DebugLogger.log("SUCCESS: Got body from attribute '" + attr + "' (bytes), size=" + bytes.length);
                                return body;
                            } else {
                                DebugLogger.log("Attribute '" + attr + "' byte array is empty");
                            }
                        }
                    } else {
                        DebugLogger.log("Attribute '" + attr + "' not found");
                    }
                } catch (NoSuchMethodException e) {
                    DebugLogger.log("getAttribute method not found");
                    break;
                } catch (Throwable e) {
                    DebugLogger.log("Error checking attribute '" + attr + "': " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            }
            
            DebugLogger.log("No request body extraction succeeded");
        } catch (Throwable e) {
            DebugLogger.log("Exception in tryExtractRequestBodyFromWrapper: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        return null;
    }
    
    private static boolean isTextBased(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.contains("text/") ||
               lower.contains("json") ||
               lower.contains("xml") ||
               lower.contains("html") ||
               lower.contains("javascript") ||
               lower.contains("form-urlencoded");
    }
    
    /**
     * Dynamically identify body type from content-type header
     */
    private static String identifyBodyType(String contentType) {
        if (contentType == null) return null;
        String lower = contentType.toLowerCase();
        
        if (lower.contains("application/json") || lower.contains("json")) {
            return "json";
        } else if (lower.contains("application/xml") || lower.contains("text/xml") || lower.contains("xml")) {
            return "xml";
        } else if (lower.contains("text/html") || lower.contains("html")) {
            return "html";
        } else if (lower.contains("application/x-www-form-urlencoded") || lower.contains("urlencoded")) {
            return "form";
        } else if (lower.contains("text/plain") || lower.contains("text/")) {
            return "text";
        } else if (lower.contains("javascript")) {
            return "javascript";
        }
        return null;
    }

    private static boolean isReadableResponseWrapper(Object response) {
        if (response == null) return false;
        Class<?> cls = response.getClass();
        
        // Try to call any no-arg method that returns byte[] or String
        // This is WAS-agnostic and works with any wrapper
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (m.getParameterCount() == 0 && !m.getDeclaringClass().equals(Object.class)) {
                Class<?> returnType = m.getReturnType();
                if (returnType == byte[].class || returnType == String.class) {
                    // Found a potential body getter
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNoArgMethod(Class<?> cls, String methodName) {
        try {
            cls.getMethod(methodName, (Class<?>[]) null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
    
    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    private static Set<String> parseCsvToLowerSet(String value, String delimiterRegex) {
        Set<String> set = new HashSet<>();
        if (value == null || value.trim().isEmpty()) return set;
        String[] parts = value.split(delimiterRegex);
        for (String part : parts) {
            if (part != null) {
                String p = part.trim().toLowerCase();
                if (!p.isEmpty()) set.add(p);
            }
        }
        return set;
    }

    private static boolean shouldExcludeSessionKey(String key) {
        String lower = key == null ? "" : key.toLowerCase();
        if (getSessionExcludeKeys().contains(lower)) return true;
        return lower.contains("password") || lower.contains("passwd") || lower.contains("token") || lower.contains("secret");
    }

    private static String stringifySessionValue(String key, Object value) {
        try {
            if (value == null) return "[null]";
            
            Class<?> valueClass = value.getClass();
            String className = valueClass.getName();
            
            // Handle primitive types and strings directly
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                String text = value.toString();
                if (text.length() > 500) {
                    return text.substring(0, 500) + "... (truncated)";
                }
                return text;
            }
            
            // Handle collections and maps with Gson
            if (value instanceof java.util.Map || value instanceof java.util.Collection || value.getClass().isArray()) {
                try {
                    String json = new com.google.gson.Gson().toJson(value);
                    if (json.length() > 1000) {
                        return json.substring(0, 1000) + "... (truncated)";
                    }
                    return json;
                } catch (Throwable e) {
                    return "[collection: " + className + ", size=" + getCollectionSize(value) + "]";
                }
            }
            
            // Handle Spring Security Context specially
            if (className.contains("SecurityContext") || className.contains("Authentication")) {
                try {
                    StringBuilder sb = new StringBuilder("[SecurityContext: ");
                    // Try to get authentication object
                    try {
                        Object auth = valueClass.getMethod("getAuthentication").invoke(value);
                        if (auth != null) {
                            // Try to get principal
                            try {
                                Object principal = auth.getClass().getMethod("getPrincipal").invoke(auth);
                                sb.append("principal=").append(principal != null ? principal.toString() : "null");
                            } catch (Throwable ignored) {}
                            // Try to get name
                            try {
                                Object name = auth.getClass().getMethod("getName").invoke(auth);
                                sb.append(", name=").append(name != null ? name.toString() : "null");
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    sb.append("]");
                    return sb.toString();
                } catch (Throwable e) {
                    return "[SecurityContext: error extracting details]";
                }
            }
            
            // For other objects, try to extract useful information
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("[").append(className.substring(className.lastIndexOf('.') + 1)).append(": ");
                
                // Try common getter methods
                String[] commonGetters = {"getId", "getName", "getUsername", "getEmail", "toString"};
                boolean foundAny = false;
                for (String getter : commonGetters) {
                    try {
                        java.lang.reflect.Method method = valueClass.getMethod(getter);
                        Object result = method.invoke(value);
                        if (result != null) {
                            if (foundAny) sb.append(", ");
                            sb.append(getter.substring(3).toLowerCase()).append("=").append(result.toString());
                            foundAny = true;
                        }
                    } catch (Throwable ignored) {}
                }
                
                if (!foundAny) {
                    // Just use toString as fallback
                    String text = value.toString();
                    if (text.length() > 200) {
                        text = text.substring(0, 200) + "...";
                    }
                    sb.append(text);
                }
                
                sb.append("]");
                String result = sb.toString();
                if (result.length() > 1000) {
                    return result.substring(0, 1000) + "... (truncated)";
                }
                return result;
            } catch (Throwable e) {
                return "[" + className + ": error extracting details]";
            }
        } catch (Throwable ignored) {
            return "[unavailable]";
        }
    }
    
    private static int getCollectionSize(Object value) {
        try {
            if (value instanceof java.util.Collection) {
                return ((java.util.Collection<?>) value).size();
            } else if (value instanceof java.util.Map) {
                return ((java.util.Map<?, ?>) value).size();
            } else if (value.getClass().isArray()) {
                return java.lang.reflect.Array.getLength(value);
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static List<Pattern> parseRegexList(String value) {
        List<Pattern> patterns = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return patterns;
        String[] parts = value.split(";");
        for (String part : parts) {
            String p = part == null ? "" : part.trim();
            if (p.isEmpty()) continue;
            try {
                patterns.add(Pattern.compile(p));
            } catch (Throwable ignored) {}
        }
        return patterns;
    }

    private static boolean shouldExcludeUrl(String path) {
        if (path == null || path.isEmpty()) return false;
        for (Pattern pattern : getExcludeUrlPatterns()) {
            try {
                if (pattern.matcher(path).matches()) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static boolean isLoginUrl(Object reqObj) {
        if (reqObj == null) return false;
        try {
            Class<?> reqClass = reqObj.getClass();
            String requestUri = null;
            
            // Safe extraction of request URI  
            try {
                requestUri = (String) reqClass.getMethod("getRequestURI", (Class<?>[]) null)
                    .invoke(reqObj, (Object[]) null);
            } catch (Exception e) {
                return false;
            }
            
            if (requestUri == null) return false;
            
            String lowerUri = requestUri.toLowerCase();
            
            // Hard-coded patterns (safest approach)
            if (lowerUri.contains("/login") || 
                lowerUri.contains("/auth") || 
                lowerUri.contains("/signin") ||
                lowerUri.contains("/authenticate")) {
                return true;
            }
            
            return false;
        } catch (Throwable e) {
            return false;
        }
    }
}
