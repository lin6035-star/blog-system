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

    // 4. 任务勾选：校验归属 → 改 tasks JSON 第 index 项的 done → 写回（进度实时聚合，不存库）
    void updateTaskDone(Long planId, Long stageId, int taskIndex, boolean done, Long userId);

    // 5. 标题关键词匹配（分词 AND 全命中，查询 Tool 详情用；不过滤状态）
    List<LearningPlans> matchPlansByTitle(Long userId, String keyword);

    // 6. 整句消息点名匹配（入口用，只匹配 ACTIVE）：
//    分词 + 命中词段数打分，返回最高分计划列表——唯一即点名成功，并列即歧义（需追问）
    List<LearningPlans> matchActivePlansByMessage(Long userId, String message);

    // 7. 阶段级点名（难点攻坚 LOCATE_STAGE 用）：校验归属 → 阶段按（标题+任务标题）分词打分
//    → 返回最高分列表——唯一即定位成功，并列/空即歧义（列全部阶段追问）
    List<LearningStages> matchStagesByMessage(Long planId, Long userId, String message);

    // 8. 追加任务点（难点攻坚 APPEND_TASKS 用）：读 stage tasks JSON → 过滤空/重复标题
//    （与已有任务 + 输入内去重，重跑天然幂等）→ 追加 done=false → 写回（只动目标阶段行）
    void appendTasks(Long planId, Long stageId, List<String> taskTitles, Long userId);

    void deletePlan(Long planId, Long userId);
}
