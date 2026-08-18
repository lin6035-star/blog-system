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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 学习计划匹配逻辑测试（纯规则无 LLM）：
 * matchActivePlansByMessage = 入口点名（打分取最高并列），matchPlansByTitle = 查询 Tool 详情（AND 全命中）。
 * 用户 106 与现有测试类数据隔离。
 */
@SpringBootTest
class LearningPlanMatchServiceTests {

    private static final Long TEST_USER = 106L;

    @Autowired
    private LearningPlansService learningPlansService;

    @Autowired
    private LearningPlanMapper learningPlanMapper;

    @Autowired
    private LearningStageMapper learningStageMapper;

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

    private LearningPlans createPlan(String title, String status) {
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

    private LearningStages createStage(Long planId, int orderNum, String title, String tasksJson) {
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

    //1. 唯一最高分 = 点名成功（"Redis"指名词让 Redis 计划得分高于其他）
    @Test
    void uniqueTopScoreWins() {
        createPlan("Java 学习计划", LearningPlans.STATUS_ACTIVE);
        createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);

        List<LearningPlans> matched = learningPlansService.matchActivePlansByMessage(
                TEST_USER, "帮我压缩一下我的 Redis 计划的周期");

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).getTitle()).isEqualTo("Redis 学习计划");
    }

    //2. 并列最高分 = 歧义（未点名的泛词让多个计划同分）
    @Test
    void tieReturnsAllTopPlans() {
        createPlan("Java 学习计划", LearningPlans.STATUS_ACTIVE);
        createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);

        List<LearningPlans> matched = learningPlansService.matchActivePlansByMessage(
                TEST_USER, "帮我压缩学习计划的周期");

        assertThat(matched).hasSize(2);
    }

    //3. 非 ACTIVE 计划不参与入口点名
    @Test
    void nonActivePlansIgnored() {
        createPlan("Redis 学习计划", LearningPlans.STATUS_COMPLETED);

        List<LearningPlans> matched = learningPlansService.matchActivePlansByMessage(
                TEST_USER, "压缩 Redis 计划的周期");

        assertThat(matched).isEmpty();
    }

    //4. 零命中（无实词指向任何计划标题）→ 空
    @Test
    void noHitReturnsEmpty() {
        createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);

        List<LearningPlans> matched = learningPlansService.matchActivePlansByMessage(
                TEST_USER, "调整一下");

        assertThat(matched).isEmpty();
    }

    //5. 中文点名靠 2-gram 命中（"机器学习" 拆成 机器/学习 等词段命中标题）
    @Test
    void chineseMentionMatchesByBigram() {
        createPlan("Java 学习计划", LearningPlans.STATUS_ACTIVE);
        createPlan("机器学习入门计划", LearningPlans.STATUS_ACTIVE);

        List<LearningPlans> matched = learningPlansService.matchActivePlansByMessage(
                TEST_USER, "压缩机器学习那个计划");

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).getTitle()).isEqualTo("机器学习入门计划");
    }

    //6. 标题关键词 AND 匹配：省略标题中段也能命中（查询 Tool 详情路径）
    @Test
    void titleMatchSkipsMiddleSegment() {
        createPlan("RocketMQ 30天入门学习计划", LearningPlans.STATUS_ACTIVE);

        List<LearningPlans> matched = learningPlansService.matchPlansByTitle(
                TEST_USER, "RocketMQ学习计划");

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).getTitle()).isEqualTo("RocketMQ 30天入门学习计划");
    }

    //7. 阶段点名：消息命中某任务标题 → 定位到该任务所在阶段
    @Test
    void stageMatchHitsByTaskTitle() {
        LearningPlans plan = createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        createStage(plan.getId(), 1, "基础阶段", "[{\"title\":\"Redis 数据结构\",\"done\":false}]");
        createStage(plan.getId(), 2, "进阶阶段", "[{\"title\":\"缓存击穿\",\"done\":false}]");

        List<LearningStages> matched = learningPlansService.matchStagesByMessage(
                plan.getId(), TEST_USER, "缓存击穿看不懂");

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).getTitle()).isEqualTo("进阶阶段");
    }

    //8. 阶段点名：消息命中阶段标题直接定位
    @Test
    void stageMatchHitsByStageTitle() {
        LearningPlans plan = createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        createStage(plan.getId(), 1, "基础阶段", "[{\"title\":\"Redis 数据结构\",\"done\":false}]");
        createStage(plan.getId(), 2, "进阶阶段", "[{\"title\":\"缓存击穿\",\"done\":false}]");

        List<LearningStages> matched = learningPlansService.matchStagesByMessage(
                plan.getId(), TEST_USER, "基础阶段学不会");

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).getTitle()).isEqualTo("基础阶段");
    }

    //9. 阶段点名：消息词段在多个阶段同分 → 并列歧义（列候选追问）
    @Test
    void stageMatchTieReturnsAllTopStages() {
        LearningPlans plan = createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        createStage(plan.getId(), 1, "Redis 基础", "[{\"title\":\"数据结构\",\"done\":false}]");
        createStage(plan.getId(), 2, "Redis 进阶", "[{\"title\":\"持久化\",\"done\":false}]");

        List<LearningStages> matched = learningPlansService.matchStagesByMessage(
                plan.getId(), TEST_USER, "Redis 这块看不懂");

        assertThat(matched).hasSize(2);
    }

    //10. 阶段点名：消息无词段命中任何阶段 → 空（入口列全部阶段追问）
    @Test
    void stageMatchNoHitReturnsEmpty() {
        LearningPlans plan = createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        createStage(plan.getId(), 1, "基础阶段", "[{\"title\":\"数据结构\",\"done\":false}]");

        List<LearningStages> matched = learningPlansService.matchStagesByMessage(
                plan.getId(), TEST_USER, "就是看不懂啊");

        assertThat(matched).isEmpty();
    }

    //11. 阶段点名：跨用户计划拒绝（归属校验）
    @Test
    void stageMatchRejectsOtherUsersPlan() {
        LearningPlans plan = createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        createStage(plan.getId(), 1, "基础阶段", "[{\"title\":\"数据结构\",\"done\":false}]");

        assertThatThrownBy(() -> learningPlansService.matchStagesByMessage(
                plan.getId(), 999L, "数据结构看不懂"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("学习计划不存在或无权访问");
    }

    //12. appendTasks：过滤与已有任务重复 + 输入内去重 + 空标题，新任务 done=false
    @Test
    void appendTasksDeduplicatesAndAppends() {
        LearningPlans plan = createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        LearningStages stage = createStage(plan.getId(), 1, "基础阶段",
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

    //13. appendTasks 幂等：同标题再调一次 → 不再追加（retry 重跑安全）
    @Test
    void appendTasksIsIdempotentOnRepeatedCall() {
        LearningPlans plan = createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        LearningStages stage = createStage(plan.getId(), 1, "基础阶段", "[]");

        learningPlansService.appendTasks(plan.getId(), stage.getId(),
                List.of("缓存击穿前置", "互斥锁实现"), TEST_USER);
        learningPlansService.appendTasks(plan.getId(), stage.getId(),
                List.of("缓存击穿前置", "互斥锁实现"), TEST_USER);

        LearningStages reloaded = learningStageMapper.selectById(stage.getId());
        assertThat(parseForTest(reloaded.getTasks())).hasSize(2);
    }

    //14. appendTasks：跨用户拒绝（归属校验）
    @Test
    void appendTasksRejectsOtherUsersPlan() {
        LearningPlans plan = createPlan("Redis 学习计划", LearningPlans.STATUS_ACTIVE);
        LearningStages stage = createStage(plan.getId(), 1, "基础阶段", "[]");

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
