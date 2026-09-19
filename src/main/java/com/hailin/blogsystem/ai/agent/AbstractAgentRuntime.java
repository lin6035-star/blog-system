package com.hailin.blogsystem.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiAgentStepStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 领域 Agent 公共循环骨架（V2.5 抽取）。
 *
 * 循环形状（原 LearningAgentRuntime V1 只读语义，领域无关）：
 * <pre>
 * create AgentRun
 * while usedSteps < maxSteps:
 *   decider.decide(...)                       // 决策下一步（LLM / mock）
 *   validate action against whitelist         // 后端校验白名单
 *   FINAL_ANSWER  -> 保存回答，COMPLETED
 *   ASK_USER      -> 保存问题，WAITING_USER
 *   SUGGEST_WORKFLOW -> 零观察裁判 -> 建议（确认门控，不直接启动）
 *   execute readonly action                   // executor 执行，observation 落 step + 进上下文
 * maxSteps 到顶 -> 从已有 observations 汇总回答
 * </pre>
 *
 * 领域差异全部走钩子（decider / 白名单 / 文案 / SUGGEST_WRITE 扩展终态），
 * 学习域与文章域各自薄壳继承，不复制循环。
 *
 * 边界（与 V1 一致）：
 * - 非法动作 / 空决策 -> FAILED + 安全文案
 * - 动作执行失败 -> step 记 FAILED，追加失败 observation，循环继续
 * - 单条 observation ≤ 1500 字符，累计上下文 ≤ 6000 字符
 */
@Slf4j
public abstract class AbstractAgentRuntime {

    protected static final int MAX_OBSERVATION_CHARS = 1500;
    protected static final int MAX_CONTEXT_CHARS = 6000;
    protected static final int DEFAULT_MAX_STEPS = 5;

    protected final AgentActionExecutor executor;
    protected final AiAgentRunMapper runMapper;
    protected final AiAgentStepMapper stepMapper;
    protected final ObjectMapper objectMapper;

    protected AbstractAgentRuntime(
            AgentActionExecutor executor,
            AiAgentRunMapper runMapper,
            AiAgentStepMapper stepMapper,
            ObjectMapper objectMapper
    ) {
        this.executor = executor;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
    }

    // ==================== 领域钩子 ====================

    protected abstract AgentStepDecider decider();

    protected abstract Set<AgentStepActionType> allowedActions();

    protected abstract Set<String> allowedSuggestWorkflowTypes();

    /** FINAL_ANSWER emitter 成功文案（中性，刷新补拉共用 AgentStepLabelSupport 同源）。 */
    protected String finalAnswerSuccessMessage() {
        return "已生成最终回答";
    }

    /** FINAL_ANSWER 缺 answer 时兜底正文。 */
    protected abstract String emptyAnswerFallback();

    /** ASK_USER 缺 question 时兜底正文。 */
    protected abstract String emptyAskUserFallback();

    /** SUGGEST_WORKFLOW 缺 reason 时兜底原因。 */
    protected abstract String defaultWorkflowSuggestionReason(String workflowType);

    /** SUGGEST_WORKFLOW 零观察拒绝时追加进上下文的系统提示（含本领域只读动作列表）。 */
    protected abstract String suggestWorkflowRejectHint();

    /** maxSteps 到顶且零观察时的兜底正文。 */
    protected abstract String emptySummaryFallback();

    /** maxSteps 到顶拼接 observation 的抬头。 */
    protected abstract String summaryHeader();

    /** SUGGEST_WORKFLOW 建议携带的 articleId（仅文章域非空；学习域默认 null）。 */
    protected String resolveSuggestionArticleId(PageContextDTO pageContext, AiAgentRun run) {
        return null;
    }

    /** 决策目标（学习域原样；文章域注入当前文章 + 会话锚候选线索）。 */
    protected String effectiveGoal(String goal, Long userId, Long sessionId, PageContextDTO pageContext) {
        return goal;
    }

    /**
     * V3.8：每步决策后解析本 run 的定位目标（文章域用；默认不解析）。
     * 决议结果存 run.targetArticleId（瞬态，不落库）。解析失败（目标候选缺失/无效）时置 null，
     * 由动作执行层按缺失目标处理（FAILED step + observation，循环继续 LLM 收尾，不猜）。
     */
    protected void resolveStepTarget(AiAgentRun run, AgentStepDecision decision, PageContextDTO pageContext) {
    }

    /**
     * V3.8：动作执行前对决策做后端预处理（文章域把决议目标并入 QUERY_ARTICLE 的 input；
     * 默认原样返回）。终态动作（FINAL_ANSWER / ASK_USER / SUGGEST_*）不经此钩子。
     */
    protected AgentStepDecision prepareStepDecision(AiAgentRun run, AgentStepDecision decision) {
        return decision;
    }

    /**
     * V3.8：动作执行成功回调（文章域写会话锚；默认空）。执行失败不回调。
     */
    protected void onStepSucceeded(AiAgentRun run, AgentStepDecision decision, String observation) {
    }

    /**
     * 动作执行失败是否属于「终局失败」（V2.5）。
     *
     * 终局失败 = 继续循环也无法改变结果（如文章归属校验失败），
     * 失败 step 落库后直接结束 run（COMPLETED + 后端直接生成友好文案，不再走 LLM）。
     * 默认 false：临时故障（如 ES 挂了）保持循环继续，由下一轮决策收尾。
     */
    protected boolean isTerminalFailure(Throwable e) {
        return false;
    }

    // ==================== V3.11 证据收敛门钩子 ====================

    /**
     * V3.11：FINAL_ANSWER 前的证据收敛门（领域钩子）。
     *
     * 返回 null = 不验证（默认，学习/通用域原路径）；返回 Verdict：
     * - NEED_MORE：拦截该 FINAL_ANSWER（落 FAILED step + 提示 observation），循环继续补查
     * - ASK_USER：转问（构造新 ASK_USER decision，question 来自验证器）
     * - SUFFICIENT：放行走原收尾
     */
    protected Verdict verifyBeforeFinalization(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            List<AgentStepActionType> successfulActions
    ) {
        return null;
    }

