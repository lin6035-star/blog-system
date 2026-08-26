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
}
