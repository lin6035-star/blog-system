package com.hailin.blogsystem.controller;


import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.service.LearningPlansService;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/learning-plans")
@RequiredArgsConstructor
public class LearningPlansController {

    private final LearningPlansService learningPlanService;

    //我的学习计划列表（个人中心入口）
    @GetMapping
    public Result<List<LearningPlans>> listMine() {
        return Result.success(learningPlanService.listByUser(UserContext.get()));
    }

    //计划详情：plan + stages + 聚合进度（Service 内校验归属权限）
    @GetMapping("/{id}")
    public Result<LearningPlansDetailVO> detail(@PathVariable Long id) {
        return Result.success(learningPlanService.getDetail(id, UserContext.get()));
    }

    //任务勾选：body {"done": true/false}，taskIndex 为任务在阶段 tasks 列表中的下标（从 0 开始）
    @PatchMapping("/{planId}/stages/{stageId}/tasks/{taskIndex}")
    public Result<Void> updateTaskDone(
            @PathVariable Long planId,
            @PathVariable Long stageId,
            @PathVariable int taskIndex,
            @RequestBody Map<String, Boolean> body) {
        Boolean done = body == null ? null : body.get("done");
        if (done == null) {
            throw new IllegalArgumentException("done 参数不能为空");
        }
        learningPlanService.updateTaskDone(planId, stageId, taskIndex, done, UserContext.get());
        return Result.success(null);
    }

    //删除学习规划
    @DeleteMapping("/{id}")
    public Result<Void> deleteMine(@PathVariable Long id) {
        learningPlanService.deletePlan(id, UserContext.get());
        return Result.success(null);
    }
}
