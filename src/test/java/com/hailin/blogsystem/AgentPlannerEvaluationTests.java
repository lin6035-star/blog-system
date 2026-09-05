package com.hailin.blogsystem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.planner.AgentPlannerSupport;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文件驱动的 Planner 评测。
 *
 * 这里不调用真实 LLM，只验证：
 * LLM 建议 + 后端规则 + 成本护栏
 * 是否产生预期的最终 AgentDecision。
 * @Tag("eval")：纳入评测门离线样例集（-Dgroups=eval）
 */
@Tag("eval")
class AgentPlannerEvaluationTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BlogAiProperties properties;
    private AiWorkflowRunMapper workflowRunMapper;
    private AgentPlannerSupport planner;

    private final AtomicReference<List<AiWorkflowRun>> currentRuns =
            new AtomicReference<>(List.of());

    @BeforeEach
    void setUp() {
        properties = new BlogAiProperties();
        workflowRunMapper = mock(AiWorkflowRunMapper.class);

        // 每个评测用例可以设置不同的历史自动拉起次数。
        when(workflowRunMapper.selectList(any()))
                .thenAnswer(invocation -> currentRuns.get());

        planner = new AgentPlannerSupport(
                properties,
                workflowRunMapper,
                objectMapper
        );
    }

    @Test
    void allPlannerEvaluationCasesMatchExpectedDecision() throws Exception {
        InputStream inputStream = getClass()
                .getResourceAsStream("/agent/planner-evaluation-cases.json");

        assertThat(inputStream)
                .as("Planner 评测集文件必须存在")
                .isNotNull();

        List<PlannerEvaluationCase> cases = objectMapper.readValue(
                Objects.requireNonNull(inputStream),
                new TypeReference<>() {
                }
        );

        assertThat(cases).isNotEmpty();

        for (PlannerEvaluationCase evaluationCase : cases) {
            currentRuns.set(
                    buildAutoStartedRuns(evaluationCase.autoStartedCount())
            );

            AiIntent intent = buildIntent(evaluationCase);

            AgentDecision decision = planner.decide(
                    evaluationCase.message(),
                    intent,
                    pageContext(evaluationCase),
                    1L,
                    session(100L)
            );

            assertThat(decision.getAction())
                    .as("用例：%s，实际原因：%s",
                            evaluationCase.name(),
                            decision.getReason())
                    .isEqualTo(
                            AgentAction.valueOf(
                                    evaluationCase.expectedAction()
                            )
                    );

            if (evaluationCase.expectedWorkflowType() != null) {
                assertThat(decision.getWorkflowType())
                        .as("用例：%s", evaluationCase.name())
                        .isEqualTo(
                                AiWorkflowType.valueOf(
                                        evaluationCase.expectedWorkflowType()
                                )
                        );
            } else {
                assertThat(decision.getWorkflowType())
                        .as("用例：%s 不应该产生 Workflow 类型",
                                evaluationCase.name())
                        .isNull();
            }

            if (evaluationCase.expectedToolName() != null) {
                assertThat(decision.getToolName())
                        .as("用例：%s", evaluationCase.name())
                        .isEqualTo(evaluationCase.expectedToolName());
            }

            if (evaluationCase.expectedRuleHit() != null) {
                assertThat(decision.getRuleHits())
                        .as("用例：%s", evaluationCase.name())
                        .contains(evaluationCase.expectedRuleHit());
            }

            if (evaluationCase.expectedReasonContains() != null) {
                assertThat(decision.getReason())
                        .as("用例：%s", evaluationCase.name())
                        .contains(evaluationCase.expectedReasonContains());
            }

            if (evaluationCase.expectedClientActionType() != null) {
                assertThat(decision.getClientActionType())
                        .as("用例：%s", evaluationCase.name())
                        .isEqualTo(
                                evaluationCase.expectedClientActionType()
                        );
            }
        }
    }

    private PageContextDTO pageContext(
            PlannerEvaluationCase evaluationCase
    ) {
        if (evaluationCase.pageType() == null
                && evaluationCase.articleId() == null) {
            return null;
        }

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType(evaluationCase.pageType());
        pageContext.setArticleId(evaluationCase.articleId());

        return pageContext;
    }

    private AiIntent buildIntent(PlannerEvaluationCase evaluationCase) {
        AiIntent intent = new AiIntent();
        intent.setIntent(evaluationCase.intent());
        intent.setConfidence(evaluationCase.confidence());
        intent.setRisk(evaluationCase.risk());
        intent.setSuggestedAction(evaluationCase.suggestedAction());
        intent.setSuggestedWorkflowType(
                evaluationCase.suggestedWorkflowType()
        );

        intent.setActionType(evaluationCase.actionType());
        intent.setArticleId(evaluationCase.articleId());
        intent.setTarget(evaluationCase.target());
        intent.setParam(evaluationCase.param());
        intent.setContent(evaluationCase.content());

        return intent;
    }

    private AiSessions session(Long sessionId) {
        AiSessions session = new AiSessions();
        session.setId(sessionId);
        return session;
    }

    private List<AiWorkflowRun> buildAutoStartedRuns(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> {
                    AiWorkflowRun run = new AiWorkflowRun();
                    run.setContextJson("{\"agentAutoStarted\":true}");
                    return run;
                })
                .toList();
    }

    private record PlannerEvaluationCase(
            String name,
            String message,
            String intent,
            String suggestedAction,
            String suggestedWorkflowType,
            Double confidence,
            String risk,

            String actionType,
            String articleId,
            String target,
            String param,
            String content,

            String pageType,

            String expectedAction,
            String expectedWorkflowType,
            String expectedToolName,
            String expectedClientActionType,
            String expectedRuleHit,
            String expectedReasonContains,
            int autoStartedCount
    ) {
    }
}
