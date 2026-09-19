package com.hailin.blogsystem.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内搜索：ES 全文检索。
 *
 * <p><b>与 RAG 检索的分工</b>（设计稿 §2）：站内搜索服务**人**（要相关性排序 + 高亮 + 分页），
 * RAG 服务**模型**（要召回率，topK 小、不要高亮）。所以**索引是两套**：
 * RAG 那个是 chunk 级且 mapping 由 Spring AI 自动创建，这里是文章级、自己管 mapping。
 *
 * <p><b>ES 是加速器，不是唯一真相</b>：检索失败一律返回 {@code null}，
 * 由调用方降级回原来的 LIKE 查询。**不要把这个降级分支当死代码删掉**——
 * ES 容器挂了、索引没建好、网络抖一下，都靠它兜住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleSearchService {

    public static final String INDEX_NAME = "article_search_index";

    /** 高亮片段的长度上限（字符），避免把整段正文塞回前端。 */
    private static final int HIGHLIGHT_FRAGMENT_SIZE = 120;

    private final ElasticsearchClient esClient;

    /** 检索结果：命中的文章 id（**已按相关性排序**）+ 每个 id 对应的高亮片段。 */
    public record SearchHits(List<Long> articleIds, long total, Map<Long, String> highlights) {
    }

    // ---------- 索引管理 ----------

    /**
     * 建索引（幂等）。应用启动时调一次。
     *
     * <p>mapping 里中文分词用 **ik_max_word**（插件提供）—— 对比默认 analyzer 的差别：
     * {@code ik_max_word: 秒杀|缓存|穿透} vs {@code standard: 秒|杀|缓|存|穿|透}。
     * 单字切分下「秒杀」搜不出自己，这就是要装 IK 的原因。
     */
    public void ensureIndex() {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(INDEX_NAME)).value();
            if (exists) {
                return;
            }
            esClient.indices().create(c -> c
                    .index(INDEX_NAME)
                    .mappings(m -> m
                            .properties("title", p -> p.text(t -> t.analyzer("ik_max_word")))
                            .properties("summary", p -> p.text(t -> t.analyzer("ik_max_word")))
                            .properties("content", p -> p.text(t -> t.analyzer("ik_max_word")))
                            .properties("categoryId", p -> p.keyword(k -> k))
                            .properties("authorId", p -> p.keyword(k -> k))
                            // status 用 keyword（不是 text）：检索期过滤靠 term 精确匹配，分词字段查不准
                            .properties("status", p -> p.keyword(k -> k))
                            .properties("publishedAt", p -> p.date(d -> d))
                            .properties("viewCount", p -> p.long_(l -> l))
                    )
            );
            log.info("[SEARCH-INDEX] 索引已创建 index={}", INDEX_NAME);
        } catch (Exception e) {
            // 建索引失败不阻断启动：搜索会走降级，其余功能不受影响
            log.error("[SEARCH-INDEX] 创建索引失败（搜索将走降级），index={}", INDEX_NAME, e);
        }
    }

    /** 写入/更新一篇文章的搜索文档。 */
    public void indexArticle(Articles article) {
        if (article == null || article.getId() == null) {
            return;
        }
        try {
            esClient.index(i -> i
                    .index(INDEX_NAME)
                    .id(String.valueOf(article.getId()))
                    .document(toDocument(article))
            );
        } catch (Exception e) {
            log.error("[SEARCH-INDEX] 写入失败 articleId={}（该文章暂时搜不到，重建索引可恢复）",
                    article.getId(), e);
        }
    }

    /** 删除一篇文章的搜索文档。 */
    public void deleteArticle(Long articleId) {
        if (articleId == null) {
            return;
        }
        try {
            esClient.delete(d -> d.index(INDEX_NAME).id(String.valueOf(articleId)));
        } catch (Exception e) {
            log.error("[SEARCH-INDEX] 删除失败 articleId={}（应从搜索结果中消失但仍在，"
                    + "靠检索期的 status 过滤兜住）", articleId, e);
        }
    }

    private Map<String, Object> toDocument(Articles article) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("title", article.getTitle());
        doc.put("summary", article.getSummary());
        doc.put("content", article.getContent());
        doc.put("categoryId", article.getCategoryId() == null ? null : String.valueOf(article.getCategoryId()));
        doc.put("authorId", article.getAuthorId() == null ? null : String.valueOf(article.getAuthorId()));
        doc.put("status", String.valueOf(article.getStatus()));
        doc.put("publishedAt", article.getPublishedAt() == null
                ? null
                : article.getPublishedAt().atZone(ZoneId.systemDefault()).toInstant().toString());
        doc.put("viewCount", article.getViewCount());
        return doc;
    }

    // ---------- 检索 ----------

    /**
     * 全文检索。**返回 null 表示 ES 不可用，调用方应降级回 LIKE。**
     *
     * @param sort {@code recommend} = 按热度，其余 = 按时间；两者都只在**相关性相同时**才起作用
     */
    public SearchHits search(String keyword, Long categoryId, String sort,
                             long page, long pageSize) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        try {
            SearchResponse<Map> response = esClient.search(s -> {
                s.index(INDEX_NAME)
                        .from((int) ((page - 1) * pageSize))
                        .size((int) pageSize)
                        // 多字段权重：标题 > 摘要 > 正文
                        .query(q -> q.bool(b -> {
                            b.must(m -> m.multiMatch(mm -> mm
                                    .fields("title^3", "summary^2", "content")
                                    .query(keyword.trim())
                                    /*
                                     * ⚠️ 必须显式 AND。multi_match 默认是 **OR** 语义——
                                     * 「建造者」分词后是「建造 | 者」，只要命中任一个词就算匹配，
                                     * 于是「面向进阶开发**者**」这种完全无关的文章全被捞进来
                                     * （实测：搜「建造者」返回 17 条，高亮显示大部分只命中了「者」）。
                                     * 站内搜索要的是「用户搜的词都出现在文章里」，不是「沾一个边就算」。
                                     */
                                    .operator(Operator.And)));
                            // ⚠️ 检索期过滤：索引期只收 PUBLISHED，但索引同步是异步的，
                            //    「刚设为隐藏、索引还没删」的窗口里只靠索引期就会搜出不该看的文章。
                            b.filter(f -> f.term(t -> t
                                    .field("status")
                                    .value(String.valueOf(BlogConstants.ArticlesStatus.PUBLISHED))));
                            if (categoryId != null) {
                                b.filter(f -> f.term(t -> t
                                        .field("categoryId")
                                        .value(String.valueOf(categoryId))));
                            }
                            return b;
                        }))
                        .highlight(h -> h
                                .fields("title", HighlightField.of(f -> f))
                                .fields("summary", HighlightField.of(f -> f))
                                .fields("content", HighlightField.of(f -> f
                                        .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)))
                                .preTags("<em>")
                                .postTags("</em>"));
                return s;
            }, Map.class);

            List<Long> ids = new ArrayList<>();
            Map<Long, String> highlights = new HashMap<>();
            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.id() == null) {
                    continue;
                }
                Long id = Long.valueOf(hit.id());
                ids.add(id);
                String fragment = firstHighlight(hit);
                if (fragment != null) {
                    highlights.put(id, fragment);
                }
            }

            long total = response.hits().total() == null ? ids.size() : response.hits().total().value();
            log.info("[SEARCH-ES] keyword={} categoryId={} 命中={} total={} 耗时={}ms",
                    keyword, categoryId, ids.size(), total, response.took());
            return new SearchHits(ids, total, highlights);
        } catch (Exception e) {
            // 走降级：调用方会回落到 LIKE。这里是 warn 不是 error——ES 抖一下不该刷满 error 日志
            log.warn("[SEARCH-ES] 检索失败，将降级到 LIKE：keyword={} error={}", keyword, e.getMessage());
            return null;
        }
    }

    private String firstHighlight(Hit<Map> hit) {
        if (hit.highlight() == null || hit.highlight().isEmpty()) {
            return null;
        }
        // 摘要优先，其次正文——标题太短，做成片段信息量不足
        for (String field : List.of("summary", "content", "title")) {
            List<String> fragments = hit.highlight().get(field);
            if (fragments != null && !fragments.isEmpty()) {
                return fragments.get(0);
            }
        }
        return null;
    }

    /** 重建全量索引（运维/修复用）。 */
    public int rebuild(List<Articles> publishedArticles) {
        ensureIndex();
        int count = 0;
        for (Articles article : publishedArticles) {
            indexArticle(article);
            count++;
        }
        log.info("[SEARCH-INDEX] 全量重建完成，共 {} 篇", count);
        return count;
    }

    /**
     * 索引里的文档数。**ES 不可用时返回 -1**（调用方据此跳过「首次灌数据」，
     * 而不是把 -1 当成"索引是空的"去灌一遍）。
     */
    public long count() {
        try {
            return esClient.count(c -> c.index(INDEX_NAME)).count();
        } catch (Exception e) {
            log.warn("[SEARCH-INDEX] 读取索引文档数失败（ES 不可用？）：{}", e.getMessage());
            return -1L;
        }
    }

    /** 供健康检查用：ES 是否可用。 */
    public boolean isAvailable() {
        try {
            return esClient.ping().value();
        } catch (IOException e) {
            return false;
        }
    }
}
