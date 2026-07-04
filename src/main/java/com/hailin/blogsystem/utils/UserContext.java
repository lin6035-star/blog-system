package com.hailin.blogsystem.utils;

/**
 * 请求级 ThreadLocal，存储当前登录用户 ID。
 * 由 JwtInterceptor 在请求进入时写入，请求结束时清除。
 */
public class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();

    public static void set(Long userId) {
        CURRENT_USER.set(userId);
    }

    public static Long get() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
