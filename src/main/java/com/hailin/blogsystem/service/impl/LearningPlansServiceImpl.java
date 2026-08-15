package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.mapper.LearningPlanMapper;
import com.hailin.blogsystem.mapper.LearningStageMapper;
import com.hailin.blogsystem.service.LearningPlansService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LearningPlansServiceImpl extends ServiceImpl<LearningPlanMapper, LearningPlans>
        implements LearningPlansService {

    private final LearningStageMapper learningStageMapper;
    private final ObjectMapper objectMapper;

    //幂等：按 source_workflow_run_id 查（唯一键）。
    //已存在 → 更新 plan 字段 + 删旧 stages 重插（覆盖式）
    //不存在 → 插入 plan + 插入 stages
    @Override
    @Transactional
    public void saveOrUpdatePlan(LearningPlans plan, List<LearningStages> stages) {
        LearningPlans existing = plan.getSourceWorkflowRunId() == null ? null
                : getOne(new LambdaQueryWrapper<LearningPlans>()
                        .eq(LearningPlans::getSourceWorkflowRunId, plan.getSourceWorkflowRunId()));

        if (existing != null) {
            plan.setId(existing.getId());
            updateById(plan);
            learningStageMapper.delete(new LambdaQueryWrapper<LearningStages>()
                    .eq(LearningStages::getPlanId, existing.getId()));
        } else {
            save(plan);  //雪花 id 回填到 plan，后续 stages 用它做 planId
        }

        for (LearningStages stage : stages) {
            stage.setPlanId(plan.getId());
            learningStageMapper.insert(stage);
        }
    }

    @Override
    public List<LearningPlans> listByUser(Long userId) {
        return list(new LambdaQueryWrapper<LearningPlans>()
                .eq(LearningPlans::getUserId, userId)
                .orderByDesc(LearningPlans::getCreatedAt));
    }

    //权限：plan.userId 必须等于当前用户；进度实时聚合（不存库）
    @Override
    public LearningPlansDetailVO getDetail(Long planId, Long userId) {
        LearningPlans plan = getById(planId);
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("学习计划不存在或无权访问");
        }

        List<LearningStages> stages = learningStageMapper.selectList(
                new LambdaQueryWrapper<LearningStages>()
                        .eq(LearningStages::getPlanId, planId)
                        .orderByAsc(LearningStages::getOrderNum));

        LearningPlansDetailVO vo = new LearningPlansDetailVO();
        vo.setPlan(plan);

        List<LearningPlansDetailVO.StageProgress> stageProgressList = new ArrayList<>();
        int doneTasks = 0;
        int totalTasks = 0;
        for (LearningStages stage : stages) {
            LearningPlansDetailVO.StageProgress sp = new LearningPlansDetailVO.StageProgress();
            sp.setId(stage.getId());
            sp.setOrderNum(stage.getOrderNum());
            sp.setTitle(stage.getTitle());
            sp.setTasks(parseTasks(stage.getTasks()));
            stageProgressList.add(sp);

            for (LearningPlansDetailVO.TaskItem task : sp.getTasks()) {
                totalTasks++;
                if (task.isDone()) {
                    doneTasks++;
                }
            }
        }
        vo.setStages(stageProgressList);
        vo.setDoneTasks(doneTasks);
        vo.setTotalTasks(totalTasks);
        return vo;
    }

    //JSON 字符串 → TaskItem 列表；解析失败兜底空列表
    private List<LearningPlansDetailVO.TaskItem> parseTasks(String tasksJson) {
        if (tasksJson == null || tasksJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(tasksJson, new TypeReference<>() {});
            List<LearningPlansDetailVO.TaskItem> tasks = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                LearningPlansDetailVO.TaskItem task = new LearningPlansDetailVO.TaskItem();
                task.setTitle(String.valueOf(item.getOrDefault("title", "")));
                task.setDone(Boolean.TRUE.equals(item.get("done")));
                tasks.add(task);
            }
            return tasks;
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }
}
