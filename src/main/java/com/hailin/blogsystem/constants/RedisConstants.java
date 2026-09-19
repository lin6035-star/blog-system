package com.hailin.blogsystem.constants;

public class RedisConstants
{
    public static final String ARTICLE_VIEW_KEY_PREFIX = "article:view:";
    public static final String ARTICLE_HOT_KEY = "article:hot";
    public static final String ARTICLE_LIKED_USER_KEY_PREFIX = "article:liked:user:";
    public static final String ARTICLE_FAVORITED_USER_KEY_PREFIX = "article:favorited:user:";
    public static final String ARTICLE_DETAIL_KEY_PREFIX = "article:detail:";
    public static final String ARTICLE_LIST_KEY_PREFIX = "article:list:";
    public static final String CATEGORY_LIST_KEY = "category:list";
    public static final String TAG_LIST_KEY = "tag:list";
    public static final String COMMENT_LIKED_USER_KEY_PREFIX = "comment:liked:user:";
    public static final String COMMENT_LIST_KEY_PREFIX = "comment:list:article:";
    //RAG 索引同步失败标记：rag:index:fail:{action}:{articleId}，action = index / delete / rebuild，articleId 为空用 all
    public static final String RAG_INDEX_FAILURE_KEY_PREFIX = "rag:index:fail:";
    //缓存互斥锁（防击穿）：cache:lock:article:detail:{articleId}
    //与 Workflow 锁分开：这里是短 TTL + 非阻塞获取，拿不到就轮询或降级，不抛业务异常
    public static final String CACHE_LOCK_ARTICLE_DETAIL_KEY_PREFIX = "cache:lock:article:detail:";
    public static final String AI_WORKFLOW_ACTION_LOCK_KEY_PREFIX =
            "ai:workflow:action:";
    public static final String AI_WORKFLOW_ACTION_IDEMPOTENCY_KEY_PREFIX =
            "ai:workflow:idempotency:";
    //Agent Run 建议确认/取消的并发锁：ai:agent:action:{agentRunId}
    public static final String AI_AGENT_RUN_ACTION_LOCK_KEY_PREFIX =
            "ai:agent:action:";
    //AI 接口限流：ai:rate:{bucket}:{user|ip}:{identity}:{window}，bucket = chat / workflow / rag
    public static final String AI_RATE_LIMIT_KEY_PREFIX =
            "ai:rate:";
    //学习计划列表（ACTIVE）：learning:plan:list:{userId}
    //分类器每条消息都要读这个列表（要"选计划"就得先有选项），写计划/改任务状态时失效
    public static final String LEARNING_PLAN_LIST_KEY_PREFIX = "learning:plan:list:";

    //登录失败计数的账号维度（固定窗口）：login:fail:account:{归一化用户名}
    //与 IP 维度分开计数，不用「用户名 + IP」组合键——组合只拦得住单 IP 定向爆破，
    //拦不住分布式爆破（一个账号、多 IP）和撞库（多账号、同源 IP）。覆盖矩阵见 LoginAttemptLimiter 类注释
    public static final String LOGIN_FAIL_ACCOUNT_KEY_PREFIX = "login:fail:account:";
    //登录失败计数的 IP 维度：login:fail:ip:{IP}。阈值宽于账号维度，只为拦住撞库
    public static final String LOGIN_FAIL_IP_KEY_PREFIX = "login:fail:ip:";
    //登出 token 黑名单：token:blacklist:{jti}，TTL = token 的剩余有效期（不是固定时长）
    public static final String TOKEN_BLACKLIST_KEY_PREFIX = "token:blacklist:";


    public static final double ARTICLE_VIEW_HOT_SCORE = 1.0;
    public static final double ARTICLE_LIKE_HOT_SCORE = 2.0;
    public static final double ARTICLE_UNLIKE_HOT_SCORE = -2.0;
    public static final double ARTICLE_FAVORITE_HOT_SCORE = 3.0;
    public static final double ARTICLE_UNFAVORITE_HOT_SCORE = -3.0;
    public static final double ARTICLE_COMMENT_HOT_SCORE = 4.0;
    public static final double ARTICLE_DELETE_COMMENT_HOT_SCORE = -2.0;


    public static final long ARTICLE_DETAIL_TTL_MINUTES = 10;
    public static final long ARTICLE_LIST_TTL_MINUTES = 5;
    public static final long COMMON_LIST_TTL_MINUTES = 30;
    public static final long COMMENT_LIST_TTL_MINUTES = 3;
    public static final long LEARNING_PLAN_LIST_TTL_MINUTES = 5;

    //文章独立访客（HyperLogLog）：article:uv:{articleId}:{yyyyMMdd}
    //为什么按天分 key：HyperLogLog **不支持删除元素**，单 key 续 TTL 会让历史访客永远留在基数里，
    //越算越虚。按天分之后每天一个 key，TTL 自然过期，语义就是"这一天的独立访客"。
    //这也是它和浏览量（累计值）语义不同的地方——UI 上必须标清楚是"今日访客"，不能和浏览数并列当累计看
    public static final String ARTICLE_UV_KEY_PREFIX = "article:uv:";
    //UV key 保留天数：当天 + 往前的历史。留 8 天是为了"近 7 天 UV"这类合并查询有余量
    public static final long ARTICLE_UV_TTL_DAYS = 8;

