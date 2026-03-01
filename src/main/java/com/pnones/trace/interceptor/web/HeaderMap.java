package com.pnones.trace.interceptor.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

public class HeaderMap extends LinkedHashMap<String, String> {
    public HeaderMap(HttpServletRequest req) {
        Enumeration<String> names = req.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String n = names.nextElement();
                put(n, req.getHeader(n));
            }
        }
    }
}
