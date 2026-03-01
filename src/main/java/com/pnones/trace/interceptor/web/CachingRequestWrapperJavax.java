package com.pnones.trace.interceptor.web;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.*;

/**
 * Request Body를 캐싱하는 Wrapper (javax.servlet 버전)
 * Spring Boot 2.x와 호환
 */
public class CachingRequestWrapperJavax extends HttpServletRequestWrapper {
    private final byte[] body;

    public CachingRequestWrapperJavax(HttpServletRequest request) throws IOException {
        super(request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = request.getInputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        this.body = out.toByteArray();
    }

    public String getBody() {
        try { 
            return new String(body, getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding()); 
        } catch (Exception e) { 
            return ""; 
        }
    }

    @Override
    public ServletInputStream getInputStream() {
        final ByteArrayInputStream bin = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override 
            public boolean isFinished() { 
                return bin.available() == 0; 
            }
            @Override 
            public boolean isReady() { 
                return true; 
            }
            @Override 
            public void setReadListener(ReadListener readListener) {}
            @Override 
            public int read() { 
                return bin.read(); 
            }
        };
    }
}
