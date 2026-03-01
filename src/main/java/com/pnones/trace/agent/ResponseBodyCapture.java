package com.pnones.trace.agent;

import com.pnones.trace.config.TraceConfig;

import java.lang.reflect.Method;

/**
 * Response body 캡처 유틸리티
 * WAS별로 다른 내부 구조를 reflection으로 처리하여 범용적으로 동작
 */
public class ResponseBodyCapture {
    private static final boolean ENABLED = TraceConfig.getBoolean("http.capture.response-body", true);
    private static final int MAX_SIZE = TraceConfig.getInt("http.capture.response-body-max-bytes", 10240);
    
    /**
     * Response 객체에서 body 내용을 추출
     * Tomcat, Jetty, Undertow 등의 Response 구현체에서 버퍼 내용을 읽어옴
     */
    public static String captureResponseBody(Object response) {
        if (!ENABLED || response == null) {
            return null;
        }
        
        try {
            // 먼저 content type을 확인하여 텍스트 기반인지 확인
            String contentType = getContentType(response);
            if (contentType != null && !isTextBased(contentType)) {
                return null; // 바이너리 콘텐츠는 캡처하지 않음
            }
            
            // Tomcat Response 시도
            String body = captureFromTomcat(response);
            if (body != null) {
                return truncate(body);
            }
            
            // Jetty Response 시도
            body = captureFromJetty(response);
            if (body != null) {
                return truncate(body);
            }
            
            // Undertow Response 시도
            body = captureFromUndertow(response);
            if (body != null) {
                return truncate(body);
            }
            
            return null;
        } catch (Throwable t) {
            // 캡처 실패는 무시 (애플리케이션에 영향 주지 않음)
            return null;
        }
    }
    
    /**
     * Tomcat Response에서 버퍼 읽기
     */
    private static String captureFromTomcat(Object response) {
        try {
            // org.apache.catalina.connector.Response
            Class<?> responseClass = response.getClass();
            
            // Try to get OutputBuffer
            Method getOutputBuffer = findMethod(responseClass, "getOutputBuffer");
            if (getOutputBuffer != null) {
                Object outputBuffer = getOutputBuffer.invoke(response);
                if (outputBuffer != null) {
                    // Get buffer content
                    Method toByteArray = findMethod(outputBuffer.getClass(), "toByteArray");
                    if (toByteArray != null) {
                        byte[] bytes = (byte[]) toByteArray.invoke(outputBuffer);
                        if (bytes != null && bytes.length > 0) {
                            return new String(bytes, "UTF-8");
                        }
                    }
                }
            }
            
            // Alternative: try getCoyoteResponse().getOutputBuffer()
            Method getCoyoteResponse = findMethod(responseClass, "getCoyoteResponse");
            if (getCoyoteResponse != null) {
                Object coyoteResponse = getCoyoteResponse.invoke(response);
                if (coyoteResponse != null) {
                    Method getOutputBuffer2 = findMethod(coyoteResponse.getClass(), "getOutputBuffer");
                    if (getOutputBuffer2 != null) {
                        Object outputBuffer = getOutputBuffer2.invoke(coyoteResponse);
                        if (outputBuffer != null) {
                            // Try to get ByteChunk or buffer
                            Method doGet = findMethod(outputBuffer.getClass(), "doGet");
                            if (doGet != null) {
                                Object byteChunk = doGet.invoke(outputBuffer);
                                if (byteChunk != null) {
                                    Method getBytes = findMethod(byteChunk.getClass(), "getBytes");
                                    Method getStart = findMethod(byteChunk.getClass(), "getStart");
                                    Method getLength = findMethod(byteChunk.getClass(), "getLength");
                                    if (getBytes != null && getStart != null && getLength != null) {
                                        byte[] bytes = (byte[]) getBytes.invoke(byteChunk);
                                        int start = (Integer) getStart.invoke(byteChunk);
                                        int length = (Integer) getLength.invoke(byteChunk);
                                        if (bytes != null && length > 0) {
                                            return new String(bytes, start, length, "UTF-8");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
    
    /**
     * Jetty Response에서 버퍼 읽기
     */
    private static String captureFromJetty(Object response) {
        try {
            // org.eclipse.jetty.server.Response
            Class<?> responseClass = response.getClass();
            
            // Try getHttpOutput().getContent()
            Method getHttpOutput = findMethod(responseClass, "getHttpOutput");
            if (getHttpOutput != null) {
                Object httpOutput = getHttpOutput.invoke(response);
                if (httpOutput != null) {
                    Method getContent = findMethod(httpOutput.getClass(), "getContent");
                    if (getContent != null) {
                        Object content = getContent.invoke(httpOutput);
                        if (content instanceof byte[]) {
                            return new String((byte[]) content, "UTF-8");
                        }
                    }
                }
            }
            
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
    
    /**
     * Undertow Response에서 버퍼 읽기
     */
    private static String captureFromUndertow(Object response) {
        try {
            // io.undertow.servlet.spec.HttpServletResponseImpl
            Class<?> responseClass = response.getClass();
            
            // Try to get exchange and sender
            Method getExchange = findMethod(responseClass, "getExchange");
            if (getExchange != null) {
                Object exchange = getExchange.invoke(response);
                if (exchange != null) {
                    // This is complex in Undertow, usually needs async handling
                    // For now, return null (Undertow response capture needs different approach)
                }
            }
            
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
    
    /**
     * Content-Type으로 텍스트 기반 여부 확인
     */
    private static boolean isTextBased(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.contains("text") || 
               lower.contains("json") || 
               lower.contains("xml") || 
               lower.contains("javascript") ||
               lower.contains("html") ||
               lower.contains("plain");
    }
    
    /**
     * Response에서 Content-Type 가져오기
     */
    private static String getContentType(Object response) {
        try {
            Method getContentType = findMethod(response.getClass(), "getContentType");
            if (getContentType != null) {
                Object ct = getContentType.invoke(response);
                return ct != null ? ct.toString() : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }
    
    /**
     * Method 찾기 (상속 포함)
     */
    private static Method findMethod(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }
    
    /**
     * 최대 크기로 truncate
     */
    private static String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_SIZE) return s;
        return s.substring(0, MAX_SIZE) + "... (truncated at " + MAX_SIZE + " bytes)";
    }
}
