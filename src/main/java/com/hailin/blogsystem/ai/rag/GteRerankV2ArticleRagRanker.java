package com.hailin.blogsystem.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class GteRerankV2ArticleRagRanker implements ArticleRagRanker{

    private final BlogAiProperties blogAiProperties;
    private final DefaultArticleRagRanker defaultArticleRagRanker;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    private final RestClient restClient = RestClient.create();

    @Override
    public List<ArticleRagContext> rank(String question, List<ArticleRagContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            log.info("gte-rerank-v2 skip: empty contexts");
            return List.of();
        }
        if (contexts.size() == 1) {
            log.info("gte-rerank-v2 skip: only one candidate");
            return contexts;
        }

        BlogAiProperties.Rerank rerank = blogAiProperties.getRag().getRerank();
        if (rerank == null || !rerank.isEnabled()) {
            log.info("gte-rerank-v2 disabled, fallback to default");
            return defaultArticleRagRanker.rank(question, contexts);
        }
        if (rerank.getWorkspaceId() == null || rerank.getWorkspaceId().isBlank()) {
            log.info("gte-rerank-v2 workspaceId missing, fallback to default");
            return defaultArticleRagRanker.rank(question, contexts);
        }

        try {
            List<ArticleRagContext> limited = contexts.stream()
                    .limit(rerank.getMaxDocuments())
                    .toList();

            List<String> documents = limited.stream()
                    .map(this::toDocumentText)
                    .toList();

            String url = "https://%s.cn-beijing.maas.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
                    .formatted(rerank.getWorkspaceId().trim());

            Map<String, Object> input = Map.of(
                    "query", question,
                    "documents", documents
            );

            Map<String, Object> parameters = Map.of(
                    "return_documents", false,
                    "top_n", rerank.getTopN()
            );

            Map<String, Object> body = Map.of(
                    "model", rerank.getModel(),
                    "input", input,
                    "parameters", parameters
            );

            log.info("gte-rerank-v2 request: candidate={}, topN={}, model={}",
                    limited.size(), rerank.getTopN(), rerank.getModel());

            JsonNode response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            log.info("gte-rerank-v2 response raw: hasOutput={}, hasResults={}",
                    response != null && response.has("output"),
                    response != null && response.path("output").has("results"));

            List<ArticleRagContext> ranked = parseRanked(response, limited, rerank.getTopN());
            if (ranked.isEmpty()) {
                log.info("gte-rerank-v2 empty results, fallback to default");
                return defaultArticleRagRanker.rank(question, contexts);
            }

            log.info("gte-rerank-v2 排序成功，question={}, candidate={}, ranked={}",
                    question, limited.size(), ranked.size());

            return ranked;
        } catch (Exception e) {
            log.warn("gte-rerank-v2 失败，回退规则 rerank: {}", e.getMessage());
            return defaultArticleRagRanker.rank(question, contexts);
        }
    }

    private List<ArticleRagContext> parseRanked(JsonNode response,
                                                List<ArticleRagContext> contexts,
                                                int topN) {
        JsonNode results = response == null ? null : response.path("output").path("results");
        if (results == null || !results.isArray()) {
            return List.of();
        }

        List<ArticleRagContext> ranked = new ArrayList<>();
        Set<Long> seenArticleIds = new LinkedHashSet<>();

        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= contexts.size()) {
                continue;
            }

            ArticleRagContext context = contexts.get(index);
            Long articleId = context.articleId();
            if (articleId != null && seenArticleIds.add(articleId)) {
                ranked.add(context);
            }

            if (ranked.size() >= Math.max(1, topN)) {
                break;
            }
        }

        return ranked;
    }

    private String toDocumentText(ArticleRagContext context) {
        String title = context.title() == null ? "" : context.title();
        String content = context.content() == null ? "" : context.content();
        String text = "标题：" + title + "\n片段：" + content;
        return text.length() > 1200 ? text.substring(0, 1200) : text;
    }
}
