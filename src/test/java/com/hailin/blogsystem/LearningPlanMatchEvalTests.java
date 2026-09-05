package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.mapper.LearningPlanMapper;
import com.hailin.blogsystem.mapper.LearningStageMapper;
import com.hailin.blogsystem.service.LearningPlansService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 学习计划匹配评测（纯规则无 LLM）：
 * 评测集 = learning-plan-match-evaluation-cases.json 驱动的 14 个匹配样例
 * （PLAN_MATCH 入口点名 / PLAN_TITLE_MATCH 查询详情 / STAGE_MATCH 阶段定位），
 * 加样例 = 加 JSON 行；另有 3 个 appendTasks 行为测试保持普通 Java 测试
 * （写操作 + 多步动作序列不适合声明式 JSON）。
 * 用户 107 与旧测试类（106）数据隔离。
 */
@SpringBootTest
@Tag("eval")
class LearningPlanMatchEvalTests {

    private static final Long TEST_USER = 107L;

    @Autowired
    private LearningPlansService learningPlansService;

    @Autowired
    private LearningPlanMapper learningPlanMapper;

    @Autowired
    private LearningStageMapper learningStageMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // 评测集结构
    // ------------------------------------------------------------------

    record PlanFixture(String ref, String title, String status) {
    }

    record TaskFixture(String title) {
    }

    record StageFixture(String planRef, int orderNum, String title, List<TaskFixture> tasks) {
    }

    record Fixtures(List<PlanFixture> plans, List<StageFixture> stages) {
    }

    record Expected(Integer matchedCount, List<String> matchedTitles, String errorContains) {
    }

    record StageMatchEvalCase(
            String name,
            String category,
            String target,
            String message,
            Long callerUserId,
            Fixtures fixtures,
            Expected expected
    ) {
    }

    @AfterEach
    void cleanup() {
        List<LearningPlans> plans = learningPlanMapper.selectList(
                new LambdaQueryWrapper<LearningPlans>().eq(LearningPlans::getUserId, TEST_USER));
        if (!plans.isEmpty()) {
            List<Long> planIds = plans.stream().map(LearningPlans::getId).toList();
            learningStageMapper.delete(new LambdaQueryWrapper<LearningStages>()
                    .in(LearningStages::getPlanId, planIds));
        }
        learningPlanMapper.delete(new LambdaQueryWrapper<LearningPlans>()
                .eq(LearningPlans::getUserId, TEST_USER));
    }

    // ------------------------------------------------------------------
    // 评测集加载 + 结构校验（fail fast，报错带 case name）
    // ------------------------------------------------------------------

    static Stream<Arguments> evalCases() throws Exception {
        InputStream inputStream = LearningPlanMatchEvalTests.class
                .getResourceAsStream("/agent/learning-plan-match-evaluation-cases.json");

        assertThat(inputStream)
                .as("阶段定位评测集文件必须存在")
                .isNotNull();

        List<StageMatchEvalCase> cases = new ObjectMapper().readValue(
                Objects.requireNonNull(inputStream),
                new TypeReference<>() {
                }
        );

        assertThat(cases)
                .as("阶段定位评测集不能为空")
                .isNotEmpty();

        for (StageMatchEvalCase c : cases) {
            validateCase(c);
        }

        return cases.stream().map(c -> Arguments.of(c.name(), c));
    }

