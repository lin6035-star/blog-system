package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.AiWorkflowStepLog;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningPlanDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.mapper.AiWorkflowStepLogMapper;
import com.hailin.blogsystem.mapper.LearningPlanMapper;
import com.hailin.blogsystem.mapper.LearningStageMapper;
import com.hailin.blogsystem.service.AiWorkflowRunService;
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
 * LEARNING_PLAN（第三个 Workflow）链路测试。
 * 和 AiWorkflowRunServiceTests 一样走真实 LLM（用例 2/3/4/5 有 LLM 调用，1/6 纯规则）。
 * 用户 102 与现有测试类（101）数据隔离。
 */
@SpringBootTest
class LearningPlanWorkflowServiceTests {

    private static final Long TEST_USER = 102L;

    @Autowired
    private AiWorkflowRunService aiWorkflowRunService;

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
        session.setTitle("LearningPlan Test Session");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        aiSessionMapper.insert(session);
        return session;
    }

    private AiWorkflowLearningPlanDTO dto(AiSessions session, String goal) {
        AiWorkflowLearningPlanDTO dto = new AiWorkflowLearningPlanDTO();
        dto.setConversationId(session.getId());
        dto.setGoal(goal);
        return dto;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contextOf(AiWorkflowRunVO vo) {
        return (Map<String, Object>) vo.getContext();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planOf(AiWorkflowRunVO vo) {
        Map<String, Object> stepResults = (Map<String, Object>) contextOf(vo).get("stepResults");
        return (Map<String, Object>) stepResults.get("plan");
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

    //1. CTA 弱模式：无规划诉求 → 停 WAITING_REQUIREMENT_CONFIRM（纯规则，无 LLM）
    @Test
    void weakModeStopsAtRequirementConfirm() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningPlanWorkflow(
                dto(session, "我想要学习Python，但是不知道怎么开始"));

        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) contextOf(created).get("clarification");
        assertThat(clarification.get("required")).isEqualTo(true);
        assertThat(String.valueOf(clarification.get("question"))).isNotBlank();
    }

    //2. 强模式：明确规划诉求 → 直接生成计划到确认（真实 LLM）
    @Test
    void strongModeGoesDirectlyToPlanConfirm() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningPlanWorkflow(
                dto(session, "帮我制定Java学习路线，零基础，三个月入门"));

        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM.name());
        Map<String, Object> plan = planOf(created);
        assertThat(plan).isNotNull();
        assertThat(plan.get("stages")).isInstanceOf(List.class);
        assertThat((List<?>) plan.get("stages")).isNotEmpty();
    }

    //3. 弱模式补充信息后生成计划（真实 LLM）
    @Test
    void rejectWithSupplementGeneratesPlan() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningPlanWorkflow(
                dto(session, "我想学Redis"));
        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());

        AiWorkflowRunVO advanced = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()), "零基础，三个月入门");

        assertThat(advanced.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM.name());
        assertThat(planOf(advanced)).isNotNull();
    }

    //4. approve → SAVE_PLAN 落库 → COMPLETED
    @Test
    void approveSavesPlanToDatabase() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningPlanWorkflow(
                dto(session, "帮我制定Java学习路线，零基础，三个月入门"));
        Long runId = Long.valueOf(created.getId());

        AiWorkflowRunVO approved = aiWorkflowRunService.approve(runId);
        assertThat(approved.getStatus()).isEqualTo(AiWorkflowStatus.COMPLETED.name());

        List<LearningPlans> plans = learningPlanMapper.selectList(
                new LambdaQueryWrapper<LearningPlans>().eq(LearningPlans::getSourceWorkflowRunId, runId));
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).getUserId()).isEqualTo(TEST_USER);

        List<LearningStages> stages = learningStageMapper.selectList(
                new LambdaQueryWrapper<LearningStages>().eq(LearningStages::getPlanId, plans.get(0).getId()));
        assertThat(stages).isNotEmpty();
    }

    //5. SAVE_PLAN 幂等：FAILED 后 retry 重跑保存，不产生第二条计划
    @Test
    void savePlanIsIdempotentOnRetry() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningPlanWorkflow(
                dto(session, "帮我制定Java学习路线，零基础，三个月入门"));
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
                new LambdaQueryWrapper<LearningPlans>().eq(LearningPlans::getSourceWorkflowRunId, runId));
        assertThat(plans).hasSize(1);
    }

    //6. 否定反馈 → CANCELLED + 清 session 绑定（纯规则，无 LLM）
    @Test
    void negativeFeedbackCancelsAndClearsSessionBinding() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningPlanWorkflow(
                dto(session, "我想学Redis"));
        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());

        //创建成功路径已绑定 session
        AiSessions bound = aiSessionMapper.selectById(session.getId());
        assertThat(bound.getActiveWorkflowRunId()).isEqualTo(Long.valueOf(created.getId()));

        AiWorkflowRunVO rejected = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()), "算了不学了");

        assertThat(rejected.getStatus()).isEqualTo(AiWorkflowStatus.CANCELLED.name());

        AiSessions cleared = aiSessionMapper.selectById(session.getId());
        assertThat(cleared.getActiveWorkflowRunId()).isNull();
    }
}
