package com.hailin.blogsystem.ai.tool;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AiArticleDetailResult;
import com.hailin.blogsystem.entity.dto.AiArticleSearchResult;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.baomidou.mybatisplus.extension.ddl.DdlScriptErrorHandler.PrintlnLogErrorHandler.log;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiArticleTools {

    private final ArticlesService articlesService;

    @Tool(description = "搜索海林Blog中已经发布的公开文章。当用户询问站内是否有某个技术主题、推荐相关文章、查找博客文章时使用。")
    public List<AiArticleSearchResult> searchPublishedArticles(
            @ToolParam(description = "搜索关键词，例如 Redis,Spring Boot,缓存穿透,微服务")
            String keyWord
    ){
        return articlesService.lambdaQuery()
                .select(Articles::getId,Articles::getTitle,Articles::getSummary,Articles::getCreatedAt,Articles::getViewCount,Articles::getCoverUrl)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .and(wrapper -> wrapper
                        .like(Articles::getTitle,keyWord)
                        .or()
                        .like(Articles::getSummary,keyWord)
                )
                .orderByDesc(Articles::getCreatedAt)
                .last("LIMIT 3")
                .list()
                .stream()
                .map(article -> new AiArticleSearchResult(
                        article.getId(),
                        article.getTitle(),
                        article.getSummary(),
                        article.getViewCount(),
                        article.getCoverUrl(),
                        article.getCreatedAt()
                ))
                .toList();
    }


    @Tool(description = "根据文章ID获取海林Blog中已发布公开文章的详细内容。当用户想了解某篇搜索结果文章的详细内容、总结某篇文章、继续追问某篇文章时使用。")
    public AiArticleDetailResult getPublishedArticleDetail(
            @ToolParam(description = "文章ID，例如 12")
            Long articleId
    ){
        log.info("AI工具调用：getPublishedArticleDetail, articleId={}", articleId);
        Articles article = articlesService.lambdaQuery()
                .select(Articles::getId,
                        Articles::getTitle,
                        Articles::getSummary,
                        Articles::getContent,
                        Articles::getViewCount,
                        Articles::getCreatedAt)
                .eq(Articles::getId,articleId)
                .eq(Articles::getStatus,BlogConstants.ArticlesStatus.PUBLISHED)
                .one();

        if (article == null) {
            return null;
        }

        return new AiArticleDetailResult(
                article.getId(),
                article.getTitle(),
                article.getSummary(),
                article.getContent(),
                article.getViewCount(),
                article.getCreatedAt()
        );
    }



    private String limitText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n\n[文章内容过长，后半部分已省略]";
    }
}
