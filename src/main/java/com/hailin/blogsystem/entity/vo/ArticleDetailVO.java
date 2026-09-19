package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.Articles;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ArticleDetailVO {

    private Long id;
    private Long categoryId;
    private Long authorId;
    private String authorName;
    private String categoryName;
    private String title;
    private String summary;
    private String content;
    private String coverUrl;
    private Integer status;
    private Integer viewCount;
    private LocalDateTime publishedAt;
    private Integer commentCount = 0;
    private Integer favoriteCount = 0;
    private Integer likeCount = 0;
    private Integer liked = 0;
    private Integer favorited = 0;
    private Integer shareCount = 0;

    /**
     * 搜索命中的高亮片段（含 {@code <em>} 标记），**只在走 ES 检索时有值**，
     * 普通列表接口为 null。
     *
     * <p>⚠️ 前端渲染前必须经过 DOMPurify（项目统一清洗出口）——
     * 虽然这个字段是 ES 生成的、来源可信，但「带 HTML 标签的字符串」一律按不可信处理，
     * 免得以后有人把用户输入拼进来。
     */
    private String highlight;
    /**
     * 今日独立访客数（HyperLogLog 近似值，非累计）。
     *
     * 注意与 {@link #viewCount} 的语义差别：viewCount 是**累计**浏览量，
     * 这个是**当天**的独立访客——前端必须标清楚"今日访客"，不能让用户当成累计值。
     * 实时计算、不进详情缓存（缓存 10 分钟会让它看起来不动）。
     */
    private Integer uvCount = 0;

    public static ArticleDetailVO from(Articles article) {
        if (article == null) {
            return null;
        }

        ArticleDetailVO vo = new ArticleDetailVO();
        vo.setId(article.getId());
        vo.setCategoryId(article.getCategoryId());
        vo.setAuthorId(article.getAuthorId());
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        vo.setContent(article.getContent());
        vo.setCoverUrl(article.getCoverUrl());
        vo.setStatus(article.getStatus());
        vo.setViewCount(article.getViewCount());
        vo.setPublishedAt(article.getPublishedAt());
        vo.setCommentCount(article.getCommentCount());
        vo.setLikeCount(article.getLikeCount());
        vo.setFavoriteCount(article.getFavoriteCount());
        vo.setShareCount(article.getShareCount());
        return vo;
    }
}
