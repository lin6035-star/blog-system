package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.mapper.LearningPlanMapper;
import com.hailin.blogsystem.mapper.LearningStageMapper;
import com.hailin.blogsystem.service.LearningPlansService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.hailin.blogsystem.component.CacheTtlSupport;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
@Slf4j
public class LearningPlansServiceImpl extends ServiceImpl<LearningPlanMapper, LearningPlans>
        implements LearningPlansService {

    //提取字母数字段和汉字段（"RocketMQ学习计划" → rocketmq / 学习计划）
    //V4.x：字母数字段补上 + # .（编程语言/框架名：C++ / C# / .NET / Node.js）——
    //原 [a-z0-9]+ 会把 "C++" 切成 "c"，与「C语言系统学习计划」同分并列（实测踩过）。
    //字符集与 RAG 侧 DefaultArticleRagRanker 的分词保持一致。
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9+#.]+|[\\u4e00-\\u9fa5]+");

    /**
     * 计划定位里的通用词不应压过具体技术名：
     * 「C++ 学习计划」同时命中 C++ 计划和 C 语言学习计划时，
     * 「学习计划」只能作为弱兜底，不能制造并列。
     */
    private static final Set<String> GENERIC_PLAN_TOKENS = Set.of(
            "计划", "学习", "学习计划", "规划", "进度", "阶段", "任务"
    );

    /** 阶段序号形态：「第X阶段 / X阶段 / 阶段X」（X = 中文数字或阿拉伯数字） */
    private static final Pattern STAGE_ORDINAL_PATTERN = Pattern.compile(
            "第?([一二三四五六七八九十\\d]+)阶段|阶段([一二三四五六七八九十\\d]+)");

    private final LearningStageMapper learningStageMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheTtlSupport cacheTtlSupport;

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
        //V2.4 乐观锁：updateById 返回 0 = version CAS 失败（并发勾选/追加），必须报出来
        if (learningStageMapper.updateById(stage) == 0) {
            throw new IllegalArgumentException("计划正在被修改，请稍后重试");
        }

        refreshPlanStatus(planId);//检查学习规划是否都已完成
    }

    //任务重命名（V3.3）：校验计划归属 → 改 tasks JSON 第 index 项的 title（done 保留）→ 写回。
    //newTitle 非空校验由调用方（AgentWriteActionService）负责，本方法与 updateTaskDone 同风格信任参数。
    @Override
    @Transactional
    public void renameTask(Long planId, Long stageId, int taskIndex, String newTitle, Long userId) {
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
        tasks.get(taskIndex).put("title", newTitle);

        try {
            stage.setTasks(objectMapper.writeValueAsString(tasks));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("任务序列化失败", e);
        }
        stage.setUpdatedAt(LocalDateTime.now());
        //V3.3 乐观锁：updateById 返回 0 = version CAS 失败（并发勾选/追加/改名），必须报出来
        if (learningStageMapper.updateById(stage) == 0) {
            throw new IllegalArgumentException("计划正在被修改，请稍后重试");
        }

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

    /**
     * ACTIVE 计划列表（Redis 缓存 + 写时失效）。
     *
     * 分类器是所有消息的必经之路（说"你好"也要经过），而它每次都要这份列表才能"选计划"——
     * 缓存挡住绝大多数重复查询。缓存只存 id/title/status 三列（调用方只需从列表里选）。
     *
     * 一致性：写计划 / 换计划状态时失效（evictPlanListCache），TTL 兜底；
     * Redis 不可用时整体退化为直接查库（fail-open，不影响分类主流程）。
     */
    @Override
    public List<LearningPlans> listActiveByUserCached(Long userId) {
        if (userId == null) {
            return List.of();
        }
        String key = RedisConstants.LEARNING_PLAN_LIST_KEY_PREFIX + userId;

        //1. 读缓存（Redis 异常不影响，继续查库）
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<LearningPlans>>() {});
            }
        } catch (Exception e) {
            log.warn("学习计划列表缓存读取失败，回退查库: userId={}", userId, e);
        }

        //2. 查库：状态过滤下推 SQL + 只取三列（原来取全表字段再在内存过滤 ACTIVE）
        List<LearningPlans> actives = list(new LambdaQueryWrapper<LearningPlans>()
                .select(LearningPlans::getId, LearningPlans::getTitle, LearningPlans::getStatus)
                .eq(LearningPlans::getUserId, userId)
                .eq(LearningPlans::getStatus, LearningPlans.STATUS_ACTIVE)
                .orderByDesc(LearningPlans::getCreatedAt));

        //3. 回写（空列表也缓存——防穿透；写失败不影响本次返回）
        try {
            stringRedisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(actives),
                    cacheTtlSupport.jitter(Duration.ofMinutes(RedisConstants.LEARNING_PLAN_LIST_TTL_MINUTES)));
        } catch (Exception e) {
            log.warn("学习计划列表缓存写入失败: userId={}", userId, e);
        }

        return actives;
    }

    //计划列表缓存失效：写计划 / 改任务状态（可能带动计划状态）后调用。
    //失败无害——TTL 兜底；顺序上在写库之后删（Cache-Aside），极端并发下最坏是 TTL 内读到旧列表。
    private void evictPlanListCache(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(RedisConstants.LEARNING_PLAN_LIST_KEY_PREFIX + userId);
        } catch (Exception e) {
            log.warn("学习计划列表缓存失效失败（TTL 兜底）: userId={}", userId, e);
        }
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

    //整句消息点名匹配：分词 + 命中词段数打分，返回最高分计划列表。
    // 唯一最高分 = 点名成功；并列 = 歧义（需追问）；空 = 未点名（入口 fallback 最新 ACTIVE）
    //
    // 不按状态过滤（2026-09-14 修）：原先只认 ACTIVE，导致《Java后端学习路线规划》（COMPLETED）
    // 压根不参与匹配，于是「java」命中了标题里恰好含该词的《Agent 计划（Java后端方向）》——
    // 锚被写到错误的计划上，且每次新会话都重演。锚自身的设计也写着「完成 ≠ 不能再改」
    // （LearningPlanAnchorService.resolve 不校验状态），两处语义原先打架，这里对齐：
    // **匹配只看名字像不像，不替用户决定能不能操作**。
    // 多命中仍返回并列（调用方追问），放开状态不会让系统变"更敢猜"。
    @Override
    public List<LearningPlans> matchPlansByMessage(Long userId, String message) {
        List<String> tokens = tokenize(message);
        List<LearningPlans> plans = listByUser(userId);
        if (tokens.isEmpty() || plans.isEmpty()) {
            return new ArrayList<>();
        }

        // 先用具体词裁决。只要具体词命中至少一个计划，就不让「计划 / 学习」等通用词参与并列。
        List<String> specificTokens = tokens.stream()
                .filter(token -> !GENERIC_PLAN_TOKENS.contains(token))
                .toList();
        if (!specificTokens.isEmpty()) {
            List<LearningPlans> specificMatches = bestScoredPlans(plans, specificTokens);
            if (!specificMatches.isEmpty()) {
                return specificMatches;
            }
        }

        // 没有具体词命中时保留原语义：通用请求对多个计划并列，交给用户选择。
        return bestScoredPlans(plans, tokens);
    }

    private List<LearningPlans> bestScoredPlans(List<LearningPlans> plans, List<String> tokens) {
        int bestScore = 0;
        List<LearningPlans> bestPlans = new ArrayList<>();
        for (LearningPlans plan : plans) {
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
        // 序号直解优先：用户说「阶段三/第3阶段」且阶段标题是主题名（无「阶段N」字样）时，
        // 语义 = 按顺序第 N 个 → 直接查 order_num。标题分词是主题词路径，序号语义必须走 order_num
        // （否则数字 token 会误撞任务标题里的语义数字，如「3 主 3 从」→ 定位错到含 3 的阶段）
        Integer ordinal = extractStageOrdinal(message);
        if (ordinal != null && ordinal >= 1) {
            List<LearningStages> byOrdinal = learningStageMapper.selectList(
                    new LambdaQueryWrapper<LearningStages>()
                            .eq(LearningStages::getPlanId, planId)
                            .eq(LearningStages::getOrderNum, ordinal));
            if (!byOrdinal.isEmpty()) {
                return byOrdinal;
            }
        }
        // 阶段序号归一后再分词：用户说「第三阶段」标题写「阶段三」——2-gram 对词序敏感，
        // 原样分词只有通用「阶段」能命中，多阶段全同分 → 定位不了被迫列候选
        List<String> tokens = tokenize(normalizeStageOrdinals(message));
        List<LearningStages> stages = learningStageMapper.selectList(new LambdaQueryWrapper<LearningStages>()
                .eq(LearningStages::getPlanId, planId)
                .orderByAsc(LearningStages::getOrderNum));
        if (tokens.isEmpty() || stages.isEmpty()) {
            return new ArrayList<>();
        }

        int bestScore = 0;
        List<LearningStages> bestStages = new ArrayList<>();
        for (LearningStages stage : stages) {
            int stageScore = score(normalizeStageOrdinals(stageText(stage)), tokens);
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

    //消息里的阶段序号（「第三阶段 / 阶段三 / 第3阶段 / 阶段3」）→ order_num；无序号 → null
    private Integer extractStageOrdinal(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = STAGE_ORDINAL_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        String num = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (num.matches("\\d+")) {
            return Integer.parseInt(num);
        }
        // 中文序号：一~九 / 十~十九 / 几十 / 几十几
        int index = num.indexOf('十');
        if (index >= 0) {
            int tens = index == 0 ? 1 : digitVal(num.charAt(0));
            int unit = index < num.length() - 1 ? digitVal(num.charAt(index + 1)) : 0;
            return tens * 10 + unit;
        }
        return digitVal(num.charAt(0));
    }

    private int digitVal(char chinese) {
        return "零一二三四五六七八九".indexOf(chinese); //「一」→ 1 …「九」→ 9；未命中 → -1
    }

    //「第X阶段 / X阶段 / 阶段X」双向归一为「阶段（中文序号）」：
    // 标题与消息同形后 2-gram 才能互相命中（「第三阶段」vs「阶段三」词序不同）。
    // 归一到中文而非阿拉伯：阿拉伯「阶段3」的数字 token 是通用 contains 匹配，
    // 会误撞任务标题里的语义数字（如「搭建 3 主 3 从集群」→ 阶段定位错到含 3 的阶段）；
    // 中文「阶段三」的 2-gram「段三」几乎不可能出现在任务标题里
    private String normalizeStageOrdinals(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        Matcher matcher = STAGE_ORDINAL_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String num = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            matcher.appendReplacement(sb, "阶段" + toChineseOrdinal(num));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    //序号归一为中文（一~九十九；已是中文/解析不了保原文）
    private String toChineseOrdinal(String num) {
        if (num.matches("[一二三四五六七八九十]+")) {
            return num;
        }
        if (!num.matches("\\d+") || num.length() > 2) {
            return num;
        }
        int value = Integer.parseInt(num);
        if (value < 1 || value > 99) {
            return num;
        }
        String[] units = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (value <= 9) {
            return units[value];
        }
        if (value == 10) {
            return "十";
        }
        if (value < 20) {
            return "十" + units[value - 10];
        }
        if (value % 10 == 0) {
            return units[value / 10] + "十";
        }
        return units[value / 10] + "十" + units[value % 10];
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
        //V2.4 乐观锁：CAS 失败（并发勾选/追加）必须报出来，不能静默丢更新
        if (learningStageMapper.updateById(stage) == 0) {
            throw new IllegalArgumentException("计划正在被修改，请稍后重试");
        }

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
        evictPlanListCache(userId);//删除不经过 refreshPlanStatus，单独失效
    }

    private void refreshPlanStatus(Long planId){
        LearningPlans plan = getById(planId);
        if(plan == null){
            return;
        }
        //计划状态可能被本次聚合改写（ACTIVE ↔ COMPLETED）→ 列表缓存失效。
        //放这里的理由：4 个写路径（保存 / 勾选 / 改名 / 追加任务）都汇聚到本方法，单点覆盖。
        evictPlanListCache(plan.getUserId());

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
            //V2.4 乐观锁：CAS 失败静默忽略——状态聚合是幂等操作，失败说明另一线程已刷新过，
            //下次任务变更会再触发 refreshPlanStatus，不存在丢更新
            updateById(plan);
        }

    }
}
