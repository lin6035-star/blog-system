package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.AiWorkflowStepLog;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import com.hailin.blogsystem.entity.vo.AiWorkflowStepLogVO;
import com.hailin.blogsystem.mapper.AiWorkflowStepLogMapper;
import com.hailin.blogsystem.service.AiWorkflowStepLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class AiWorkflowStepLogServiceImpl extends ServiceImpl<AiWorkflowStepLogMapper, AiWorkflowStepLog>
        implements AiWorkflowStepLogService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    @Override
    public void recordSuccess(Long workflowRunId,
                             String step,
                             String inputSummary,
                             String outputSummary,
                             long durationMs){
        recordSuccess(workflowRunId, step, inputSummary, outputSummary, durationMs, LOG_TYPE_OPERATION);
    }

    @Override
    //REQUIRES_NEW：步骤日志独立事务立即提交——生成草稿这类长步骤期间前端就能查到，且外层事务失败回滚时不会把成功日志一起卷掉
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long workflowRunId,
                             String step,
                             String inputSummary,
                             String outputSummary,
                             long durationMs,
                             String logType){

        AiWorkflowStepLog log = buildBaseLog(workflowRunId, step, STATUS_SUCCESS, durationMs, logType);
        log.setInputSummary(truncate(inputSummary, 2000));
        log.setOutputSummary(truncate(outputSummary, 2000));
        save(log);
    }

    @Override
    public void recordFailure(Long workflowRunId, String step, String inputSummary, Exception e, long durationMs) {
        recordFailure(workflowRunId, step, inputSummary, e, durationMs, LOG_TYPE_OPERATION);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long workflowRunId, String step, String inputSummary, Exception e, long durationMs, String logType) {
        AiWorkflowStepLog log = buildBaseLog(workflowRunId, step, STATUS_FAILED, durationMs, logType);
        log.setInputSummary(truncate(inputSummary, 2000));
        log.setErrorMessage(truncate(e == null ? null : e.getMessage(), 1000));
        save(log);
    }

    @Override
    public List<AiWorkflowStepLogVO> listByWorkflowRunId(Long workflowRunId) {
        return lambdaQuery()
                .eq(AiWorkflowStepLog::getWorkflowRunId, workflowRunId)
                .orderByAsc(AiWorkflowStepLog::getStartedAt)
                .orderByAsc(AiWorkflowStepLog::getId)
                .list()
                .stream()
                .map(AiWorkflowStepLogVO::from)
                .toList();
    }

    private AiWorkflowStepLog buildBaseLog(Long workflowRunId,
                                           String step,
                                           String status,
                                           long durationMs,
                                           String logType){

        LocalDateTime endedAt = LocalDateTime.now();
        long safeDurationMs = Math.max(durationMs, 0);

        AiWorkflowStepLog log = new AiWorkflowStepLog();
        log.setWorkflowRunId(workflowRunId);
        log.setLogType(logType == null ? LOG_TYPE_OPERATION : logType);
        log.setStep(step);
        log.setStepOrder(resolveStepOrder(step));
        log.setStatus(status);
        //retryCount 按 logType 独立统计：操作级和步骤级日志互不干扰
        log.setRetryCount(resolveRetryCount(workflowRunId, step, logType));
        log.setStartedAt(endedAt.minus(safeDurationMs, ChronoUnit.MILLIS));
        log.setEndedAt(endedAt);
        log.setDurationMs(safeDurationMs);
        log.setInputTokens(0);
        log.setOutputTokens(0);
        log.setCreatedAt(endedAt);
        return log;
    }

    private Integer resolveStepOrder(String step) {
        try {
            return AiWorkflowStep.valueOf(step).getOrder();
        } catch (Exception e) {
            return 0;
        }
    }

    private Integer resolveRetryCount(Long workflowRunId, String step, String logType) {
        Long count = lambdaQuery()
                .eq(AiWorkflowStepLog::getWorkflowRunId, workflowRunId)
                .eq(AiWorkflowStepLog::getStep, step)
                .eq(AiWorkflowStepLog::getLogType, logType == null ? LOG_TYPE_OPERATION : logType)
                .count();
        return count == null ? 0 : count.intValue();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
