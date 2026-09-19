package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.ai.agent.ArticleEvidenceVerifier;
import com.hailin.blogsystem.ai.agent.Verdict;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文章域证据收敛门测试（V3.11）。
 * - 规则闸（零 LLM）：R1 目标文章未读 → NEED_MORE / R2 零观察 → 放行
 * - 语义闸（mock ChatClient）：SUFFICIENT / NEED_MORE(missingEvidence) / ASK_USER /
 *   ASK_USER 缺 question 走修复 / JSON 两次失败 fail-open / 调用异常 fail-open
 * - token 统计（2026-09-17）：语义闸用量累加到 run
 *
 * 2026-09-17 改造：收敛门从 `.call().content()` 改为 `.call().chatResponse()`
 * ——content() 拿不到 metadata 里的 usage。stub 相应换成 chatResponse()。
 */
class ArticleEvidenceVerifierTests {

    private ChatClient.Builder builder;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ArticleEvidenceVerifier verifier;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        verifier = new ArticleEvidenceVerifier(builder, new ObjectMapper(), new AiJudgeModelSupport(""));
    }

    /** 只带文本的响应：metadata 为 null，验证 usage 取值对 null 安全。 */
    private static ChatResponse textResponse(String text) {
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult())
                .thenReturn(new Generation(new AssistantMessage(text == null ? "" : text)));
        return response;
    }

    /** 带 usage 的响应。 */
    private static ChatResponse usageResponse(String text, int prompt, int completion, int total) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(prompt);
        when(usage.getCompletionTokens()).thenReturn(completion);
        when(usage.getTotalTokens()).thenReturn(total);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult())
                .thenReturn(new Generation(new AssistantMessage(text == null ? "" : text)));
        when(response.getMetadata()).thenReturn(metadata);
        return response;
    }

    private static AiAgentRun run(Long targetArticleId, String goal) {
        AiAgentRun run = new AiAgentRun();
        run.setId(1L);
        run.setGoal(goal);
        run.setTargetArticleId(targetArticleId);
        return run;
    }

    private static AgentStepDecision finalAnswer(String answer) {
        return AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                .withInput(Map.of("answer", answer));
    }

    private static final List<AgentStepActionType> READ_ARTICLE = List.of(AgentStepActionType.QUERY_ARTICLE);
    private static final List<AgentStepActionType> NO_READ = List.of(AgentStepActionType.SEARCH_RAG);

    // ==================== 规则闸（零 LLM 调用） ====================

    @Test
    void r1TargetResolvedButArticleNeverReadReturnsNeedMore() {
        Verdict verdict = verifier.verify(
                run(12L, "分析《Redis 缓存》这篇文章"),
                finalAnswer("这篇文章结构不错"),
                List.of("站内文章知识检索结果：..."),
                NO_READ
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.NEED_MORE);
        assertThat(verdict.missingEvidence()).isEqualTo(Verdict.MissingEvidence.ARTICLE_CONTENT.name());
        assertThat(verdict.missingEvidenceLabel()).isEqualTo("文章正文细节");
    }

    @Test
    void r3RepeatedObservationWithoutNewEvidencePasses() {
        // 2026-09-10 手测：决策器换 anchorMode 重读同一篇，观察逐字相同 →
        // 语义闸反复判 NEED_MORE → 补查 → 又是同一段 → 6 步耗尽空转到顶。
        // 补查已证明拿不到新材料时放行，让 LLM 用手上证据尽力作答。
        String sameObservation = "当前文章分析：\n- 标题：《Vibe Coding 入门指南》\n- 字数：约 1700 字";

        Verdict verdict = verifier.verify(
                run(12L, "这篇文章的 Vibe Coding 这一小节写得怎么样"),
                finalAnswer("这一小节写得不错"),
                List.of(sameObservation, sameObservation),   // 第二轮与第一轮逐字相同
                READ_ARTICLE
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.SUFFICIENT);
        // 未调用 LLM 语义闸（规则闸直接放行）
        verify(chatClient, never()).prompt();
    }

    @Test
    void r3NewEvidenceStillGoesThroughSemanticGate() {
        // 反向锁：真拿到新材料时不能走 R3 放行，必须照常过语义闸
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse(
                "{\"verdict\":\"NEED_MORE\",\"missingEvidence\":\"ARTICLE_CONTENT\",\"reason\":\"还是缺细节\"}"));

        Verdict verdict = verifier.verify(
                run(12L, "这篇文章的 Vibe Coding 这一小节写得怎么样"),
                finalAnswer("这一小节写得不错"),
                List.of(
                        "当前文章分析：\n- 标题：《Vibe Coding 入门指南》",
                        "聚焦片段：\n## 怎么做一次 Vibe Coding？\n\n播放一首 Lo-fi"   // 新内容
                ),
                READ_ARTICLE
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.NEED_MORE);
    }

    @Test
    void r1TargetNullSkipsArticleGate() {
        // 目标未决议（不涉及具体文章）→ 规则闸放行，走语义闸（无 QUERY_ARTICLE 也不拦）
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse("{\"verdict\":\"SUFFICIENT\",\"reason\":\"够了\"}"));

        Verdict verdict = verifier.verify(
                run(null, "帮我看看写作风格"),
                finalAnswer("你的写作偏技术说明风"),
                List.of("记忆摘要：..."),
                NO_READ
        );

        // 语义闸被调用 → LLM 返回 SUFFICIENT（非 fail-open）
        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.SUFFICIENT);
    }

    @Test
    void r2ZeroObservationDirectAnswerReturnsSufficient() {
        // 零查询直接答（收尾寒暄）→ 放行且不调 LLM（callSpec 未 stub 返回 null 也不影响）
        Verdict verdict = verifier.verify(
                run(null, "好的谢谢"),
                finalAnswer("不客气"),
                List.of(),
                NO_READ
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.SUFFICIENT);
    }

    // ==================== 语义闸（mock LLM） ====================

    @Test
    void semanticGateSufficientPassesThrough() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse("{\"verdict\":\"SUFFICIENT\",\"reason\":\"文章结构分析有依据\"}"));

        Verdict verdict = verifier.verify(
                run(12L, "这篇文章结构怎么样"),
                finalAnswer("全文分三节，小标题清晰"),
                List.of("当前文章分析：\n- 标题：《Redis 缓存》\n- 小标题结构：三节"),
                READ_ARTICLE
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.SUFFICIENT);
    }

    @Test
    void semanticGateNeedMoreCarriesMissingEvidence() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse("{\"verdict\":\"NEED_MORE\","
                + "\"missingEvidence\":\"ARTICLE_CONTENT\",\"reason\":\"草案提到了缓存击穿段，但证据里没有该段内容\"}"));

        Verdict verdict = verifier.verify(
                run(12L, "缓存击穿那段写得怎么样"),
                finalAnswer("缓存击穿那段逻辑清晰"),
                List.of("当前文章分析：\n- 标题：《Redis 缓存》\n- 正文预览：整体结构…"),
                READ_ARTICLE
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.NEED_MORE);
        assertThat(verdict.missingEvidenceLabel()).isEqualTo("文章正文细节");
        assertThat(verdict.reason()).contains("缓存击穿段");
    }

    @Test
    void semanticGateAskUserCarriesQuestion() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse("{\"verdict\":\"ASK_USER\","
                + "\"question\":\"你想让我分析这篇文章的哪个方面？\",\"reason\":\"用户没说清分析维度\"}"));

        Verdict verdict = verifier.verify(
                run(12L, "分析一下这篇"),
                finalAnswer("这篇的优点是…"),
                List.of("当前文章分析：\n- 标题：《Redis 缓存》"),
                READ_ARTICLE
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.ASK_USER);
        assertThat(verdict.question()).isEqualTo("你想让我分析这篇文章的哪个方面？");
    }

    @Test
    void askUserWithoutQuestionRepairsOnceThenFailOpen() {
        // 第一次 ASK_USER 缺 question（非法）→ 修复一次；修复仍缺 → fail-open SUFFICIENT
        when(callSpec.chatResponse())
                .thenAnswer(inv -> textResponse("{\"verdict\":\"ASK_USER\",\"reason\":\"缺问题\"}"))
                .thenAnswer(inv -> textResponse("{\"verdict\":\"ASK_USER\",\"reason\":\"还是缺问题\"}"));

        Verdict verdict = verifier.verify(
                run(12L, "分析一下这篇"),
                finalAnswer("这篇的优点是…"),
                List.of("当前文章分析：\n- 标题：《Redis 缓存》"),
                READ_ARTICLE
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.SUFFICIENT);
    }

    @Test
    void jsonParseFailureTwiceFailsOpen() {
        when(callSpec.chatResponse())
                .thenAnswer(inv -> textResponse("不是 JSON"))
                .thenAnswer(inv -> textResponse("还不是 JSON"));

        Verdict verdict = verifier.verify(
                run(12L, "这篇文章结构怎么样"),
                finalAnswer("全文分三节"),
                List.of("当前文章分析：\n- 标题：《Redis 缓存》"),
                READ_ARTICLE
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.SUFFICIENT);
    }

    @Test
    void llmCallExceptionFailsOpen() {
        when(callSpec.chatResponse()).thenThrow(new RuntimeException("LLM 挂了"));

        Verdict verdict = verifier.verify(
                run(12L, "这篇文章结构怎么样"),
                finalAnswer("全文分三节"),
                List.of("当前文章分析：\n- 标题：《Redis 缓存》"),
                READ_ARTICLE
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.SUFFICIENT);
    }

    @Test
    void repairPathSecondCallParses() {
        when(callSpec.chatResponse())
                .thenAnswer(inv -> textResponse("输出了一些解释文字"))
                .thenAnswer(inv -> textResponse("{\"verdict\":\"SUFFICIENT\",\"reason\":\"修复成功\"}"));

        Verdict verdict = verifier.verify(
                run(12L, "这篇文章结构怎么样"),
                finalAnswer("全文分三节"),
                List.of("当前文章分析：\n- 标题：《Redis 缓存》"),
                READ_ARTICLE
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.SUFFICIENT);
    }

    @Test
    void emptyAnswerSkipsSemanticGate() {
        // 草案为空（骨架会走兜底文案）→ 不调 LLM 直接放行
        Verdict verdict = verifier.verify(
                run(12L, "这篇文章结构怎么样"),
                finalAnswer(""),
                List.of("当前文章分析：\n- 标题：《Redis 缓存》"),
                READ_ARTICLE
        );

        assertThat(verdict.type()).isEqualTo(Verdict.VerdictType.SUFFICIENT);
    }

    // ==================== token 统计（2026-09-17） ====================

    @Test
    void semanticGateUsageIsAccumulatedIntoRun() {
        when(callSpec.chatResponse()).thenAnswer(inv -> usageResponse(
                "{\"verdict\":\"SUFFICIENT\",\"reason\":\"够了\"}", 300, 30, 330));

        AiAgentRun run = run(12L, "这篇文章结构怎么样");
        verifier.verify(
                run,
                finalAnswer("全文分三节"),
                List.of("当前文章分析：\n- 标题：《Redis 缓存》"),
                READ_ARTICLE
        );

        assertThat(run.getTotalTokens())
                .as("收敛门是收尾前的独立校验，用量归 run（不属于任何一步）")
                .isEqualTo(330);
        assertThat(run.getInputTokens()).isEqualTo(300);
        assertThat(run.getOutputTokens()).isEqualTo(30);
    }

    @Test
    void repairAttemptUsageIsAlsoAccumulated() {
        // 第一次解析失败触发 repair：两次都是真花钱的调用，都要算
        when(callSpec.chatResponse())
                .thenAnswer(inv -> usageResponse("不是 JSON", 100, 10, 110))
                .thenAnswer(inv -> usageResponse("{\"verdict\":\"SUFFICIENT\",\"reason\":\"修复成功\"}", 200, 20, 220));

        AiAgentRun run = run(12L, "这篇文章结构怎么样");
        verifier.verify(
                run,
                finalAnswer("全文分三节"),
                List.of("当前文章分析：\n- 标题：《Redis 缓存》"),
                READ_ARTICLE
        );

        assertThat(run.getTotalTokens()).isEqualTo(330);
    }

    @Test
    void ruleGateLeavesTokensUntouchedWhenLlmNotCalled() {
        // 规则闸（R1）直接拦截，一次 LLM 都没调 → 不能凭空产生用量
        AiAgentRun run = run(12L, "分析《Redis 缓存》这篇文章");
        verifier.verify(
                run,
                finalAnswer("这篇文章结构不错"),
                List.of("站内文章知识检索结果：..."),
                NO_READ
        );

        assertThat(run.getTotalTokens())
                .as("零 LLM 调用的路径不应写入任何用量")
                .isNull();
    }
}
