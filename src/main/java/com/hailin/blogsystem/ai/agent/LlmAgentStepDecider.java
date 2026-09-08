package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM 步骤决策器（非流式）。
 *
 * 决策 prompt 只含：目标、已裁剪观察、白名单动作说明、输出格式。
 * 不自动注入记忆（记忆只通过 QUERY_MEMORY 显式获取）。
 *
 * 可靠性：
 * - 温度 0.2，只输出 {actionType, input} JSON
 * - JSON 解析失败修复一次（严格提示重问），仍失败返回 null（Runtime FAILED）
 * - 白名单外 actionType 视为解析失败，同样修复一次
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmAgentStepDecider implements AgentStepDecider {

    private static final double TEMPERATURE = 0.2;

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public AgentStepDecision decide(
            String goal,
            String clippedContext,
            int stepNo,
            int maxSteps
    ) {
        // 主调用
        String json = callDecide(goal, clippedContext, stepNo, maxSteps, false);
        AgentStepDecision decision = parseDecision(json);
        if (decision != null) {
            return decision;
        }

        // 修复一次
        String repaired = callDecide(goal, clippedContext, stepNo, maxSteps, true);
        decision = parseDecision(repaired);
        if (decision != null) {
            log.info("Agent 决策 JSON 修复成功");
            return decision;
        }

        log.warn("Agent 决策 JSON 两次解析均失败，goal={}", truncate(goal, 100));
        return null;
    }

    @Override
    public String summarize(String goal, String clippedContext) {
        try {
            String content = chatClientBuilder.build()
                    .prompt()
                    .system(buildSummarizeSystemPrompt())
                    .user(buildSummarizeUserPrompt(goal, clippedContext))
                    .options(OpenAiChatOptions.builder()
                            .temperature(TEMPERATURE)
                            .build())
                    .call()
                    .content();
            return content == null ? null : content.trim();
        } catch (Exception e) {
            log.warn("Agent 收尾总结失败，降级拼接。goal={}", truncate(goal, 100), e);
            return null;
        }
    }

    private String callDecide(
            String goal,
            String clippedContext,
            int stepNo,
            int maxSteps,
            boolean repair
    ) {
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(buildSystemPrompt(repair))
                    .user(buildUserPrompt(goal, clippedContext, stepNo, maxSteps, repair))
                    .options(OpenAiChatOptions.builder()
                            .temperature(TEMPERATURE)
                            .build())
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("Agent 决策调用失败，goal={}", truncate(goal, 100), e);
            return null;
        }
    }

    private AgentStepDecision parseDecision(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(cleanJson(json));
            String actionText = root.path("actionType").asText("").trim();
            if (actionText.isBlank()) {
                return null;
            }

            AgentStepActionType actionType;
            try {
                actionType = AgentStepActionType.valueOf(actionText);
            } catch (IllegalArgumentException e) {
                log.warn("Agent 决策返回白名单外动作: {}", actionText);
                return null;
            }

            JsonNode inputNode = root.path("input");
            Map<String, Object> input = new HashMap<>();
            if (inputNode != null && inputNode.isObject()) {
                inputNode.properties().forEach(
                        entry -> input.put(entry.getKey(), entry.getValue().asText())
                );
            }
            // V3.8：顶层 anchorMode（文章域指代定位模式）并入 input，由领域 runtime 消费。
            // 并入 input 而非决策 record——零构造点波及，学习/通用域无此字段不受影响。
            String anchorMode = root.path("anchorMode").asText("").trim();
            if (!anchorMode.isBlank()) {
                input.put("anchorMode", anchorMode);
            }
            // V3.10：顶层 "thought"（LLM 侧 ReAct 术语）→ AgentThoughtSanitizer 分级闸门清洗
            // → 存入 thoughtSummary（展示副产品，非决策依据；缺失/违规 → null 全链路可容忍）。
            String thoughtSummary = AgentThoughtSanitizer.sanitize(root.path("thought").asText(""));
            return new AgentStepDecision(actionType, input, thoughtSummary);
        } catch (Exception e) {
            log.warn("Agent 决策 JSON 解析失败: {}", truncate(json, 200));
            return null;
        }
    }

    private String cleanJson(String raw) {
        return raw
                .replace("```json", "")
                .replace("```", "")
                .trim();
    }

    protected String buildSystemPrompt(boolean repair) {
        if (repair) {
            return """
                    你刚才的输出不是合法的 JSON 或包含了非法动作。请重新输出。
                    只能输出一个 JSON 对象：{"actionType":"...","input":{...}}。
                    actionType 只能是：QUERY_LEARNING_DASHBOARD / QUERY_MEMORY / SEARCH_RAG / ASK_USER / FINAL_ANSWER / SUGGEST_WORKFLOW / SUGGEST_WRITE。
                    禁止输出 markdown、代码块或任何解释文字。
                    """;
        }
        return """
                你是学习助手 Agent 的决策器。你的任务是在只读动作白名单中选择下一步动作。

                动作白名单（只能从以下选择）：
                - QUERY_LEARNING_DASHBOARD：查询用户学习计划总览或指定计划详情。
                  input.planRef = 计划名称关键词（可选）
                - QUERY_MEMORY：查询用户长期记忆（学习偏好等）。
                  input.question = 记忆检索关键词
                - SEARCH_RAG：检索站内文章知识。
                  input.keyword = 检索关键词
                - ASK_USER：信息不足需要用户澄清（终态，执行后本轮结束）。
                  input.question = 要问用户的问题
                - FINAL_ANSWER：给出最终学习建议（终态）。
                  input.answer = 完整的建议文本（中文）
                - SUGGEST_WORKFLOW：观察显示用户需要进入固定业务流程时，建议启动学习类 Workflow（终态，后端裁判 + 用户确认后才会真正启动，你无法直接启动）。
                  input.workflowType = 只能是 LEARNING_PLAN / LEARNING_PROGRESS / LEARNING_ASSIST
                  input.reason = 建议原因（中文）
                  input.initialMessage = 用户原始诉求（可选）
                  input.risk = 风险等级 LOW / MEDIUM / HIGH（可选）
                - SUGGEST_WRITE：用户要求修改学习计划任务 / 追加新任务 / 重命名已有任务时，产出写动作提案（终态，后端裁判 + 用户确认后才会执行，你不能直接改任何数据）。注意双层 actionType：外层固定 SUGGEST_WRITE，内层 input.actionType 才是写动作类型，三选一：
                  - input.actionType = UPDATE_TASK_DONE：勾选完成 / 取消勾选已有任务。
                    input.taskTitle = 已有任务标题（从观察中摘录，必须真实存在）
                    input.done = true（勾选完成）/ false（取消勾选）
                  - input.actionType = ADD_LEARNING_TASK：向某阶段追加用户点名的新任务（一次一个）。
                    input.taskTitle = 用户要求添加的新任务标题（来自用户原话，不在观察中；不加不猜）
                  - input.actionType = UPDATE_LEARNING_TASK：把已有任务改名 / 重命名（一次一个，只改名）。
                    input.taskTitle = 被改名的任务标题（旧名，从观察中摘录，必须真实存在）
                    input.newTitle = 用户给的新任务名（来自用户原话；不猜不改写）
                  input.stageTitle = 目标阶段标题（从观察中摘录）
                  input.planRef = 计划名称关键词（可选，用于定位计划）

                决策规则：
                - 已有观察足够回答目标时，直接 FINAL_ANSWER
                - 不知道用户学习进度时，先 QUERY_LEARNING_DASHBOARD
                - 学习偏好可能影响建议时，QUERY_MEMORY
                - 需要站内文章知识支撑时，SEARCH_RAG
                - 存在多个计划且无法确定目标时，ASK_USER 让用户选择，不要猜
                - 观察显示用户需要调整计划 / 制定计划 / 攻坚阶段时，用 SUGGEST_WORKFLOW 建议对应流程（只能建议 LEARNING_PLAN / LEARNING_PROGRESS / LEARNING_ASSIST，不能建议文章类 Workflow）
                - 用户明确要求勾选 / 取消勾选某个已有任务时，用 SUGGEST_WRITE 提案（input.actionType=UPDATE_TASK_DONE，taskTitle 必须来自观察，不能编造）
                - 用户明确给出任务名要求添加到某计划/阶段（如「给 XX 计划加一个 XX 任务」）时，用 SUGGEST_WRITE 提案（input.actionType=ADD_LEARNING_TASK，taskTitle=用户给的新任务名；这与「帮我把阶段拆细」的 LEARNING_ASSIST 拆解诉求不同——拆解是 Workflow 建议，不是写动作）
                - 用户明确要求把某个已有任务改名 / 重命名（如「把缓存击穿任务改名为缓存击穿防护」）时，用 SUGGEST_WRITE 提案（input.actionType=UPDATE_LEARNING_TASK，taskTitle=旧名必须来自观察，newTitle=新名来自用户原话；一次只改一个任务，不要顺带做勾选等其他修改）
                - 写动作提案必须带内层 input.actionType（外层固定 SUGGEST_WRITE，两个 actionType 含义不同，别混淆）
                - 禁止在没有任何查询观察时使用 SUGGEST_WORKFLOW / SUGGEST_WRITE（后端会拒绝）
                - 每步只能输出一个动作

                只输出 JSON：{"actionType":"...","input":{...}}
                """;
    }

    private String buildUserPrompt(
            String goal,
            String clippedContext,
            int stepNo,
            int maxSteps,
            boolean repair
    ) {
        StringBuilder sb = new StringBuilder();
        if (repair) {
            sb.append("（这是修复重试，请务必只输出合法 JSON）\n");
        }
        sb.append("目标：").append(goal).append('\n');
        sb.append("已有观察：\n").append(clippedContext.isBlank() ? "（无）" : clippedContext).append('\n');
        sb.append("当前步数：").append(stepNo).append('/').append(maxSteps);
        // V3.10：thought 书写规范只在正常轮追加（repair 轮宽容缺失，不增加解析负担）。
        // 规范领域无关，收在基类单点——三域决策器共用 buildUserPrompt，零领域 prompt 改动。
        if (!repair) {
            sb.append("\n\nJSON 顶层可选字段 \"thought\"——给用户看的思考摘要：\n")
                    .append("- 一句中文，解释这一步的动机（为什么查这个、看到什么、为什么下一步这么做），第一人称、口语化\n")
                    .append("- 禁止出现任何动作英文名（QUERY_xxx / SEARCH_RAG / SUGGEST_xxx 等）与机制词：")
                    .append("调用、接口、API、模型、工具、参数、JSON、白名单、决策、第 N 步、步骤\n")
                    .append("- 不要把最终答案写进 thought\n")
                    .append("- 想不出自然的说法就省略该字段（缺失不影响任何流程）\n");
        }
        return sb.toString();
    }

    protected String buildSummarizeSystemPrompt() {
        return """
                你是学习助手 Agent。根据提供的观察信息，用中文给用户一份简洁的学习建议。
                要求：
                - 3-6 句话，直接给建议，不要寒暄
                - 基于观察中的真实信息（计划标题、阶段、任务、记忆、文章知识）
                - 观察中没有的信息不要编造
                - 只输出建议正文，不要输出 JSON 或任何多余说明
                """;
    }

    private String buildSummarizeUserPrompt(String goal, String clippedContext) {
        return "目标：" + goal + "\n\n观察信息：\n"
                + (clippedContext.isBlank() ? "（无）" : clippedContext);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
