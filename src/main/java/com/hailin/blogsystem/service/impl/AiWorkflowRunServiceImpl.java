package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.workflow.AiWorkflowAdvanceResult;
import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.ai.workflow.ArticleOptimizeWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.CreateArticleWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.LearningPlanWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.WorkflowHandler;
import com.hailin.blogsystem.ai.workflow.WorkflowHandlerRegistry;
import com.hailin.blogsystem.ai.workflow.WorkflowStatusSupport;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiWorkflowCreateArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningPlanDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowStepLogVO;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import com.hailin.blogsystem.service.AiWorkflowStepLogService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private final WorkflowHandlerRegistry workflowHandlerRegistry;
    private final AiSessionService aiSessionService;
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
        //现在创建接口自己会绑定会话 + 写日志，AiMessageServiceImpl 那边就不再需要重复绑定了（它那边只调 createArticleWorkflow）。
        // 冲突检查保证一个会话同时只有一个任务。
        Long userId = requireLogin();
        long start = System.currentTimeMillis();

        //开头检查冲突
        Long conversationId = dto == null ? null : dto.getConversationId();
        checkActiveWorkflowConflict(conversationId, userId);

        //create 只初始化 run（不执行 LLM），save 后再跑初始步骤，这样 runStep 能正常落步骤日志
        AiWorkflowAdvanceResult result = createArticleWorkflowHandler.create(userId, dto, emitter);
        save(result.getRun());

        try {
            result = createArticleWorkflowHandler.runInitialSteps(result.getRun(), emitter);
            updateById(result.getRun());

            //绑定
            bindSessionActiveWorkflow(result.getRun());

            //写Step Log
            recordWorkflowSuccess(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "创建文章工作流",
                    outputStatus(result.getRun()),
                    start
            );

            return toVo(result);
        } catch (RuntimeException e) {
            markFailed(result.getRun(), e);

            //失败也绑定会话：前端刷新后还能从会话恢复 FAILED 面板和重试入口
            bindSessionActiveWorkflow(result.getRun());

            recordWorkflowFailure(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "创建文章工作流",
                    e,
                    start
            );

            return toVo(result.getRun());
        }
    }

    @Override  //创建文章优化 Workflow
    @Transactional
    public AiWorkflowRunVO createArticleOptimizeWorkflow(AiWorkflowOptimizeArticleDTO dto) {
        return createArticleOptimizeWorkflow(dto, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createArticleOptimizeWorkflow(AiWorkflowOptimizeArticleDTO dto, AiWorkflowStepEmitter emitter) {
        Long userId = requireLogin();
        long start = System.currentTimeMillis();

        //和创建文章一致：一个会话同时只能有一个任务
        Long conversationId = dto == null ? null : dto.getConversationId();
        checkActiveWorkflowConflict(conversationId, userId);

        //create 只初始化 run（不执行 LLM），save 后再跑初始步骤
        AiWorkflowAdvanceResult result = articleOptimizeWorkflowHandler.create(userId, dto, emitter);
        save(result.getRun());

        try {
            result = articleOptimizeWorkflowHandler.runInitialSteps(result.getRun(), emitter);
            updateById(result.getRun());

            //绑定
            bindSessionActiveWorkflow(result.getRun());

            //写Step Log
            recordWorkflowSuccess(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "创建文章优化工作流",
                    outputStatus(result.getRun()),
                    start
            );

            return toVo(result);
        } catch (RuntimeException e) {
            markFailed(result.getRun(), e);

            //失败也绑定会话：前端刷新后还能从会话恢复 FAILED 面板和重试入口
            bindSessionActiveWorkflow(result.getRun());

            recordWorkflowFailure(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "创建文章优化工作流",
                    e,
                    start
            );

            return toVo(result.getRun());
        }
    }

    @Override  //创建学习规划 Workflow
    @Transactional
    public AiWorkflowRunVO createLearningPlanWorkflow(AiWorkflowLearningPlanDTO dto) {
        return createLearningPlanWorkflow(dto, AiWorkflowStepEmitter.noop());
    }
    @Override
    @Transactional
    public AiWorkflowRunVO createLearningPlanWorkflow(AiWorkflowLearningPlanDTO dto, AiWorkflowStepEmitter emitter) {
        Long userId = requireLogin();
        long start = System.currentTimeMillis();

        //和其他 workflow 一致：一个会话同时只能有一个任务
        Long conversationId = dto == null ? null : dto.getConversationId();
        checkActiveWorkflowConflict(conversationId, userId);

        //create 只初始化 run（不执行 LLM），save 后再跑初始步骤
        AiWorkflowAdvanceResult result = learningPlanWorkflowHandler.create(userId, dto, emitter);
        save(result.getRun());

        try {
            result = learningPlanWorkflowHandler.runInitialSteps(result.getRun(), emitter);
            updateById(result.getRun());

            //绑定
            bindSessionActiveWorkflow(result.getRun());

            //写Step Log
            recordWorkflowSuccess(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "创建学习规划工作流",
                    outputStatus(result.getRun()),
                    start
            );

            return toVo(result);
        } catch (RuntimeException e) {
            markFailed(result.getRun(), e);

            //失败也绑定会话：前端刷新后还能从会话恢复 FAILED 面板和重试入口
            bindSessionActiveWorkflow(result.getRun());

            recordWorkflowFailure(
                    result.getRun(),
                    currentStep(result.getRun()),
                    "创建学习规划工作流",
                    e,
                    start
            );

            return toVo(result.getRun());
        }
    }

    @Override  //查询 Workflow。
    public AiWorkflowRunVO getWorkflowRun(Long id) {
        //必须校验 userId，避免用户看到别人的 workflow。
        Long userId = requireLogin();
        AiWorkflowRun run = getOwnedRun(id,userId);

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
        Long userId = requireLogin();
        AiWorkflowRun run = getOwnedRun(id, userId);

        long start = System.currentTimeMillis();
        String step = currentStep(run);

        try{
            //推进状态（按 workflowType 路由到对应 Handler）
            WorkflowHandler handler = workflowHandlerRegistry.get(run.getWorkflowType());
            AiWorkflowAdvanceResult result = handler.approve(run, emitter);
            updateById(result.getRun());
            clearErrorMessage(run.getId());

            //handler 内直接置 COMPLETED 的流程（如学习规划 SAVE_PLAN）不走 complete()，
            // 这里统一收口：结束态清 session 绑定，避免刷新后误恢复已结束的 workflow
            if (AiWorkflowStatus.COMPLETED.name().equals(result.getRun().getStatus())) {
                clearSessionActiveWorkflow(result.getRun());
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
            markFailed(run, e);

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
        Long userId = requireLogin();
        AiWorkflowRun run = getOwnedRun(id, userId);

        long start = System.currentTimeMillis();
        String step = currentStep(run);

        try{
            WorkflowHandler handler = workflowHandlerRegistry.get(run.getWorkflowType());
            AiWorkflowAdvanceResult result = handler.reject(run, feedback,emitter);
            updateById(result.getRun());
            clearErrorMessage(run.getId());

            //否定反馈导致 CANCELLED（如"算了"）→ 清 session 绑定，让下一条消息回普通聊天
            if (AiWorkflowStatus.CANCELLED.name().equals(result.getRun().getStatus())) {
                clearSessionActiveWorkflow(result.getRun());
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
            markFailed(run, e);

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

    @Override  //完成 Workflow：仅 WAITING_USER_SAVE（编辑器已填充，用户保存/发布后收口）。
    @Transactional
    public AiWorkflowRunVO complete(Long id) {
        Long userId = requireLogin();
        long start = System.currentTimeMillis();
        AiWorkflowRun run = getOwnedRun(id, userId);

        if (!AiWorkflowStatus.WAITING_USER_SAVE.name().equals(run.getStatus())) {
            throw new IllegalArgumentException("当前状态不允许完成 Workflow");
        }

        run.setStatus(AiWorkflowStatus.COMPLETED.name());
        touch(run);
        updateById(run);

        clearSessionActiveWorkflow(run);

        //complete 不会调用 handler，
        // 但它是 workflow 生命周期收口，也应该记一条成功日志。
        recordWorkflowSuccess(
                run,
                currentStep(run),
                "老板完成工作流",
                outputStatus(run),
                start
        );

        return toVo(run);
    }

    @Override  //取消 Workflow。
    @Transactional
    public void cancel(Long id) {
        Long userId = requireLogin();
        AiWorkflowRun run = getOwnedRun(id, userId);

        long start = System.currentTimeMillis();
        String step = currentStep(run);

        if (AiWorkflowStatus.COMPLETED.name().equals(run.getStatus())
                || AiWorkflowStatus.CANCELLED.name().equals(run.getStatus())) {
            return;
        }

        run.setStatus(AiWorkflowStatus.CANCELLED.name());
        touch(run);
        updateById(run);

        clearSessionActiveWorkflow(run);

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
        Long userId = requireLogin();
        AiWorkflowRun run = getOwnedRun(id, userId);

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
        Long userId = requireLogin();
        AiWorkflowRun run = getOwnedRun(id, userId);

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
            clearErrorMessage(run.getId());

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
            markFailed(run, e);

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
    private void markFailed(AiWorkflowRun run, Exception e) {
        run.setStatus(AiWorkflowStatus.FAILED.name());
        run.setErrorMessage(e == null ? "Workflow执行失败" : e.getMessage());
        touch(run);
        updateById(run);

        //原始堆栈只进后端日志（前端/DB 展示友好文案，排障信息不丢）
        log.error("Workflow 执行失败: runId={}, step={}, error={}",
                run.getId(), run.getCurrentStep(), e.getMessage(), e);
    }

    //MyBatis-Plus updateById 默认不更新 null 字段，清空 errorMessage 必须显式 set
    private void clearErrorMessage(Long runId) {
        lambdaUpdate()
                .eq(AiWorkflowRun::getId, runId)
                .set(AiWorkflowRun::getErrorMessage, null)
                .update();
    }

    //有关工作流冲突的检查方法
    private void checkActiveWorkflowConflict(Long conversationId, Long userId) {
        if (conversationId == null) {
            return;
        }
        //查会话
        AiSessions session = aiSessionService.getById(conversationId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new IllegalArgumentException("AI会话不存在");
        }

        Long activeWorkflowRunId = session.getActiveWorkflowRunId();
        if (activeWorkflowRunId == null) {
            //不存在，放行
            return;
        }

        //存在，查那个run
        AiWorkflowRun activeRun = getById(activeWorkflowRunId);
        if (activeRun == null || workflowStatusSupport.isFinished(activeRun.getStatus())) {
            //脏绑定，自动清掉
            clearSessionActiveWorkflowBySession(session);
            return;
        }

        throw new IllegalArgumentException("当前会话还有未完成的 Workflow，请先完成或取消当前任务");
    }
    private void bindSessionActiveWorkflow(AiWorkflowRun run) {
        if (run.getConversationId() == null) {
            return;
        }

        aiSessionService.lambdaUpdate()
                .eq(AiSessions::getId, run.getConversationId())
                .eq(AiSessions::getUserId, run.getUserId())
                .set(AiSessions::getActiveWorkflowRunId, run.getId())
                .set(AiSessions::getUpdatedAt, LocalDateTime.now())
                .update();
    }
    private void clearSessionActiveWorkflowBySession(AiSessions session) {
        aiSessionService.lambdaUpdate()
                .eq(AiSessions::getId, session.getId())
                .eq(AiSessions::getUserId, session.getUserId())
                .set(AiSessions::getActiveWorkflowRunId, null)
                .set(AiSessions::getUpdatedAt, LocalDateTime.now())
                .update();

        session.setActiveWorkflowRunId(null);
    }


    private AiWorkflowRun getOwnedRun(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("Workflow ID不能为空");
        }

        AiWorkflowRun run = lambdaQuery()
                .eq(AiWorkflowRun::getId, id)
                .eq(AiWorkflowRun::getUserId, userId)
                .one();

        if (run == null) {
            throw new IllegalArgumentException("Workflow不存在");
        }

        return run;
    }
    private void touch(AiWorkflowRun run) {
        run.setUpdatedAt(LocalDateTime.now());
    }

    private void clearSessionActiveWorkflow(AiWorkflowRun run) {
        if (run.getConversationId() == null) {
            return;
        }

        aiSessionService.lambdaUpdate()
                .eq(AiSessions::getId, run.getConversationId())
                .eq(AiSessions::getUserId, run.getUserId())
                .eq(AiSessions::getActiveWorkflowRunId, run.getId())
                .set(AiSessions::getActiveWorkflowRunId, null)
                .set(AiSessions::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    private Long requireLogin() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
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
