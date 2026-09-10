package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.ArticleEvidenceVerifier;
import com.hailin.blogsystem.ai.agent.Verdict;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文章域证据收敛门真实 LLM 在线评测（V3.11，设计稿 §六）。
 *
 * 固定样例集 verifier-evaluation-cases.json（goal + observations + answer → 期望三态），
 * 每条真实调用 ArticleEvidenceVerifier 语义闸（规则闸在离线单测覆盖，这里样例统一带
 * QUERY_ARTICLE 成功动作确保走语义闸）。
 *
 * 门的意义：锁「明显证据不足的回答不会被放过」（NEED_MORE/ASK_USER 例），
 * 防止决策器 prompt / Verifier prompt 迭代后收敛门退化成恒放行。
 *
 * 跑法：-Dgroups=eval -DrunRealLlm=true（在线门一组；不依赖 ES，VectorStore 已 mock）
 */
@Tag("eval")
@SpringBootTest
@EnabledIfSystemProperty(named = "runRealLlm", matches = "true")
class ArticleEvidenceVerifierOnlineTests {

    @Autowired
    private ArticleEvidenceVerifier verifier;

    @MockBean
    private VectorStore vectorStore;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private record VerifierEvalCase(
            String name,
            String goal,
            List<String> observations,
            String answer,
            String expectedVerdict,
            String expectedMissingEvidence
    ) {
    }

    static Stream<Arguments> evalCases() throws Exception {
        InputStream inputStream = new ClassPathResource("agent/verifier-evaluation-cases.json")
                .getInputStream();
        List<VerifierEvalCase> cases = OBJECT_MAPPER.readValue(
                Objects.requireNonNull(inputStream),
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, VerifierEvalCase.class)
        );
        assertThat(cases).as("验证器评测集不能为空").isNotEmpty();
        return cases.stream().map(c -> Arguments.of(c.name(), c));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("evalCases")
    void verdictMatchesFixedEvalCase(String caseName, VerifierEvalCase c) {
        AiAgentRun run = new AiAgentRun();
        run.setId(1L);
        run.setGoal(c.goal());
        // 样例统一带决议目标 + QUERY_ARTICLE 成功动作 → 规则闸 R1/R2 不触发，稳定走语义闸
        run.setTargetArticleId(12L);

        AgentStepDecision decision = AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                .withInput(Map.of("answer", c.answer()));

        Verdict verdict = verifier.verify(run, decision, c.observations(),
                List.of(AgentStepActionType.QUERY_ARTICLE));

        // 两档断言：SUFFICIENT 例精确锁「证据够要放行」；
        // NEED_MORE/ASK_USER 例锁「明显不足不能被放水」（ASK_USER 与 NEED_MORE 互判都形成闭环，
        // 都是「拦」，实测分界抖动属可接受语义差异，不做硬锁）
        if ("SUFFICIENT".equals(c.expectedVerdict())) {
            assertThat(verdict.type().name())
                    .as("用例 %s：verdict", caseName)
                    .isEqualTo("SUFFICIENT");
        } else {
            assertThat(verdict.type())
                    .as("用例 %s：明显不足的回答不应放行", caseName)
                    .isNotEqualTo(Verdict.VerdictType.SUFFICIENT);
        }
        // missingEvidence 归因只在 NEED_MORE 时校验（样例 5/8 的归因稳定可硬锁）
        if (c.expectedMissingEvidence() != null && !c.expectedMissingEvidence().isBlank()
                && verdict.type() == Verdict.VerdictType.NEED_MORE) {
            assertThat(Verdict.MissingEvidence.from(verdict.missingEvidence()).name())
                    .as("用例 %s：missingEvidence 归因", caseName)
                    .isEqualTo(c.expectedMissingEvidence());
        }
    }

    @Test
    void semanticGateIsNotLazySufficient() {
        // 防退化的独立探针：明显缺正文证据的问答必须被拦（与样例 5 双保险，独立跑一次）
        AiAgentRun run = new AiAgentRun();
        run.setId(1L);
        run.setGoal("缓存击穿那一段写得怎么样");
        run.setTargetArticleId(12L);

        AgentStepDecision decision = AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                .withInput(Map.of("answer", "缓存击穿那段用互斥锁方案，锁超时 5 秒，很严谨"));

        Verdict verdict = verifier.verify(run, decision, List.of(
                "当前文章分析：\n- 标题：《Redis 缓存设计》\n- 字数：约 3200 字\n- 正文预览：本文介绍…（截断）"),
                List.of(AgentStepActionType.QUERY_ARTICLE));

        assertThat(verdict.type())
                .as("语义闸不应放过无依据的具体论断")
                .isNotEqualTo(Verdict.VerdictType.SUFFICIENT);
    }
}
