package com.hailin.blogsystem.utils;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIpUtils {

    private static final String UNKNOWN = "unknown";

    private ClientIpUtils() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String ip = firstValidIp(request.getHeader("CF-Connecting-IP"));
        if (ip == null) {
            ip = firstValidIp(request.getHeader("X-Forwarded-For"));
        }
        if (ip == null) {
            ip = firstValidIp(request.getHeader("X-Real-IP"));
        }
        if (ip == null) {
            ip = firstValidIp(request.getRemoteAddr());
        }
        return ip;
    }

    private static String firstValidIp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        for (String candidate : value.split(",")) {
            String ip = candidate.trim();
            if (!ip.isEmpty() && !UNKNOWN.equalsIgnoreCase(ip)) {
                return ip;
            }
        }
        return null;
    }
}
