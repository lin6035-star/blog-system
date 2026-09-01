package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 通用域 Agent 决策器（V3 通用思考模式）。
 *
 * 复用 LlmAgentStepDecider 的调用 / 解析 / 修复骨架，仅替换领域 prompt：
 * - 白名单：QUERY_MEMORY / SEARCH_RAG / ASK_USER / FINAL_ANSWER（无 Workflow 建议、无写提案）
 * - 核心约束：能不查就不查、能直接答直接答（延迟第一道闸）
 * - QUERY_MEMORY 为主（V3 增量），SEARCH_RAG 只作证据补充，不是默认第一步
 */
@Component
public class GeneralAgentStepDecider extends LlmAgentStepDecider {

    public GeneralAgentStepDecider(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        super(chatClientBuilder, objectMapper);
    }

    @Override
    protected String buildSystemPrompt(boolean repair) {
        if (repair) {
            return """
                    你刚才的输出不是合法的 JSON 或包含了非法动作。请重新输出。
                    只能输出一个 JSON 对象：{"actionType":"...","input":{...}}。
                    actionType 只能是：QUERY_MEMORY / SEARCH_RAG / ASK_USER / FINAL_ANSWER。
                    禁止输出 markdown、代码块或任何解释文字。
                    """;
        }
        return """
                你是通用助手 Agent 的决策器。你的任务是在只读动作白名单中选择下一步动作。

                动作白名单（只能从以下选择）：
                - QUERY_MEMORY：查询用户长期记忆（用户情况、偏好、历史决策等）。
                  input.question = 记忆检索关键词
                - SEARCH_RAG：检索站内文章知识，补证据。
                  input.keyword = 检索关键词
                - ASK_USER：信息不足需要用户澄清（终态，执行后本轮结束）。
                  input.question = 要问用户的问题
                - FINAL_ANSWER：给出最终回答（终态）。
                  input.answer = 完整的回答文本（中文）

                决策规则：
                - 能不查就不查、能直接答直接答：已有信息足够回答时，直接 FINAL_ANSWER，不要为了检索而检索
                - 目标依赖用户记忆 / 前文状态 / 历史情况时，先 QUERY_MEMORY
                - SEARCH_RAG 只是证据补充，不是默认第一步：只有站内知识能补足回答时才检索
                - 记忆和 RAG 都不需要时，直接 FINAL_ANSWER（不执行任何查询）
                - 查询后仍信息不足，且需要用户补充背景时，ASK_USER（不要编造）
                - 每步只能输出一个动作

                只输出 JSON：{"actionType":"...","input":{...}}
                """;
    }

    @Override
    protected String buildSummarizeSystemPrompt() {
        return """
                你是通用助手 Agent。根据提供的观察信息，用中文给用户一份简洁的回答。
                要求：
                - 3-6 句话，直接给结论，不要寒暄
                - 基于观察中的真实信息（记忆、文章知识）
                - 观察中没有的信息不要编造
                - 只输出回答正文，不要输出 JSON 或任何多余说明
                """;
    }
}
