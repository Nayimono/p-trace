package com.pnones.trace.interceptor.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

public class CachingResponseWrapper extends HttpServletResponseWrapper {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final ServletOutputStream out = new ServletOutputStream() {
        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener(WriteListener writeListener) {}
        @Override public void write(int b) throws IOException { buf.write(b); }
    };
    private PrintWriter pw;

    public CachingResponseWrapper(HttpServletResponse resp) {
        super(resp);
    }

    @Override
    public ServletOutputStream getOutputStream() { return out; }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (pw == null) pw = new PrintWriter(buf);
        return pw;
    }

    public String getBody() {
        try {
            if (pw != null) pw.flush();
            return new String(buf.toByteArray(), getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding());
        } catch (Exception e) { return ""; }
    }
}
