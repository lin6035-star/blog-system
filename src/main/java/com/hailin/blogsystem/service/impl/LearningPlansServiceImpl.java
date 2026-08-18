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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LearningPlansServiceImpl extends ServiceImpl<LearningPlanMapper, LearningPlans>
        implements LearningPlansService {

    //提取字母数字段和汉字段（"RocketMQ学习计划" → rocketmq / 学习计划）
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+|[\\u4e00-\\u9fa5]+");

    private final LearningStageMapper learningStageMapper;
    private final ObjectMapper objectMapper;

    //幂等保存，两条覆盖路径：
    //  plan.id 非空（调整已有计划）→ 按 id 覆盖，目标必须存在且属于同一用户
    //  plan.id 空 + source_workflow_run_id 非空（新建计划）→ 按 source 唯一键幂等
    //已存在 → 更新 plan 字段（保留原 createdAt）+ 删旧 stages 重插（覆盖式）；不存在 → 插入
    @Override
    @Transactional
    public void saveOrUpdatePlan(LearningPlans plan, List<LearningStages> stages) {
        LearningPlans existing = null;
        if (plan.getId() != null) {
            existing = getById(plan.getId());
            if (existing == null || !existing.getUserId().equals(plan.getUserId())) {
                throw new IllegalArgumentException("学习计划不存在或无权访问");
            }
        } else if (plan.getSourceWorkflowRunId() != null) {
            existing = getOne(new LambdaQueryWrapper<LearningPlans>()
                    .eq(LearningPlans::getSourceWorkflowRunId, plan.getSourceWorkflowRunId()));
        }

        if (existing != null) {
            plan.setId(existing.getId());
            plan.setCreatedAt(existing.getCreatedAt());
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

        refreshPlanStatus(plan.getId());//检查学习规划是否都已完成
    }

    //任务勾选：校验计划归属 → 改 tasks JSON 第 index 项的 done → 写回（进度实时聚合，不存库）
    @Override
    @Transactional
    public void updateTaskDone(Long planId, Long stageId, int taskIndex, boolean done, Long userId) {
        LearningPlans plan = getById(planId);
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("学习计划不存在或无权访问");
        }

        LearningStages stage = learningStageMapper.selectOne(new LambdaQueryWrapper<LearningStages>()
                .eq(LearningStages::getId, stageId)
                .eq(LearningStages::getPlanId, planId));
        if (stage == null) {
            throw new IllegalArgumentException("学习阶段不存在");
        }

        List<Map<String, Object>> tasks = parseTaskMaps(stage.getTasks());
        if (taskIndex < 0 || taskIndex >= tasks.size()) {
            throw new IllegalArgumentException("任务不存在");
        }
        tasks.get(taskIndex).put("done", done);

        try {
            stage.setTasks(objectMapper.writeValueAsString(tasks));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("任务序列化失败", e);
        }
        stage.setUpdatedAt(LocalDateTime.now());
        learningStageMapper.updateById(stage);

        refreshPlanStatus(planId);//检查学习规划是否都已完成
    }

    //JSON 字符串 → List<Map>；解析失败兜底空列表
    private List<Map<String, Object>> parseTaskMaps(String tasksJson) {
        if (tasksJson == null || tasksJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(tasksJson, new TypeReference<>() {});
            return raw == null ? new ArrayList<>() : raw;
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
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

    // ==================== 计划匹配（查询 Tool / 入口点名共用） ====================

    //标题关键词匹配：分词 AND 全命中（不过滤状态，查询 Tool 详情用）。
    // 分词解决"RocketMQ学习计划" vs "RocketMQ 30天入门学习计划"非连续子串的漏配
    @Override
    public List<LearningPlans> matchPlansByTitle(Long userId, String keyword) {
        List<String> tokens = tokenize(keyword);
        if (tokens.isEmpty()) {
            return new ArrayList<>();
        }
        return listByUser(userId).stream()
                .filter(plan -> allTokensHit(plan.getTitle(), tokens))
                .toList();
    }

    //整句消息点名匹配（入口用，只 ACTIVE）：分词 + 命中词段数打分，返回最高分计划列表。
    // 唯一最高分 = 点名成功；并列 = 歧义（需追问）；空 = 未点名（入口 fallback 最新 ACTIVE）
    @Override
    public List<LearningPlans> matchActivePlansByMessage(Long userId, String message) {
        List<String> tokens = tokenize(message);
        List<LearningPlans> actives = listByUser(userId).stream()
                .filter(plan -> LearningPlans.STATUS_ACTIVE.equals(plan.getStatus()))
                .toList();
        if (tokens.isEmpty() || actives.isEmpty()) {
            return new ArrayList<>();
        }

        int bestScore = 0;
        List<LearningPlans> bestPlans = new ArrayList<>();
        for (LearningPlans plan : actives) {
            int score = score(plan.getTitle(), tokens);
            if (score > bestScore) {
                bestScore = score;
                bestPlans.clear();
                bestPlans.add(plan);
            } else if (score == bestScore && score > 0) {
                bestPlans.add(plan);
            }
        }
        return bestPlans;
    }

    //分词：字母数字段 + 汉字段；长汉字段（>4 字）拆 2-gram 滑窗——
    // 中文整句点名（"压缩机器学习那个计划"）也能靠 2-gram 命中标题词（机器/学习/计划）
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            String segment = matcher.group();
            if (segment.length() <= 4 || !isHan(segment.charAt(0))) {
                tokens.add(segment);
            } else {
                for (int i = 0; i + 1 < segment.length(); i++) {
                    tokens.add(segment.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    private boolean isHan(char c) {
        return c >= '一' && c <= '龥';
    }

    private boolean allTokensHit(String title, List<String> tokens) {
        if (title == null) {
            return false;
        }
        String lowerTitle = title.toLowerCase();
        for (String token : tokens) {
            if (!lowerTitle.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private int score(String title, List<String> tokens) {
        if (title == null) {
            return 0;
        }
        String lowerTitle = title.toLowerCase();
        int score = 0;
        for (String token : tokens) {
            if (lowerTitle.contains(token)) {
                score++;
            }
        }
        return score;
    }

    //阶段级点名（攻坚 LOCATE_STAGE 用）：阶段按"标题 + 全部任务标题"拼接文本打分。
    // 唯一最高分 = 定位成功；并列 = 歧义；空 = 未点名（Handler 列全部阶段追问）
    @Override
    public List<LearningStages> matchStagesByMessage(Long planId, Long userId, String message) {
        LearningPlans plan = getById(planId);
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("学习计划不存在或无权访问");
        }
        List<String> tokens = tokenize(message);
        List<LearningStages> stages = learningStageMapper.selectList(new LambdaQueryWrapper<LearningStages>()
                .eq(LearningStages::getPlanId, planId)
                .orderByAsc(LearningStages::getOrderNum));
        if (tokens.isEmpty() || stages.isEmpty()) {
            return new ArrayList<>();
        }

        int bestScore = 0;
        List<LearningStages> bestStages = new ArrayList<>();
        for (LearningStages stage : stages) {
            int stageScore = score(stageText(stage), tokens);
            if (stageScore > bestScore) {
                bestScore = stageScore;
                bestStages.clear();
                bestStages.add(stage);
            } else if (stageScore == bestScore && stageScore > 0) {
                bestStages.add(stage);
            }
        }
        return bestStages;
    }

    //阶段打分文本 = 阶段标题 + 全部任务标题（消息命中某个任务也能定位到所在阶段）
    private String stageText(LearningStages stage) {
        StringBuilder sb = new StringBuilder(stage.getTitle() == null ? "" : stage.getTitle());
        for (LearningPlansDetailVO.TaskItem task : parseTasks(stage.getTasks())) {
            sb.append(" ").append(task.getTitle());
        }
        return sb.toString();
    }

    //追加任务点（攻坚 APPEND_TASKS 用）：过滤空/重复标题（与已有任务 + 输入内去重，
    // 去重使 approve 后 retry 重跑天然幂等）→ 追加 done=false → 写回（只动目标阶段行）
    @Override
    @Transactional
    public void appendTasks(Long planId, Long stageId, List<String> taskTitles, Long userId) {
        LearningPlans plan = getById(planId);
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("学习计划不存在或无权访问");
        }

        LearningStages stage = learningStageMapper.selectOne(new LambdaQueryWrapper<LearningStages>()
                .eq(LearningStages::getId, stageId)
                .eq(LearningStages::getPlanId, planId));
        if (stage == null) {
            throw new IllegalArgumentException("学习阶段不存在");
        }

        List<Map<String, Object>> tasks = parseTaskMaps(stage.getTasks());
        Set<String> existing = new HashSet<>();
        for (Map<String, Object> task : tasks) {
            Object title = task.get("title");
            existing.add(title == null ? "" : String.valueOf(title).trim().toLowerCase());
        }
        int appended = 0;
        for (String raw : taskTitles == null ? List.<String>of() : taskTitles) {
            String title = raw == null ? "" : raw.trim();
            if (title.isBlank() || existing.contains(title.toLowerCase())) {
                continue;
            }
            Map<String, Object> task = new HashMap<>();
            task.put("title", title);
            task.put("done", Boolean.FALSE);
            tasks.add(task);
            existing.add(title.toLowerCase());
            appended++;
        }
        if (appended == 0) {
            return;  //没有可追加的任务（全重复/全空），不写库
        }
        try {
            stage.setTasks(objectMapper.writeValueAsString(tasks));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("任务序列化失败", e);
        }
        stage.setUpdatedAt(LocalDateTime.now());
        learningStageMapper.updateById(stage);

        refreshPlanStatus(planId);//检查学习规划是否都已完成
    }


    @Override
    @Transactional //删除学习规划
    public void deletePlan(Long planId, Long userId) {
        LearningPlans plan = getById(planId);
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("学习计划不存在或无权访问");
        }

        learningStageMapper.delete(new LambdaQueryWrapper<LearningStages>()
                .eq(LearningStages::getPlanId, planId));
        removeById(planId);
    }

    private void refreshPlanStatus(Long planId){
        LearningPlans plan = getById(planId);
        if(plan == null){
            return;
        }

        int total = 0;
        int done = 0;
        List<LearningStages> stages = learningStageMapper.selectList(
                new LambdaQueryWrapper<LearningStages>()
                        .eq(LearningStages::getPlanId, planId));

        for (LearningStages stage : stages) {
            for (Map<String, Object> task : parseTaskMaps(stage.getTasks())) {
                total++;
                if (Boolean.TRUE.equals(task.get("done"))) {
                    done++;
                }
            }
        }

        String nextStatus = total > 0 && done == total
                ? LearningPlans.STATUS_COMPLETED
                : LearningPlans.STATUS_ACTIVE;

        if (!nextStatus.equals(plan.getStatus())) {
            plan.setStatus(nextStatus);
            plan.setUpdatedAt(LocalDateTime.now());
            updateById(plan);
        }

    }
}
