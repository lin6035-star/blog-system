package com.hailin.blogsystem;

import com.hailin.blogsystem.service.impl.AiIntentClassifierImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AiIntentClassifierPromptTests {

    @Autowired
    private AiIntentClassifierImpl classifier;

    @Test
    void promptDefinesSemanticLearningWorkflowIntents() throws Exception {
        Method method = AiIntentClassifierImpl.class.getDeclaredMethod("buildSystemPrompt");
        method.setAccessible(true);
        String prompt = (String) method.invoke(classifier);

        assertThat(prompt).contains("LEARNING_PROGRESS");
        assertThat(prompt).contains("LEARNING_ASSIST");
        assertThat(prompt).contains("LEARNING_PLAN_QUERY");
        assertThat(prompt).contains("learningPlanRef");
        assertThat(prompt).contains("learningStageRef");
        assertThat(prompt).contains("confidence");
        assertThat(prompt).contains("CREATE_ARTICLE");
        assertThat(prompt).contains("OPTIMIZE_ARTICLE");
        assertThat(prompt).contains("ARTICLE_SEARCH");
        assertThat(prompt).contains("ARTICLE_DETAIL_QA");
        // V2.5：文章侧 Agent 意图 + 模糊优化诉求规则
        assertThat(prompt).contains("ARTICLE_AGENT");
        assertThat(prompt).contains("模糊的优化诉求");
    }

    @Test
    void promptDefinesFullDomainWorkflowRouting() throws Exception {
        Method method = AiIntentClassifierImpl.class
                .getDeclaredMethod("buildSystemPrompt");
        method.setAccessible(true);

        String prompt = (String) method.invoke(classifier);

        assertThat(prompt)
                .contains("CREATE_ARTICLE")
                .contains("OPTIMIZE_ARTICLE")
                .contains("LEARNING_PLAN")
                .contains("LEARNING_PROGRESS")
                .contains("LEARNING_ASSIST");

        assertThat(prompt)
                .contains("ARTICLE_SEARCH")
                .contains("ARTICLE_DETAIL_QA");

        assertThat(prompt)
                .contains("ARTICLE_SEARCH")
                .contains("suggestedAction=CHAT");
    }

    @Test
    void promptDefinesAgentSuggestionFields() throws Exception {
        Method method = AiIntentClassifierImpl.class.getDeclaredMethod("buildSystemPrompt");
        method.setAccessible(true);
        String prompt = (String) method.invoke(classifier);

        assertThat(prompt).contains("suggestedAction");
        assertThat(prompt).contains("suggestedWorkflowType");
        assertThat(prompt).contains("risk");
        assertThat(prompt).contains("reason");

        assertThat(prompt).contains("CHAT");
        assertThat(prompt).contains("TOOL");
        assertThat(prompt).contains("WORKFLOW");
        assertThat(prompt).contains("CTA");

        assertThat(prompt).contains("这些字段只是给后端 Planner 的建议");
    }

    @Test
    void promptDefinesNeedsThinkingSemanticRule() throws Exception {
        Method method = AiIntentClassifierImpl.class.getDeclaredMethod("buildSystemPrompt");
        method.setAccessible(true);
        String prompt = (String) method.invoke(classifier);

        // V3 通用思考模式：needsThinking 只对 GENERAL_CHAT 生效、默认 false、语义判定非词表
        assertThat(prompt).contains("needsThinking");
        assertThat(prompt).contains("needsThinkingReason");
        assertThat(prompt).contains("只对 GENERAL_CHAT 有意义");
        assertThat(prompt).contains("默认 false");
        assertThat(prompt).contains("判定标准是语义，不是关键词");
        assertThat(prompt).contains("依赖用户记忆/历史状态");
        assertThat(prompt).contains("不影响路由结果");
    }
}
