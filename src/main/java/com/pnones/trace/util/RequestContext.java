package com.pnones.trace.util;

import java.util.UUID;

public class RequestContext {
    private static final ThreadLocal<String> ctx = new ThreadLocal<>();

    public static String getRequestId() {
        String v = ctx.get();
        if (v == null) {
            v = UUID.randomUUID().toString();
            ctx.set(v);
        }
        return v;
    }

    public static void setRequestId(String id) { ctx.set(id); }
    public static void clear() { ctx.remove(); }
}
