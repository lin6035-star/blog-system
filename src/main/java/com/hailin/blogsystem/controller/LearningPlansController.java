package com.hailin.blogsystem.controller;


import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.service.LearningPlansService;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
