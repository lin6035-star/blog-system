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

    //防止缓存穿透
    public static final String CACHE_NULL_VALUE = "__NULL__";
    public static final long CACHE_NULL_TTL_MINUTES = 3;

    //浏览量计数兜底 TTL：正常 30 秒同步任务就取走了，TTL 只在异常时兜底。
    //必须设：浏览量 key 若永远无 TTL，内存打满时淘汰策略（volatile-*）无对象可选，Redis 会写入报错
    public static final long ARTICLE_VIEW_TTL_MINUTES = 30;

    //用户集合缓存（点赞 / 收藏）：{key} 存 ID 集合，{key}:loaded 存加载状态（FULL / EMPTY）
    public static final String USER_SET_LOADED_SUFFIX = ":loaded";
    //Set TTL 必须 > 标记 TTL：标记在时集合一定在。
    //反过来（标记比集合活得久）会出现「标记在、集合已空」的窗口，读到空集合 = 静默显示"没点过赞"
    public static final long USER_SET_TTL_MINUTES = 40;
    public static final long USER_SET_LOADED_TTL_MINUTES = 30;

    //RAG 索引失败标记 TTL（天）
    public static final long RAG_INDEX_FAILURE_TTL_DAYS = 7;

    private RedisConstants(){

    }
}
