package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.tool.AiLearningDashboardTool;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.mapper.LearningPlanMapper;
import com.hailin.blogsystem.mapper.LearningStageMapper;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Learning Dashboard Tool 测试。
 * V1 只暴露一个轻量读工具给模型，所以这里重点测输入容错、用户隔离和响应字段稳定性。
 */
@SpringBootTest
class AiLearningDashboardToolTests {

    private static final Long TEST_USER = 106L;
    private static final Long OTHER_USER = 107L;

    @Autowired
    private AiLearningDashboardTool dashboardTool;

    @Autowired
    private LearningPlanMapper learningPlanMapper;

    @Autowired
    private LearningStageMapper learningStageMapper;

    @Autowired
    private AiSessionMapper aiSessionMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        List<LearningPlans> testPlans = learningPlanMapper.selectList(
                new LambdaQueryWrapper<LearningPlans>()
                        .in(
                                LearningPlans::getUserId,
                                TEST_USER,
                                OTHER_USER
                        )
        );

        // 空列表时 IN () 会生成非法 SQL（H2 直接报语法错误），先判空再删阶段
        if (!testPlans.isEmpty()) {
            List<Long> planIds = testPlans.stream()
                    .map(LearningPlans::getId)
                    .toList();

            learningStageMapper.delete(
                    new LambdaQueryWrapper<LearningStages>()
                            .in(LearningStages::getPlanId, planIds)
            );
        }

        learningPlanMapper.delete(
                new LambdaQueryWrapper<LearningPlans>()
                        .in(
                                LearningPlans::getUserId,
                                TEST_USER,
                                OTHER_USER
                        )
        );

        aiSessionMapper.delete(
                new LambdaQueryWrapper<AiSessions>()
                        .in(
                                AiSessions::getUserId,
                                TEST_USER,
                                OTHER_USER
                        )
        );

