package com.hailin.blogsystem.ai.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * run 生命周期管理（Manager = 生命周期）：登录校验 / run 归属查询 / 活跃 Workflow 冲突检查 /
 * 会话绑定与清理 / 失败标记 / 错误信息清理。
 * 纯工具不做事务——事务边界在调用方 ServiceImpl 的 @Transactional 上，
 * 本类 mapper 操作与调用方同事务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowRunManager {

    private final AiWorkflowRunMapper aiWorkflowRunMapper;
    private final AiSessionService aiSessionService;
    private final WorkflowStatusSupport workflowStatusSupport;

    public Long requireLogin() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }

    public AiWorkflowRun getOwnedRun(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("Workflow ID不能为空");
        }

        AiWorkflowRun run = aiWorkflowRunMapper.selectOne(new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getId, id)
                .eq(AiWorkflowRun::getUserId, userId));

        if (run == null) {
            throw new IllegalArgumentException("Workflow不存在");
        }

        return run;
    }

    //会话已有未完成的活跃 Workflow → 拒绝起新 Workflow；脏绑定（run 已结束/不存在）自动清理放行
    public void checkActiveWorkflowConflict(Long conversationId, Long userId) {
        if (conversationId == null) {
            return;
        }
        AiSessions session = aiSessionService.getById(conversationId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new IllegalArgumentException("AI会话不存在");
        }

        Long activeWorkflowRunId = session.getActiveWorkflowRunId();
        if (activeWorkflowRunId == null) {
            return;
        }

        AiWorkflowRun activeRun = aiWorkflowRunMapper.selectById(activeWorkflowRunId);
        if (activeRun == null || workflowStatusSupport.isFinished(activeRun.getStatus())) {
            //脏绑定，自动清掉
            clearSessionActiveWorkflowBySession(session);
            return;
        }

        throw new IllegalArgumentException("当前会话还有未完成的 Workflow，请先完成或取消当前任务");
    }

    public void bindSessionActiveWorkflow(AiWorkflowRun run) {
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

    //Workflow 结束态统一收口：清 session 绑定，避免刷新后误恢复已结束的 workflow
    public void clearSessionActiveWorkflow(AiWorkflowRun run) {
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

    private void clearSessionActiveWorkflowBySession(AiSessions session) {
        aiSessionService.lambdaUpdate()
                .eq(AiSessions::getId, session.getId())
                .eq(AiSessions::getUserId, session.getUserId())
                .set(AiSessions::getActiveWorkflowRunId, null)
                .set(AiSessions::getUpdatedAt, LocalDateTime.now())
                .update();

        session.setActiveWorkflowRunId(null);
    }

    //失败标记：FAILED + 友好 errorMessage；原始堆栈只进后端日志（前端/DB 展示友好文案，排障信息不丢）
    public void markFailed(AiWorkflowRun run, Exception e) {
        run.setStatus(AiWorkflowStatus.FAILED.name());
        run.setErrorMessage(e == null ? "Workflow执行失败" : e.getMessage());
        touch(run);
        aiWorkflowRunMapper.updateById(run);

        log.error("Workflow 执行失败: runId={}, step={}, error={}",
                run.getId(), run.getCurrentStep(), e.getMessage(), e);
    }

    //MyBatis-Plus updateById 默认不更新 null 字段，清空 errorMessage 必须显式 set
    public void clearErrorMessage(Long runId) {
        aiWorkflowRunMapper.update(null, new LambdaUpdateWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getId, runId)
                .set(AiWorkflowRun::getErrorMessage, null));
    }

    public void touch(AiWorkflowRun run) {
        run.setUpdatedAt(LocalDateTime.now());
    }
}
