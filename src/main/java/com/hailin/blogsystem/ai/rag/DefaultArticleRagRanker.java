package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DefaultArticleRagRanker implements ArticleRagRanker{


    private static final int MAX_CONTEXTS = 5;
    private static final Pattern CHINESE_TEXT_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");
    private static final Pattern KEYWORD_PATTERN =
            Pattern.compile("[a-z0-9+#.]+|[\\u4e00-\\u9fa5]{2,}");

    private static final Set<String> STOP_WORDS = Set.of(
            "有没有", "关于", "相关", "文章", "内容", "找一下", "搜索", "推荐",
            "一下", "什么", "怎么", "如何", "这个", "那个", "一篇", "几篇",
            "帮我", "请问"
    );

    @Override
    public List<ArticleRagContext> rank(String question, List<ArticleRagContext> contexts) {
        if(contexts == null || contexts.isEmpty()){
            return List.of();
        }

        List<String> keywords = extractKeywords(question);
        List<ScoredContext> scoredContexts = new ArrayList<>();
        boolean hasKeywordMatchedContext = false;

        for(int i = 0;i<contexts.size();i++){
            ArticleRagContext context = contexts.get(i);
            int keywordScore = scoreKeywordMatches(context,keywords);
            hasKeywordMatchedContext = hasKeywordMatchedContext || keywordScore > 0;

            int score = keywordScore;

            // 保留原始召回顺序的价值：向量召回排前的片段一般仍然可信。
            score += Math.max(0, 20 - i);

            scoredContexts.add(new ScoredContext(context,score));
        }

        // 按 articleId 去重，保留最高分 chunk
        Map<Long, ArticleRagContext> deduped = new LinkedHashMap<>();
        boolean shouldFilterUnmatchedContexts = !keywords.isEmpty() && hasKeywordMatchedContext;
        scoredContexts.stream()
                .filter(sc -> !shouldFilterUnmatchedContexts || scoreKeywordMatches(sc.context, keywords) > 0)
                .sorted(Comparator.comparingInt(ScoredContext::score).reversed())
                .forEach(sc -> {
                    Long articleId = sc.context.articleId();
                    if (articleId != null && !deduped.containsKey(articleId)) {
                        deduped.put(articleId, sc.context);
                    }
                });

        return deduped.values().stream()
                .limit(MAX_CONTEXTS)
                .toList();
    }

    private int scoreKeywordMatches(ArticleRagContext context, List<String> keywords) {
        String title = normalize(context.title());
        String content = normalize(context.content());

        int score = 0;

        for (String keyword : keywords) {
            if (title.contains(keyword)) {
                score += 10;
            }
            if (content.contains(keyword)) {
                score += 3;
            }
        }

        return score;
    }

    private List<String> extractKeywords(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        String text = normalize(question);
        for (String stopWord : STOP_WORDS) {
            text = text.replace(stopWord, " ");
        }
        text = text.replace("的", " ");

        Set<String> keywords = new LinkedHashSet<>();
        Matcher matcher = KEYWORD_PATTERN.matcher(text);

        while (matcher.find()) {
            String keyword = matcher.group().trim();
            if (!keyword.isBlank() && !STOP_WORDS.contains(keyword)) {
                keywords.add(keyword);
            }
        }

        Matcher chineseMatcher = CHINESE_TEXT_PATTERN.matcher(text);
        while (chineseMatcher.find()) {
            String chineseText = chineseMatcher.group();
            for (int i = 0; i < chineseText.length() - 1; i++) {
                keywords.add(chineseText.substring(i, i + 2));
            }
        }

        return new ArrayList<>(keywords);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }

    private record ScoredContext(ArticleRagContext context, int score) {
    }
}