    // ==================== V3.13 Plan Preview 域钩子 ====================

    /**
     * V3.13：Plan Preview 域开关。**默认 false**，仅文章域为 true。
     *
     * 共享骨架会触及三域（`AgentStepDecision.plan` 字段 / 决策器解析 / 采集落库发事件），
     * 没有显式开关就意味着学习域和通用域被动吃到 plan 逻辑，且无法从代码上断言
     * 「这两个域没变」。解析层例外（无域语义），prompt 规则与采集链路都在开关之内。
     */
    protected boolean supportsPlanPreview() {
        return false;
    }

    /**
     * V3.11：maxSteps 到顶前的保守收尾门（领域钩子，默认 false = 原 summarize 收尾）。
     * 触发条件由领域实现（如：目标文章从没成功读过 / 存在未被满足的验证拒绝），
     * 返回 true 时走 {@link #completeWithConservativeShutdown}，不给 summarize 硬答的机会。
     */
    protected boolean conservativeShutdownRequired(
            AiAgentRun run,
            List<AgentStepActionType> successfulActions,
            boolean hasUnresolvedVerifierRejection
    ) {
        return false;
    }

    /** V3.11：保守收尾文案（默认空兜底，领域 override 给用户可读解释）。 */
    protected String conservativeShutdownMessage() {
        return emptySummaryFallback();
    }

    /** V3.11：NEED_MORE 拦截后追加进上下文的系统提示（领域可 override 补动作指引）。 */
    protected String verifierRejectHint(Verdict verdict) {
        return "系统提示：你的回答依据不足（缺 " + verdict.missingEvidenceLabel()
                + "）：" + (verdict.reason() == null || verdict.reason().isBlank()
                ? "证据不足以支撑该回答" : verdict.reason())
                + "。请先补充查询再回答，不要编造。";
    }

    /** 决策循环步数上限（学习/文章域默认 5；通用域收紧到 3 控延迟）。 */
    protected int maxSteps() {
        return DEFAULT_MAX_STEPS;
    }

    /**
     * 领域扩展终态（如学习域 SUGGEST_WRITE）。
     * 返回非 null 表示已作为终态处理；默认 null 继续走通用终态。
     */
    /**
     * 扩展终态动作豁免普通执行：领域钩子拒绝（返回 null）后循环跳过 execute，
     * 直接进入下一轮决策（拒绝路径已自行推进 step 计数与观察）。
     */
    protected boolean isTerminalExtension(AgentStepDecision decision) {
        return false;
    }

    protected AgentRunResult handleExtraTerminalAction(
            AiAgentRun run,
            AgentStepDecision decision,
            String goal,
            List<String> observations,
            AgentStepEmitter emitter,
            PageContextDTO pageContext
    ) {
        return null;
    }

    /**
     * V3.12：FINAL_ANSWER 终态结论钩子（默认空实现，只有需要结论继承的域覆写）。
     *
     * 只挂 completeWithAnswer 一处——那是 FINAL_ANSWER 的唯一出口。**不能挂 finish()**：
     * 它同时服务保守收尾与 maxSteps 汇总，那两种不是「结论」。
     * SUGGEST_WORKFLOW 的结论走 confirm 时刻写（AgentRunSuggestionService），不在骨架。
     *
     * **调用时机**：Agent Run 已落库（finish 之后）才调——先写锚后落库会在落库失败时
     * 留下孤儿锚（结论锚存在但 run 没成功完成）。只有 run 成功落库才允许产生结论锚。
     *
     * fail-open：实现方必须自行兜异常（写锚失败不能影响用户看到回答）。
     */
    protected void onAnswerConcluded(
            AiAgentRun run,
            String finalAnswer,
            List<AgentStepActionType> successfulActions
    ) {
    }

    // ==================== 公共入口 ====================

    /**
     * 终局失败内部信号（V2.5）：执行器抛出的终局异常由 executeActionAndRecordStep
     * 转换后抛出，run() 捕获后直接收尾（不继续循环、不走 LLM）。
     */
    protected static class AgentRunTerminalException extends RuntimeException {
        private final int stepNo;

        public AgentRunTerminalException(int stepNo, String message) {
            super(message);
            this.stepNo = stepNo;
        }

        public int getStepNo() {
            return stepNo;
        }
    }

    public AgentRunResult run(Long userId, Long sessionId, String goal) {
        return run(userId, sessionId, goal, null, AgentStepEmitter.noop());
    }

    public AgentRunResult run(
            Long userId,
            Long sessionId,
            String goal,
            AgentStepEmitter emitter
    ) {
        return run(userId, sessionId, goal, null, emitter);
    }

