package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
// 统一封装文章 RAG 搜索策略：向量 + keyword 混合检索 → rerank → MySQL 兜底。
public class ArticleRagSearchService {

    private static final List<String> ARTICLE_SEARCH_STOP_WORDS = List.of(
            "有没有", "关于", "相关", "文章", "内容", "找一下", "搜索", "推荐",
            "一下", "什么", "怎么", "如何", "这个", "那个", "一篇", "几篇",
            "帮我", "请问", "的"
    );

    private final ArticleRagRetrieveService articleRagRetrieveService;
    private final ArticleRagRanker articleRagRanker;
    private final ArticlesService articlesService;
    private final ArticleRagKeywordRetriever articleRagKeywordRetriever;

    public ArticleRagSearchResult search(String question, AiIntent intent){
        if (!shouldSearchArticleRag(intent)) {
            log.info(
                    "RAG 跳过文章检索，intent={}",
                    intent == null ? null : intent.getIntent()
            );
            return new ArticleRagSearchResult(ArticleRagSearchStrategy.EMPTY, List.of());
        }

        // 1. ARTICLE_SEARCH：向量 + keyword
        List<ArticleRagContext> vectorContexts = articleRagRetrieveService.retrieve(question);

        List<ArticleRagContext> contexts;
        if (isArticleSearch(intent)) {
            String keyword = getArticleSearchKeyword(question, intent);
            List<ArticleRagContext> keywordContexts = articleRagKeywordRetriever.retrieve(keyword);
            contexts = mergeContexts(vectorContexts, keywordContexts);

            log.info(
                    "ARTICLE_SEARCH 使用混合检索，keyword={}, vector={}, keywordHit={}, merged={}",
                    keyword,
                    vectorContexts.size(),
                    keywordContexts.size(),
                    contexts.size()
            );
        } else {
            contexts = vectorContexts;
        }

        String rankQuestion = isArticleSearch(intent) ? getArticleSearchKeyword(question, intent) : question;
        List<ArticleRagContext> rankedContexts = articleRagRanker.rank(rankQuestion, contexts);


        log.info(
                "RAG {}检索完成，vector={}, ranked={}",
                isArticleSearch(intent) ? "混合" : "向量",
                vectorContexts.size(),
                rankedContexts.size()
        );

        if (!rankedContexts.isEmpty()) {
            ArticleRagSearchStrategy strategy = isArticleSearch(intent) ? ArticleRagSearchStrategy.HYBRID : ArticleRagSearchStrategy.VECTOR;
            return new ArticleRagSearchResult(strategy, rankedContexts);
        }

        if(isArticleSearch(intent)){
            //MySQL兜底
            List<ArticleRagContext> fallbackContexts = buildMysqlFallbackContexts(intent);
            if (!fallbackContexts.isEmpty()) {
                return new ArticleRagSearchResult(ArticleRagSearchStrategy.MYSQL_FALLBACK, fallbackContexts);
            }
        }

        return new ArticleRagSearchResult(ArticleRagSearchStrategy.EMPTY, List.of());
    }

    private boolean isArticleSearch(AiIntent intent) {
        return intent != null && "ARTICLE_SEARCH".equals(intent.getIntent());
    }

    private boolean shouldSearchArticleRag(AiIntent intent) {
        return isArticleSearch(intent);
    }

    private String getArticleSearchKeyword(String question, AiIntent intent) {
        String rawKeyword;
        if (intent != null && intent.getKeyWord() != null && !intent.getKeyWord().isBlank()) {
            rawKeyword = intent.getKeyWord().trim();
        } else {
            rawKeyword = question == null ? "" : question.trim();
        }

        String keyword = rawKeyword;
        for (String stopWord : ARTICLE_SEARCH_STOP_WORDS) {
            keyword = keyword.replace(stopWord, " ");
        }
        keyword = keyword.replaceAll("\\s+", " ").trim();

        if (!keyword.isBlank()) {
            return keyword;
        }
        return rawKeyword;
    }

    private List<ArticleRagContext> buildMysqlFallbackContexts(AiIntent intent) {
        String keyword = intent.getKeyWord();
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        PageVO<ArticleDetailVO> pageResult = articlesService.getArticles(1L, 3L, keyword, null, "lasted");
        List<ArticleDetailVO> list = pageResult.getList();

        if (list == null || list.isEmpty()) {
            log.info("ARTICLE_SEARCH MySQL 兜底无结果，keyword={}", keyword);
            return List.of();
        }

        log.info("ARTICLE_SEARCH 使用 MySQL 兜底生成引用，keyword={}, count={}", keyword, list.size());

        return list.stream()
                .filter(article -> article.getId() != null)
                .map(article -> new ArticleRagContext(
                        article.getId(),
                        article.getTitle(),
                        0,
                        buildSnippet(article)
                ))
                .toList();
    }

    private String buildSnippet(ArticleDetailVO article) {
        if (article.getSummary() != null && !article.getSummary().isBlank()) {
            return article.getSummary();
        }
        if (article.getContent() != null && !article.getContent().isBlank()) {
            String content = article.getContent();
            return content.length() <= 50 ? content : content.substring(0, 50) + "\n\n[文章内容过长，后半部分已省略]";
        }
        return article.getTitle() == null ? "" : article.getTitle();
    }

    private List<ArticleRagContext> mergeContexts(
            List<ArticleRagContext> vectorContexts,
            List<ArticleRagContext> keywordContexts
    ){

        Map<String,ArticleRagContext> merged = new LinkedHashMap<>();

        for (ArticleRagContext context : vectorContexts) {
            merged.putIfAbsent(contextKey(context), context);
        }

        for(ArticleRagContext context : keywordContexts){
            merged.putIfAbsent(contextKey(context), context);
        }

        return new ArrayList<>(merged.values());
    }

    private String contextKey(ArticleRagContext context) {
        return context.articleId() + ":" + context.chunkIndex();
    }
}
