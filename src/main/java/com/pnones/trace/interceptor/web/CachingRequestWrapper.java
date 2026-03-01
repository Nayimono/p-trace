package com.pnones.trace.interceptor.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;

public class CachingRequestWrapper extends HttpServletRequestWrapper {
    private final byte[] body;

    public CachingRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = request.getInputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        this.body = out.toByteArray();
    }

    public String getBody() {
        try { return new String(body, getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding()); } catch (Exception e) { return ""; }
    }

    @Override
    public ServletInputStream getInputStream() {
        final ByteArrayInputStream bin = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return bin.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener readListener) {}
            @Override public int read() { return bin.read(); }
        };
    }
}