    public AgentRunResult run(
            Long userId,
            Long sessionId,
            String goal,
            PageContextDTO pageContext,
            AgentStepEmitter emitter
    ) {
        String effectiveGoal = effectiveGoal(goal, userId, sessionId, pageContext);
        AiAgentRun run = createRun(userId, sessionId, effectiveGoal);
        log.info("Agent Run 创建: runId={}, userId={}, goal={}", run.getId(), userId, truncate(effectiveGoal, 100));

        // V4⑥ 可观测性：run 期间的所有日志带上 agentRunId，与 traceId 配合做「日志 ↔ DB」互查
        MDC.put("agentRunId", String.valueOf(run.getId()));

        List<String> observations = new ArrayList<>();
        List<AgentStepActionType> successfulActions = new ArrayList<>();   // V3.11：结构化事实（只读动作成功列表）
        List<Integer> successfulActionSteps = new ArrayList<>();           // V3.11：成功动作步号（到顶判据用）
        // V3.11：最后一次验证拒绝的步号（run 局部）。到顶保守收尾判据 =
        // 「最后一次验证拒绝之后没有再成功补查」——拦后补到新材料就不该被保守一刀切
        // （2026-09-10 手测修正：原「拦过即保守」把「补查成功但没步数答」的场景也保守了）
        Integer lastVerifierRejectionStep = null;
        try {

            while (run.getUsedSteps() < run.getMaxSteps()) {
                int nextStepNo = run.getUsedSteps() + 1;

                // 本步决策的用量单独收集：既累到 run 级，也落到 step 行（谁花的算谁的）
                TokenUsageAccumulator stepUsage = new TokenUsageAccumulator();
                AgentStepDecision decision = decider().decide(
                        effectiveGoal,
                        clipContext(observations),
                        nextStepNo,
                        run.getMaxSteps(),
                        stepUsage
                );
                AgentTokenRecorder.accumulate(run, stepUsage);

                // 白名单校验：null / 非法动作直接 FAILED
                if (decision == null || decision.actionType() == null
                        || !allowedActions().contains(decision.actionType())) {
                    String detail = decision == null || decision.actionType() == null
                            ? "空决策"
                            : "非法动作: " + decision.actionType();
                    emitter.emit(nextStepNo, "DECISION", "FAILED", "决策无效：" + detail, null);
                    return markFailed(run, "Agent 决策无效（" + detail + "）");
                }

                // V3.13 Plan Preview：首步采集计划（仅域开关开启；全程 fail-open）
                collectPlanIfApplicable(run, decision, nextStepNo, emitter);

                // V3.8：解析本 run 定位目标（在扩展终态与动作执行前，SUGGEST_WRITE 提案端消费 run 决议目标）
                resolveStepTarget(run, decision, pageContext);

                // 领域扩展终态（学习域 SUGGEST_WRITE 在此处理）
                AgentRunResult extra = handleExtraTerminalAction(
                        run, decision, effectiveGoal, observations, emitter, pageContext);
                if (extra != null) {
                    return extra;
                }
                // 扩展终态动作被领域拒绝（零观察 / 重复预检）→ 跳过本轮普通执行，
                // 观察已带拒绝原因推进，循环由 LLM 下一轮决策收尾
                if (isTerminalExtension(decision)) {
                    continue;
                }

                // 终态：FINAL_ANSWER
                if (decision.actionType() == AgentStepActionType.FINAL_ANSWER) {
                    // V3.11 证据收敛门：领域钩子判定「证据是否足以支撑该回答草案」。
                    // NEED_MORE 拦截回循环补查；ASK_USER 转问；SUFFICIENT/null（无验证器）放行。
                    Verdict verdict = verifyBeforeFinalization(
                            run, decision, observations, successfulActions);
                    if (verdict != null && verdict.type() == Verdict.VerdictType.NEED_MORE) {
                        lastVerifierRejectionStep = nextStepNo;
                        String rejectReason = "回答证据不足（缺 " + verdict.missingEvidenceLabel()
                                + "）：" + (verdict.reason() == null || verdict.reason().isBlank()
                                ? "证据不足以支撑该回答" : verdict.reason());
                        emitter.emit(nextStepNo, "FINAL_ANSWER", "FAILED",
                                "回答被拦截：证据不足", null);
                        recordRejectedStep(run, decision, nextStepNo, rejectReason, stepUsage);
                        observations.add(verifierRejectHint(verdict));
                        run.setCurrentStep(nextStepNo);
                        run.setUsedSteps(nextStepNo);
                        run.setContextJson(toJson(clipContext(observations)));
                        run.setUpdatedAt(LocalDateTime.now());
                        runMapper.updateById(run);
                        continue;
                    }
                    if (verdict != null && verdict.type() == Verdict.VerdictType.ASK_USER) {
                        // 转问：构造新 ASK_USER decision（question 来自验证器，verifier 侧已保证非空），
                        // 只落一条 ASK_USER 终态 step，不保留伪造的 FINAL_ANSWER step
                        AgentStepDecision askDecision = new AgentStepDecision(
                                AgentStepActionType.ASK_USER,
                                Map.of("question", verdict.question() == null ? "" : verdict.question()),
                                null);
                        emitter.emit(nextStepNo, "ASK_USER", "SUCCESS",
                                "需要向你确认一个问题", null);
                        return markWaitingUser(run, askDecision, observations, stepUsage);
                    }
                    emitter.emit(nextStepNo, "FINAL_ANSWER", "SUCCESS",
                            finalAnswerSuccessMessage(), decision.thoughtSummary());
                    return completeWithAnswer(run, decision, observations, successfulActions, stepUsage);
                }

                // 终态：ASK_USER（问题快照留 run）
                if (decision.actionType() == AgentStepActionType.ASK_USER) {
                    emitter.emit(nextStepNo, "ASK_USER", "SUCCESS",
                            "需要向你确认一个问题", decision.thoughtSummary());
                    return markWaitingUser(run, decision, observations, stepUsage);
                }

                // 终态：SUGGEST_WORKFLOW
                // 裁判规则：至少 1 条观察才允许建议（防"没查就拍脑袋"）。
                // 零观察建议被拒：落 FAILED step + 追加拒绝提示，循环继续（maxSteps 有界保护）。
                if (decision.actionType() == AgentStepActionType.SUGGEST_WORKFLOW) {
                    if (observations.isEmpty()) {
                        String rejectReason = "首轮零观察建议被拒绝：必须先执行至少一个只读查询";
                        emitter.emit(nextStepNo, "SUGGEST_WORKFLOW", "FAILED",
                                "建议被拒绝：需先完成一次查询", null);
                        recordRejectedStep(run, decision, nextStepNo, rejectReason, stepUsage);
                        observations.add(suggestWorkflowRejectHint());
                        run.setCurrentStep(nextStepNo);
                        run.setUsedSteps(nextStepNo);
                        run.setContextJson(toJson(clipContext(observations)));
                        run.setUpdatedAt(LocalDateTime.now());
                        runMapper.updateById(run);
                        continue;
                    }
                    return suggestWorkflow(run, decision, effectiveGoal, observations, emitter, pageContext, stepUsage);
                }

                // V3.8：后端预处理（决议目标并入 QUERY_ARTICLE input）。
                // 提到事件之前，让重复检查比对的是**实际落库的 input**（含后端注入的决议目标）
                AgentStepDecision prepared = prepareStepDecision(run, decision);

                // V4 规则级重复拦截：同动作 + 同参数的查询再来一次不会有新结果
                if (rejectDuplicatedQuery(run, prepared, nextStepNo, observations, emitter, stepUsage)) {
                    run.setCurrentStep(nextStepNo);
                    run.setUsedSteps(nextStepNo);
                    run.setContextJson(toJson(clipContext(observations)));
                    run.setUpdatedAt(LocalDateTime.now());
                    runMapper.updateById(run);
                    continue;
                }

                // 执行只读动作（执行前后推步骤事件，前端实时渲染思考过程）
                // V3.10：RUNNING/SUCCESS 携带同一句 thoughtSummary（行文本跨状态稳定，D4），
                // FAILED 不携带——失败时优先展示失败文案，不让动机句覆盖失败原因
                emitter.emit(nextStepNo, decision.actionType().name(), "RUNNING",
                        "正在" + actionLabel(decision.actionType()) + "...", decision.thoughtSummary());
                String observation = executeActionAndRecordStep(
                        run, prepared, userId, nextStepNo, emitter, pageContext,
                        successfulActions, successfulActionSteps, stepUsage
                );
                observations.add(observation);

                run.setCurrentStep(nextStepNo);
                run.setUsedSteps(nextStepNo);
                run.setContextJson(toJson(clipContext(observations)));
                run.setUpdatedAt(LocalDateTime.now());
                runMapper.updateById(run);
            }

            // V3.11 到顶路径保守收尾门：领域判定（如目标文章从没读到 / 验证拒绝未被满足）
            // 「未被满足」= 最后一次验证拒绝之后没有再成功补查（2026-09-10 手测修正：
            // 拦后补到新材料的不保守——材料可能已够，交给 summarize 基于新证据收尾）
            Integer lastSuccessfulActionStep = successfulActionSteps.isEmpty()
                    ? null
                    : successfulActionSteps.get(successfulActionSteps.size() - 1);
            boolean hasUnresolvedVerifierRejection = lastVerifierRejectionStep != null
                    && (lastSuccessfulActionStep == null
                    || lastSuccessfulActionStep < lastVerifierRejectionStep);
            if (conservativeShutdownRequired(run, successfulActions, hasUnresolvedVerifierRejection)) {
                return completeWithConservativeShutdown(run, observations);
            }
            // maxSteps 到顶：从已有 observations 汇总回答
            return completeWithSummary(run, effectiveGoal, observations);
        } catch (AgentRunTerminalException e) {
            // 终局失败：失败 step 已落库，这里直接收尾（COMPLETED + 友好文案），不再走 LLM
            log.info("Agent Run 终局失败收尾: runId={}, reason={}", run.getId(), e.getMessage());
            return completeWithTerminalFailure(run, observations, e);
        } catch (Exception e) {
            log.error("Agent Run 执行异常: runId={}", run.getId(), e);
            return markFailed(run, "Agent 执行异常，请稍后重试");
        } finally {
            // token 兜底落库：上面 8 个出口各靠自己的 updateById 顺带写入，
            // 这里再单列补一次，防某个出口走的是不带全字段的单列 UPDATE 导致 token 丢失。
            // 与已有 updateById 重复写同值是幂等的
            persistTokens(run);
            // 只移除本键——不能用 MDC.clear()，它会连 Spring 放的 traceId 一起清掉
            MDC.remove("agentRunId");
        }
    }

