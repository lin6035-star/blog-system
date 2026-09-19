package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.LearningPlanMapper;
import com.hailin.blogsystem.service.AiIntentClassifier;
import org.junit.jupiter.api.AfterEach;
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

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("eval")
@SpringBootTest
@EnabledIfSystemProperty(named = "runRealLlm", matches = "true")
class AiIntentClassifierIntegrationTests {

    @Autowired
    private AiIntentClassifier aiIntentClassifier;

    /** mock 掉真 ES vectorStore（评测门只测分类器 prompt，不依赖本地 ES 可用性） */
    @MockBean
    private VectorStore vectorStore;

    /** 别名选中用例的计划 fixture 用户：避开其他测试类占用的 101/102/103/106/107 */
    private static final Long PLAN_TEST_USER = 109L;

    @Autowired
    private LearningPlanMapper learningPlanMapper;

    // ------------------------------------------------------------------
    // 评测集：JSON 文件驱动（classifier-evaluation-cases.json）
    // 加样例 = 加 JSON 行；-DevalCase=子串 可只跑名称匹配的样例（省 LLM 调用）
    // ------------------------------------------------------------------

    /**
     * 一条分类器评测样例。字段全部可空，非 null 才断言；
     * 显式 false 也能断言（expectedNeedsThinking），不能靠 null 跳过。
     */
    record ClassifierEvalCase(
            String name,
            String category,
            String message,
            String pageType,
            String pageArticleId,
            String expectedIntent,
            String expectedPlanRefContains,
            String expectedStageRefContains,
            Boolean expectedPlanRefNonBlank,
            Double expectedConfidenceMin,
            Boolean expectedNeedsThinking,
            Boolean expectedNeedsThinkingReasonNonBlank,
            String expectedSuggestedAction
    ) {
    }

    static Stream<Arguments> evalCases() throws Exception {
        InputStream inputStream = AiIntentClassifierIntegrationTests.class
                .getResourceAsStream("/agent/classifier-evaluation-cases.json");

        assertThat(inputStream)
                .as("分类器评测集文件必须存在")
                .isNotNull();

        List<ClassifierEvalCase> cases = new ObjectMapper().readValue(
                Objects.requireNonNull(inputStream),
                new TypeReference<>() {
                }
        );

        assertThat(cases)
                .as("分类器评测集不能为空")
                .isNotEmpty();

        // 每例必须带 name/message，否则无法定位失败
        for (ClassifierEvalCase c : cases) {
            assertThat(c.name())
                    .as("样例缺少 name")
                    .isNotBlank();
            assertThat(c.message())
                    .as("样例 %s 缺少 message", c.name())
                    .isNotBlank();
        }

        String evalCaseFilter = System.getProperty("evalCase");
        if (evalCaseFilter != null && !evalCaseFilter.isBlank()) {
            cases = cases.stream()
                    .filter(c -> c.name().contains(evalCaseFilter))
                    .toList();
            assertThat(cases)
                    .as("-DevalCase=%s 无匹配样例", evalCaseFilter)
                    .isNotEmpty();
        }

        return cases.stream().map(c -> Arguments.of(c.name(), c));
    }

    // ------------------------------------------------------------------
    // V4.x：分类器拿到「用户真实计划列表」之后的别名选中能力
    // 与固定样例集分开：这两条依赖库内 fixture（两个易混标题），锁的是「换个说法对不对得上」与「没点名不许猜」
    // ------------------------------------------------------------------

    @Test
    void picksPlanFromInjectedListByAlias() {
        // 旧行为：learningPlanRef="c++学习计划" 被分词切成 "c"，与「C语言系统学习计划」同分并列 → 弹卡让用户重选
        insertActivePlan("C语言系统学习计划");
        LearningPlans cppPlan = insertActivePlan("C++ 系统学习与工程化实践计划");

        AiIntent intent = aiIntentClassifier.classify(
                "帮我把c++的第一阶段浓缩成四个小任务", null, PLAN_TEST_USER, null);

        assertThat(intent.getLearningPlanId())
                .as("用户说了 c++，应选中《C++ 系统学习与工程化实践计划》，而不是《C语言系统学习计划》")
                .isEqualTo(String.valueOf(cppPlan.getId()));
    }

