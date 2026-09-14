package com.hailin.blogsystem.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.service.LearningPlansService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 追问续答：上一轮 Agent 以 ASK_USER 收尾（在等用户回答）时，这一句优先当作"回答追问"处理。
 *
 * **解决什么**（2026-09-14 实测）：分类器是**无状态**的——只看当前这一条消息。
 * 追问的回答（「Java后端学习路线啊，不是Agent」）在它眼里是个孤立短语，
 * 实测被判成 `LEARNING_PLAN`（reason：「用户明确要求规划Java后端学习路线」），
 * **直接起了新建计划工作流**——用户真正要的"分析"反而丢了。
 *
 * 而这句话本身**信息是够的**：按匹配算法，「Java后端学习路线」能唯一命中
 * 《Java后端学习路线规划》（"路线"只在该标题里），只是**它压根没走到匹配那一步**。
 *
 * **怎么做**：本会话最新 Agent run 状态是 `WAITING_USER`（其注释原话：
 * "V1 不恢复，用户下句话开新请求"）时，先拿这句话去**定位计划**——
 * 唯一命中就**沿用上一轮诉求**继续（沿用而非重判意图）。
 *
 * **不误伤**：不是 WAITING_USER / 匹配不上 / 仍多命中 → 返回 null，调用方照常走分类器。
 * 用户换话题时匹配自然失败，行为与改动前完全一致；新 run 一旦创建，
 * 状态不再是 WAITING_USER，本机制自动失效（**无需任何清理逻辑**）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentFollowUpResolver {

    private final AiAgentRunMapper runMapper;
    private final LearningPlansService learningPlansService;

    /** 续答结果：沿用原诉求的 intent + 合并了原诉求的 message */
    public record Resolution(AiIntent intent, String message) {
    }

    /**
     * @return 续答上下文；null 表示"这一句不是追问续答"，调用方按正常流程分类
     */
    public Resolution resolve(Long sessionId, Long userId, String message) {
        if (sessionId == null || userId == null || message == null || message.isBlank()) {
            return null;
        }

        AiAgentRun last = runMapper.selectOne(
                new LambdaQueryWrapper<AiAgentRun>()
                        .eq(AiAgentRun::getSessionId, sessionId)
                        .eq(AiAgentRun::getUserId, userId)
                        .orderByDesc(AiAgentRun::getId)
                        .last("limit 1"));
        if (last == null || !AiAgentRunStatus.WAITING_USER.name().equals(last.getStatus())) {
            return null;
        }

        List<LearningPlans> matched = learningPlansService.matchPlansByMessage(userId, message);
        if (matched.size() != 1) {
            return null;
        }
        LearningPlans target = matched.get(0);

        // 沿用上一轮的**原诉求**：用户这一句只是在回答"是哪份计划"，单拎出来没有诉求。
        // 只取诉求本身——run.goal 是 effectiveGoal 的落库结果，诉求后面跟着**上一轮注入的定位线索**
        // （「【会话最近讨论的学习计划】…」+ 那段用法指引），原样搬进新 goal 既冗余、又可能过时：
        // 当轮 effectiveGoal 会按**当前**锚重新注入一次。
        String baseGoal = extractRawGoal(last.getGoal());
        if (baseGoal.isBlank()) {
            baseGoal = message;
        }

        AiIntent intent = new AiIntent();
        intent.setIntent("LEARNING_AGENT");
        intent.setConfidence(1.0);
        intent.setSuggestedAction("AGENT");
        intent.setRisk("LOW");
        intent.setReason("追问续答：已定位到《" + target.getTitle() + "》，沿用上一轮诉求");
        intent.setLearningPlanRef(target.getTitle());
        intent.setLearningPlanId(String.valueOf(target.getId()));

        log.info("追问续答命中: sessionId={}, planId={}, title={}（沿用上一轮 goal 继续）",
                sessionId, target.getId(), target.getTitle());

        return new Resolution(intent, baseGoal + "\n（用户补充回答：" + message + "）");
    }

    /**
     * 从落库的 effectiveGoal 里剥出用户原诉求。
     *
     * effectiveGoal = 原诉求 + "\n【…】…"（当轮注入的定位线索），所以**以 "\n【" 为界**截断即可。
     * 找不到标记（老数据 / 没注入过）时原样返回。
     */
    private String extractRawGoal(String storedGoal) {
        if (storedGoal == null || storedGoal.isBlank()) {
            return "";
        }
        int mark = storedGoal.indexOf("\n【");
        return (mark > 0 ? storedGoal.substring(0, mark) : storedGoal).trim();
    }
}