        UserContext.clear();
    }

    @Test
    void notLoginReturnsNotLoginEmptyState() throws Exception {
        UserContext.clear();

        JsonNode result = readJson(dashboardTool.call(null));

        assertThat(result.path("emptyState").asText()).isEqualTo("NOT_LOGIN");
        assertThat(result.path("emptyHint").asText()).contains("未登录");
    }

    @Test
    void emptyQuestionFallsBackToEmptyStringAndReturnsNoPlansState() throws Exception {
        UserContext.set(TEST_USER);

        JsonNode fromNull = readJson(dashboardTool.call(null));
        JsonNode fromEmptyJson = readJson(dashboardTool.call("{}"));

        assertThat(fromNull.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(fromNull.path("question").asText()).isEmpty();
        assertThat(fromNull.path("emptyState").asText()).isEqualTo("NO_PLANS");
        assertThat(fromNull.path("summary").path("totalPlanCount").asInt()).isZero();
        assertThat(fromEmptyJson.path("question").asText()).isEmpty();
    }

    @Test
    void returnsOnlyCurrentUsersPlanSummaries() throws Exception {
        UserContext.set(TEST_USER);
        createPlan(TEST_USER, "Java 后端学习计划", LearningPlans.STATUS_ACTIVE);
        createPlan(TEST_USER, "Redis 学习计划", LearningPlans.STATUS_COMPLETED);
        createPlan(OTHER_USER, "其他用户的学习计划", LearningPlans.STATUS_ACTIVE);

        JsonNode result = readJson(dashboardTool.call(
                "{\"question\":\"今天学什么\"}",
                new ToolContext(Map.of("userId", TEST_USER))
        ));

        assertThat(result.path("emptyState").asText()).isEqualTo("OK");
        assertThat(result.path("question").asText()).isEqualTo("今天学什么");
        assertThat(result.path("summary").path("totalPlanCount").asInt()).isEqualTo(2);
        assertThat(result.path("summary").path("activePlanCount").asInt()).isEqualTo(1);
        assertThat(result.path("planSummaries")).hasSize(2);
        assertThat(result.toString()).contains("Java 后端学习计划", "Redis 学习计划");
        assertThat(result.toString()).doesNotContain("其他用户的学习计划");
    }

    @Test
    void plainTextToolInputIsAcceptedAsQuestion() throws Exception {
        UserContext.set(TEST_USER);
        createPlan(TEST_USER, "Redis 学习计划", LearningPlans.STATUS_ACTIVE);

        JsonNode result = readJson(dashboardTool.call("今天学什么"));

        assertThat(result.path("question").asText()).isEqualTo("今天学什么");
        assertThat(result.path("emptyState").asText()).isEqualTo("OK");
    }

    @Test
    void longPlanTitleIsCappedInModelFacingResponse() throws Exception {
        UserContext.set(TEST_USER);
        createPlan(TEST_USER, "这是一个非常非常非常非常非常非常长的学习计划标题", LearningPlans.STATUS_ACTIVE);

        JsonNode result = readJson(dashboardTool.call("{}"));

        String title = result.path("planSummaries").get(0).path("title").asText();
        assertThat(title).hasSizeLessThanOrEqualTo(30);
    }

    @Test
    void toolDefinitionExposesSingleStableDashboardToolName() {
        assertThat(dashboardTool.getToolDefinition().name()).isEqualTo("getLearningDashboard");
        assertThat(dashboardTool.getToolDefinition().inputSchema()).contains("question");
    }

    @Test
    void returnsActivePlanSnapshotAndPendingHints() throws Exception {
        UserContext.set(TEST_USER);

        LearningPlans plan = createPlan(
                TEST_USER,
                "Redis 学习计划",
                LearningPlans.STATUS_ACTIVE
        );

        LearningStages stage = new LearningStages();
        stage.setPlanId(plan.getId());
        stage.setOrderNum(1);
        stage.setTitle("Redis 基础");
        stage.setTasks("""
                [
                  {"title":"Redis 数据类型","done":true},
                  {"title":"Hash 底层结构","done":false},
                  {"title":"过期删除策略","done":false},
                  {"title":"持久化机制","done":false},
                  {"title":"集群原理","done":false}
                ]
                """);
        stage.setCreatedAt(LocalDateTime.now());
        stage.setUpdatedAt(LocalDateTime.now());

        learningStageMapper.insert(stage);

        JsonNode result = readJson(
                dashboardTool.call(
                        "{\"question\":\"Redis 学完了下一步学什么\"}",
                        new ToolContext(Map.of("userId", TEST_USER))
                )
        );

        assertThat(result.path("summary")
                .path("activePlanCount")
                .asInt()).isEqualTo(1);

        assertThat(result.path("summary")
                .path("hasActiveWorkflow")
                .asBoolean()).isFalse();

        assertThat(result.path("emptyState").asText())
                .isEqualTo("OK");

        assertThat(result.path("planSummaries").get(0)
                .path("progressPercent").asInt())
                .isEqualTo(20);

        JsonNode snapshot = result.path("activePlanSnapshot");

        assertThat(snapshot.path("planId").asLong())
                .isEqualTo(plan.getId());

        assertThat(snapshot.path("currentStage")
                .path("title")
                .asText())
                .isEqualTo("Redis 基础");

        assertThat(result.path("cappedHints"))
                .hasSize(3);

        assertThat(result.path("cappedHints").get(0).asText())
                .isEqualTo("Hash 底层结构");

        assertThat(result.path("cappedHints").get(1).asText())
                .isEqualTo("过期删除策略");

        assertThat(result.path("cappedHints").get(2).asText())
                .isEqualTo("持久化机制");
    }

    @Test
    void multipleActivePlansReturnsMultipleActiveState() throws Exception {
        UserContext.set(TEST_USER);

        createPlan(
                TEST_USER,
                "Java 学习计划",
                LearningPlans.STATUS_ACTIVE
        );

        createPlan(
                TEST_USER,
                "Redis 学习计划",
                LearningPlans.STATUS_ACTIVE
        );

        JsonNode result = readJson(
                dashboardTool.call("{}")
        );

        assertThat(result.path("emptyState").asText())
                .isEqualTo("MULTIPLE_ACTIVE");

        assertThat(result.path("summary")
                .path("activePlanCount")
                .asInt())
                .isEqualTo(2);

        assertThat(result.path("emptyHint").asText())
                .contains("多个");
    }

    @Test
    void summaryReportsActiveWorkflowForCurrentOwnedSession() throws Exception {
        UserContext.set(TEST_USER);

        createPlan(
                TEST_USER,
                "Redis 学习计划",
                LearningPlans.STATUS_ACTIVE
        );

        AiSessions session = new AiSessions();
        session.setUserId(TEST_USER);
        session.setTitle("Redis 学习");
        session.setActiveWorkflowRunId(999L);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        aiSessionMapper.insert(session);

        JsonNode result = readJson(
                dashboardTool.call(
                        "{}",
                        new ToolContext(
                                Map.of(
                                        "userId", TEST_USER,
                                        "sessionId", session.getId()
                                )
                        )
                )
        );

        assertThat(result.path("summary")
                .path("hasActiveWorkflow")
                .asBoolean())
                .isTrue();
    }

    private LearningPlans createPlan(Long userId, String title, String status) {
        LearningPlans plan = new LearningPlans();
        plan.setUserId(userId);
        plan.setTitle(title);
        plan.setGoal("学习目标");
        plan.setStatus(status);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        learningPlanMapper.insert(plan);
        return plan;
    }

    private JsonNode readJson(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
