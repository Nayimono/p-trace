package com.pnones.trace.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Iterator;
import java.util.Map;

public class MaskUtil {
    private static final Gson gson = new Gson();

    public static String maskBody(String body, String[] maskKeys, String replace) {
        if (body == null) return null;
        String trimmed = body.trim();
        // try JSON
        try {
            JsonElement el = JsonParser.parseString(trimmed);
            if (el.isJsonObject()) {
                maskJsonObject(el.getAsJsonObject(), maskKeys, replace);
                return gson.toJson(el);
            }
        } catch (Exception ignored) {}

        // try URL-encoded form
        try {
            String decoded = URLDecoder.decode(body, "UTF-8");
            String masked = maskUrlEncoded(decoded, maskKeys, replace);
            return masked;
        } catch (UnsupportedEncodingException ignored) {}

        // fallback: simple key=value masking
        String out = body;
        for (String k : maskKeys) if (k != null && !k.isEmpty()) {
            out = out.replaceAll("(?i)(" + java.util.regex.Pattern.quote(k) + ")=([^&\\s]+)", "$1=" + replace);
            out = out.replaceAll("(?i)(\\\"" + java.util.regex.Pattern.quote(k) + "\\\"\\s*:\\s*)\\\"?[^\\\"]+\\\"?", "$1\\\"" + replace + "\\\"");
        }
        return out;
    }

    private static void maskJsonObject(JsonObject obj, String[] maskKeys, String replace) {
        Iterator<Map.Entry<String, JsonElement>> it = obj.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonElement> e = it.next();
            String k = e.getKey();
            JsonElement v = e.getValue();
            for (String mk : maskKeys) if (mk != null && !mk.isEmpty() && k.equalsIgnoreCase(mk)) {
                obj.addProperty(k, replace);
                v = null;
                break;
            }
            if (v != null && v.isJsonObject()) maskJsonObject(v.getAsJsonObject(), maskKeys, replace);
        }
    }

    private static String maskUrlEncoded(String decoded, String[] maskKeys, String replace) {
        String[] parts = decoded.split("&");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            int idx = p.indexOf('=');
            if (idx > 0) {
                String k = p.substring(0, idx);
                String v = p.substring(idx + 1);
                boolean masked = false;
                for (String mk : maskKeys) if (mk != null && !mk.isEmpty() && k.equalsIgnoreCase(mk)) { v = replace; masked = true; break; }
                sb.append(k).append("=").append(v);
            } else sb.append(p);
            if (i < parts.length - 1) sb.append('&');
        }
        return sb.toString();
    }

    public static void maskHeaders(java.util.Map<String, String> headers, String[] maskHeaders, String replace) {
        if (headers == null) return;
        for (String mh : maskHeaders) if (mh != null && !mh.isEmpty()) {
            for (String k : headers.keySet().toArray(new String[0])) {
                if (k.equalsIgnoreCase(mh)) headers.put(k, replace);
            }
        }
    }
}
