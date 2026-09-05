package com.hailin.blogsystem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.service.AiIntentClassifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
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

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("evalCases")
    void classifierMatchesFixedEvalCase(String caseName, ClassifierEvalCase c) {
        AiIntent intent = aiIntentClassifier.classify(c.message(), null);

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
