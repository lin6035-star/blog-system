package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.AiWorkflowStepLog;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AiWorkflowCreateArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowStepLogVO;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.mapper.AiWorkflowStepLogMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import com.hailin.blogsystem.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AiWorkflowRunServiceTests {

    @Autowired
    private AiWorkflowRunService aiWorkflowRunService;

    @Autowired
    private AiWorkflowRunMapper aiWorkflowRunMapper;

    @Autowired
    private AiWorkflowStepLogMapper aiWorkflowStepLogMapper;

    @Autowired
    private AiSessionMapper aiSessionMapper;

    @Autowired
    private ArticlesMapper articlesMapper;

    private AiSessions createTestSession() {
        AiSessions session = new AiSessions();
        session.setUserId(101L);
        session.setTitle("Workflow Test Session");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        aiSessionMapper.insert(session);
        return session;
    }

    //日志的 startedAt 是"结束时刻-耗时"反推，毫秒级抖动会导致同一次操作的两条日志排序不稳定，断言按内容定位
    private AiWorkflowStepLogVO findLog(List<AiWorkflowStepLogVO> logs, String inputSummaryContains) {
        return logs.stream()
                .filter(l -> l.getInputSummary() != null && l.getInputSummary().contains(inputSummaryContains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到日志: " + inputSummaryContains));
    }

    @AfterEach
    void clearUserContext() {
        List<AiWorkflowRun> runs = aiWorkflowRunMapper.selectList(new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getUserId, 101L));

        List<Long> runIds = runs.stream()
                .map(AiWorkflowRun::getId)
                .toList();

        if (!runIds.isEmpty()) {
            aiWorkflowStepLogMapper.delete(new LambdaQueryWrapper<AiWorkflowStepLog>()
                    .in(AiWorkflowStepLog::getWorkflowRunId, runIds));
        }

        aiWorkflowRunMapper.delete(new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getUserId, 101L));

        UserContext.clear();
    }

    @Test
    void createArticleWorkflowBindsActiveWorkflowToSession() {
        UserContext.set(101L);

        AiSessions session = createTestSession();

        AiWorkflowCreateArticleDTO dto = new AiWorkflowCreateArticleDTO();
        dto.setConversationId(session.getId());
        dto.setRequirement("帮我写一篇 Redis 缓存博客");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleWorkflow(dto);

        AiSessions savedSession = aiSessionMapper.selectById(session.getId());

        assertThat(savedSession.getActiveWorkflowRunId())
                .isEqualTo(Long.valueOf(created.getId()));
    }

    @Test
    void createArticleWorkflowRejectsWhenSessionHasActiveWorkflow() {
        UserContext.set(101L);

        AiSessions session = createTestSession();

        AiWorkflowCreateArticleDTO firstDto = new AiWorkflowCreateArticleDTO();
        firstDto.setConversationId(session.getId());
        firstDto.setRequirement("帮我写一篇 Redis 缓存博客");

        AiWorkflowRunVO first = aiWorkflowRunService.createArticleWorkflow(firstDto);

        AiWorkflowCreateArticleDTO secondDto = new AiWorkflowCreateArticleDTO();
        secondDto.setConversationId(session.getId());
        secondDto.setRequirement("帮我写一篇 Kafka 消息队列博客");

        assertThatThrownBy(() -> aiWorkflowRunService.createArticleWorkflow(secondDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前会话还有未完成的 Workflow");

        AiSessions savedSession = aiSessionMapper.selectById(session.getId());
        assertThat(savedSession.getActiveWorkflowRunId())
                .isEqualTo(Long.valueOf(first.getId()));
    }

    @Test
    void createArticleWorkflowClearsFinishedActiveWorkflowAndCreatesNewOne() {
        UserContext.set(101L);

        AiSessions session = createTestSession();

        AiWorkflowRun finishedRun = new AiWorkflowRun();
        finishedRun.setUserId(101L);
        finishedRun.setConversationId(session.getId());
        finishedRun.setWorkflowType("CREATE_ARTICLE");
        finishedRun.setWorkflowVersion("1.0");
        finishedRun.setStatus(AiWorkflowStatus.COMPLETED.name());
        finishedRun.setCurrentStep("FILL_ARTICLE");
        finishedRun.setContextJson("{}");
        finishedRun.setCreatedAt(LocalDateTime.now());
        finishedRun.setUpdatedAt(LocalDateTime.now());
        aiWorkflowRunMapper.insert(finishedRun);

        session.setActiveWorkflowRunId(finishedRun.getId());
        aiSessionMapper.updateById(session);

        AiWorkflowCreateArticleDTO dto = new AiWorkflowCreateArticleDTO();
        dto.setConversationId(session.getId());
        dto.setRequirement("帮我写一篇 Redis 缓存博客");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleWorkflow(dto);

        AiSessions savedSession = aiSessionMapper.selectById(session.getId());
        assertThat(savedSession.getActiveWorkflowRunId())
                .isEqualTo(Long.valueOf(created.getId()));
    }

    @Test
    void retryRejectsNonFailedWorkflow() {
        UserContext.set(101L);

        AiWorkflowCreateArticleDTO dto = new AiWorkflowCreateArticleDTO();
        dto.setRequirement("帮我写一篇 Redis 缓存博客");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleWorkflow(dto);

        assertThatThrownBy(() -> aiWorkflowRunService.retry(Long.valueOf(created.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有失败的 Workflow 可以重试");
    }

    @Test
    void retryFailedGenerateDraftWorkflowReturnsToDraftConfirm() {
        UserContext.set(101L);

        AiWorkflowCreateArticleDTO dto = new AiWorkflowCreateArticleDTO();
        dto.setRequirement("帮我写一篇 Redis 缓存原理的博客");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleWorkflow(dto);

        AiWorkflowRun run = aiWorkflowRunMapper.selectById(Long.valueOf(created.getId()));
        run.setStatus(AiWorkflowStatus.FAILED.name());
        run.setCurrentStep("GENERATE_DRAFT");
        run.setErrorMessage("模拟草稿生成失败");
        aiWorkflowRunMapper.updateById(run);

        AiWorkflowRunVO retried = aiWorkflowRunService.retry(run.getId());

        assertThat(retried.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
        assertThat(retried.getCurrentStep()).isEqualTo("QUALITY_CHECK");
        assertThat(retried.getErrorMessage()).isNull();

        AiWorkflowRun saved = aiWorkflowRunMapper.selectById(run.getId());
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
        assertThat(saved.getCurrentStep()).isEqualTo("QUALITY_CHECK");
    }

    @Test
    void retryWorkflowRecordsStepLog() {
        UserContext.set(101L);

        AiWorkflowCreateArticleDTO dto = new AiWorkflowCreateArticleDTO();
        dto.setRequirement("帮我写一篇 Redis 缓存原理的博客");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleWorkflow(dto);

        AiWorkflowRun run = aiWorkflowRunMapper.selectById(Long.valueOf(created.getId()));
        run.setStatus(AiWorkflowStatus.FAILED.name());
        run.setCurrentStep("GENERATE_DRAFT");
        run.setErrorMessage("模拟草稿生成失败");
        aiWorkflowRunMapper.updateById(run);

        aiWorkflowRunService.retry(run.getId());

        List<AiWorkflowStepLogVO> logs = aiWorkflowRunService.listStepLogs(run.getId());

        //create（4 条初始步骤 + 操作级）+ retry（操作级 + 重新生成草稿步骤 + 质量检查步骤）
        assertThat(logs).hasSize(8);

        AiWorkflowStepLogVO retryLog = findLog(logs, "老板重试失败步骤");
        assertThat(retryLog.getStep()).isEqualTo("QUALITY_CHECK");
        assertThat(retryLog.getStatus()).isEqualTo("SUCCESS");
        assertThat(retryLog.getRetryCount()).isZero();
        assertThat(retryLog.getOutputSummary()).contains("等待确认草稿");

        //步骤级日志：每一步的真实耗时独立记录（retry 走"根据修改意见重写草稿"文案）
        AiWorkflowStepLogVO draftStepLog = findLog(logs, "正在根据修改意见重写草稿");
        assertThat(draftStepLog.getStep()).isEqualTo("GENERATE_DRAFT");
        assertThat(draftStepLog.getStatus()).isEqualTo("SUCCESS");

        AiWorkflowStepLogVO checkStepLog = findLog(logs, "正在执行质量检查");
        assertThat(checkStepLog.getStep()).isEqualTo("QUALITY_CHECK");
        assertThat(checkStepLog.getStatus()).isEqualTo("SUCCESS");
        assertThat(checkStepLog.getDurationMs()).isNotNull();
    }

    @Test
    void createArticleWorkflowRecordsStepLog() {
        UserContext.set(101L);

        AiWorkflowCreateArticleDTO dto = new AiWorkflowCreateArticleDTO();
        dto.setRequirement("帮我写一篇 Redis 缓存博客");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleWorkflow(dto);

        List<AiWorkflowStepLogVO> logs =
                aiWorkflowRunService.listStepLogs(Long.valueOf(created.getId()));

        //create 后 5 条：需求分析/记忆召回/检索/大纲 4 条步骤级 + 1 条操作级
        assertThat(logs).hasSize(5);

        //初始步骤的步骤级日志，执行详情从第 1 步开始完整
        AiWorkflowStepLogVO analyzeLog = findLog(logs, "正在分析写作需求");
        assertThat(analyzeLog.getStep()).isEqualTo("REQUIREMENT_ANALYZE");
        assertThat(analyzeLog.getStatus()).isEqualTo("SUCCESS");

        AiWorkflowStepLogVO memoryLog = findLog(logs, "正在读取写作偏好");
        assertThat(memoryLog.getStep()).isEqualTo("MEMORY_RETRIEVE");
        assertThat(memoryLog.getStatus()).isEqualTo("SUCCESS");

        AiWorkflowStepLogVO ragLog = findLog(logs, "正在检索站内相关文章");
        assertThat(ragLog.getStep()).isEqualTo("RAG_SEARCH");
        assertThat(ragLog.getStatus()).isEqualTo("SUCCESS");

        AiWorkflowStepLogVO stepLog = findLog(logs, "正在生成文章大纲");
        assertThat(stepLog.getWorkflowRunId()).isEqualTo(created.getId());
        assertThat(stepLog.getStep()).isEqualTo("GENERATE_OUTLINE");
        assertThat(stepLog.getStepOrder()).isEqualTo(4);
        assertThat(stepLog.getStatus()).isEqualTo("SUCCESS");
        assertThat(stepLog.getRetryCount()).isZero();
        assertThat(stepLog.getDurationMs()).isNotNull();
        assertThat(stepLog.getDurationMs()).isGreaterThanOrEqualTo(0L);

        AiWorkflowStepLogVO opLog = findLog(logs, "创建文章工作流");
        assertThat(opLog.getOutputSummary()).contains("等待确认大纲");
    }

    @Test
    void approveWorkflowRecordsNextStepLog() {
        UserContext.set(101L);

        AiWorkflowCreateArticleDTO dto = new AiWorkflowCreateArticleDTO();
        dto.setRequirement("帮我写一篇 Redis 缓存原理的博客");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleWorkflow(dto);
        AiWorkflowRunVO approved = aiWorkflowRunService.approve(Long.valueOf(created.getId()));

        List<AiWorkflowStepLogVO> logs =
                aiWorkflowRunService.listStepLogs(Long.valueOf(created.getId()));

        assertThat(approved.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
        //create（4 条初始步骤 + 操作级）+ approve（操作级 + 生成草稿步骤 + 质量检查步骤）
        //真实 LLM 输出不确定：草稿可能一次通过（8 条），也可能触发 auto-retry 多一轮（10 条）
        boolean autoRetried = logs.stream().anyMatch(l -> l.getRetryCount() == 1);
        if (autoRetried) {
            assertThat(logs).hasSize(10);
        } else {
            assertThat(logs).hasSize(8);
        }

        AiWorkflowStepLogVO createStepLog = findLog(logs, "正在生成文章大纲");
        assertThat(createStepLog.getStep()).isEqualTo("GENERATE_OUTLINE");
        assertThat(createStepLog.getRetryCount()).isZero();

        AiWorkflowStepLogVO createOpLog = findLog(logs, "创建文章工作流");
        assertThat(createOpLog.getStep()).isEqualTo("GENERATE_OUTLINE");

        AiWorkflowStepLogVO approveLog = findLog(logs, "老板确认当前步骤");
        assertThat(approveLog.getStep()).isEqualTo("QUALITY_CHECK");
        assertThat(approveLog.getStepOrder()).isEqualTo(6);
        assertThat(approveLog.getStatus()).isEqualTo("SUCCESS");
        assertThat(approveLog.getRetryCount()).isZero();
        assertThat(approveLog.getOutputSummary()).contains("等待确认草稿");

        //步骤级日志：生成草稿耗时独立记录在 GENERATE_DRAFT 名下，不再混进操作总耗时
        AiWorkflowStepLogVO draftStepLog = findLog(logs, "正在生成正文草稿");
        assertThat(draftStepLog.getStep()).isEqualTo("GENERATE_DRAFT");
        assertThat(draftStepLog.getStepOrder()).isEqualTo(5);
        assertThat(draftStepLog.getStatus()).isEqualTo("SUCCESS");
        assertThat(draftStepLog.getOutputSummary()).contains("步骤完成");
        assertThat(draftStepLog.getDurationMs()).isNotNull();

        AiWorkflowStepLogVO checkStepLog = findLog(logs, "正在执行质量检查");
        assertThat(checkStepLog.getStep()).isEqualTo("QUALITY_CHECK");
        assertThat(checkStepLog.getStatus()).isEqualTo("SUCCESS");

        if (autoRetried) {
            //auto-retry：第一次质量检查失败后，系统把问题喂回模型自动重写一次
            AiWorkflowStepLogVO retryDraftLog = findLog(logs, "草稿未通过质量检查，正在重新生成正文");
            assertThat(retryDraftLog.getStep()).isEqualTo("GENERATE_DRAFT");
            assertThat(retryDraftLog.getRetryCount()).isEqualTo(1);
            assertThat(retryDraftLog.getStatus()).isEqualTo("SUCCESS");

            AiWorkflowStepLogVO retryCheckLog = findLog(logs, "正在重新执行质量检查");
            assertThat(retryCheckLog.getStep()).isEqualTo("QUALITY_CHECK");
            assertThat(retryCheckLog.getRetryCount()).isEqualTo(1);
            assertThat(retryCheckLog.getStatus()).isEqualTo("SUCCESS");
        }
    }

    @Test
    void createsArticleWorkflowWithStructuredContext() {
        UserContext.set(101L);

        AiWorkflowCreateArticleDTO dto = new AiWorkflowCreateArticleDTO();
        dto.setRequirement("帮我写一篇 Redis 博客");

        AiWorkflowRunVO vo = aiWorkflowRunService.createArticleWorkflow(dto);

        assertThat(vo.getId()).isNotBlank();
        assertThat(vo.getWorkflowType()).isEqualTo("CREATE_ARTICLE");
        assertThat(vo.getWorkflowVersion()).isEqualTo("1.0");
        assertThat(vo.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_OUTLINE_CONFIRM.name());
        assertThat(vo.getCurrentStep()).isEqualTo("GENERATE_OUTLINE");
        assertThat(vo.getContext()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) vo.getContext();
        assertThat(context.get("workflowVersion")).isEqualTo("1.0");
        assertThat(context).containsKeys("requirement", "stepResults", "feedbackHistory");

        //结果数据统一放 stepResults，确认卡片记录停靠点（前端按 type 渲染确认面板）
        @SuppressWarnings("unchecked")
        Map<String, Object> stepResults = (Map<String, Object>) context.get("stepResults");
        assertThat(String.valueOf(stepResults.get("outline"))).isNotBlank();

        @SuppressWarnings("unchecked")
        Map<String, Object> confirmation = (Map<String, Object>) context.get("confirmation");
        assertThat(confirmation.get("type")).isEqualTo("OUTLINE");
        assertThat(confirmation.get("step")).isEqualTo("GENERATE_OUTLINE");

        AiWorkflowRun saved = aiWorkflowRunMapper.selectById(Long.valueOf(vo.getId()));
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_OUTLINE_CONFIRM.name());
    }

    @Test
    void rejectReworksDraftAndAdvancesToFillConfirmWhenQualityPasses() {
        UserContext.set(101L);

        AiWorkflowCreateArticleDTO dto = new AiWorkflowCreateArticleDTO();
        dto.setRequirement("帮我写一篇 Redis 缓存原理的博客");

        // create → WAITING_OUTLINE_CONFIRM
        AiWorkflowRunVO created = aiWorkflowRunService.createArticleWorkflow(dto);
        // approve → LLM 生成草稿 + 规则质量检查 → WAITING_DRAFT_CONFIRM
        AiWorkflowRunVO approved = aiWorkflowRunService.approve(Long.valueOf(created.getId()));

        assertThat(approved.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> approvedStepResults = (Map<String, Object>) approved.getContext().get("stepResults");
        assertThat(approvedStepResults.get("draft")).isNotNull();
        assertThat(approvedStepResults.get("qualityCheck")).isNotNull();

        // reject → 记录反馈 + 重写草稿 + 重算质量检查
        // 质量通过 → 跳过草稿重新确认，直接进入 WAITING_FILL_CONFIRM
        AiWorkflowRunVO reworked = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()),
                "章节顺序不自然，请先讲缓存穿透再讲缓存击穿");

        // 质量通过则前进到填充确认
        assertThat(reworked.getStatus()).isIn(
                AiWorkflowStatus.WAITING_FILL_CONFIRM.name(),
                AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name()); // LLM 偶发质量不过时兜底
        @SuppressWarnings("unchecked")
        Map<String, Object> reworkedStepResults = (Map<String, Object>) reworked.getContext().get("stepResults");
        assertThat(reworkedStepResults.get("draft")).isNotNull();
        assertThat(reworkedStepResults.get("qualityCheck")).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) reworked.getContext();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> feedbackHistory =
                (List<Map<String, Object>>) context.get("feedbackHistory");
        assertThat(feedbackHistory).isNotEmpty();
        assertThat(feedbackHistory.get(feedbackHistory.size() - 1).get("userFeedback"))
                .isEqualTo("章节顺序不自然，请先讲缓存穿透再讲缓存击穿");

        // 落库状态与响应一致
        AiWorkflowRun saved = aiWorkflowRunMapper.selectById(Long.valueOf(created.getId()));
        assertThat(saved.getStatus()).isEqualTo(reworked.getStatus());
    }

    @Test//创建优化 Workflow
    void createArticleOptimizeWorkflowCreatesRunAndWaitsPlanConfirm() {
        UserContext.set(101L);

        Articles article = new Articles();
        article.setAuthorId(101L);
        article.setCategoryId(1L);
        article.setTitle("Redis 缓存文章");
        article.setSummary("Redis 缓存总结");
        article.setContent("这是一篇 Redis 缓存文章，包含缓存穿透、缓存击穿和高并发场景。");
        article.setStatus(0);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articlesMapper.insert(article);

        AiWorkflowOptimizeArticleDTO dto = new AiWorkflowOptimizeArticleDTO();
        dto.setArticleId(article.getId());
        dto.setInstruction("补充更多项目实战细节");

        AiWorkflowRunVO vo = aiWorkflowRunService.createArticleOptimizeWorkflow(dto);

        assertThat(vo.getWorkflowType()).isEqualTo("OPTIMIZE_ARTICLE");
        assertThat(vo.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_PLAN_CONFIRM.name());
        assertThat(vo.getCurrentStep()).isEqualTo("GENERATE_OPTIMIZATION_PLAN");

        Map<String, Object> context = vo.getContext();
        assertThat(context).containsKeys("input", "memoryContext", "ragContext", "stepResults", "feedbackHistory");

        Map<String, Object> stepResults = (Map<String, Object>) context.get("stepResults");
        assertThat(stepResults).containsKeys("article", "analysis", "optimizationPlan");
    }

    private Articles createTestArticle(Long authorId) {
        Articles article = new Articles();
        article.setAuthorId(authorId);
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
        articlesMapper.insert(article);
        return article;
    }

    @Test//确认优化方案后进入优化稿确认
    void approveOptimizePlanRewritesArticleAndWaitsDraftConfirm() {
        UserContext.set(101L);

        Articles article = createTestArticle(101L);

        AiWorkflowOptimizeArticleDTO dto = new AiWorkflowOptimizeArticleDTO();
        dto.setArticleId(article.getId());
        dto.setInstruction("补充更多 Redis 项目实战细节");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleOptimizeWorkflow(dto);

        AiWorkflowRunVO approved = aiWorkflowRunService.approve(Long.valueOf(created.getId()));

        assertThat(approved.getWorkflowType()).isEqualTo("OPTIMIZE_ARTICLE");
        assertThat(approved.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
        assertThat(approved.getCurrentStep()).isEqualTo("CONTENT_CHECK");

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) approved.getContext();

        @SuppressWarnings("unchecked")
        Map<String, Object> stepResults = (Map<String, Object>) context.get("stepResults");

        assertThat(stepResults).containsKeys("optimizedContent", "contentCheck");
        assertThat(String.valueOf(stepResults.get("optimizedContent"))).isNotBlank();

        @SuppressWarnings("unchecked")
        Map<String, Object> contentCheck = (Map<String, Object>) stepResults.get("contentCheck");

        assertThat(contentCheck).containsKeys("passed", "issues", "suggestions");
    }

    @Test//不是自己的文章不能优化
    void createArticleOptimizeWorkflowRejectsOtherUsersArticle() {
        UserContext.set(101L);

        Articles article = createTestArticle(202L);

        AiWorkflowOptimizeArticleDTO dto = new AiWorkflowOptimizeArticleDTO();
        dto.setArticleId(article.getId());
        dto.setInstruction("帮我优化这篇文章");

        assertThatThrownBy(() -> aiWorkflowRunService.createArticleOptimizeWorkflow(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能优化自己的文章");
    }

    @Test//打回优化方案后仍停在方案确认
    void rejectOptimizePlanRegeneratesPlanAndWaitsPlanConfirm() {
        UserContext.set(101L);

        Articles article = createTestArticle(101L);

        AiWorkflowOptimizeArticleDTO dto = new AiWorkflowOptimizeArticleDTO();
        dto.setArticleId(article.getId());
        dto.setInstruction("补充更多 Redis 项目实战细节");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleOptimizeWorkflow(dto);

        AiWorkflowRunVO rejected = aiWorkflowRunService.reject(
                Long.valueOf(created.getId()),
                "优化方案太泛泛，请明确增加缓存穿透、缓存击穿和项目案例"
        );

        assertThat(rejected.getWorkflowType()).isEqualTo("OPTIMIZE_ARTICLE");
        assertThat(rejected.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_PLAN_CONFIRM.name());
        assertThat(rejected.getCurrentStep()).isEqualTo("GENERATE_OPTIMIZATION_PLAN");

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) rejected.getContext();

        @SuppressWarnings("unchecked")
        Map<String, Object> stepResults = (Map<String, Object>) context.get("stepResults");

        assertThat(stepResults).containsKey("optimizationPlan");
        assertThat(String.valueOf(stepResults.get("optimizationPlan"))).isNotBlank();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> feedbackHistory =
                (List<Map<String, Object>>) context.get("feedbackHistory");

        assertThat(feedbackHistory).isNotEmpty();
        assertThat(feedbackHistory.get(feedbackHistory.size() - 1).get("userFeedback"))
                .isEqualTo("优化方案太泛泛，请明确增加缓存穿透、缓存击穿和项目案例");
    }

    @Test//确认优化稿后返回 editorAction，填充编辑器即完成
    void approveOptimizeDraftReturnsEditorActionAndCompletes() {
        UserContext.set(101L);

        Articles article = createTestArticle(101L);

        AiWorkflowOptimizeArticleDTO dto = new AiWorkflowOptimizeArticleDTO();
        dto.setArticleId(article.getId());
        dto.setInstruction("补充更多 Redis 项目实战细节");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleOptimizeWorkflow(dto);

        AiWorkflowRunVO draftReady = aiWorkflowRunService.approve(Long.valueOf(created.getId()));
        assertThat(draftReady.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());

        AiWorkflowRunVO filled = aiWorkflowRunService.approve(Long.valueOf(created.getId()));

        assertThat(filled.getWorkflowType()).isEqualTo("OPTIMIZE_ARTICLE");
        assertThat(filled.getStatus()).isEqualTo(AiWorkflowStatus.COMPLETED.name());
        assertThat(filled.getCurrentStep()).isEqualTo("FILL_ARTICLE");
        assertThat(filled.getEditorAction()).isNotNull();
        assertThat(filled.getEditorAction().getType()).isEqualTo("fillArticle");
        assertThat(filled.getEditorAction().getTitle()).isEqualTo("Redis 缓存文章");
        assertThat(filled.getEditorAction().getContent()).isNotBlank();
    }

    @Test//retry 链路测试
    void retryFailedOptimizeRewriteReturnsToDraftConfirm() {
        UserContext.set(101L);

        Articles article = createTestArticle(101L);

        AiWorkflowOptimizeArticleDTO dto = new AiWorkflowOptimizeArticleDTO();
        dto.setArticleId(article.getId());
        dto.setInstruction("补充更多 Redis 项目实战细节");

        AiWorkflowRunVO created = aiWorkflowRunService.createArticleOptimizeWorkflow(dto);

        AiWorkflowRun run = aiWorkflowRunMapper.selectById(Long.valueOf(created.getId()));
        run.setStatus(AiWorkflowStatus.FAILED.name());
        run.setCurrentStep("REWRITE_ARTICLE");
        run.setErrorMessage("模拟优化稿生成失败");
        aiWorkflowRunMapper.updateById(run);

        AiWorkflowRunVO retried = aiWorkflowRunService.retry(run.getId());

        assertThat(retried.getWorkflowType()).isEqualTo("OPTIMIZE_ARTICLE");
        assertThat(retried.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
        assertThat(retried.getCurrentStep()).isEqualTo("CONTENT_CHECK");
        assertThat(retried.getErrorMessage()).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) retried.getContext();

        @SuppressWarnings("unchecked")
        Map<String, Object> stepResults = (Map<String, Object>) context.get("stepResults");

        assertThat(stepResults).containsKeys("optimizedContent", "contentCheck");
        assertThat(String.valueOf(stepResults.get("optimizedContent"))).isNotBlank();

        @SuppressWarnings("unchecked")
        Map<String, Object> contentCheck = (Map<String, Object>) stepResults.get("contentCheck");

        assertThat(contentCheck).containsKeys("passed", "issues", "suggestions");

        AiWorkflowRun saved = aiWorkflowRunMapper.selectById(run.getId());
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
        assertThat(saved.getCurrentStep()).isEqualTo("CONTENT_CHECK");
    }
}