    private static void validateCase(StageMatchEvalCase c) {
        assertThat(c.name())
                .as("样例缺少 name")
                .isNotBlank();
        assertThat(c.target())
                .as("样例 %s 缺少 target", c.name())
                .isNotBlank();
        assertThat(c.category())
                .as("样例 %s 缺少 category", c.name())
                .isNotBlank();
        assertThat(c.message())
                .as("样例 %s 缺少 message", c.name())
                .isNotBlank();
        assertThat(c.fixtures())
                .as("样例 %s 缺少 fixtures", c.name())
                .isNotNull();
        assertThat(c.fixtures().plans())
                .as("样例 %s 缺少 plans", c.name())
                .isNotEmpty();
        assertThat(c.expected())
                .as("样例 %s 缺少 expected", c.name())
                .isNotNull();

        // category 与 target 必须配套
        String expectedCategory = switch (c.target()) {
            case "PLAN_MATCH" -> "plan-match";
            case "PLAN_TITLE_MATCH" -> "plan-title-match";
            case "STAGE_MATCH" -> "stage-match";
            default -> throw new AssertionError("样例 " + c.name() + " 的 target 非法: " + c.target());
        };
        assertThat(c.category())
                .as("样例 %s：category 与 target 不配套", c.name())
                .isEqualTo(expectedCategory);

        // STAGE_MATCH 需要一个且仅一个 fixture plan（服务签名按 planId 查）
        if ("STAGE_MATCH".equals(c.target())) {
            assertThat(c.fixtures().plans())
                    .as("样例 %s：STAGE_MATCH 恰好需要一个 fixture plan", c.name())
                    .hasSize(1);
        }

        // stages 的 planRef 必须解析到本 case 的 plan ref；orderNum 不许重复
        if (c.fixtures().stages() != null) {
            Set<String> refs = c.fixtures().plans().stream().map(PlanFixture::ref).collect(java.util.stream.Collectors.toSet());
            Set<Integer> orderNums = new java.util.HashSet<>();
            for (StageFixture s : c.fixtures().stages()) {
                assertThat(refs)
                        .as("样例 %s：stages[%s].planRef 悬空", c.name(), s.title())
                        .contains(s.planRef());
                assertThat(orderNums.add(s.orderNum()))
                        .as("样例 %s：orderNum %s 重复", c.name(), s.orderNum())
                        .isTrue();
            }
        }

        // errorContains 与 matchedCount 互斥
        if (c.expected().errorContains() != null) {
            assertThat(c.expected().matchedCount())
                    .as("样例 %s：errorContains 与 matchedCount 互斥", c.name())
                    .isNull();
        }
    }

    // ------------------------------------------------------------------
    // 参数化评测
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("evalCases")
    void matchFixedEvalCase(String caseName, StageMatchEvalCase c) {
        Map<String, Long> refToId = insertFixtures(c.fixtures());
        Long callerUserId = c.callerUserId() != null ? c.callerUserId() : TEST_USER;

        switch (c.target()) {
            case "PLAN_MATCH" -> {
                List<LearningPlans> matched = learningPlansService
                        .matchActivePlansByMessage(callerUserId, c.message());
                assertMatchResult(caseName, c.expected(),
                        matched.size(),
                        matched.stream().map(LearningPlans::getTitle).toList());
            }
            case "PLAN_TITLE_MATCH" -> {
                List<LearningPlans> matched = learningPlansService
                        .matchPlansByTitle(callerUserId, c.message());
                assertMatchResult(caseName, c.expected(),
                        matched.size(),
                        matched.stream().map(LearningPlans::getTitle).toList());
            }
            case "STAGE_MATCH" -> {
                Long planId = refToId.values().iterator().next();
                if (c.expected().errorContains() != null) {
                    assertThatThrownBy(() -> learningPlansService
                            .matchStagesByMessage(planId, callerUserId, c.message()))
                            .as("用例 %s：应抛异常", caseName)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining(c.expected().errorContains());
                    return;
                }
                List<LearningStages> matched = learningPlansService
                        .matchStagesByMessage(planId, callerUserId, c.message());
                assertMatchResult(caseName, c.expected(),
                        matched.size(),
                        matched.stream().map(LearningStages::getTitle).toList());
            }
            default -> throw new AssertionError("用例 " + caseName + " target 非法: " + c.target());
        }
    }

    private void assertMatchResult(String caseName, Expected expected,
                                   int matchedCount, List<String> matchedTitles) {
        assertThat(matchedCount)
                .as("用例 %s：命中数量", caseName)
                .isEqualTo(expected.matchedCount());

        if (expected.matchedTitles() != null) {
            assertThat(matchedTitles)
                    .as("用例 %s：命中标题", caseName)
                    .containsExactlyElementsOf(expected.matchedTitles());
        }
    }

    // ------------------------------------------------------------------
    // fixture 插入
    // ------------------------------------------------------------------

    private Map<String, Long> insertFixtures(Fixtures fixtures) {
        Map<String, Long> refToId = new HashMap<>();
        for (PlanFixture p : fixtures.plans()) {
            refToId.put(p.ref(), insertPlan(p.title(), p.status()).getId());
        }
        if (fixtures.stages() != null) {
            for (StageFixture s : fixtures.stages()) {
                insertStage(refToId.get(s.planRef()), s.orderNum(), s.title(), toTasksJson(s.tasks()));
            }
        }
        return refToId;
    }

