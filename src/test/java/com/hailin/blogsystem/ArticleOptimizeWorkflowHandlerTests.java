package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.workflow.AiWorkflowAdvanceResult;
import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.ai.workflow.ArticleOptimizeWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.LlmStreamCaller;
import com.hailin.blogsystem.ai.workflow.WorkflowContextSupport;
import com.hailin.blogsystem.ai.workflow.WorkflowKnowledgeSupport;
import com.hailin.blogsystem.ai.workflow.WorkflowQualitySupport;
import com.hailin.blogsystem.ai.workflow.WorkflowStatusSupport;
import com.hailin.blogsystem.ai.workflow.WorkflowStepLogRecorder;
import com.hailin.blogsystem.ai.workflow.WorkflowStepRunner;
import com.hailin.blogsystem.ai.workflow.WorkflowTokenRecorder;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import com.hailin.blogsystem.service.AiWorkflowStepLogService;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleOptimizeWorkflowHandlerTests {

    private static final Long RUN_ID = 2098694839777398785L;
    private static final Long USER_ID = 101L;
    private static final Long ARTICLE_ID = 202L;

    private ArticleOptimizeWorkflowHandler handler;
    private ArticlesService articlesService;
    private LlmStreamCaller llmStreamCaller;
    private WorkflowKnowledgeSupport workflowKnowledgeSupport;
    private WorkflowContextSupport workflowContextSupport;

    @BeforeEach
    void setUp() {
        workflowContextSupport = new WorkflowContextSupport(new ObjectMapper());
        articlesService = mock(ArticlesService.class);
        llmStreamCaller = mock(LlmStreamCaller.class);
        workflowKnowledgeSupport = mock(WorkflowKnowledgeSupport.class);

        handler = new ArticleOptimizeWorkflowHandler(
                workflowContextSupport,
                new WorkflowStatusSupport(),
                new WorkflowStepRunner(new WorkflowStepLogRecorder(mock(AiWorkflowStepLogService.class))),
                articlesService,
                new WorkflowTokenRecorder(),
                llmStreamCaller,
                workflowKnowledgeSupport,
                mock(WorkflowQualitySupport.class),
                mock(ArticleSessionAnchorService.class)
        );
    }

    @Test
    void runInitialStepsRetriesOnceWhenOptimizationPlanStreamReturnsEmptyContent() {
        when(articlesService.getById(ARTICLE_ID)).thenReturn(testArticle());
        when(workflowKnowledgeSupport.retrieveMemoryContext(eq(USER_ID), anyString())).thenReturn("");
        when(workflowKnowledgeSupport.retrieveRagReferences(anyString(), anyString())).thenReturn(List.of());
        when(llmStreamCaller.call(
                eq("优化方案生成失败："),
                eq(AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN),
                eq("optimizationPlan"),
                any(),
                anyString(),
                anyString(),
                eq(2000)
        ))
                .thenThrow(new RuntimeException("优化方案生成失败：模型返回空内容"))
                .thenReturn(new LlmStreamCaller.LlmStreamResult("## 可执行优化方案", new TokenUsageAccumulator()));

        AiWorkflowRun run = newOptimizeRun();
        List<String> events = new ArrayList<>();

        AiWorkflowAdvanceResult result = handler.runInitialSteps(run, collectingEmitter(events));

        assertThat(result.getRun().getStatus()).isEqualTo(AiWorkflowStatus.WAITING_PLAN_CONFIRM.name());
        assertThat(result.getRun().getCurrentStep()).isEqualTo(AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN.name());
        assertThat(result.getRun().getContextJson()).contains("可执行优化方案");
        assertThat(events).contains(
                "GENERATE_OPTIMIZATION_PLAN|FAILED|优化方案生成失败：模型返回空内容",
                "GENERATE_OPTIMIZATION_PLAN|RUNNING|方案生成返回空内容，正在重新生成...",
                "GENERATE_OPTIMIZATION_PLAN|SUCCESS|步骤完成"
        );
        verify(llmStreamCaller, times(2)).call(
                eq("优化方案生成失败："),
                eq(AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN),
                eq("optimizationPlan"),
                any(),
                anyString(),
                anyString(),
                eq(2000)
        );
    }

    @Test
    void runInitialStepsDoesNotRetryNonEmptyContentFailures() {
        when(articlesService.getById(ARTICLE_ID)).thenReturn(testArticle());
        when(workflowKnowledgeSupport.retrieveMemoryContext(eq(USER_ID), anyString())).thenReturn("");
        when(workflowKnowledgeSupport.retrieveRagReferences(anyString(), anyString())).thenReturn(List.of());
        when(llmStreamCaller.call(
                eq("优化方案生成失败："),
                eq(AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN),
                eq("optimizationPlan"),
                any(),
                anyString(),
                anyString(),
                eq(2000)
        )).thenThrow(new RuntimeException("优化方案生成失败：AI 服务暂时繁忙，请稍后重试"));

        AiWorkflowRun run = newOptimizeRun();

        assertThatThrownBy(() -> handler.runInitialSteps(run, AiWorkflowStepEmitter.noop()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("优化方案生成失败：AI 服务暂时繁忙，请稍后重试");

        assertThat(run.getCurrentStep()).isEqualTo(AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN.name());
        verify(llmStreamCaller, times(1)).call(
                eq("优化方案生成失败："),
                eq(AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN),
                eq("optimizationPlan"),
                any(),
                anyString(),
                anyString(),
                eq(2000)
        );
    }

    @Test
    void approvePlanKeepsCurrentStepAtRewriteWhenRewriteFails() {
        when(llmStreamCaller.call(
                eq("文章重写失败："),
                eq(AiWorkflowStep.REWRITE_ARTICLE),
                eq("optimizedContent"),
                any(),
                anyString(),
                anyString(),
                eq(8500)
        )).thenThrow(new RuntimeException("文章重写失败：AI 服务暂时繁忙，请稍后重试"));

        AiWorkflowRun run = newPlanConfirmRun();

        assertThatThrownBy(() -> handler.approve(run, AiWorkflowStepEmitter.noop()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("文章重写失败：AI 服务暂时繁忙，请稍后重试");

        assertThat(run.getCurrentStep()).isEqualTo(AiWorkflowStep.REWRITE_ARTICLE.name());
    }

    private AiWorkflowRun newOptimizeRun() {
        AiWorkflowOptimizeArticleDTO dto = new AiWorkflowOptimizeArticleDTO();
        dto.setArticleId(ARTICLE_ID);
        dto.setInstruction("补充更多项目实战细节");

        AiWorkflowRun run = handler.create(USER_ID, dto).getRun();
        run.setId(RUN_ID);
        return run;
    }

    private Articles testArticle() {
        Articles article = new Articles();
        article.setId(ARTICLE_ID);
        article.setAuthorId(USER_ID);
        article.setCategoryId(1L);
        article.setTitle("Redis 缓存文章");
        article.setSummary("Redis 缓存总结");
        article.setContent("""
                # Redis 缓存文章

                Redis 缓存常用于提高系统读性能。

                本文简单介绍缓存穿透、缓存击穿和高并发场景。
                """);
        article.setStatus(0);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return article;
    }

    private AiWorkflowRun newPlanConfirmRun() {
        Map<String, Object> article = new HashMap<>();
        article.put("id", ARTICLE_ID);
        article.put("title", "Redis 缓存文章");
        article.put("summary", "Redis 缓存总结");
        article.put("content", "# Redis 缓存文章\n\nRedis 缓存常用于提高系统读性能。");

        Map<String, Object> stepResults = new HashMap<>();
        stepResults.put("article", article);
        stepResults.put("analysis", Map.of("contentLength", 33));
        stepResults.put("optimizationPlan", "## 优化方案\n补充项目案例。");

        Map<String, Object> context = new HashMap<>();
        context.put("workflowVersion", "1.0");
        context.put("input", Map.of("articleId", ARTICLE_ID, "instruction", "补充项目案例"));
        context.put("memoryContext", "");
        context.put("ragContext", Map.of("references", List.of()));
        context.put("stepResults", stepResults);
        context.put("feedbackHistory", new ArrayList<>());

        AiWorkflowRun run = new AiWorkflowRun();
        run.setId(RUN_ID);
        run.setUserId(USER_ID);
        run.setWorkflowType("OPTIMIZE_ARTICLE");
        run.setWorkflowVersion("1.0");
        run.setStatus(AiWorkflowStatus.WAITING_PLAN_CONFIRM.name());
        run.setCurrentStep(AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN.name());
        run.setContextJson(workflowContextSupport.toJson(context));
        run.setRetryCount(0);
        run.setInputTokens(0);
        run.setOutputTokens(0);
        run.setTotalTokens(0);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        return run;
    }

    private AiWorkflowStepEmitter collectingEmitter(List<String> events) {
        return (step, status, message) -> {
            String normalizedMessage = message == null ? "" : message;
            if (normalizedMessage.startsWith("步骤完成")) {
                normalizedMessage = "步骤完成";
            }
            events.add(step + "|" + status + "|" + normalizedMessage);
        };
    }
}
