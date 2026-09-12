package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.ArticleAgentActionExecutorImpl;
import com.hailin.blogsystem.ai.agent.ArticleNotOwnedException;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文章域动作执行器测试（V2.5）。
 * 验证 QUERY_ARTICLE 查库验归属（articleId 只当线索）+ 结构摘要输出 + 失败语义。
 */
class ArticleAgentActionExecutorTests {

    private ArticlesService articlesService;
    private ArticleSessionAnchorService anchorService;
    private ArticleAgentActionExecutorImpl executor;

    @BeforeEach
    void setUp() {
        articlesService = mock(ArticlesService.class);
        anchorService = new ArticleSessionAnchorService(
                mock(AiSessionService.class), articlesService);
        executor = new ArticleAgentActionExecutorImpl(
                articlesService,
                mock(AiMemoryRetrieveService.class),
                mock(AiEpisodicMemoryRetrieveService.class),
                mock(ArticleRagSearchService.class),
                new BlogAiProperties(),
                anchorService
        );
    }

    private static PageContextDTO articleContext(String articleId) {
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId(articleId);
        return pageContext;
    }

    @Test
    void queryArticleOwnedArticleReturnsStructureSummary() {
        Articles article = article(12L, 100L);
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("《Redis 缓存设计》");
        assertThat(observation).contains("已发布");
        assertThat(observation).contains("字数");
        assertThat(observation).contains("- 小标题结构：");
        assertThat(observation).contains("## 缓存穿透");
    }

    @Test
    void queryArticleResolvedTargetBeatsPageContext() {
        // V3.11 P0：决议目标（prepareStepDecision 注入 input.articleId）优先于 pageContext——
        // 跨页场景（B 页 99 指会话锚 A 12）必须读决议目标 12，不能被当前页 99 抢走
        Articles article = article(12L, 100L);
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")),
                100L,
                articleContext("99")
        );

