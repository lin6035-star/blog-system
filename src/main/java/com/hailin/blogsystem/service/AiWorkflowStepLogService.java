package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.AiWorkflowStepLog;
import com.hailin.blogsystem.entity.vo.AiWorkflowStepLogVO;

import java.util.List;

public interface AiWorkflowStepLogService extends IService<AiWorkflowStepLog> {
    String LOG_TYPE_OPERATION = "OPERATION";
    String LOG_TYPE_STEP = "STEP";

    //旧签名默认操作级日志（兼容现有调用）
    void recordSuccess(Long workflowRunId, String step, String inputSummary, String outputSummary, long durationMs);

    //logType 区分操作级/步骤级，retryCount 按类型独立统计
    void recordSuccess(Long workflowRunId, String step, String inputSummary, String outputSummary, long durationMs, String logType);

    /**
     * 带 token 用量的步骤级记录。
     *
     * 只有步骤级日志会经过 LLM——操作级（上面两个旧签名）不调模型，token 恒为 0。
     * 真实用量由 WorkflowTokenRecorder 暂存、WorkflowStepRunner 取走后传入，handler 侧零改动。
     */
    void recordSuccess(Long workflowRunId, String step, String inputSummary, String outputSummary,
                       long durationMs, String logType, int inputTokens, int outputTokens);

    void recordFailure(Long workflowRunId, String step, String inputSummary, Exception e, long durationMs);

    void recordFailure(Long workflowRunId, String step, String inputSummary, Exception e, long durationMs, String logType);

    void recordFailure(Long workflowRunId, String step, String inputSummary, Exception e,
                       long durationMs, String logType, int inputTokens, int outputTokens);

    List<AiWorkflowStepLogVO> listByWorkflowRunId(Long workflowRunId);
}
