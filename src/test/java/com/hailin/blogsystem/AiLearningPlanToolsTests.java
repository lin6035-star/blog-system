package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.tool.AiLearningPlanTools;
import com.hailin.blogsystem.ai.tool.QueryLearningPlansTool;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
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
 * 学习计划查询 Tool（普通聊天 Tool Calling）测试。
 * 纯规则无 LLM 调用；用户 104/105 与现有测试类（101/102/103）数据隔离。
 */
@SpringBootTest
class AiLearningPlanToolsTests {

    private static final Long TEST_USER = 104L;
    private static final Long OTHER_USER = 105L;

    @Autowired
    private AiLearningPlanTools aiLearningPlanTools;

    @Autowired
    private QueryLearningPlansTool queryLearningPlansTool;

    @Autowired
    private LearningPlanMapper learningPlanMapper;

    @Autowired
    private LearningStageMapper learningStageMapper;

    @AfterEach
    void cleanup() {
        List<LearningPlans> plans = learningPlanMapper.selectList(
                new LambdaQueryWrapper<LearningPlans>()
                        .in(LearningPlans::getUserId, TEST_USER, OTHER_USER));
        if (!plans.isEmpty()) {
            List<Long> planIds = plans.stream().map(LearningPlans::getId).toList();
            learningStageMapper.delete(new LambdaQueryWrapper<LearningStages>()
                    .in(LearningStages::getPlanId, planIds));
        }
        learningPlanMapper.delete(new LambdaQueryWrapper<LearningPlans>()
                .in(LearningPlans::getUserId, TEST_USER, OTHER_USER));
        UserContext.clear();
    }

    //直接插库造一个计划（含 1 个阶段、2 个任务、1 个已完成）
    private LearningPlans createPlan(Long userId, String title, String status) {
        LearningPlans plan = new LearningPlans();
        plan.setUserId(userId);
        plan.setTitle(title);
        plan.setGoal("Java 后端开发");
        plan.setStatus(status);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        learningPlanMapper.insert(plan);

        LearningStages stage = new LearningStages();
        stage.setPlanId(plan.getId());
        stage.setOrderNum(1);
        stage.setTitle("基础阶段");
        stage.setTasks("[{\"title\":\"Java 语法\",\"done\":true},{\"title\":\"集合框架\",\"done\":false}]");
        stage.setCreatedAt(LocalDateTime.now());
        stage.setUpdatedAt(LocalDateTime.now());
        learningStageMapper.insert(stage);
        return plan;
    }

    //1. 无计划 → 返回空列表文案；call(null) 模拟 LLM 调用无参工具时的空 toolInput（Spring AI 空断言 bug 回归）
    @Test
    void noPlansReturnsEmptyMessage() {
        UserContext.set(TEST_USER);

        String result = queryLearningPlansTool.call(null);

        assertThat(result).contains("还没有任何学习计划");
    }

    //2. 列表返回概要：标题、进度、状态、阶段数；空字符串 / "{}" 输入同样照常执行
    @Test
    void listReturnsSummariesWithProgressAndStatus() {
        UserContext.set(TEST_USER);
        createPlan(TEST_USER, "Java 进阶计划", LearningPlans.STATUS_ACTIVE);
        createPlan(TEST_USER, "Redis 学习计划", LearningPlans.STATUS_COMPLETED);

        String result = queryLearningPlansTool.call("");

        assertThat(result).contains("共有 2 个学习计划");
        assertThat(result).contains("《Java 进阶计划》").contains("（进行中）");
        assertThat(result).contains("《Redis 学习计划》").contains("（已完成）");
        assertThat(result).contains("进度：1/2 项任务已完成");
        assertThat(result).contains("共 1 个阶段");

        assertThat(queryLearningPlansTool.call("{}")).contains("共有 2 个学习计划");
    }

    //3. 标题关键词返回完整阶段任务（目标、阶段标题、任务与 done 标记）
    @Test
    void detailReturnsStagesAndTasksByKeyword() {
        UserContext.set(TEST_USER);
        createPlan(TEST_USER, "Java 进阶计划", LearningPlans.STATUS_ACTIVE);

        String result = aiLearningPlanTools.queryLearningPlanDetail("Java", new ToolContext(Map.of("userId", TEST_USER)));

        assertThat(result).contains("《Java 进阶计划》");
        assertThat(result).contains("目标：Java 后端开发");
        assertThat(result).contains("阶段1：基础阶段");
        assertThat(result).contains("[x] 1. Java 语法");
        assertThat(result).contains("[ ] 2. 集合框架");
        assertThat(result).contains("总进度：1/2 项任务已完成");
    }

