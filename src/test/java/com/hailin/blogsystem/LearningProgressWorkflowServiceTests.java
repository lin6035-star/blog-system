package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.AiWorkflowStepLog;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningProgressDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.mapper.AiWorkflowStepLogMapper;
import com.hailin.blogsystem.mapper.LearningPlanMapper;
import com.hailin.blogsystem.mapper.LearningStageMapper;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import com.hailin.blogsystem.service.LearningPlansService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LEARNING_PROGRESS（第四个 Workflow）链路测试。
 * 和 LearningPlanWorkflowServiceTests 一样走真实 LLM（用例 2/3/5 有 LLM 调用，1/4/6 纯规则）。
 * 用户 103 与现有测试类（101/102）数据隔离。
 */
@SpringBootTest
class LearningProgressWorkflowServiceTests {

    private static final Long TEST_USER = 103L;

    @Autowired
    private AiWorkflowRunService aiWorkflowRunService;

    @Autowired
    private LearningPlansService learningPlansService;

    @Autowired
    private AiWorkflowRunMapper aiWorkflowRunMapper;

    @Autowired
    private AiWorkflowStepLogMapper aiWorkflowStepLogMapper;

    @Autowired
    private AiSessionMapper aiSessionMapper;

    @Autowired
    private LearningPlanMapper learningPlanMapper;

    @Autowired
    private LearningStageMapper learningStageMapper;

    private AiSessions createTestSession() {
        AiSessions session = new AiSessions();
        session.setUserId(TEST_USER);
        session.setTitle("LearningProgress Test Session");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        aiSessionMapper.insert(session);
        return session;
    }

    //直接插库造一个 ACTIVE 计划（含 1 个阶段、2 个任务、1 个已完成）
    private LearningPlans createActivePlan(String title) {
        LearningPlans plan = new LearningPlans();
        plan.setUserId(TEST_USER);
        plan.setTitle(title);
        plan.setGoal("Java 后端开发");
        plan.setStatus(LearningPlans.STATUS_ACTIVE);
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

    private AiWorkflowLearningProgressDTO dto(AiSessions session, LearningPlans plan, String request) {
        AiWorkflowLearningProgressDTO dto = new AiWorkflowLearningProgressDTO();
        dto.setConversationId(session.getId());
        dto.setPlanId(plan.getId());
        dto.setRequest(request);
        return dto;
    }

    //入口点名命中多个计划时的 DTO：planId 空 + 候选列表
    private AiWorkflowLearningProgressDTO dtoWithCandidates(AiSessions session, List<LearningPlans> plans, String request) {
        AiWorkflowLearningProgressDTO dto = new AiWorkflowLearningProgressDTO();
        dto.setConversationId(session.getId());
        dto.setPlanId(null);
        dto.setRequest(request);
        dto.setCandidates(plans.stream()
                .map(plan -> new AiWorkflowLearningProgressDTO.Candidate(plan.getId(), plan.getTitle()))
                .toList());
        return dto;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contextOf(AiWorkflowRunVO vo) {
        return (Map<String, Object>) vo.getContext();
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

        List<AiWorkflowRun> runs = aiWorkflowRunMapper.selectList(
                new LambdaQueryWrapper<AiWorkflowRun>().eq(AiWorkflowRun::getUserId, TEST_USER));
        List<Long> runIds = runs.stream().map(AiWorkflowRun::getId).toList();
        if (!runIds.isEmpty()) {
            aiWorkflowStepLogMapper.delete(new LambdaQueryWrapper<AiWorkflowStepLog>()
                    .in(AiWorkflowStepLog::getWorkflowRunId, runIds));
        }
        aiWorkflowRunMapper.delete(new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getUserId, TEST_USER));

        aiSessionMapper.delete(new LambdaQueryWrapper<AiSessions>()
                .eq(AiSessions::getUserId, TEST_USER));
        UserContext.clear();
    }

    //1. 调整诉求不清 → LOAD_PLAN 加载快照后停 WAITING_REQUIREMENT_CONFIRM（纯规则，无 LLM）
    @Test
    void unclearRequestStopsAtRequirementConfirmWithPlanLoaded() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Java 学习计划");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningProgressWorkflow(
                dto(session, plan, "帮我调整一下"));

        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        Map<String, Object> context = contextOf(created);
        @SuppressWarnings("unchecked")
        Map<String, Object> confirmation = (Map<String, Object>) context.get("confirmation");
        assertThat(confirmation.get("type")).isEqualTo("REQUIREMENT");
        assertThat(confirmation.get("step")).isEqualTo("ANALYZE_CHANGE");