    @Test
    void doesNotGuessWhenNoPlanMentioned() {
        insertActivePlan("C语言系统学习计划");
        insertActivePlan("C++ 系统学习与工程化实践计划");

        AiIntent intent = aiIntentClassifier.classify(
                "帮我把第一阶段浓缩成四个小任务", null, PLAN_TEST_USER, null);

        assertThat(intent.getLearningPlanId())
                .as("用户没点名计划、多候选下不得猜——宁可留给后端追问")
                .isNull();
    }

    private LearningPlans insertActivePlan(String title) {
        LearningPlans plan = new LearningPlans();
        plan.setUserId(PLAN_TEST_USER);
        plan.setTitle(title);
        plan.setStatus(LearningPlans.STATUS_ACTIVE);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        learningPlanMapper.insert(plan);
        return plan;
    }

    @AfterEach
    void cleanupPlanFixture() {
        learningPlanMapper.delete(new LambdaQueryWrapper<LearningPlans>()
                .eq(LearningPlans::getUserId, PLAN_TEST_USER));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("evalCases")
    void classifierMatchesFixedEvalCase(String caseName, ClassifierEvalCase c) {
        // 样例可带页面上下文（V3.4 起：article-detail 分界依赖 pageType/articleId）
        PageContextDTO pageContext = null;
        if (c.pageType() != null) {
            pageContext = new PageContextDTO();
            pageContext.setPageType(c.pageType());
            pageContext.setArticleId(c.pageArticleId());
        }
        // userId 传 null：固定样例集锁的是「分类器只看原话能理解到什么」（原有 22 例语义不变）；
        // 注入真实计划列表后的「别名选中」能力由 picksPlanFromInjectedListByAlias 单独验证
        AiIntent intent = aiIntentClassifier.classify(c.message(), pageContext, null, null);

        if (c.expectedIntent() != null) {
            assertThat(intent.getIntent())
                    .as("用例 %s：intent", caseName)
                    .isEqualTo(c.expectedIntent());
        }
        if (c.expectedPlanRefContains() != null) {
            assertThat(intent.getLearningPlanRef())
                    .as("用例 %s：learningPlanRef 应包含", caseName)
                    .contains(c.expectedPlanRefContains());
        }
        if (c.expectedStageRefContains() != null) {
            assertThat(intent.getLearningStageRef())
                    .as("用例 %s：learningStageRef 应包含", caseName)
                    .contains(c.expectedStageRefContains());
        }
        if (Boolean.TRUE.equals(c.expectedPlanRefNonBlank())) {
            assertThat(intent.getLearningPlanRef())
                    .as("用例 %s：learningPlanRef 不应为空", caseName)
                    .isNotBlank();
        }
        if (c.expectedConfidenceMin() != null) {
            assertThat(intent.getConfidence())
                    .as("用例 %s：confidence", caseName)
                    .isGreaterThanOrEqualTo(c.expectedConfidenceMin());
        }
        if (c.expectedNeedsThinking() != null) {
            assertThat(intent.getNeedsThinking())
                    .as("用例 %s：needsThinking", caseName)
                    .isEqualTo(c.expectedNeedsThinking());
        }
        if (Boolean.TRUE.equals(c.expectedNeedsThinkingReasonNonBlank())) {
            assertThat(intent.getNeedsThinkingReason())
                    .as("用例 %s：needsThinkingReason 不应为空", caseName)
                    .isNotBlank();
        }
        if (c.expectedSuggestedAction() != null) {
            assertThat(intent.getSuggestedAction())
                    .as("用例 %s：suggestedAction", caseName)
                    .isEqualTo(c.expectedSuggestedAction());
        }
    }
}
