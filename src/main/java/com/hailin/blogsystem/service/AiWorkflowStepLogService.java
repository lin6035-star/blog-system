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

    void recordFailure(Long workflowRunId, String step, String inputSummary, Exception e, long durationMs);

    void recordFailure(Long workflowRunId, String step, String inputSummary, Exception e, long durationMs, String logType);

    List<AiWorkflowStepLogVO> listByWorkflowRunId(Long workflowRunId);
}