        //旧计划快照已加载（含进度）
        @SuppressWarnings("unchecked")
        Map<String, Object> oldPlan = (Map<String, Object>) context.get("oldPlan");
        assertThat(oldPlan.get("title")).isEqualTo("Java 学习计划");
        assertThat(oldPlan.get("stages")).isInstanceOf(List.class);
    }

    //2. 强模式：明确调整诉求 → 直接生成计划到确认（真实 LLM）
    @Test
    void clearRequestGoesDirectlyToPlanConfirm() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Java 学习计划");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningProgressWorkflow(
                dto(session, plan, "后面的任务有点多，帮我压缩整体周期，去掉一些重复任务"));

        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> stepResults = (Map<String, Object>) contextOf(created).get("stepResults");
        @SuppressWarnings("unchecked")
        Map<String, Object> generatedPlan = (Map<String, Object>) stepResults.get("plan");
        assertThat(generatedPlan).isNotNull();
        assertThat(generatedPlan.get("stages")).isInstanceOf(List.class);
    }

    //3. approve → 按 planId 覆盖（不新建计划）→ COMPLETED
    @Test
    void approveOverwritesOriginalPlan() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Java 学习计划");
        Long planId = plan.getId();

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningProgressWorkflow(
                dto(session, plan, "后面的任务有点多，帮我压缩整体周期，去掉一些重复任务"));
        Long runId = Long.valueOf(created.getId());

        AiWorkflowRunVO approved = aiWorkflowRunService.approve(runId);
        assertThat(approved.getStatus()).isEqualTo(AiWorkflowStatus.COMPLETED.name());

        //仍是原计划（没有新建），source 记录为本次调整 run
        List<LearningPlans> plans = learningPlanMapper.selectList(
                new LambdaQueryWrapper<LearningPlans>().eq(LearningPlans::getUserId, TEST_USER));
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).getId()).isEqualTo(planId);
        assertThat(plans.get(0).getSourceWorkflowRunId()).isEqualTo(runId);
        //创建时间不被覆盖（H2 时间戳精度到微秒，忽略纳秒比较）
        assertThat(plans.get(0).getCreatedAt()).isEqualToIgnoringNanos(plan.getCreatedAt());
    }

    //4. 否定反馈 → CANCELLED（纯规则，无 LLM）
    @Test
    void negativeFeedbackCancelsWorkflow() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Java 学习计划");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningProgressWorkflow(
                dto(session, plan, "帮我调整一下"));
        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());

        AiWorkflowRunVO rejected = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()), "算了，不改了");

        assertThat(rejected.getStatus()).isEqualTo(AiWorkflowStatus.CANCELLED.name());
    }

    //5. SAVE_PLAN 幂等：FAILED 后 retry 重跑覆盖，计划仍一条且 id 不变
    @Test
    void savePlanOverwriteIsIdempotentOnRetry() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Java 学习计划");
        Long planId = plan.getId();

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningProgressWorkflow(
                dto(session, plan, "后面的任务有点多，帮我压缩整体周期，去掉一些重复任务"));
        Long runId = Long.valueOf(created.getId());

        aiWorkflowRunService.approve(runId);

        //模拟"保存之后失败"：手动改 FAILED（current_step 已是 SAVE_PLAN），再 retry
        AiWorkflowRun run = aiWorkflowRunMapper.selectById(runId);
        run.setStatus(AiWorkflowStatus.FAILED.name());
        run.setErrorMessage("模拟保存后失败");
        aiWorkflowRunMapper.updateById(run);

        AiWorkflowRunVO retried = aiWorkflowRunService.retry(runId);
        assertThat(retried.getStatus()).isEqualTo(AiWorkflowStatus.COMPLETED.name());

        List<LearningPlans> plans = learningPlanMapper.selectList(
                new LambdaQueryWrapper<LearningPlans>().eq(LearningPlans::getUserId, TEST_USER));
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).getId()).isEqualTo(planId);
    }

    //6. 任务勾选：updateTaskDone 写 done → 详情聚合进度变化（纯规则，无 LLM）
    @Test
    void updateTaskDoneChangesProgress() {
        UserContext.set(TEST_USER);
        LearningPlans plan = createActivePlan("Java 学习计划");

        LearningPlansDetailVO before = learningPlansService.getDetail(plan.getId(), TEST_USER);
        assertThat(before.getDoneTasks()).isEqualTo(1);  //初始：Java 语法已完成
        assertThat(before.getTotalTasks()).isEqualTo(2);
        LearningStages stage = learningStageMapper.selectList(
                new LambdaQueryWrapper<LearningStages>().eq(LearningStages::getPlanId, plan.getId())).get(0);

        //勾选第二个任务
        learningPlansService.updateTaskDone(plan.getId(), stage.getId(), 1, true, TEST_USER);
        LearningPlansDetailVO afterDone = learningPlansService.getDetail(plan.getId(), TEST_USER);
        assertThat(afterDone.getDoneTasks()).isEqualTo(2);
        assertThat(afterDone.getStages().get(0).getTasks().get(1).isDone()).isTrue();

        //取消第一个任务
        learningPlansService.updateTaskDone(plan.getId(), stage.getId(), 0, false, TEST_USER);
        LearningPlansDetailVO afterUndone = learningPlansService.getDetail(plan.getId(), TEST_USER);
        assertThat(afterUndone.getDoneTasks()).isEqualTo(1);
        assertThat(afterUndone.getStages().get(0).getTasks().get(0).isDone()).isFalse();
    }

    //7. 入口点名多计划歧义 → 选计划停确认（纯规则，无 LLM）
    @Test
    void ambiguousPlanMentionStopsForSelection() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans javaPlan = createActivePlan("Java 学习计划");
        LearningPlans redisPlan = createActivePlan("Redis 学习计划");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningProgressWorkflow(
                dtoWithCandidates(session, List.of(javaPlan, redisPlan), "帮我压缩整体周期"));

        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        Map<String, Object> context = contextOf(created);
        assertThat(context.get("awaitingPlanSelection")).isEqualTo(true);
        assertThat(context.get("oldPlan")).isNull();  //还没加载计划
        @SuppressWarnings("unchecked")
        Map<String, Object> confirmation = (Map<String, Object>) context.get("confirmation");
        assertThat(confirmation.get("type")).isEqualTo("REQUIREMENT");
        String question = String.valueOf(confirmation.get("question"));
        assertThat(question).contains("《Java 学习计划》").contains("《Redis 学习计划》");
    }

    //8. 选计划反馈（计划名）→ 确定目标计划并继续生成（真实 LLM）
    @Test
    void rejectSelectsPlanByNameAndGenerates() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans javaPlan = createActivePlan("Java 学习计划");
        LearningPlans redisPlan = createActivePlan("Redis 学习计划");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningProgressWorkflow(
                dtoWithCandidates(session, List.of(javaPlan, redisPlan),
                        "后面的任务有点多，帮我压缩整体周期，去掉一些重复任务"));
        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());

        AiWorkflowRunVO rejected = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()), "Redis 那个");

        assertThat(rejected.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM.name());
        Map<String, Object> context = contextOf(rejected);
        assertThat(context.get("targetPlanId")).isEqualTo(redisPlan.getId());  //选中的是 Redis 计划
        assertThat(context.get("awaitingPlanSelection")).isNull();  //选计划标记已清除
        @SuppressWarnings("unchecked")
        Map<String, Object> oldPlan = (Map<String, Object>) context.get("oldPlan");
        assertThat(oldPlan.get("title")).isEqualTo("Redis 学习计划");
    }

    //9. 选计划反馈匹配不上 → 留在等待态继续追问（纯规则，无 LLM）
    @Test
    void rejectWithUnmatchedFeedbackKeepsAsking() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans javaPlan = createActivePlan("Java 学习计划");
        LearningPlans redisPlan = createActivePlan("Redis 学习计划");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningProgressWorkflow(
                dtoWithCandidates(session, List.of(javaPlan, redisPlan), "帮我压缩整体周期"));

        AiWorkflowRunVO rejected = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()), "就是那个啊");

        assertThat(rejected.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        Map<String, Object> context = contextOf(rejected);
        assertThat(context.get("awaitingPlanSelection")).isEqualTo(true);  //还没选定
        assertThat(context.get("targetPlanId")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> confirmation = (Map<String, Object>) context.get("confirmation");
        //匹配不上 → 卡片文案更新为明确提示（序号越界/听不清不再被默默无视）
        assertThat(String.valueOf(confirmation.get("question"))).contains("没听懂是哪个计划");
    }
}
