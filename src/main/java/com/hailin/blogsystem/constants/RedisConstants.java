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

    //防止缓存穿透
    public static final String CACHE_NULL_VALUE = "__NULL__";
    public static final long CACHE_NULL_TTL_MINUTES = 3;

    private RedisConstants(){

    }
}