    /**
     * token 兜底落库：只更新 token 三列（与 collectPlanIfApplicable 的单列更新同一模式）。
     * 失败只记日志不抛——统计是副产品，坏了不能影响用户拿到回答。
     */
    private void persistTokens(AiAgentRun run) {
        if (run == null || run.getId() == null || run.getTotalTokens() == null) {
            return;
        }
        try {
            runMapper.update(null, new UpdateWrapper<AiAgentRun>()
                    .eq("id", run.getId())
                    .set("input_tokens", run.getInputTokens() == null ? 0 : run.getInputTokens())
                    .set("output_tokens", run.getOutputTokens() == null ? 0 : run.getOutputTokens())
                    .set("total_tokens", run.getTotalTokens()));
        } catch (Exception e) {
            log.warn("[PERF-AGENT] token 落库失败 runId={} totalTokens={}",
                    run.getId(), run.getTotalTokens(), e);
        }
    }

    /**
     * 终局失败收尾：状态 COMPLETED + finalAnswer 用终局失败原因（用户可读），
     * 前端正常展示 AI 文案，不再追加决策步骤。
     */
    private AgentRunResult completeWithTerminalFailure(
            AiAgentRun run,
            List<String> observations,
            AgentRunTerminalException e
    ) {
        String finalAnswer = (e.getMessage() == null || e.getMessage().isBlank())
                ? emptyAnswerFallback()
                : e.getMessage();
        // 失败 step 已落库，这里把进度同步到失败步（step 表数量 = usedSteps 一致）
        run.setCurrentStep(e.getStepNo());
        run.setUsedSteps(e.getStepNo());
        run.setStatus(AiAgentRunStatus.COMPLETED.name());
        run.setFinalAnswer(finalAnswer);
        run.setContextJson(toJson(clipContext(observations)));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Agent Run 结束（终局失败）: runId={}, status=COMPLETED, usedSteps={}",
                run.getId(), run.getUsedSteps());
        return AgentRunResult.of(
                run.getId(),
                AiAgentRunStatus.COMPLETED,
                finalAnswer,
                run.getUsedSteps(),
                run.getTotalTokens()
        );
    }

    // ==================== 通用内部逻辑 ====================

    /**
     * 动作中文名（思考面板展示用，与历史补拉文案一致）。
     */
    private String actionLabel(AgentStepActionType type) {
        return AgentStepLabelSupport.actionLabel(type == null ? null : type.name());
    }

    private String workflowLabel(String workflowType) {
        return AgentStepLabelSupport.workflowLabel(workflowType);
    }

    /**
     * V3.13 Plan Preview：首步采集 / 校验 / 落库 / 发事件。
     *
     * 时序写死：白名单校验通过 → plan 校验 → **单列更新落库（按影响行数判定）** → `emitPlan` → 首个步骤事件。
     * - **先落库再发事件**（与 V3.12 孤儿锚同一原则：事件不可先于持久化）——
     *   落库影响行数 ≠ 1 视为未落库，只记日志不发事件，防「实时有计划、库里没有」分叉
     * - `AGENT_PLAN` 必须在首个 `AGENT_STEP` 事件之前发，否则前端视觉顺序会闪
     * - 落库走**单列更新**，不复用 `updateById`——否则计划写失败会与 run 状态写失败
     *   混成同一个故障，fail-open 形同虚设
     *
     * 全程 fail-open：计划是展示增强，任何失败只丢计划，不影响决策与执行。
     */
    private void collectPlanIfApplicable(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            AgentStepEmitter emitter
    ) {
        if (!supportsPlanPreview() || stepNo != 1) {
            return;
        }
        // 首步即终态/需澄清动作时没有实际执行基线，计划无法用于本阶段观测，且可能在澄清前误导用户
        if (isNonExecutableFirstStep(decision.actionType())) {
            return;
        }
        try {
            List<String> plan = AgentPlanValidator.validate(decision.plan());
            if (plan == null || plan.isEmpty()) {
                return;
            }
            String planJson = toJson(plan);
            int updated = runMapper.update(null, new UpdateWrapper<AiAgentRun>()
                    .eq("id", run.getId())
                    .set("plan_json", planJson)
                    .set("updated_at", LocalDateTime.now()));
            if (updated != 1) {
                // 落库未命中（run 行不存在等）→ 只记日志不发事件，防「实时有计划、库里没有」分叉
                log.warn("Plan Preview 落库未命中 run 行，跳过事件: runId={}", run.getId());
                return;
            }
            run.setPlanJson(planJson);
            emitter.emitPlan(run.getId(), plan);
        } catch (Exception e) {
            log.warn("Plan Preview 采集失败（不影响 Agent 流程）: runId={}", run.getId(), e);
        }
    }

    /** 首步即无执行基线（终态 / 需澄清）→ 不采集计划。 */
    private boolean isNonExecutableFirstStep(AgentStepActionType actionType) {
        return actionType == AgentStepActionType.FINAL_ANSWER
                || actionType == AgentStepActionType.ASK_USER
                || actionType == AgentStepActionType.SUGGEST_WORKFLOW
                || actionType == AgentStepActionType.SUGGEST_WRITE;
    }

    private AiAgentRun createRun(Long userId, Long sessionId, String goal) {
        // 新一轮 Agent 判断开始，同 session 旧的待确认建议/提案自然过期
        cancelStalePendingSuggestions(sessionId);

        AiAgentRun run = new AiAgentRun();
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setGoal(truncate(goal, 500));
        run.setStatus(AiAgentRunStatus.RUNNING.name());
        run.setCurrentStep(0);
        run.setMaxSteps(maxSteps());
        run.setUsedSteps(0);
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        return run;
    }

    /**
     * 执行动作并落 step。
     *
     * 成功：step SUCCESS + observation 返回给循环，动作加入 successfulActions（V3.11 结构化事实，
     * 供证据收敛门判「某类动作是否成功执行过」，不依赖观察文本格式）。
     * 失败：step FAILED + 失败 observation（限长），循环继续，由下一轮决策收尾。
     */
    private String executeActionAndRecordStep(
            AiAgentRun run,
            AgentStepDecision decision,
            Long userId,
            int stepNo,
            AgentStepEmitter emitter,
            PageContextDTO pageContext,
            List<AgentStepActionType> successfulActions,
            List<Integer> successfulActionSteps,
            TokenUsageAccumulator stepUsage
    ) {
        long start = System.currentTimeMillis();
        AiAgentStep step = new AiAgentStep();
        step.setAgentRunId(run.getId());
        step.setStepNo(stepNo);
        step.setActionType(decision.actionType().name());
        step.setThoughtSummary(decision.thoughtSummary());
        step.setInputJson(toJson(decision.input()));
        step.setStatus(AiAgentStepStatus.RUNNING.name());
        step.setCreatedAt(LocalDateTime.now());
        applyStepTokens(step, stepUsage);
        stepMapper.insert(step);

        try {
            String observation = executor.execute(decision, userId, pageContext);
            step.setOutputJson(toJson(Map.of("summary", observation)));
            step.setStatus(AiAgentStepStatus.SUCCESS.name());
            step.setDurationMs(System.currentTimeMillis() - start);
            stepMapper.updateById(step);
            log.info("Agent Step 成功: runId={}, stepNo={}, action={}", run.getId(), stepNo, decision.actionType());
            emitter.emit(stepNo, decision.actionType().name(), "SUCCESS",
                    "已完成" + actionLabel(decision.actionType()), decision.thoughtSummary());
            // V3.8：执行成功领域回调（文章域写会话锚）
            onStepSucceeded(run, decision, observation);
            // V3.11：成功动作进结构化列表（供证据收敛门判定；步号供到顶保守收尾判据）
            successfulActions.add(decision.actionType());
            successfulActionSteps.add(stepNo);
            return tagObservation(decision, clipObservation(observation));
        } catch (Exception e) {
            log.warn("Agent Step 执行失败: runId={}, stepNo={}, action={}",
                    run.getId(), stepNo, decision.actionType(), e);
            step.setStatus(AiAgentStepStatus.FAILED.name());
            step.setDurationMs(System.currentTimeMillis() - start);
            step.setErrorMessage(truncate(e.getMessage(), 300));
            stepMapper.updateById(step);
            emitter.emit(stepNo, decision.actionType().name(), "FAILED",
                    actionLabel(decision.actionType()) + "失败", null);
            // 终局失败（如文章归属校验失败）：失败 step 已落库，直接抛出内部信号收尾
            if (isTerminalFailure(e)) {
                throw new AgentRunTerminalException(stepNo, e.getMessage());
            }
            return tagObservation(decision, "动作执行失败：" + truncate(e.getMessage(), 200));
        }
    }

    /**
     * 给观察加**来源标注**：模型在下一轮决策时必须看得见「我做过什么动作、用什么参数」。
     *
     * V4 根因（2026-09-11 开发者面板实测）：观察里只有结果文本，模型无法确认某段内容是不是
     * 「我要查的那个查询」的产物——于是用同一个查询反复试探（同参同果 4 次，最后一次它引用了
     * 第 3 步的结果作答，说明信息早就够了，缺的只是「已经查过」这个事实）。
     *
     * 硬拦只治症状（拦得住动作，拦不住模型的不确定），标注才是治本。
     * 两者互补：标注让模型自己看得见，硬拦兜底防它仍不确定时的空转。
     */
    private String tagObservation(AgentStepDecision decision, String observation) {
        return "[" + decision.actionType().name() + queryParamLabel(decision.input()) + "]\n" + observation;
    }

    /**
     * 只带出有语义的查询参数。
     * 内部标识（articleId / anchorMode）不进观察——模型不需要，按规则也不该看到。
     */
    private String queryParamLabel(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendQueryParam(sb, input, "focus");
        appendQueryParam(sb, input, "question");
        appendQueryParam(sb, input, "keyword");
        return sb.toString();
    }

    private void appendQueryParam(StringBuilder sb, Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return;
        }
        sb.append(' ').append(key).append('=').append(truncate(text, 60));
    }

    private AgentRunResult completeWithAnswer(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            List<AgentStepActionType> successfulActions,
            TokenUsageAccumulator stepUsage
    ) {
        String extracted = extractFinalAnswerText(decision);
        boolean realAnswer = extracted != null && !extracted.isBlank();
        // V3.12：兜底文案不是「结论」——不写锚（用 realAnswer 判断，避免比较字符串是否等于 fallback 的脆弱写法）
        String finalAnswer = realAnswer ? extracted : emptyAnswerFallback();
        recordTerminalStep(run, decision, run.getUsedSteps() + 1, stepUsage);
        AgentRunResult result = finish(run, AiAgentRunStatus.COMPLETED, finalAnswer, observations);
        // V3.12：结论钩子在 **run 成功落库之后** 调用——先写锚后落库会在落库失败时留孤儿锚。
        // 失败不影响回答（钩子实现自行 fail-open），用户照常看到正文。
        if (realAnswer) {
            onAnswerConcluded(run, finalAnswer, successfulActions);
        }
        return result;
    }

    /**
     * V3.11：从 FINAL_ANSWER decision 提取最终回答正文（answer 键兼容回退——实测 2026-09-07：
     * 模型偶发把正文写进 message 键，内容被吞成兜底文案）。
     *
     * completeWithAnswer 与证据收敛门（Verifier 验证对象）共用同一提取，
     * 保证「验证了什么」与「实际输出什么」严格一致。提取不到返回 null（由调用方兜底）。
     */
    protected String extractFinalAnswerText(AgentStepDecision decision) {
        if (decision == null || decision.input() == null) {
            return null;
        }
        Object answer = decision.input().get("answer");
        if (answer != null && !String.valueOf(answer).isBlank()) {
            return String.valueOf(answer).trim();
        }
        Object message = decision.input().get("message");
        if (message != null && !String.valueOf(message).isBlank()) {
            return String.valueOf(message).trim();
        }
        return null;
    }

    /**
     * V3.11 保守收尾：跳过 summarize 硬答，落领域保守文案（COMPLETED，复用 finish）。
     * 触发原因已由 conservativeShutdownRequired 领域实现写日志。
     */
    private AgentRunResult completeWithConservativeShutdown(
            AiAgentRun run,
            List<String> observations
    ) {
        String finalAnswer = conservativeShutdownMessage();
        if (finalAnswer == null || finalAnswer.isBlank()) {
            finalAnswer = emptySummaryFallback();
        }
        return finish(run, AiAgentRunStatus.COMPLETED, finalAnswer, observations);
    }

    private AgentRunResult markWaitingUser(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            TokenUsageAccumulator stepUsage
    ) {
        // question 键兼容回退（与 FINAL_ANSWER 的 answer/message 同款防呆：模型偶发写 message 键）
        Object question = decision.input() == null ? null : decision.input().get("question");
        if (question == null || String.valueOf(question).isBlank()) {
            Object message = decision.input() == null ? null : decision.input().get("message");
            if (message != null && !String.valueOf(message).isBlank()) {
                question = message;
            }
        }
        String questionText = question == null || String.valueOf(question).isBlank()
                ? emptyAskUserFallback()
                : String.valueOf(question);

        recordTerminalStep(run, decision, run.getUsedSteps() + 1, stepUsage);
        return finish(run, AiAgentRunStatus.WAITING_USER, questionText, observations);
    }

    /**
     * 新 Agent Run 创建时，把同 session 旧的 WAITING_WORKFLOW_CONFIRM /
     * WAITING_WRITE_CONFIRM 标记 CANCELLED（"新一轮判断开始，旧建议/提案自然过期"）。
     * confirm 接口还会再校验状态，点旧卡片无法启动 Workflow / 执行写动作。
     */
    private void cancelStalePendingSuggestions(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        AiAgentRun update = new AiAgentRun();
        update.setStatus(AiAgentRunStatus.CANCELLED.name());
        update.setUpdatedAt(LocalDateTime.now());
        runMapper.update(update, new LambdaQueryWrapper<AiAgentRun>()
                .eq(AiAgentRun::getSessionId, sessionId)
                .in(AiAgentRun::getStatus,
                        AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM.name(),
                        AiAgentRunStatus.WAITING_WRITE_CONFIRM.name()));
    }

    /**
     * SUGGEST_WORKFLOW 终态处理。
     *
     * 校验建议字段（workflowType allowlist / reason / initialMessage / risk 兜底），
     * 落终态 step，run 转 WAITING_WORKFLOW_CONFIRM，建议存 context_json。
     * 这里不启动任何 Workflow——启动由 confirm 接口在用户确认后完成。
     */
    private AgentRunResult suggestWorkflow(
            AiAgentRun run,
            AgentStepDecision decision,
            String goal,
            List<String> observations,
            AgentStepEmitter emitter,
            PageContextDTO pageContext,
            TokenUsageAccumulator stepUsage
    ) {
        Map<String, Object> input = decision.input() == null ? Map.of() : decision.input();
        String workflowType = text(input, "workflowType");
        if (workflowType == null || !allowedSuggestWorkflowTypes().contains(workflowType)) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WORKFLOW", "FAILED",
                    "建议的流程类型不在允许范围", null);
            return markFailed(run, "Agent 建议的 Workflow 类型不在允许范围内（" + workflowType + "）");
        }
        String reason = text(input, "reason");
        if (reason == null || reason.isBlank()) {
            reason = defaultWorkflowSuggestionReason(workflowType);
        }
        String initialMessage = text(input, "initialMessage");
        if (initialMessage == null || initialMessage.isBlank()) {
            initialMessage = goal;
        }
        String risk = text(input, "risk");
        if (risk == null || risk.isBlank()) {
            risk = "MEDIUM";
        }

        AgentWorkflowSuggestion suggestion = new AgentWorkflowSuggestion(
                workflowType,
                truncate(reason, 500),
                truncate(initialMessage, 500),
                risk,
                resolveSuggestionArticleId(pageContext, run)
        );

        emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WORKFLOW", "SUCCESS",
                "建议启动「" + workflowLabel(workflowType) + "」流程", decision.thoughtSummary());
        recordTerminalStep(run, decision, run.getUsedSteps() + 1, stepUsage);
        run.setStatus(AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM.name());
        // 正文直接用 reason（不带"建议启动「X」："前缀，建议卡已展示类型）
        run.setFinalAnswer(reason);
        run.setContextJson(toJson(Map.of(
                "observations", clipContext(observations),
                "pendingWorkflowSuggestion", suggestion
        )));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Agent Run 建议 Workflow: runId={}, workflowType={}", run.getId(), workflowType);
        return AgentRunResult.of(
                run.getId(),
                AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM,
                run.getFinalAnswer(),
                run.getUsedSteps(),
                run.getTotalTokens(),
                suggestion
        );
    }

    /**
     * V4 规则级重复拦截：同一动作 + 同一参数的查询重复执行，结果不会变化
     * （同 query + 同数据 = 同结果，与「第一次有没有查全」无关），属纯空转。
     *
     * 实测依据（2026-09-11 开发者只读面板观测）：一次多目标请求连发 4 次
     * `QUERY_MEMORY {"question":"写作偏好"}`，`input_json` 与 `output_json` 四次完全相同，
     * 上下文未触发裁剪（3446 &lt; 6000），且决策器 prompt 里「一次就够 / 不要重复执行完全相同的查询」
     * 两条软约束均未生效——模型无法可靠监控自己的动作历史，故改由 Runtime 硬拦。
     *
     * 边界：
     * - **只拦原样重复**：换关键词的二次查询一律放行（那才是有效探索）
     * - 只匹配 SUCCESS 的历史步，上次失败的重试不受影响
     * - 拦截落 SKIPPED step 消耗步数，避免绕过 maxSteps 形成死循环；
     *   SKIPPED 表示“系统主动省略已完成过的同参查询”，不是查询故障
     *
     * @return true 表示已拦截（调用方直接 continue）
     */
    private boolean rejectDuplicatedQuery(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            List<String> observations,
            AgentStepEmitter emitter,
            TokenUsageAccumulator stepUsage
    ) {
        AiAgentStep previous = stepMapper.selectOne(new LambdaQueryWrapper<AiAgentStep>()
                .eq(AiAgentStep::getAgentRunId, run.getId())
                .eq(AiAgentStep::getActionType, decision.actionType().name())
                .eq(AiAgentStep::getStatus, AiAgentStepStatus.SUCCESS.name())
                .eq(AiAgentStep::getInputJson, toJson(decision.input()))
                .orderByAsc(AiAgentStep::getStepNo)
                .last("LIMIT 1"));
        if (previous == null) {
            return false;
        }

        emitter.emit(stepNo, decision.actionType().name(), AiAgentStepStatus.SKIPPED.name(),
                AgentStepLabelSupport.DUPLICATE_QUERY_SKIP_MESSAGE, null);
        recordSkippedStep(run, decision, stepNo,
                AgentStepLabelSupport.DUPLICATE_QUERY_SKIP_PREFIX
                        + "：同一动作 + 同一参数在第 " + previous.getStepNo() + " 步已成功执行过",
                stepUsage);
        observations.add(duplicateQueryHint(previous.getStepNo()));
        return true;
    }

    /**
     * 拦截后给决策器的观察：不光说「不许重复」，还要给出下一步能做什么
     * （只禁不疏的话，模型会换个近义词继续空转）。
     */
    private String duplicateQueryHint(int previousStepNo) {
        return "【系统提示】你重复了第 " + previousStepNo + " 步已经成功执行过的同一个查询"
                + "（动作与参数完全相同），再执行一次也不会得到新结果。"
                + "请换关键词或换角度查询；若确实没有更多可查的，直接基于已有观察作答。";
    }

    /** 重复查询等“主动省略”动作落 SKIPPED：保留审计与步数，但不伪装成执行失败。 */
    protected void recordSkippedStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            String reason,
            TokenUsageAccumulator stepUsage
    ) {
        AiAgentStep step = new AiAgentStep();
        step.setAgentRunId(run.getId());
        step.setStepNo(stepNo);
        step.setActionType(decision.actionType().name());
        step.setInputJson(toJson(decision.input()));
        step.setStatus(AiAgentStepStatus.SKIPPED.name());
        step.setErrorMessage(reason);
        step.setDurationMs(0L);
        applyStepTokens(step, stepUsage);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
    }

    /**
     * 被后端裁判拒绝的建议也落一条 FAILED step，保证审计完整。
     */
    protected void recordRejectedStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            String reason,
            TokenUsageAccumulator stepUsage
    ) {
        AiAgentStep step = new AiAgentStep();
        step.setAgentRunId(run.getId());
        step.setStepNo(stepNo);
        step.setActionType(decision.actionType().name());
        step.setInputJson(toJson(decision.input()));
        step.setStatus(AiAgentStepStatus.FAILED.name());
        step.setErrorMessage(reason);
        step.setDurationMs(0L);
        applyStepTokens(step, stepUsage);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
    }

    /**
     * 终态动作（FINAL_ANSWER / ASK_USER / SUGGEST_*）也落一条 step，保证审计完整；
     * 同时把终态步计入 usedSteps / currentStep，保证与 step 表数量一致。
     */
    protected void recordTerminalStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            TokenUsageAccumulator stepUsage
    ) {
        AiAgentStep step = new AiAgentStep();
        step.setAgentRunId(run.getId());
        step.setStepNo(stepNo);
        step.setActionType(decision.actionType().name());
        step.setThoughtSummary(decision.thoughtSummary());
        step.setInputJson(toJson(decision.input()));
        step.setStatus(AiAgentStepStatus.SUCCESS.name());
        step.setDurationMs(0L);
        applyStepTokens(step, stepUsage);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);

        run.setCurrentStep(stepNo);
        run.setUsedSteps(stepNo);
    }

    /**
     * 把本步决策的用量写到 step 行。
     *
     * 语义是「决定要走这一步」的成本——只读动作本身不调 LLM。
     * stepUsage 为 null = 该路径不做统计（子类扩展终态路径拿不到循环内的容器），记 0。
     * 注意 run 级用量不受影响：它在循环里已累加，与 step 级是否记录无关。
     */
    private void applyStepTokens(AiAgentStep step, TokenUsageAccumulator stepUsage) {
        if (stepUsage != null) {
            step.setInputTokens(stepUsage.getPromptTokens());
            step.setOutputTokens(stepUsage.getCompletionTokens());
        }
    }

    /**
     * maxSteps 到顶后的收尾回答。
     *
     * 优先用决策器总结（LLM）；总结失败/未实现时降级为拼接 observation，
     * 保证前端一定有结果。
     */
    private AgentRunResult completeWithSummary(
            AiAgentRun run,
            String goal,
            List<String> observations
    ) {
        String clipped = clipContext(observations);
        String summarized = null;
        // 收尾总结的用量也归 run：它不是某一步的成本（到顶后才发生的一次调用）
        TokenUsageAccumulator summaryUsage = new TokenUsageAccumulator();
        try {
            summarized = decider().summarize(goal, clipped, summaryUsage);
        } catch (Exception e) {
            log.warn("Agent 收尾总结失败，降级拼接。runId={}", run.getId(), e);
        } finally {
            AgentTokenRecorder.accumulate(run, summaryUsage);
        }

        String finalAnswer = (summarized == null || summarized.isBlank())
                ? summarizeFinalAnswer(goal, observations)
                : summarized;
        return finish(run, AiAgentRunStatus.COMPLETED, finalAnswer, observations);
    }

    protected String summarizeFinalAnswer(String goal, List<String> observations) {
        if (observations.isEmpty()) {
            return emptySummaryFallback();
        }
        StringBuilder sb = new StringBuilder(summaryHeader());
        for (String observation : observations) {
            sb.append("- ").append(observation).append('\n');
        }
        return clipObservation(sb.toString());
    }

    private AgentRunResult finish(
            AiAgentRun run,
            AiAgentRunStatus status,
            String finalAnswer,
            List<String> observations
    ) {
        run.setStatus(status.name());
        run.setFinalAnswer(finalAnswer);
        run.setContextJson(toJson(clipContext(observations)));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Agent Run 结束: runId={}, status={}, usedSteps={}",
                run.getId(), status, run.getUsedSteps());
        return AgentRunResult.of(
                run.getId(),
                status,
                finalAnswer,
                run.getUsedSteps(),
                run.getTotalTokens()
        );
    }

    protected AgentRunResult markFailed(AiAgentRun run, String message) {
        run.setStatus(AiAgentRunStatus.FAILED.name());
        run.setErrorMessage(truncate(message, 1000));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.warn("Agent Run 失败: runId={}, reason={}", run.getId(), message);
        return AgentRunResult.of(
                run.getId(),
                AiAgentRunStatus.FAILED,
                null,
                run.getUsedSteps(),
                run.getTotalTokens()
        );
    }

    /**
     * 上下文裁剪：累计不超过 MAX_CONTEXT_CHARS。
     *
     * observations 里的每条已经是单条裁剪（MAX_OBSERVATION_CHARS）后的，
     * 这里只按累计上限截断，不再套单条裁剪（否则 6000 上限失效）。
     */
    protected String clipContext(List<String> observations) {
        StringBuilder sb = new StringBuilder();
        for (String observation : observations) {
            if (sb.length() >= MAX_CONTEXT_CHARS) {
                break;
            }
            sb.append(observation).append('\n');
        }
        if (sb.length() <= MAX_CONTEXT_CHARS) {
            return sb.toString();
        }
        return sb.substring(0, MAX_CONTEXT_CHARS);
    }

    protected String clipObservation(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= MAX_OBSERVATION_CHARS
                ? text
                : text.substring(0, MAX_OBSERVATION_CHARS);
    }

    protected String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    protected String text(Map<String, Object> input, String key) {
        if (input == null) {
            return null;
        }
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    protected String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