        assertThat(observation).contains("《Redis 缓存设计》");
        verify(articlesService, never()).getById(99L);
    }

    @Test
    void queryArticleFallsBackToPageContextWhenNoResolvedTarget() {
        // 无决议目标（input 无 articleId）→ pageContext 兜底线索（V2.5 行为保留）
        Articles article = article(12L, 100L);
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("《Redis 缓存设计》");
    }

    @Test
    void queryArticleWithoutClueThrows() {
        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少当前文章 ID");
    }

    @Test
    void queryArticleNonNumericClueThrows() {
        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("abc")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少当前文章 ID");
    }

    @Test
    void queryArticleNotFoundThrows() {
        when(articlesService.getById(12L)).thenReturn(null);

        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("12")
        )).isInstanceOf(com.hailin.blogsystem.ai.agent.ArticleNotOwnedException.class)
                .hasMessageContaining("文章不存在");
    }

    @Test
    void queryArticleNotOwnedNotPublishedThrows() {
        // 2026-09-10 手测修正：归属不再是读门槛（他人已发布文章可读，见
        // queryArticleReadsOthersPublishedArticle）；但他人**非公开**文章仍不可读。
        Articles article = article(12L, 999L);
        article.setStatus(2);   // 已隐藏
        when(articlesService.getById(12L)).thenReturn(article);

        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("12")
        )).isInstanceOf(ArticleNotOwnedException.class)
                .hasMessageContaining("不是公开的");
    }

    @Test
    void notLoggedInReturnsFriendlyText() {
        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                null,
                articleContext("12")
        );

        assertThat(observation).isEqualTo("当前未登录，无法分析文章。");
    }

    @Test
    void ragWithoutKeywordReturnsHint() {
        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.SEARCH_RAG),
                100L,
                null
        );

        assertThat(observation).contains("缺少关键词");
    }

    // ==================== V3.11 focus 聚焦查询 ====================

    @Test
    void focusHitsHeadingSectionReturnsSectionText() {
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "缓存击穿")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("聚焦片段");
        assertThat(observation).contains("## 缓存击穿");
        assertThat(observation).contains("内容二");
        // 聚焦时不返回全文结构摘要
        assertThat(observation).doesNotContain("当前文章分析");
        assertThat(observation).doesNotContain("字数");
    }

    @Test
    void focusWithNaturalWordingIsPurifiedBeforeMatch() {
        // 「缓存击穿那一段写得怎么样」→ 净化剔除指示/疑问词 → token「缓存击穿」命中
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "缓存击穿那一段写得怎么样")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("聚焦片段");
        assertThat(observation).contains("## 缓存击穿");
    }

    @Test
    void focusWithXiaoJieWordingIsPurifiedBeforeMatch() {
        // 2026-09-10 手测回归：「XX 这一小节写得怎么样」→ 剔除「这一小节/写得/怎么样」→ 命中小节
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "缓存击穿这一小节写得怎么样")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("聚焦片段");
        assertThat(observation).contains("## 缓存击穿");
    }

    @Test
    void focusMatchesWhenSpacingDiffers() {
        // 2026-09-10 手测回归：用户输入「VibeCoding」（无空格）vs 文章「Vibe Coding」（有空格）
        Articles article = article(12L, 100L);
        article.setContent("# 笔记\n\n## Vibe Coding 实践\n\n用自然语言驱动 AI 写代码");
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "VibeCoding这一小节写得怎么样")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("聚焦片段");
        assertThat(observation).contains("用自然语言驱动 AI 写代码");
    }

    @Test
    void queryArticleReadsOthersPublishedArticle() {
        // 2026-09-10 手测：公开博客里游客都能读的文章，Agent 只读分析不该被归属拦住。
        // 原文案「这篇文章不是你的，无法为你分析或优化」在别人文章页问「写得怎么样」时误伤。
        Articles article = article(12L, 999L);   // 作者是别人
        article.setStatus(1);                     // 已发布 → 公开可读
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,                                    // 当前用户 100 ≠ 作者 999
                articleContext("12")
        );

        assertThat(observation).contains("《Redis 缓存设计》");
        assertThat(observation).contains("小标题结构");
        // 归属边界必须写进观察：能分析，但不能提议改
        assertThat(observation).contains("不是当前用户的文章");
    }

    @Test
    void queryArticleRejectsOthersDraft() {
        // 他人非公开文章仍不可读（防隐私洞）——读权限放宽不得越界到草稿/隐藏
        Articles article = article(12L, 999L);
        article.setStatus(0);                     // 草稿
        when(articlesService.getById(12L)).thenReturn(article);

        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("12")
        )).isInstanceOf(ArticleNotOwnedException.class);
    }

    @Test
    void queryArticleOwnArticleShowsNoOwnershipNote() {
        // 自己的文章不该带「不是你的文章」提示（避免误导 LLM 不提议修改）
        Articles article = article(12L, 100L);
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("12")
        );

        assertThat(observation).doesNotContain("不是当前用户的文章");
    }

    @Test
    void focusSkipsHeadingOnlySectionAndReturnsBody() {
        // 2026-09-10 手测回归：H1 标题行 + 其后 ## 小节的结构里，
        // 「## 」之前的内容会单独成节且只含标题——命中它等于返回一个没有正文的标题，
        // 动作 SUCCESS 但证据为空，验证器据此反复判 NEED_MORE，6 步空转到顶。
        Articles article = article(12L, 100L);
        article.setTitle("Vibe Coding 入门指南：让编程随感觉流动");
        // 只让一个 ## 命中焦点词：H1 行也命中但会被跳过——若它参与，就会与小节并列从而走降级，
        // 这条测试就测不到「跳过纯标题节 + 返回正文」的本意了
        article.setContent("# Vibe Coding 入门指南：让编程随感觉流动\n\n"
                + "## 什么是 Vibe Coding？\n\n它是一种以直觉和情绪驱动的编程方式。\n\n"
                + "## 怎么开始？\n\n### 准备：调出你的创作氛围\n\n播放一首 Lo-fi，泡杯咖啡。\n\n"
                + "## 现在就开始吧\n\n今晚就打开编辑器。");
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "Vibe Coding 这一小节")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("聚焦片段");
        // 必须带出该节的正文，而不是那个只有标题的 H1 节
        assertThat(observation).contains("它是一种以直觉和情绪驱动的编程方式");
    }

    @Test
    void focusFallsBackToStructureSummaryWhenHeadingsTie() {
        // 焦点词同时命中多个小节且正文含量也分不出高下 → 明说「无法确定」+ 结构摘要。
        // 不能返回「候选清单」：那不是正文，语义闸永远判不了证据充足，只会继续空转到顶。
        Articles article = article(12L, 100L);
        article.setContent("# 笔记\n\n## Vibe Coding 实践\n\n用自然语言驱动 AI 写代码\n\n"
                + "## Vibe Coding 的坑\n\n上下文给太多反而更差");
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "Vibe Coding")),
                100L,
                articleContext("12")
        );

        // 必须**明说无法确定**（2026-09-11 实测教训：静默回退 + 「聚焦片段：」前缀会让模型
        // 以为拿到了那节内容 → 凭空评价 → 被证据门拦 → 重试同一 focus → 步数用满），
        // 再附结构摘要供它换一个更精确的定位词
        assertThat(observation).contains("未能确定");
        assertThat(observation).contains("小标题结构");
        assertThat(observation).contains("## Vibe Coding 实践");
        assertThat(observation).contains("## Vibe Coding 的坑");
    }

    @Test
    void focusResolvesTiedHeadingsByBodyContent() {
        // 标题并列时用「哪一节正文真的在讲它」消歧——能定则定，不必降级
        Articles article = article(12L, 100L);
        article.setContent("# 笔记\n\n## 实践\n\nVibe Coding 就是用自然语言驱动 AI 写代码，边写边改。\n\n"
                + "## 总结\n\n保持手感。");
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
            AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "Vibe Coding")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("聚焦片段");
        assertThat(observation).contains("用自然语言驱动 AI 写代码");
    }

    @Test
    void focusMissReturnsExplicitMessage() {
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "缓存雪崩")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("未能从这篇文章中找到");
        assertThat(observation).contains("缓存雪崩");
    }

    @Test
    void structuralFocusReturnsStructureSummaryInsteadOfMiss() {
        // 用户问「结合偏好看文章整体」时，LLM 容易把结构/小标题/摘要放进 focus。
        // 这些不是正文小节名，应该返回结构摘要，否则 verifier 会因缺少文章证据正确拦截。
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "文章结构、小标题和摘要部分")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("当前文章分析");
        assertThat(observation).contains("- 小标题结构：");
        assertThat(observation).contains("- 摘要：缓存穿透与击穿的区别");
        assertThat(observation).doesNotContain("未能从这篇文章中找到");
    }

    @Test
    void summaryFocusReturnsStructureSummaryInsteadOfMiss() {
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12", "focus", "摘要部分")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("当前文章分析");
        assertThat(observation).contains("- 摘要：缓存穿透与击穿的区别");
        assertThat(observation).doesNotContain("未能从这篇文章中找到");
    }

    @Test
    void focusWithoutFocusReturnsStructureSummary() {
        // 无 focus → 原结构摘要（已有 queryArticleOwnedArticleReturnsStructureSummary 同语义，这里锁回归）
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("当前文章分析");
        assertThat(observation).contains("字数");
    }

    private Articles article(Long id, Long authorId) {
        Articles article = new Articles();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setTitle("Redis 缓存设计");
        article.setSummary("缓存穿透与击穿的区别");
        article.setStatus(1);
        article.setContent("# Redis 缓存\n\n## 缓存穿透\n\n内容一\n\n## 缓存击穿\n\n内容二");
        article.setPublishedAt(LocalDateTime.now());
        return article;
    }
}
