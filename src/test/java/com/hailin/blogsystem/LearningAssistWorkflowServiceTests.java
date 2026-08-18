package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.AiWorkflowStepLog;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningAssistDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.ai.workflow.LearningPlanFlowSupport;
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
 * LEARNING_ASSIST（第五个 Workflow，学习难点攻坚）链路测试。
 * 和 LearningProgressWorkflowServiceTests 一样走真实 LLM（用例 3/4/5/6 有 LLM 调用，其余纯规则）。
 * 用户 107 与现有测试类（101/102/103/106）数据隔离。
 */
@SpringBootTest
class LearningAssistWorkflowServiceTests {

    private static final Long TEST_USER = 107L;

    @Autowired
    private AiWorkflowRunService aiWorkflowRunService;

    @Autowired
    private LearningPlansService learningPlansService;

    @Autowired
    private LearningPlanFlowSupport learningPlanFlowSupport;

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
        session.setTitle("LearningAssist Test Session");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        aiSessionMapper.insert(session);
        return session;
    }

    //直接插库造一个 ACTIVE 计划：多阶段（默认 2 个），每阶段 2 个任务、1 个已完成
    private LearningPlans createActivePlan(String title, String... stageTitles) {
        LearningPlans plan = new LearningPlans();
        plan.setUserId(TEST_USER);
        plan.setTitle(title);
        plan.setGoal("Java 后端开发");
        plan.setStatus(LearningPlans.STATUS_ACTIVE);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        learningPlanMapper.insert(plan);

        String[] titles = stageTitles == null || stageTitles.length == 0
                ? new String[]{"基础阶段", "进阶阶段"} : stageTitles;
        int order = 1;
        for (String stageTitle : titles) {
            LearningStages stage = new LearningStages();
            stage.setPlanId(plan.getId());
            stage.setOrderNum(order++);
            stage.setTitle(stageTitle);
            stage.setTasks("[{\"title\":\"" + stageTitle + "任务一\",\"done\":true},{\"title\":\"" + stageTitle + "任务二\",\"done\":false}]");
            stage.setCreatedAt(LocalDateTime.now());
            stage.setUpdatedAt(LocalDateTime.now());
            learningStageMapper.insert(stage);
        }
        return plan;
    }

    private AiWorkflowLearningAssistDTO dto(AiSessions session, LearningPlans plan, String request) {
        AiWorkflowLearningAssistDTO dto = new AiWorkflowLearningAssistDTO();
        dto.setConversationId(session.getId());
        dto.setPlanId(plan.getId());
        dto.setRequest(request);
        return dto;
    }

    //入口点名命中多个计划时的 DTO：planId 空 + 候选列表
    private AiWorkflowLearningAssistDTO dtoWithCandidates(AiSessions session, List<LearningPlans> plans, String request) {
        AiWorkflowLearningAssistDTO dto = new AiWorkflowLearningAssistDTO();
        dto.setConversationId(session.getId());
        dto.setPlanId(null);
        dto.setRequest(request);
        dto.setCandidates(plans.stream()
                .map(plan -> new AiWorkflowLearningAssistDTO.Candidate(plan.getId(), plan.getTitle()))
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

    //1. 多计划候选 → 选计划停确认，卡片列全部候选（纯规则，无 LLM）
    @Test
    void planCandidatesStopForSelection() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans javaPlan = createActivePlan("Java 学习计划");
        LearningPlans redisPlan = createActivePlan("Redis 学习计划");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningAssistWorkflow(
                dtoWithCandidates(session, List.of(javaPlan, redisPlan), "Redis 这块看不懂"));

        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        Map<String, Object> context = contextOf(created);
        assertThat(context.get("awaitingPlanSelection")).isEqualTo(true);
        assertThat(context.get("stageCatalog")).isNull();  //还没加载计划
        @SuppressWarnings("unchecked")
        Map<String, Object> confirmation = (Map<String, Object>) context.get("confirmation");
        assertThat(confirmation.get("type")).isEqualTo("REQUIREMENT");
        String question = String.valueOf(confirmation.get("question"));
        assertThat(question).contains("《Java 学习计划》").contains("《Redis 学习计划》");
    }

    //2. 计划内多阶段且消息打不上分 → 选阶段停确认，卡片列全部阶段（纯规则，无 LLM）
    @Test
    void stageMatchFailureStopsForStageSelection() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Redis 学习计划", "基础阶段", "进阶阶段");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningAssistWorkflow(
                dto(session, plan, "就是看不懂啊"));

        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        Map<String, Object> context = contextOf(created);
        assertThat(context.get("awaitingStageSelection")).isEqualTo(true);
        assertThat(context.get("oldPlan")).isNull();  //攻坚不写 oldPlan 键
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> catalog = (List<Map<String, Object>>) context.get("stageCatalog");
        assertThat(catalog).hasSize(2);
        assertThat(catalog.get(0).get("id")).isNotNull();  //阶段带 id 供 APPEND_TASKS 定位
        @SuppressWarnings("unchecked")
        Map<String, Object> confirmation = (Map<String, Object>) context.get("confirmation");
        String question = String.valueOf(confirmation.get("question"));
        assertThat(question).contains("《基础阶段》").contains("《进阶阶段》");
    }

    //3. 否定反馈 → CANCELLED（纯规则，无 LLM）
    @Test
    void negativeFeedbackCancelsWorkflow() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Redis 学习计划", "基础阶段", "进阶阶段");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningAssistWorkflow(
                dto(session, plan, "就是看不懂啊"));
        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());

        AiWorkflowRunVO rejected = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()), "算了，不学了");

        assertThat(rejected.getStatus()).isEqualTo(AiWorkflowStatus.CANCELLED.name());
    }

    //4. 选计划反馈匹配不上 → 留在等待态，卡片文案明确提示（纯规则，无 LLM）
    @Test
    void unmatchedPlanFeedbackKeepsAsking() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans javaPlan = createActivePlan("Java 学习计划");
        LearningPlans redisPlan = createActivePlan("Redis 学习计划");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningAssistWorkflow(
                dtoWithCandidates(session, List.of(javaPlan, redisPlan), "这块看不懂"));

        AiWorkflowRunVO rejected = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()), "就是那个啊");

        assertThat(rejected.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        Map<String, Object> context = contextOf(rejected);
        assertThat(context.get("awaitingPlanSelection")).isEqualTo(true);  //还没选定
        @SuppressWarnings("unchecked")
        Map<String, Object> confirmation = (Map<String, Object>) context.get("confirmation");
        assertThat(String.valueOf(confirmation.get("question"))).contains("没听懂是哪个计划");
    }

    //5. 选阶段反馈匹配不上 → 留在等待态，卡片文案明确提示（纯规则，无 LLM）
    @Test
    void unmatchedStageFeedbackKeepsAsking() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Redis 学习计划", "基础阶段", "进阶阶段");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningAssistWorkflow(
                dto(session, plan, "就是看不懂啊"));

        AiWorkflowRunVO rejected = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()), "就是那个阶段啊");

        assertThat(rejected.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        Map<String, Object> context = contextOf(rejected);
        assertThat(context.get("awaitingStageSelection")).isEqualTo(true);  //还没选定
        @SuppressWarnings("unchecked")
        Map<String, Object> confirmation = (Map<String, Object>) context.get("confirmation");
        assertThat(String.valueOf(confirmation.get("question"))).contains("没听懂是哪个阶段");
    }

    //6. 选计划反馈（计划名）→ 确定目标计划后进入选阶段（纯规则：候选阶段追问不含 LLM）
    @Test
    void planSelectedByNameThenStageSelection() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans javaPlan = createActivePlan("Java 学习计划");
        LearningPlans redisPlan = createActivePlan("Redis 学习计划", "基础阶段", "进阶阶段");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningAssistWorkflow(
                dtoWithCandidates(session, List.of(javaPlan, redisPlan), "就是看不懂啊"));

        AiWorkflowRunVO rejected = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()), "Redis 那个");

        assertThat(rejected.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        Map<String, Object> context = contextOf(rejected);
        assertThat(context.get("targetPlanId")).isEqualTo(redisPlan.getId());  //选中的是 Redis 计划
        assertThat(context.get("awaitingPlanSelection")).isNull();  //选计划标记已清除
        assertThat(context.get("awaitingStageSelection")).isEqualTo(true);  //进入选阶段
        assertThat(context.get("planTitle")).isEqualTo("Redis 学习计划");
    }

    //7. 选阶段反馈（序号"2"）→ 确定目标阶段后继续生成到确认（真实 LLM）
    @Test
    void stageSelectedByIndexThenGenerates() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Redis 学习计划", "基础阶段", "进阶阶段");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningAssistWorkflow(
                dto(session, plan, "缓存击穿这块看不懂"));

        AiWorkflowRunVO rejected = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()), "第二个");

        assertThat(rejected.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM.name());
        Map<String, Object> context = contextOf(rejected);
        assertThat(context.get("targetStageTitle")).isEqualTo("进阶阶段");
        @SuppressWarnings("unchecked")
        Map<String, Object> stepResults = (Map<String, Object>) context.get("stepResults");
        @SuppressWarnings("unchecked")
        Map<String, Object> generatedPlan = (Map<String, Object>) stepResults.get("plan");
        assertThat(generatedPlan).isNotNull();
        assertThat(generatedPlan.get("explanation")).isNotNull();  //讲解随结果返回
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) generatedPlan.get("stages");
        assertThat(stages.get(0).get("tasks")).isInstanceOf(List.class);
    }

    //8. 点名阶段完整生成：消息命中"进阶阶段"任务词 → 直接生成到确认（真实 LLM）
    @Test
    void stageMentionedInMessageGeneratesDirectly() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Redis 学习计划", "基础阶段", "进阶阶段");

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningAssistWorkflow(
                dto(session, plan, "进阶阶段任务一搞不明白"));

        assertThat(created.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM.name());
        Map<String, Object> context = contextOf(created);
        assertThat(context.get("targetStageTitle")).isEqualTo("进阶阶段");
        @SuppressWarnings("unchecked")
        Map<String, Object> stepResults = (Map<String, Object>) context.get("stepResults");
        @SuppressWarnings("unchecked")
        Map<String, Object> qualityCheck = (Map<String, Object>) stepResults.get("qualityCheck");
        assertThat(qualityCheck.get("passed"))
                .as("质量检查未通过: %s", qualityCheck.get("issues"))
                .isEqualTo(true);  //质量检查通过（3-5 任务且无重复）
    }

    //8.1 LLM 多给任务时，后端确定性收口为 5 个，避免真实模型波动把 7 个任务漏进确认卡片
    @Test
    void breakdownOverflowTasksAreTrimmedBeforeQualityCheck() {
        Map<String, Object> plan = Map.of(
                "title", "缓存击穿攻坚",
                "explanation", "把缓存击穿拆成可执行练习。",
                "stages", List.of(Map.of(
                        "title", "新增任务点",
                        "tasks", List.of(
                                "复习热点 key 与过期时间",
                                "画出缓存击穿请求链路",
                                "实现互斥锁保护缓存重建",
                                "补充锁释放异常场景",
                                "压测同一热点 key",
                                "整理缓存击穿面试讲法",
                                "总结项目里的落点"
                        )
                ))
        );

        Map<String, Object> normalized = learningPlanFlowSupport.normalizeBreakdownPlan(plan, List.of());
        Map<String, Object> qualityCheck = learningPlanFlowSupport.buildBreakdownQualityCheck(normalized, List.of());

        assertThat(qualityCheck.get("passed")).isEqualTo(true);
        assertThat(learningPlanFlowSupport.extractTaskTitles(normalized))
                .containsExactly(
                        "复习热点 key 与过期时间",
                        "画出缓存击穿请求链路",
                        "实现互斥锁保护缓存重建",
                        "补充锁释放异常场景",
                        "压测同一热点 key"
                );
    }

    //9. approve → 任务点追加到目标阶段：其他阶段不动、新任务 done=false（真实 LLM）
    @Test
    void approveAppendsTasksToTargetStageOnly() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Redis 学习计划", "基础阶段", "进阶阶段");
        Long planId = plan.getId();

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningAssistWorkflow(
                dto(session, plan, "进阶阶段任务一搞不明白"));
        Long runId = Long.valueOf(created.getId());

        LearningPlansDetailVO before = learningPlansService.getDetail(planId, TEST_USER);
        int beforeStage2Tasks = before.getStages().get(1).getTasks().size();
        Long stage2Id = before.getStages().get(1).getId();

        AiWorkflowRunVO approved = aiWorkflowRunService.approve(runId);
        assertThat(approved.getStatus()).isEqualTo(AiWorkflowStatus.COMPLETED.name());

        LearningPlansDetailVO after = learningPlansService.getDetail(planId, TEST_USER);
        assertThat(after.getStages()).hasSize(2);  //计划阶段数不变
        assertThat(after.getStages().get(0).getTasks()).hasSize(2);  //基础阶段不动
        assertThat(after.getStages().get(1).getId()).isEqualTo(stage2Id);  //同一阶段行
        assertThat(after.getStages().get(1).getTasks().size()).isGreaterThan(beforeStage2Tasks);  //追加了任务
        //新追加的任务都是未完成状态
        for (int i = beforeStage2Tasks; i < after.getStages().get(1).getTasks().size(); i++) {
            assertThat(after.getStages().get(1).getTasks().get(i).isDone()).isFalse();
        }
    }

    //10. approve 后手动置 FAILED → retry 重跑 APPEND_TASKS 不重复追加（去重幂等，真实 LLM 生成 + 纯规则追加）
    @Test
    void retryAppendTasksDoesNotDuplicate() {
        UserContext.set(TEST_USER);
        AiSessions session = createTestSession();
        LearningPlans plan = createActivePlan("Redis 学习计划", "基础阶段", "进阶阶段");
        Long planId = plan.getId();

        AiWorkflowRunVO created = aiWorkflowRunService.createLearningAssistWorkflow(
                dto(session, plan, "进阶阶段任务一搞不明白"));
        Long runId = Long.valueOf(created.getId());
        aiWorkflowRunService.approve(runId);

        int afterApprove = learningPlansService.getDetail(planId, TEST_USER).getStages().get(1).getTasks().size();

        //模拟"追加之后失败"：手动改 FAILED（current_step 已是 APPEND_TASKS），再 retry
        AiWorkflowRun run = aiWorkflowRunMapper.selectById(runId);
        run.setStatus(AiWorkflowStatus.FAILED.name());
        run.setErrorMessage("模拟追加后失败");
        aiWorkflowRunMapper.updateById(run);

        AiWorkflowRunVO retried = aiWorkflowRunService.retry(runId);
        assertThat(retried.getStatus()).isEqualTo(AiWorkflowStatus.COMPLETED.name());

        assertThat(learningPlansService.getDetail(planId, TEST_USER).getStages().get(1).getTasks().size())
                .isEqualTo(afterApprove);  //去重后任务数不变
    }
}
