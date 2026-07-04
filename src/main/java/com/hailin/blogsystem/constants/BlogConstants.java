package com.hailin.blogsystem.constants;

public class BlogConstants {

    private BlogConstants() {

    }

    /* 用户状态 */
    public static final class UserStatus{
        public static final int DISABLED = 0;  // 禁用
        public static final int NORMAL = 1;    // 正常

        private UserStatus() {

        }
    }

    /* 博文状态 */
    public static final class ArticlesStatus{
        public static final int DRAFT = 0;      // 草稿
        public static final int PUBLISHED = 1;  // 已发布
        public static final int HIDDEN = 2;     // 隐藏

        private ArticlesStatus() {

        }
    }

    /* 错误码 */
    public static final class ErrorCode {
        public static final int SUCCESS = 0;
        public static final int BAD_REQUEST = 40001;    // 参数校验失败
        public static final int UNAUTHORIZED = 40100;   // 未登录
        public static final int LOGIN_FAILED = 40101;   // 用户名或密码错误
        public static final int FORBIDDEN = 40300;      // 无权限
        public static final int NOT_FOUND = 40400;      // 资源不存在
        public static final int SERVER_ERROR = 50000;   // 服务器内部错误

        private ErrorCode() {

        }
    }
}