    //用户日活跃位图（Bitmap）：active:user:{yyyyMMdd}，offset = userId，活跃当天置 1
    //为什么用 Bitmap 而不是 Set：统计只需要回答"这个用户在不在"，不需要知道"是谁"。
    //一个用户 1 bit，1 亿用户的日活 = 12.5MB；换成 Set 至少要几个 GB
    public static final String USER_ACTIVE_KEY_PREFIX = "active:user:";
    //活跃位图保留天数：够算"近 30 天活跃"与留存对比，再久也没有查询会用到
    public static final long USER_ACTIVE_TTL_DAYS = 90;

    //防止缓存穿透
    public static final String CACHE_NULL_VALUE = "__NULL__";
    public static final long CACHE_NULL_TTL_MINUTES = 3;

    //浏览量计数兜底 TTL：正常 30 秒同步任务就取走了，TTL 只在异常时兜底。
    //必须设：浏览量 key 若永远无 TTL，内存打满时淘汰策略（volatile-*）无对象可选，Redis 会写入报错
    public static final long ARTICLE_VIEW_TTL_MINUTES = 30;

    //热度榜 TTL：榜单是派生数据（能从 DB 全量重建），理应过期——补上它之后
    //「允许丢失的 key 都有 TTL」这个前提才成立，volatile-lru 才不会遇到无处可淘汰的情况。
    //2 小时 > 30 分钟重建周期，正常情况永远不过期；只有后端长时间停机才会消失，届时重建补回。
    //注意：重建走 RENAME，RENAME 会把目标 key 的 TTL 覆盖成源 key（临时 key）的，
    //临时 key 每轮都是新建、无 TTL——所以必须在 RENAME **之后**补，重建前设等于没设
    public static final long ARTICLE_HOT_TTL_HOURS = 2;

    //用户集合缓存（点赞 / 收藏）：{key} 存 ID 集合，{key}:loaded 存加载状态（FULL / EMPTY）
    public static final String USER_SET_LOADED_SUFFIX = ":loaded";
    //Set TTL 必须 > 标记 TTL：标记在时集合一定在。
    //反过来（标记比集合活得久）会出现「标记在、集合已空」的窗口，读到空集合 = 静默显示"没点过赞"
    public static final long USER_SET_TTL_MINUTES = 40;
    public static final long USER_SET_LOADED_TTL_MINUTES = 30;

    //RAG 索引失败标记 TTL（天）
    public static final long RAG_INDEX_FAILURE_TTL_DAYS = 7;

    //RAG 索引重试延迟队列（ZSet，score = 下次执行时间戳，member = {actionKey}:{articleId}:{attempt}）
    //**为什么需要它**：原有的"重试 3 次"全在同一个异步线程里同步等待（1s + 2s），
    //耗尽后只写一个失败标记——那是**墓碑，不是待办**，没有任何机制会再去碰它。
    //延迟队列补的就是这一环：到期自动捞出来重试，指数退避，超过上限才真正转人工。
    public static final String RAG_RETRY_QUEUE_KEY = "rag:retry:queue";
    //队列 key 的空闲过期：**每次入队都刷新**（与浏览量那种"数据保质期"不同——
    //队列是活动集合，没有新任务进来就该自己消失，不留常驻 key）
    public static final long RAG_RETRY_QUEUE_IDLE_TTL_SECONDS = 7 * 24 * 3600L;

    //秒杀四件套的前缀：seckill:{activityId}:stock / :users / :meta / :events
    //大括号是 Redis Cluster 的 **hash tag**——同一段 Lua 里的多个 key 必须落在同一个槽。
    //当前是单机 Redis 用不上，但现在统一命名几乎零成本，将来切 Cluster 不用回头改一片 key。
    //完整拼装见 SeckillKeys（那边的 getter 顺序与 Lua 里 KEYS[1..4] 严格对应）
    public static final String SECKILL_KEY_PREFIX = "seckill:";
    //秒杀入账的 Stream 消费组名。消费者（SeckillSettleTask）和对账任务都要用它——
    //重复定义两份，迟早出现「消费组改名了、对账还在查旧组名」这种查不出原因的静默失配
    public static final String SECKILL_CONSUMER_GROUP = "seckill-settle";
    //秒杀 key **刻意不设 TTL**：库存和已抢名单是「当前有效状态」，不是可重建的派生数据。
    //给它过期时间反而会在活动进行中把状态弄丢；而 volatile-lru 只淘汰**有 TTL 的** key，
    //所以无 TTL 天然不会被淘汰——这正是我们要的。代价是活动结束后要手动清理（预热会覆盖）

    private RedisConstants(){

    }
}
