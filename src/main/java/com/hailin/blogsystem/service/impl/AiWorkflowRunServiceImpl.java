package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.workflow.AiWorkflowAdvanceResult;
import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.ai.workflow.ArticleOptimizeWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.CreateArticleWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.LearningAssistWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.LearningPlanWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.LearningProgressWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.WorkflowHandler;
import com.hailin.blogsystem.ai.workflow.WorkflowHandlerRegistry;
import com.hailin.blogsystem.ai.workflow.WorkflowActionIdempotency;
import com.hailin.blogsystem.ai.workflow.WorkflowActionLock;
import com.hailin.blogsystem.ai.workflow.WorkflowRunManager;
import com.hailin.blogsystem.ai.workflow.WorkflowStatusSupport;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiWorkflowCreateArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningAssistDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningPlanDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningProgressDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowStepLogVO;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import com.hailin.blogsystem.service.AiWorkflowStepLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 工作流运行记录服务（骨架占位，待后续实现完整工作流逻辑）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiWorkflowRunServiceImpl
        extends ServiceImpl<AiWorkflowRunMapper, AiWorkflowRun>
        implements AiWorkflowRunService {

    private static final String WORKFLOW_VERSION = "1.0";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final CreateArticleWorkflowHandler createArticleWorkflowHandler;
    private final ArticleOptimizeWorkflowHandler articleOptimizeWorkflowHandler;
    private final LearningPlanWorkflowHandler learningPlanWorkflowHandler;
    private final LearningProgressWorkflowHandler learningProgressWorkflowHandler;
    private final LearningAssistWorkflowHandler learningAssistWorkflowHandler;
    private final WorkflowHandlerRegistry workflowHandlerRegistry;
    private final WorkflowRunManager workflowRunManager;
    private final AiWorkflowStepLogService aiWorkflowStepLogService;
    private final WorkflowStatusSupport workflowStatusSupport;
    private final WorkflowActionLock workflowActionLock;
    private final WorkflowActionIdempotency workflowActionIdempotency;

    @Override
    @Transactional //创建文章创作 Workflow
    public AiWorkflowRunVO createArticleWorkflow(AiWorkflowCreateArticleDTO dto) {
        return createArticleWorkflow(dto, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createArticleWorkflow(AiWorkflowCreateArticleDTO dto, AiWorkflowStepEmitter emitter) {
        return createWorkflowInternal(
                createArticleWorkflowHandler,
                dto == null ? null : dto.getConversationId(),
                userId -> createArticleWorkflowHandler.create(userId, dto, emitter),
                "创建文章工作流",
                emitter);
    }

    @Override  //创建文章优化 Workflow
    @Transactional
    public AiWorkflowRunVO createArticleOptimizeWorkflow(AiWorkflowOptimizeArticleDTO dto) {
        return createArticleOptimizeWorkflow(dto, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createArticleOptimizeWorkflow(AiWorkflowOptimizeArticleDTO dto, AiWorkflowStepEmitter emitter) {
        return createWorkflowInternal(
                articleOptimizeWorkflowHandler,
                dto == null ? null : dto.getConversationId(),
                userId -> articleOptimizeWorkflowHandler.create(userId, dto, emitter),
                "创建文章优化工作流",
                emitter);
    }

    @Override  //创建学习规划 Workflow
    @Transactional
    public AiWorkflowRunVO createLearningPlanWorkflow(AiWorkflowLearningPlanDTO dto) {
        return createLearningPlanWorkflow(dto, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createLearningPlanWorkflow(
            AiWorkflowLearningPlanDTO dto,
            AiWorkflowStepEmitter emitter
    ) {
        return createLearningPlanWorkflow(dto, emitter, false);
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createLearningPlanWorkflow(
            AiWorkflowLearningPlanDTO dto,
            AiWorkflowStepEmitter emitter,
            boolean agentAutoStarted
    ) {
        return createLearningPlanWorkflow(dto, emitter, agentAutoStarted, null);
    }

    @Override
    @Transactional
    public AiWorkflowRunVO createLearningPlanWorkflow(
            AiWorkflowLearningPlanDTO dto,
            AiWorkflowStepEmitter emitter,
            boolean agentAutoStarted,
            String requestId
    ) {
        return createWorkflowInternal(
                learningPlanWorkflowHandler,
                dto == null ? null : dto.getConversationId(),
                userId -> learningPlanWorkflowHandler.create(userId, dto, emitter),
                "创建学习规划工作流",
                emitter,
                agentAutoStarted,
                requestId
        );
    }

    @Override  //创建学习进度 Workflow（调整已有计划）
    @Transactional
    public AiWorkflowRunVO createLearningProgressWorkflow(AiWorkflowLearningProgressDTO dto) {
        return createLearningProgressWorkflow(dto, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createLearningProgressWorkflow(
            AiWorkflowLearningProgressDTO dto,
            AiWorkflowStepEmitter emitter
    ) {
        return createLearningProgressWorkflow(dto, emitter, false);
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createLearningProgressWorkflow(
            AiWorkflowLearningProgressDTO dto,
            AiWorkflowStepEmitter emitter,
            boolean agentAutoStarted
    ) {
        return createLearningProgressWorkflow(dto, emitter, agentAutoStarted, null);
    }

    @Override
    @Transactional
    public AiWorkflowRunVO createLearningProgressWorkflow(
            AiWorkflowLearningProgressDTO dto,
            AiWorkflowStepEmitter emitter,
            boolean agentAutoStarted,
            String requestId
    ) {
        return createWorkflowInternal(
                learningProgressWorkflowHandler,
                dto == null ? null : dto.getConversationId(),
                userId -> learningProgressWorkflowHandler.create(userId, dto, emitter),
                "创建学习进度工作流",
                emitter,
                agentAutoStarted,
                requestId
        );
    }

    @Override  //创建学习难点攻坚 Workflow（拆解难点，追加任务点到对应阶段）
    @Transactional
    public AiWorkflowRunVO createLearningAssistWorkflow(AiWorkflowLearningAssistDTO dto) {
        return createLearningAssistWorkflow(dto, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createLearningAssistWorkflow(
            AiWorkflowLearningAssistDTO dto,
            AiWorkflowStepEmitter emitter
    ) {
        return createLearningAssistWorkflow(dto, emitter, false);
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createLearningAssistWorkflow(
            AiWorkflowLearningAssistDTO dto,
            AiWorkflowStepEmitter emitter,
            boolean agentAutoStarted
    ) {
        return createLearningAssistWorkflow(dto, emitter, agentAutoStarted, null);
    }

    @Override
    @Transactional
    public AiWorkflowRunVO createLearningAssistWorkflow(
            AiWorkflowLearningAssistDTO dto,
            AiWorkflowStepEmitter emitter,
            boolean agentAutoStarted,
            String requestId
    ) {
        return createWorkflowInternal(
                learningAssistWorkflowHandler,
                dto == null ? null : dto.getConversationId(),
                userId -> learningAssistWorkflowHandler.create(userId, dto, emitter),
                "创建学习难点攻坚工作流",
                emitter,
                agentAutoStarted,
                requestId
        );
    }

    //五个 create 的公共骨架：登录 → 冲突检查 → handler.create → save → runInitialSteps →
    //绑定会话 + 记日志；失败 markFailed + 也绑定（刷新可恢复重试入口）
    private AiWorkflowRunVO createWorkflowInternal(
            WorkflowHandler handler,
            Long conversationId,
            java.util.function.Function<Long, AiWorkflowAdvanceResult> createAction,
            String logLabel,
            AiWorkflowStepEmitter emitter
    ) {
        return createWorkflowInternal(
                handler,
                conversationId,
                createAction,
                logLabel,
                emitter,
                false,
                null
        );
    }
    private AiWorkflowRunVO createWorkflowInternal(
            WorkflowHandler handler,
            Long conversationId,
            java.util.function.Function<Long, AiWorkflowAdvanceResult> createAction,
            String logLabel,
            AiWorkflowStepEmitter emitter,
            boolean agentAutoStarted
    ) {
        return createWorkflowInternal(
                handler,
                conversationId,
                createAction,
                logLabel,
                emitter,
                agentAutoStarted,
                null
        );
    }
    private AiWorkflowRunVO createWorkflowInternal(
            WorkflowHandler handler,
            Long conversationId,
            java.util.function.Function<Long, AiWorkflowAdvanceResult> createAction,
            String logLabel,
            AiWorkflowStepEmitter emitter,
            boolean agentAutoStarted,
            String requestId
    ) {
        Long userId = workflowRunManager.requireLogin();
        long start = System.currentTimeMillis();

        workflowRunManager.checkActiveWorkflowConflict(conversationId, userId);

        AiWorkflowAdvanceResult result = createAction.apply(userId);

        // agentAutoStarted / requestId 统一在公共创建入口写入 contextJson（必须在第一次 save 前）
        markAgentMetadata(result.getRun(), agentAutoStarted, requestId);

        save(result.getRun());

        try {
            AiWorkflowStepEmitter safeEmitter =
                    emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
            // 初始步骤必须在 run 入库后才能落 StepLog，也要先把真实 ID 交给 SSE emitter。
            safeEmitter.bindWorkflowRunId(result.getRun().getId());
            result = handler.runInitialSteps(result.getRun(), safeEmitter);
            updateById(result.getRun());
            workflowRunManager.clearErrorMessage(result.getRun().getId());

            workflowRunManager.bindSessionActiveWorkflow(result.getRun());

            recordWorkflowSuccess(
                    result.getRun(),
                    currentStep(result.getRun()),
                    logLabel,
                    outputStatus(result.getRun()),
                    start
            );

            return toVo(result);
        } catch (RuntimeException e) {
            workflowRunManager.markFailed(result.getRun(), e);

            workflowRunManager.bindSessionActiveWorkflow(result.getRun());

            recordWorkflowFailure(
                    result.getRun(),
                    currentStep(result.getRun()),
                    logLabel,
                    e,
                    start
            );

            return toVo(result.getRun());
        }
    }
    private void markAgentMetadata(
            AiWorkflowRun run,
            boolean agentAutoStarted,
            String requestId
    ) {
        // 普通创建（非自动拉起、无 requestId）零开销：直接返回，不做 contextJson 解析重写
        if (!agentAutoStarted
                && (requestId == null || requestId.isBlank())) {
            return;
        }

        try {
            Map<String, Object> context;

            if (run.getContextJson() == null || run.getContextJson().isBlank()) {
                context = new HashMap<>();
            } else {
                context = objectMapper.readValue(
                        run.getContextJson(),
                        new TypeReference<Map<String, Object>>() {}
                );
            }

            if (agentAutoStarted) {
                context.put("agentAutoStarted", true);
            }

            if (requestId != null && !requestId.isBlank()) {
                context.put("requestId", requestId);
            }

            run.setContextJson(objectMapper.writeValueAsString(context));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法写入 Agent Workflow 元数据", e);
        }
    }

    @Override  //查询 Workflow。
    public AiWorkflowRunVO getWorkflowRun(Long id) {
        //必须校验 userId，避免用户看到别人的 workflow。
        Long userId = workflowRunManager.requireLogin();
        AiWorkflowRun run = workflowRunManager.getOwnedRun(id,userId);

        return toVo(run);
    }

    @Override  //同意当前阶段，推进到下一步。
    @Transactional
    public AiWorkflowRunVO approve(Long id) {
        return approve(id, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO approve(
            Long id,
            AiWorkflowStepEmitter emitter
    ) {
        Long userId = workflowRunManager.requireLogin();

        ClaimedWorkflow claimed =
                claimWorkflowAction(id, userId);

        AiWorkflowRun run = claimed.run();

        long start = System.currentTimeMillis();
        String step = currentStep(run);

        try {
            WorkflowHandler handler =
                    workflowHandlerRegistry.get(run.getWorkflowType());

            AiWorkflowAdvanceResult result =
                    handler.approve(run, emitter);

            updateById(result.getRun());
            workflowRunManager.clearErrorMessage(run.getId());

            if (AiWorkflowStatus.COMPLETED.name()
                    .equals(result.getRun().getStatus())) {
                workflowRunManager.clearSessionActiveWorkflow(
                        result.getRun()
                );
            }

            recordWorkflowSuccess(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "老板确认当前步骤",
                    outputStatus(result.getRun()),
                    start
            );

            return toVo(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            workflowRunManager.markFailed(run, e);

            recordWorkflowFailure(
                    run,
                    step,
                    "老板确认当前步骤",
                    e,
                    start
            );

            return toVo(run);
        } finally {
            workflowActionLock.release(
                    claimed.lockHandle()
            );
        }
    }


    @Override  //不同意当前阶段，保存用户反馈，并重新生成当前阶段内容
    @Transactional
    public AiWorkflowRunVO reject(Long id, String feedback) {

        return reject(id, feedback, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO reject(
            Long id,
            String feedback,
            AiWorkflowStepEmitter emitter
    ) {
        Long userId = workflowRunManager.requireLogin();

        ClaimedWorkflow claimed =
                claimWorkflowAction(id, userId);

        AiWorkflowRun run = claimed.run();

        long start = System.currentTimeMillis();
        String step = currentStep(run);

        try {
            WorkflowHandler handler =
                    workflowHandlerRegistry.get(run.getWorkflowType());

            AiWorkflowAdvanceResult result =
                    handler.reject(run, feedback, emitter);

            updateById(result.getRun());
            workflowRunManager.clearErrorMessage(run.getId());

            // 否定反馈导致取消，清理会话中的 active Workflow
            if (AiWorkflowStatus.CANCELLED.name()
                    .equals(result.getRun().getStatus())) {
                workflowRunManager.clearSessionActiveWorkflow(
                        result.getRun()
                );
            }

            recordWorkflowSuccess(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "老板反馈：" + feedback,
                    outputStatus(result.getRun()),
                    start
            );

            return toVo(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            workflowRunManager.markFailed(run, e);

            recordWorkflowFailure(
                    run,
                    step,
                    "老板反馈：" + feedback,
                    e,
                    start
            );

            return toVo(run);
        } finally {
            workflowActionLock.release(
                    claimed.lockHandle()
            );
        }
    }

    @Override  //取消 Workflow。
    @Transactional
    public void cancel(Long id) {
        Long userId = workflowRunManager.requireLogin();

        // 保留原有终态快速返回
        AiWorkflowRun current =
                workflowRunManager.getOwnedRun(id, userId);

        if (AiWorkflowStatus.COMPLETED.name()
                .equals(current.getStatus())
                || AiWorkflowStatus.CANCELLED.name()
                .equals(current.getStatus())) {
            return;
        }

        ClaimedWorkflow claimed =
                claimWorkflowAction(id, userId);

        AiWorkflowRun run = claimed.run();

        try {
            // 拿锁后再次检查状态
            if (AiWorkflowStatus.COMPLETED.name()
                    .equals(run.getStatus())
                    || AiWorkflowStatus.CANCELLED.name()
                    .equals(run.getStatus())) {
                return;
            }

            long start = System.currentTimeMillis();
            String step = currentStep(run);

            run.setStatus(AiWorkflowStatus.CANCELLED.name());
            workflowRunManager.touch(run);
            updateById(run);

            workflowRunManager.clearSessionActiveWorkflow(run);

            recordWorkflowSuccess(
                    run,
                    step,
                    "老板取消工作流",
                    outputStatus(run),
                    start
            );
        } finally {
            workflowActionLock.release(
                    claimed.lockHandle()
            );
        }
    }

    @Override
    public List<AiWorkflowStepLogVO> listStepLogs(Long id) {
        Long userId = workflowRunManager.requireLogin();
        AiWorkflowRun run = workflowRunManager.getOwnedRun(id, userId);

        return aiWorkflowStepLogService.listByWorkflowRunId(run.getId());
    }

    @Override
    @Transactional
    public AiWorkflowRunVO retry(Long id) {
        return retry(id, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO retry(
            Long id,
            AiWorkflowStepEmitter emitter
    ) {
        Long userId = workflowRunManager.requireLogin();

        // 保留原有的快速状态校验
        AiWorkflowRun current =
                workflowRunManager.getOwnedRun(id, userId);

        if (!AiWorkflowStatus.FAILED.name()
                .equals(current.getStatus())) {
            throw new IllegalArgumentException(
                    "只有失败的 Workflow 可以重试"
            );
        }

        ClaimedWorkflow claimed =
                claimWorkflowAction(id, userId);

        AiWorkflowRun run = claimed.run();

        try {
            // 拿锁后重新检查，防止状态已经被其他操作改变
            if (!AiWorkflowStatus.FAILED.name()
                    .equals(run.getStatus())) {
                throw new IllegalArgumentException(
                        "只有失败的 Workflow 可以重试"
                );
            }

            long start = System.currentTimeMillis();

            // 必须在 claim 成功之后递增
            incrementRetryCount(run);

            WorkflowHandler handler =
                    workflowHandlerRegistry.get(run.getWorkflowType());

            AiWorkflowAdvanceResult result =
                    handler.retry(run, emitter);

            updateById(result.getRun());
            workflowRunManager.clearErrorMessage(run.getId());

            recordWorkflowSuccess(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "老板重试失败步骤",
                    outputStatus(result.getRun()),
                    start
            );

            return toVo(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            long start = System.currentTimeMillis();
            String step = currentStep(run);

            workflowRunManager.markFailed(run, e);

            recordWorkflowFailure(
                    run,
                    step,
                    "老板重试失败步骤",
                    e,
                    start
            );

            return toVo(run);
        } finally {
            workflowActionLock.release(
                    claimed.lockHandle()
            );
        }
    }
    private ClaimedWorkflow claimWorkflowAction(Long id, Long userId) {
        // 锁前先校验归属，避免给无权限的 Workflow 加锁
        workflowRunManager.getOwnedRun(id, userId);

        WorkflowActionLock.LockHandle lockHandle =
                workflowActionLock.acquireOrThrow(id);

        try {
            // 拿锁后重新读取，不能继续使用锁前旧对象
            AiWorkflowRun run = workflowRunManager.getOwnedRun(id, userId);

            Integer expectedVersion =
                    run.getVersion() == null ? 0 : run.getVersion();

            int affectedRows =
                    baseMapper.claimAction(run.getId(), expectedVersion);

            if (affectedRows != 1) {
                throw new WorkflowActionLock.WorkflowActionBusyException();
            }

            // DB 已经 +1，内存对象也必须同步，否则后面的 updateById 会写回旧版本
            run.setVersion(expectedVersion + 1);

            return new ClaimedWorkflow(run, lockHandle);
        } catch (RuntimeException e) {
            workflowActionLock.release(lockHandle);
            throw e;
        }
    }

    private record ClaimedWorkflow(
            AiWorkflowRun run,
            WorkflowActionLock.LockHandle lockHandle
    ) {
    }

    private void incrementRetryCount(AiWorkflowRun run) {
        Integer retryCount = run.getRetryCount();
        run.setRetryCount(retryCount == null ? 1 : retryCount + 1);
    }
    private long elapsedMs(long start){
        return System.currentTimeMillis() -start;
    }
    private String currentStep(AiWorkflowRun run){
        if(run == null || run.getCurrentStep() == null || run.getCurrentStep().isBlank()){
            return AiWorkflowStep.REQUIREMENT_ANALYZE.name();
        }

        return run.getCurrentStep();
    }
    private String outputStatus(AiWorkflowRun run){
        if(run == null){
            return "状态：未知";
        }

        return "状态：" + workflowStatusSupport.statusLabel(run.getStatus()) + "，当前步骤：" + workflowStatusSupport.stepLabel(run.getCurrentStep());
    }
    private void recordWorkflowSuccess(
            AiWorkflowRun run,
            String step,
            String inputSummary,
            String outputSummary,
            long start
    ) {
        aiWorkflowStepLogService.recordSuccess(
                run.getId(),
                step,
                inputSummary,
                outputSummary,
                elapsedMs(start)
        );
    }
    private void recordWorkflowFailure(
            AiWorkflowRun run,
            String step,
            String inputSummary,
            RuntimeException e,
            long start
    ) {
        aiWorkflowStepLogService.recordFailure(
                run.getId(),
                step,
                inputSummary,
                e,
                elapsedMs(start)
        );
    }

    private AiWorkflowRunVO toVo(AiWorkflowAdvanceResult result) {
        AiWorkflowRunVO vo = AiWorkflowRunVO.from(result.getRun(), objectMapper);
        vo.setEditorAction(result.getEditorAction());
        return vo;
    }
    private AiWorkflowRunVO toVo(AiWorkflowRun run) {
        return AiWorkflowRunVO.from(run, objectMapper);
    }

    @Override
    @Transactional
    public AiWorkflowRunVO approve(
            Long id,
            String idempotencyKey,
            AiWorkflowStepEmitter emitter
    ) {
        Long userId = workflowRunManager.requireLogin();

        String fingerprint =
                workflowActionIdempotency.fingerprint("APPROVE", null);

        AiWorkflowRunVO cached =
                workflowActionIdempotency.get(
                        userId,
                        id,
                        "APPROVE",
                        idempotencyKey,
                        fingerprint
                );

        if (cached != null) {
            return cached;
        }

        AiWorkflowRunVO result = approve(id, emitter);

        workflowActionIdempotency.save(
                userId,
                id,
                "APPROVE",
                idempotencyKey,
                fingerprint,
                result
        );

        return result;
    }

    @Override
    @Transactional
    public AiWorkflowRunVO reject(
            Long id,
            String feedback,
            String idempotencyKey,
            AiWorkflowStepEmitter emitter
    ) {
        Long userId = workflowRunManager.requireLogin();

        String fingerprint =
                workflowActionIdempotency.fingerprint(
                        "REJECT",
                        feedback
                );

        AiWorkflowRunVO cached =
                workflowActionIdempotency.get(
                        userId,
                        id,
                        "REJECT",
                        idempotencyKey,
                        fingerprint
                );

        if (cached != null) {
            return cached;
        }

        AiWorkflowRunVO result =
                reject(id, feedback, emitter);

        workflowActionIdempotency.save(
                userId,
                id,
                "REJECT",
                idempotencyKey,
                fingerprint,
                result
        );

        return result;
    }

    @Override
    @Transactional
    public AiWorkflowRunVO retry(
            Long id,
            String idempotencyKey,
            AiWorkflowStepEmitter emitter
    ) {
        Long userId = workflowRunManager.requireLogin();

        String fingerprint =
                workflowActionIdempotency.fingerprint("RETRY", null);

        AiWorkflowRunVO cached =
                workflowActionIdempotency.get(
                        userId,
                        id,
                        "RETRY",
                        idempotencyKey,
                        fingerprint
                );

        if (cached != null) {
            return cached;
        }

        AiWorkflowRunVO result = retry(id, emitter);

        workflowActionIdempotency.save(
                userId,
                id,
                "RETRY",
                idempotencyKey,
                fingerprint,
                result
        );

        return result;
    }

}
