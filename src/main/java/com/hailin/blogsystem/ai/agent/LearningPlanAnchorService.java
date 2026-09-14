package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.service.LearningPlansService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 会话学习计划锚（V4.x）：ai_sessions.last_learning_plan_* 单点状态的读写。
 *
 * **解决什么**：分类器只接收当前这一条消息（不读对话历史），多轮对话里第二轮省略主语就断片——
 * 「帮我分析我的c++学习计划」→「我是要应对学校课程，你会怎么改」，第二句没有计划名，
 * 系统只能反问"哪个计划"（实测反馈："我不是说了吗"）。
 *
 * 锚记的是"本会话最近一次**真实定位到**的学习计划"，让后端在"原话没点名"时有兜底依据。
 * 与文章锚（V3.8）同构：**分类器不读锚**，锚由后端单点消费。
 *
 * 写点（受控——只有真的指向某一个计划时才写）：
 * - 分类器从注入列表里选中（source=CLASSIFIER）
 * - 后端按原话匹配唯一命中（source=BACKEND_MATCH）
 * 纯查询（dashboard 看全部计划）**不写**——它没有指向某一个。
 *
 * 读点返回 null 即"宁可不猜"：会话不存在 / 不属于该用户 / 无锚 / 计划已删 / 计划不是本人的。
 * 调用方拿到 null 时保留原有追问行为，不改变任何失败语义。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningPlanAnchorService {

    public static final String SOURCE_CLASSIFIER = "CLASSIFIER";
    public static final String SOURCE_BACKEND_MATCH = "BACKEND_MATCH";
    /** 用户在多候选卡里自己选定的那个——比任何推断都权威 */
    public static final String SOURCE_USER_SELECTION = "USER_SELECTION";

    private final AiSessionService aiSessionService;
    private final LearningPlansService learningPlansService;

    /**
     * 写点：覆盖式更新会话学习计划锚。标题取数据库权威，
     * 计划已删时容忍 null（本体 id 仍可定位，resolve 会判失效）。
     */
    public void mark(Long sessionId, Long planId, String source) {
        if (sessionId == null || planId == null || source == null) {
            return;
        }
        AiSessions session = aiSessionService.getById(sessionId);
        if (session == null) {
            return;
        }
        LearningPlans plan = learningPlansService.getById(planId);

        AiSessions update = new AiSessions();
        update.setId(sessionId);
        update.setLastLearningPlanId(planId);
        update.setLastLearningPlanTitle(plan == null ? null : plan.getTitle());
        update.setLastLearningPlanSource(source);
        update.setLastLearningPlanUpdatedAt(LocalDateTime.now());
        aiSessionService.updateById(update);
        // info 级：实测排查"锚为什么没生效"时，这条是唯一能看到的写入痕迹（只写入不作数——还要看它写的是哪个计划）
        log.info("会话学习计划锚更新: sessionId={}, planId={}, title={}, source={}",
                sessionId, planId, update.getLastLearningPlanTitle(), source);
    }

    /**
     * 读点：返回有效锚计划（会话存在 + 归属当前用户 + 有锚 + 计划存在 + 计划归属当前用户），
     * 任一不满足返回 null（宁可不猜，由调用方保留原有追问行为）。
     * 返回**实体**：调用方（工作流启动）本来就需要它，且标题是查库权威现取——改名不漂。
     *
     * **不校验计划状态**：COMPLETED 的计划也可以被继续调整——"完成"不等于"不能再改"。
     */
    public LearningPlans resolve(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return null;
        }
        AiSessions session = aiSessionService.getById(sessionId);
        if (session == null || session.getUserId() == null
                || !session.getUserId().equals(userId)) {
            return null;
        }
        if (session.getLastLearningPlanId() == null) {
            return null;
        }
        LearningPlans plan = learningPlansService.getById(session.getLastLearningPlanId());
        if (plan == null || !userId.equals(plan.getUserId())) {
            return null;
        }
        return plan;
    }
}
