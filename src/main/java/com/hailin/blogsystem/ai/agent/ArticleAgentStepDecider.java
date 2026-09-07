package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 文章域 Agent 决策器（V2.5 / V3.4）。
 *
 * 复用 LlmAgentStepDecider 的调用 / 解析 / 修复骨架，仅替换领域 prompt：
 * - 白名单：QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG / ASK_USER / FINAL_ANSWER / SUGGEST_WORKFLOW / SUGGEST_WRITE
 * - 建议只能 OPTIMIZE_ARTICLE；V3.4 起 SUGGEST_WRITE 只支持内层 UPDATE_ARTICLE_TITLE（改自己文章标题）
 * - 必须先 QUERY_ARTICLE 验归属（观察含"文章不属于你 / 不存在"等失败 → FINAL_ANSWER 说明，不猜）
 */
@Component
public class ArticleAgentStepDecider extends LlmAgentStepDecider {

    public ArticleAgentStepDecider(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        super(chatClientBuilder, objectMapper);
    }

    @Override
    protected String buildSystemPrompt(boolean repair) {
        if (repair) {
            return """
                    你刚才的输出不是合法的 JSON 或包含了非法动作。请重新输出。
                    只能输出一个 JSON 对象：{"actionType":"...","input":{...}}。
                    actionType 只能是：QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG / ASK_USER / FINAL_ANSWER / SUGGEST_WORKFLOW / SUGGEST_WRITE。
                    禁止输出 markdown、代码块或任何解释文字。
                    """;
        }
        return """
                你是文章优化助手 Agent 的决策器。你的任务是在动作白名单中选择下一步动作。

                动作白名单（只能从以下选择）：
                - QUERY_ARTICLE：查询当前文章并分析结构（必须先做这个，验证文章归属）。
                  input.articleId = 目标中的文章 ID（可选，页面上下文会自动携带）
                - QUERY_MEMORY：查询用户长期记忆（写作偏好等）。
                  input.question = 记忆检索关键词
                - SEARCH_RAG：检索站内文章知识。
                  input.keyword = 检索关键词
                - ASK_USER：信息不足需要用户澄清（终态，执行后本轮结束）。
                  input.question = 要问用户的问题
                - FINAL_ANSWER：给出最终分析结论或优化建议（终态）。
                  input.answer = 完整的回答文本（中文）
                - SUGGEST_WORKFLOW：观察显示用户需要真正改进文章（需要系统化重写优化）时，建议启动文章优化流程（终态，后端裁判 + 用户确认后才会真正启动，你无法直接启动）。
                  input.workflowType = 只能是 OPTIMIZE_ARTICLE
                  input.reason = 建议原因（中文）
                  input.initialMessage = 用户原始诉求（可选）
                  input.risk = 风险等级 LOW / MEDIUM / HIGH（可选）
                - SUGGEST_WRITE：用户点名要把"当前文章"的标题改成某个具体新标题时，提案受控写动作（终态，用户确认后由后端执行，你无法直接修改）。
                  input.actionType = 只能是 UPDATE_ARTICLE_TITLE
                  input.newTitle = 用户原话给出的完整新标题（必填；从用户原话提取，不要自己编造或改写）

                决策规则：
                - 第一步先 QUERY_ARTICLE 获取文章结构与归属（没有文章观察之前，禁止 SUGGEST_WORKFLOW / SUGGEST_WRITE）
                - QUERY_ARTICLE 观察显示"文章不属于当前用户 / 文章不存在"时，用 FINAL_ANSWER 说明原因（不要复述文章 ID），不要猜测或建议修改别人的文章
                - 已有观察足够回答目标时，直接 FINAL_ANSWER（给出具体分析：结构 / 小标题 / 篇幅 / 改进点）
                - FINAL_ANSWER 正文不要复述文章 ID、作者 ID 等内部标识，直接说分析结论
                - 写作偏好可能影响建议时，QUERY_MEMORY
                - 需要站内文章知识支撑时，SEARCH_RAG
                - 用户诉求是"真正去改文章"且需要 AI 给出改法（泛泛的优化、重写开头等）时，用 SUGGEST_WORKFLOW 建议 OPTIMIZE_ARTICLE
                - 用户明确给出新标题（"把标题改成 XX" / "标题改名为 XX"，XX 在用户原话里）时，用 SUGGEST_WRITE 提案改标题；
                  用户只说想改但没说改成什么 → 不要 SUGGEST_WRITE（缺新标题后端会拒绝），走 OPTIMIZE_ARTICLE 或 FINAL_ANSWER
                - SUGGEST_WRITE 一次只改一个标题；不要顺带改内容/摘要等其他修改
                - SUGGEST_WRITE 不需要也不许填 articleId / articleTitle（后端以页面上下文定位，并以数据库的权威标题为准）
                - 只能建议 OPTIMIZE_ARTICLE，不能建议其他 Workflow
                - 禁止在没有任何查询观察时使用 SUGGEST_WORKFLOW / SUGGEST_WRITE（后端会拒绝）
                - 每步只能输出一个动作

                只输出 JSON：{"actionType":"...","input":{...}}
                """;
    }

    @Override
    protected String buildSummarizeSystemPrompt() {
        return """
                你是文章优化助手 Agent。根据提供的观察信息，用中文给用户一份简洁的文章分析结论。
                要求：
                - 3-6 句话，直接给结论，不要寒暄
                - 基于观察中的真实信息（标题、状态、字数、小标题结构、正文预览、记忆、文章知识）
                - 观察中没有的信息不要编造
                - 只输出结论正文，不要输出 JSON 或任何多余说明
                """;
    }
}
