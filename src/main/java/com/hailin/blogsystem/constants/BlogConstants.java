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

    /* 文章点赞状态 */
    public static final class ArticleLikes{
        public static final int LIKED = 1;
        public static final int UNLIKED = 0;
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
        public static final int CONFLICT = 40900;  // 资源正在处理中
        public static final int RATE_LIMITED = 42900;   // 请求过于频繁
        public static final int INSUFFICIENT_BALANCE = 40200;  // 额度不足（HTTP 402）

        private ErrorCode() {

        }
    }

    /* 钱包 */
    public static final class Wallet {
        /**
         * 余额下界，<b>必须与 db/init.sql 的 chk_wallet_balance_floor 约束保持一致</b>。
         *
         * <p>余额允许为负（预扣门槛是 balance &gt; 0 而非 balance &gt;= reserved，
         * 透支一轮是正常业务状态），留一个宽松下界是为了让「符号写反 / 扣减跑飞」
         * 这类 bug 仍然被 DB 拦住——放弃非负约束不等于放弃这道防线。
         *
         * <p>启动时由 {@link com.hailin.blogsystem.component.BillingReserveLimitValidator}
         * 校验「最大预扣 × 安全系数 &lt; |下界|」：预扣调大后触碰它时，
         * 报错信息（约束冲突）完全指不到根因。
         */
        public static final long BALANCE_FLOOR = -1000000L;

        private Wallet() {
        }
    }

    /* 秒杀 */
    public static final class Seckill {

        /* 活动状态 */
        public static final String ACTIVITY_DRAFT = "DRAFT";
        public static final String ACTIVITY_ACTIVE = "ACTIVE";
        public static final String ACTIVITY_ENDED = "ENDED";

        /* 订单状态 */
        public static final String ORDER_GRANTED = "GRANTED";

        /** 业务失败（DB 条件更新影响 0 行 = 售罄）。终态，重投不会有不同结果，必须 ACK */
        public static final String ORDER_FAILED = "FAILED";

        /** 技术失败重试超限的死信终态——没有它，「重试中」和「消费者崩了」在对账眼里是同一个样子 */
        public static final String ORDER_FAILED_RETRY = "FAILED_RETRY";

        private Seckill() {
        }
    }
}
