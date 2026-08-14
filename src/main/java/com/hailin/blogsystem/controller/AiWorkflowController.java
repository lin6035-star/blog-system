package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.dto.AiWorkflowCreateArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO;
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

@RestController
@RequestMapping("/api/ai/workflows")
@RequiredArgsConstructor
public class AiWorkflowController {

    private final AiWorkflowRunService aiWorkflowRunService;
    private final AiWorkflowStreamService aiWorkflowStreamService;

    @PostMapping("/article/create")  //创建工作流
    public Result<AiWorkflowRunVO> createArticleWorkflow(@RequestBody AiWorkflowCreateArticleDTO dto) {
        return Result.success(aiWorkflowRunService.createArticleWorkflow(dto));
    }

    @PostMapping("/article/optimize")  //创建文章优化工作流
    public Result<AiWorkflowRunVO> createArticleOptimizeWorkflow(@RequestBody AiWorkflowOptimizeArticleDTO dto) {
        return Result.success(aiWorkflowRunService.createArticleOptimizeWorkflow(dto));
    }

    @GetMapping("/{id}")
    public Result<AiWorkflowRunVO> getWorkflowRun(@PathVariable Long id) {
        return Result.success(aiWorkflowRunService.getWorkflowRun(id));
    }

    @PostMapping("/{id}/approve")
    public Result<AiWorkflowRunVO> approve(@PathVariable Long id) {
        return Result.success(aiWorkflowRunService.approve(id));
    }

    @PostMapping("/{id}/reject")
    public Result<AiWorkflowRunVO> reject(@PathVariable Long id, @RequestBody AiWorkflowRejectDTO dto) {
        return Result.success(aiWorkflowRunService.reject(id, dto.getFeedback()));
    }

    @PostMapping("/{id}/complete")
    public Result<AiWorkflowRunVO> complete(@PathVariable Long id) {
        return Result.success(aiWorkflowRunService.complete(id));
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

    @PostMapping("/{id}/retry")
    public Result<AiWorkflowRunVO> retry(@PathVariable Long id){
        return Result.success(aiWorkflowRunService.retry(id));
    }

    @PostMapping(value = "/{id}/approve/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatEventVO> approveStream(@PathVariable Long id) {
        return aiWorkflowStreamService.approve(id);
    }

    @PostMapping(value = "/{id}/reject/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatEventVO> rejectStream(@PathVariable Long id, @RequestBody AiWorkflowRejectDTO dto) {
        return aiWorkflowStreamService.reject(id, dto.getFeedback());
    }

    @PostMapping(value = "/{id}/retry/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatEventVO> retryStream(@PathVariable Long id) {
        return aiWorkflowStreamService.retry(id);
    }
}