    //3b. 分词匹配：关键词省略标题中段也能命中（"RocketMQ学习计划" vs "RocketMQ 30天入门学习计划"）
    @Test
    void detailMatchesWhenKeywordSkipsMiddleSegment() {
        UserContext.set(TEST_USER);
        createPlan(TEST_USER, "RocketMQ 30天入门学习计划", LearningPlans.STATUS_ACTIVE);

        String result = aiLearningPlanTools.queryLearningPlanDetail("RocketMQ学习计划", new ToolContext(Map.of("userId", TEST_USER)));

        assertThat(result).contains("《RocketMQ 30天入门学习计划》");
        assertThat(result).contains("总进度：1/2 项任务已完成");
    }

    //4. 纯数字兼容路径：LLM 复制了计划ID → 按 ID 查详情
    @Test
    void detailReturnsByPlanId() {
        UserContext.set(TEST_USER);
        LearningPlans plan = createPlan(TEST_USER, "Java 进阶计划", LearningPlans.STATUS_ACTIVE);

        String result = aiLearningPlanTools.queryLearningPlanDetail(String.valueOf(plan.getId()), new ToolContext(Map.of("userId", TEST_USER)));

        assertThat(result).contains("《Java 进阶计划》");
        assertThat(result).contains("总进度：1/2 项任务已完成");
    }

    //5. 多个标题匹配 → 返回候选列表，不擅自代替用户选择
    @Test
    void detailReturnsCandidatesWhenMultipleMatches() {
        UserContext.set(TEST_USER);
        createPlan(TEST_USER, "Java 进阶计划", LearningPlans.STATUS_ACTIVE);
        createPlan(TEST_USER, "Java 基础复习", LearningPlans.STATUS_ACTIVE);

        String result = aiLearningPlanTools.queryLearningPlanDetail("Java", new ToolContext(Map.of("userId", TEST_USER)));

        assertThat(result).contains("找到多个标题匹配");
        assertThat(result).contains("《Java 进阶计划》").contains("《Java 基础复习》");
        assertThat(result).doesNotContain("阶段1");
    }

    //6. 越权：其他用户用标题关键词查不到本人的计划，且不泄露计划内容
    @Test
    void detailRejectsOtherUsersPlan() {
        UserContext.set(TEST_USER);
        createPlan(TEST_USER, "Java 进阶计划", LearningPlans.STATUS_ACTIVE);

        String result = aiLearningPlanTools.queryLearningPlanDetail("Java", new ToolContext(Map.of("userId", OTHER_USER)));

        assertThat(result).contains("没有标题包含");
        assertThat(result).doesNotContain("Java 进阶计划");
    }

    //7. 越权（ID 路径）：其他用户拿到 ID 也查不到，且不泄露计划内容
    @Test
    void detailRejectsOtherUsersPlanById() {
        UserContext.set(TEST_USER);
        LearningPlans plan = createPlan(TEST_USER, "Java 进阶计划", LearningPlans.STATUS_ACTIVE);

        String result = aiLearningPlanTools.queryLearningPlanDetail(String.valueOf(plan.getId()), new ToolContext(Map.of("userId", OTHER_USER)));

        assertThat(result).contains("不存在或无权访问");
        assertThat(result).doesNotContain("Java 进阶计划");
    }

    //8. 幻觉关键词 / 不存在的 ID → 友好文案，不抛异常
    @Test
    void detailRejectsUnknownRef() {
        UserContext.set(TEST_USER);

        assertThat(aiLearningPlanTools.queryLearningPlanDetail("不存在的主题", new ToolContext(Map.of("userId", TEST_USER))))
                .contains("没有标题包含");
        assertThat(aiLearningPlanTools.queryLearningPlanDetail("999999", new ToolContext(Map.of("userId", TEST_USER))))
                .contains("不存在或无权访问");
    }
}
