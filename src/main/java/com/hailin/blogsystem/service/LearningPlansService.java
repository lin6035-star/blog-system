package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;

import java.util.List;

public interface LearningPlansService extends IService<LearningPlans> {

    // 1. 保存/更新计划：幂等——按 source_workflow_run_id 查
//    已存在 → 更新 plan 字段 + 删掉旧 stages 重插（覆盖式）
//    不存在 → 插入 plan + 插入 stages
//    @Transactional，防止重试产生重复计划
    void saveOrUpdatePlan(LearningPlans plan, List<LearningStages> stages);

    // 2. 我的计划列表（个人中心入口）
    List<LearningPlans> listByUser(Long userId);

    // 3. 计划详情：plan + 按 order_num 排序的 stages
//    必须校验 plan.userId == userId（参考 getOwnedRun 的权限模式）
//    进度实时聚合：统计所有 stages 的 tasks 中 done=true / 总数，不存库
    LearningPlansDetailVO getDetail(Long planId, Long userId);
}
