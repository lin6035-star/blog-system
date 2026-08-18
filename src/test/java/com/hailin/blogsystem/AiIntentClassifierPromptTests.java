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
    }
}
