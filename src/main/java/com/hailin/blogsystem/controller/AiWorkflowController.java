package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.dto.AiWorkflowRejectDTO;
import com.hailin.blogsystem.entity.vo.AiChatEventVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowStepLogVO;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import com.hailin.blogsystem.service.AiWorkflowStreamService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Workflow 接口。
 *
 * <p><b>动作类只提供流式（SSE）入口</b>——创建 / approve / reject / retry 的同步版已移除。
 * 同步版会在这个 Tomcat 线程上从头跑到 RunInitialSteps 结束（含 LLM，几十秒），
 * 既不受 AI 长任务准入的并发上限约束，又把 HTTP 线程当 worker 用，是绕过准入的后门。
 * 前端早已全面切到流式（{@code ai.ts} 里那批非流式方法无任何调用点），同步版没有调用方。
 *
 * <p>查询类（详情 / 步骤日志 / 取消）保留同步——它们不跑 LLM，不存在上面这个问题。
 *
 * <p>如果将来确实需要同步启动 Workflow，走 {@code AiOrchestrationTaskAdmission.runAdmitted}
 * 占名额，不要直接调 service 的同步重载。
 */
@RestController
@RequestMapping("/api/ai/workflows")
@RequiredArgsConstructor
public class AiWorkflowController {

    private final AiWorkflowRunService aiWorkflowRunService;
    private final AiWorkflowStreamService aiWorkflowStreamService;

    @GetMapping("/{id}")
    public Result<AiWorkflowRunVO> getWorkflowRun(@PathVariable Long id) {
        return Result.success(aiWorkflowRunService.getWorkflowRun(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        aiWorkflowRunService.cancel(id);
        return Result.success();
    }

    @GetMapping("/{id}/steps")
    public Result<List<AiWorkflowStepLogVO>> listStepLogs(@PathVariable Long id){
        return Result.success(aiWorkflowRunService.listStepLogs(id));
    }

    @PostMapping(value = "/{id}/approve/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatEventVO> approveStream(
            @PathVariable Long id,
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) String idempotencyKey
    ) {
        return aiWorkflowStreamService.approve(id, idempotencyKey);
    }

    @PostMapping(value = "/{id}/reject/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatEventVO> rejectStream(
            @PathVariable Long id,
            @RequestBody AiWorkflowRejectDTO dto,
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) String idempotencyKey
    ) {
        return aiWorkflowStreamService.reject(
                id,
                dto.getFeedback(),
                idempotencyKey
        );
    }

    @PostMapping(value = "/{id}/retry/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatEventVO> retryStream(
            @PathVariable Long id,
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) String idempotencyKey
    ) {
        return aiWorkflowStreamService.retry(id, idempotencyKey);
    }
}