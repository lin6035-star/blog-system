package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import java.util.List;


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
    public AiWorkflowRunVO createLearningPlanWorkflow(AiWorkflowLearningPlanDTO dto, AiWorkflowStepEmitter emitter) {
        return createWorkflowInternal(
                learningPlanWorkflowHandler,
                dto == null ? null : dto.getConversationId(),
                userId -> learningPlanWorkflowHandler.create(userId, dto, emitter),
                "创建学习规划工作流",
                emitter);
    }

    @Override  //创建学习进度 Workflow（调整已有计划）
    @Transactional
    public AiWorkflowRunVO createLearningProgressWorkflow(AiWorkflowLearningProgressDTO dto) {
        return createLearningProgressWorkflow(dto, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createLearningProgressWorkflow(AiWorkflowLearningProgressDTO dto, AiWorkflowStepEmitter emitter) {
        return createWorkflowInternal(
                learningProgressWorkflowHandler,
                dto == null ? null : dto.getConversationId(),
                userId -> learningProgressWorkflowHandler.create(userId, dto, emitter),
                "创建学习进度工作流",
                emitter);
    }

    @Override  //创建学习难点攻坚 Workflow（拆解难点，追加任务点到对应阶段）
    @Transactional
    public AiWorkflowRunVO createLearningAssistWorkflow(AiWorkflowLearningAssistDTO dto) {
        return createLearningAssistWorkflow(dto, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createLearningAssistWorkflow(AiWorkflowLearningAssistDTO dto, AiWorkflowStepEmitter emitter) {
        return createWorkflowInternal(
                learningAssistWorkflowHandler,
                dto == null ? null : dto.getConversationId(),
                userId -> learningAssistWorkflowHandler.create(userId, dto, emitter),
                "创建学习难点攻坚工作流",
                emitter);
    }

    //五个 create 的公共骨架：登录 → 冲突检查 → handler.create → save → runInitialSteps →
    //绑定会话 + 记日志；失败 markFailed + 也绑定（刷新可恢复重试入口）
    private AiWorkflowRunVO createWorkflowInternal(
            WorkflowHandler handler,
            Long conversationId,
            java.util.function.Function<Long, AiWorkflowAdvanceResult> createAction,
            String logLabel,
            AiWorkflowStepEmitter emitter) {
        Long userId = workflowRunManager.requireLogin();
        long start = System.currentTimeMillis();

        workflowRunManager.checkActiveWorkflowConflict(conversationId, userId);

        //create 只初始化 run（不执行 LLM），save 后再跑初始步骤，这样 runStep 能正常落步骤日志
        AiWorkflowAdvanceResult result = createAction.apply(userId);
        save(result.getRun());

        try {
            result = handler.runInitialSteps(result.getRun(), emitter);
            updateById(result.getRun());
            workflowRunManager.clearErrorMessage(result.getRun().getId());

            //绑定
            workflowRunManager.bindSessionActiveWorkflow(result.getRun());

            //写Step Log
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

            //失败也绑定会话：前端刷新后还能从会话恢复 FAILED 面板和重试入口
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
    public AiWorkflowRunVO approve(Long id,AiWorkflowStepEmitter emitter) {
        Long userId = workflowRunManager.requireLogin();
        AiWorkflowRun run = workflowRunManager.getOwnedRun(id, userId);

        long start = System.currentTimeMillis();
        String step = currentStep(run);

        try{
            //推进状态（按 workflowType 路由到对应 Handler）
            WorkflowHandler handler = workflowHandlerRegistry.get(run.getWorkflowType());
            AiWorkflowAdvanceResult result = handler.approve(run, emitter);
            updateById(result.getRun());
            workflowRunManager.clearErrorMessage(run.getId());

            //handler 内直接置 COMPLETED 的流程（如学习规划 SAVE_PLAN）不走 complete()，
            // 这里统一收口：结束态清 session 绑定，避免刷新后误恢复已结束的 workflow
            if (AiWorkflowStatus.COMPLETED.name().equals(result.getRun().getStatus())) {
                workflowRunManager.clearSessionActiveWorkflow(result.getRun());
            }

            //成功，记success日志
            recordWorkflowSuccess(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "老板确认当前步骤",
                    outputStatus(result.getRun()),
                    start
            );

            return toVo(result);
        }catch(IllegalArgumentException e){
            //业务拦截（如"质量检查未通过"）→ 原样抛给前端提示
            throw e;
        }catch (RuntimeException e){
            //其他异常（LLM 挂了等）
            workflowRunManager.markFailed(run, e);

            //失败 → 记 FAILED 日志（REQUIRES_NEW 独立事务）
            recordWorkflowFailure(
                    run,
                    step,
                    "老板确认当前步骤",
                    e,
                    start
            );

            //不抛异常，把 FAILED 状态返回给前端
            return toVo(run);
        }
    }


    @Override  //不同意当前阶段，保存用户反馈，并重新生成当前阶段内容
    @Transactional
    public AiWorkflowRunVO reject(Long id, String feedback) {

        return reject(id, feedback, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO reject(Long id, String feedback,AiWorkflowStepEmitter emitter) {
        Long userId = workflowRunManager.requireLogin();
        AiWorkflowRun run = workflowRunManager.getOwnedRun(id, userId);

        long start = System.currentTimeMillis();
        String step = currentStep(run);

        try{
            WorkflowHandler handler = workflowHandlerRegistry.get(run.getWorkflowType());
            AiWorkflowAdvanceResult result = handler.reject(run, feedback,emitter);
            updateById(result.getRun());
            workflowRunManager.clearErrorMessage(run.getId());

            //否定反馈导致 CANCELLED（如"算了"）→ 清 session 绑定，让下一条消息回普通聊天
            if (AiWorkflowStatus.CANCELLED.name().equals(result.getRun().getStatus())) {
                workflowRunManager.clearSessionActiveWorkflow(result.getRun());
            }

            recordWorkflowSuccess(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "老板反馈：" + feedback,
                    outputStatus(result.getRun()),
                    start
            );

            return toVo(result);
        }catch (IllegalArgumentException e) {
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
        }

    }

    @Override  //取消 Workflow。
    @Transactional
    public void cancel(Long id) {
        Long userId = workflowRunManager.requireLogin();
        AiWorkflowRun run = workflowRunManager.getOwnedRun(id, userId);

        long start = System.currentTimeMillis();
        String step = currentStep(run);

        if (AiWorkflowStatus.COMPLETED.name().equals(run.getStatus())
                || AiWorkflowStatus.CANCELLED.name().equals(run.getStatus())) {
            return;
        }

        run.setStatus(AiWorkflowStatus.CANCELLED.name());
        workflowRunManager.touch(run);
        updateById(run);

        workflowRunManager.clearSessionActiveWorkflow(run);

        //cancel 是用户主动取消，也可以记一条成功日志，
        // 方便以后知道 workflow 为什么结束
        recordWorkflowSuccess(
                run,
                step,
                "老板取消工作流",
                outputStatus(run),
                start
        );
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
    public AiWorkflowRunVO retry(Long id,AiWorkflowStepEmitter emitter) {
        Long userId = workflowRunManager.requireLogin();
        AiWorkflowRun run = workflowRunManager.getOwnedRun(id, userId);

        if (!AiWorkflowStatus.FAILED.name().equals(run.getStatus())) {
            throw new IllegalArgumentException("只有失败的 Workflow 可以重试");
        }

        long start = System.currentTimeMillis();
        String step = currentStep(run);

        // retry_count + 1
        incrementRetryCount(run);

        try {
            WorkflowHandler handler = workflowHandlerRegistry.get(run.getWorkflowType());
            AiWorkflowAdvanceResult result = handler.retry(run,emitter);
            updateById(result.getRun());
            workflowRunManager.clearErrorMessage(run.getId());

            // 重试成功也记一条
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
            workflowRunManager.markFailed(run, e);

            // 重试又失败 → 再次 FAILED，还能再重试
            recordWorkflowFailure(
                    run,
                    step,
                    "老板重试失败步骤",
                    e,
                    start
            );

            return toVo(run);
        }
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

}
