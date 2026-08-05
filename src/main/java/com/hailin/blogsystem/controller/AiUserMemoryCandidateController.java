package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.vo.AiUserMemoryCandidateVO;
import com.hailin.blogsystem.service.AiUserMemoryCandidateService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/memory-candidates")
@RequiredArgsConstructor
public class AiUserMemoryCandidateController {

    private final AiUserMemoryCandidateService aiUserMemoryCandidateService;

    @GetMapping  //查看当前登录用户待确认的记忆
    public Result<List<AiUserMemoryCandidateVO>> listPendingCandidates() {
        return Result.success(aiUserMemoryCandidateService.listPendingCandidates());
    }

    @PostMapping("/{id}/confirm")  //确认候选，写入正式记忆
    public Result<Void> confirmCandidate(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String overrideContent = body != null ? body.get("content") : null;
        aiUserMemoryCandidateService.confirmCandidate(id, overrideContent);
        return Result.success();
    }

    @PostMapping("/{id}/reject")  //拒绝候选，只改状态
    public Result<Void> rejectCandidate(@PathVariable Long id) {
        aiUserMemoryCandidateService.rejectCandidate(id);
        return Result.success();
    }
}