    private String toTasksJson(List<TaskFixture> tasks) {
        try {
            return objectMapper.writeValueAsString(
                    tasks.stream().map(t -> Map.of("title", t.title())).toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private LearningPlans insertPlan(String title, String status) {
        LearningPlans plan = new LearningPlans();
        plan.setUserId(TEST_USER);
        plan.setTitle(title);
        plan.setGoal("Java 后端开发");
        plan.setStatus(status);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        learningPlanMapper.insert(plan);
        return plan;
    }

    private LearningStages insertStage(Long planId, int orderNum, String title, String tasksJson) {
        LearningStages stage = new LearningStages();
        stage.setPlanId(planId);
        stage.setOrderNum(orderNum);
        stage.setTitle(title);
        stage.setTasks(tasksJson);
        stage.setCreatedAt(LocalDateTime.now());
        stage.setUpdatedAt(LocalDateTime.now());
        learningStageMapper.insert(stage);
        return stage;
    }

    // ------------------------------------------------------------------
    // appendTasks 行为测试（保持普通 Java 测试，不 JSON 化：
    // 写操作 + 多步动作序列 + 结构性读回校验不适合声明式样例）
    // ------------------------------------------------------------------

    //1. appendTasks：过滤与已有任务重复 + 输入内去重 + 空标题，新任务 done=false
    @Test
    void appendTasksDeduplicatesAndAppends() {
        LearningPlans plan = insertPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        LearningStages stage = insertStage(plan.getId(), 1, "基础阶段",
                "[{\"title\":\"数据结构\",\"done\":true}]");

        learningPlansService.appendTasks(plan.getId(), stage.getId(),
                List.of("数据结构", "缓存击穿前置", "缓存击穿前置", "", "互斥锁实现"), TEST_USER);

        //读回 tasks JSON 校验
        LearningStages reloaded = learningStageMapper.selectById(stage.getId());
        assertThat(reloaded.getTasks())
                .contains("\"缓存击穿前置\"")
                .contains("\"互斥锁实现\"")
                .contains("\"数据结构\"");  //原任务保留
        List<LearningPlansDetailVO.TaskItem> parsed = parseForTest(reloaded.getTasks());
        assertThat(parsed).hasSize(3);  //原 1 + 去重后新 2
        assertThat(parsed.get(0).isDone()).isTrue();  //原任务 done 状态不变
        assertThat(parsed.get(1).isDone()).isFalse();
        assertThat(parsed.get(1).getTitle()).isEqualTo("缓存击穿前置");
    }

    //2. appendTasks 幂等：同标题再调一次 → 不再追加（retry 重跑安全）
    @Test
    void appendTasksIsIdempotentOnRepeatedCall() {
        LearningPlans plan = insertPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        LearningStages stage = insertStage(plan.getId(), 1, "基础阶段", "[]");

        learningPlansService.appendTasks(plan.getId(), stage.getId(),
                List.of("缓存击穿前置", "互斥锁实现"), TEST_USER);
        learningPlansService.appendTasks(plan.getId(), stage.getId(),
                List.of("缓存击穿前置", "互斥锁实现"), TEST_USER);

        LearningStages reloaded = learningStageMapper.selectById(stage.getId());
        assertThat(parseForTest(reloaded.getTasks())).hasSize(2);
    }

    //3. appendTasks：跨用户拒绝（归属校验）
    @Test
    void appendTasksRejectsOtherUsersPlan() {
        LearningPlans plan = insertPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        LearningStages stage = insertStage(plan.getId(), 1, "基础阶段", "[]");

        assertThatThrownBy(() -> learningPlansService.appendTasks(
                plan.getId(), stage.getId(), List.of("新任务"), 999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("学习计划不存在或无权访问");
    }

    //测试内解析 tasks JSON → TaskItem 列表
    private List<LearningPlansDetailVO.TaskItem> parseForTest(String tasksJson) {
        try {
            List<Map<String, Object>> raw = new ObjectMapper().readValue(tasksJson, new TypeReference<>() {});
            List<LearningPlansDetailVO.TaskItem> tasks = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                LearningPlansDetailVO.TaskItem task = new LearningPlansDetailVO.TaskItem();
                task.setTitle(String.valueOf(item.getOrDefault("title", "")));
                task.setDone(Boolean.TRUE.equals(item.get("done")));
                tasks.add(task);
            }
            return tasks;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
