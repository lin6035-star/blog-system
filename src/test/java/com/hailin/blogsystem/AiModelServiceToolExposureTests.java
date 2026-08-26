package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.tool.*;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.service.AiIntentClassifier;
import com.hailin.blogsystem.service.impl.AiModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelServiceToolExposureTests {

    private AiModelServiceImpl service;
    private ChatClient.Builder builder;
    private BlogAiProperties properties;
    private AiArticleTools articleTools;
    private AiNavigationTools navigationTools;
    private AiEditorTools editorTools;
    private AiArticleActionTools articleActionTools;
    private AiUserProfileTools userProfileTools;
    private AiLearningDashboardTool dashboardTool;
    private AiNavigationToolsFactory navigationToolsFactory;
    private AiEditorToolFactory editorToolFactory;
    private AiArticleActionToolsFactory articleActionToolsFactory;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        properties = new BlogAiProperties();
        properties.setSystemPrompt("");

        articleTools = mock(AiArticleTools.class);

        navigationTools = mock(AiNavigationTools.class);
        editorTools = mock(AiEditorTools.class);
        articleActionTools = mock(AiArticleActionTools.class);

        navigationToolsFactory = mock(AiNavigationToolsFactory.class);
        editorToolFactory = mock(AiEditorToolFactory.class);
        articleActionToolsFactory = mock(AiArticleActionToolsFactory.class);

        when(navigationToolsFactory.create(anyString()))
                .thenReturn(navigationTools);

        when(editorToolFactory.create(anyString()))
                .thenReturn(editorTools);

        when(articleActionToolsFactory.create(anyString()))
                .thenReturn(articleActionTools);

        userProfileTools = mock(AiUserProfileTools.class);
        dashboardTool = mock(AiLearningDashboardTool.class);

        service = new AiModelServiceImpl(
                builder,
                properties,
                articleTools,
                navigationToolsFactory,
                editorToolFactory,
                articleActionToolsFactory,
                userProfileTools,
                mock(AiIntentClassifier.class),
                dashboardTool
        );
    }

    @Test
    void dashboardDecisionOnlyExposesDashboardCallback() throws Exception {
        AiPrompt prompt = new AiPrompt();
        prompt.setLearningDashboardToolEnabled(true);

        Object[] tools = invokeBuildTools(prompt);
        ToolCallback[] callbacks = invokeBuildToolCallbacks(prompt);

        assertThat(tools).isEmpty();
        assertThat(callbacks).containsExactly(dashboardTool);
    }

    @Test
    void normalChatDoesNotExposeLegacyLearningPlanTools() throws Exception {
        AiPrompt prompt = new AiPrompt();

        Object[] tools = invokeBuildTools(prompt);
        ToolCallback[] callbacks = invokeBuildToolCallbacks(prompt);

        assertThat(callbacks).isEmpty();
        assertThat(tools).containsExactly(
                navigationTools,
                editorTools,
                articleActionTools,
                userProfileTools
        );
        assertThat(Arrays.stream(tools).map(Object::getClass).map(Class::getSimpleName))
                .doesNotContain("AiLearningPlanTools", "QueryLearningPlansTool");
    }

    @Test
    void articleChatDoesNotExposeLegacyLearningPlanTools() throws Exception {
        AiPrompt prompt = new AiPrompt();
        prompt.setArticleToolsEnabled(true);

        Object[] tools = invokeBuildTools(prompt);

        assertThat(tools).containsExactly(
                articleTools,
                navigationTools,
                editorTools,
                articleActionTools,
                userProfileTools
        );
        assertThat(Arrays.stream(tools).map(Object::getClass).map(Class::getSimpleName))
                .doesNotContain("AiLearningPlanTools", "QueryLearningPlansTool");
    }

    private Object[] invokeBuildTools(AiPrompt prompt) throws Exception {
        Method method = AiModelServiceImpl.class.getDeclaredMethod(
                "buildTools",
                AiPrompt.class,
                AiNavigationTools.class,
                AiEditorTools.class,
                AiArticleActionTools.class
        );
        method.setAccessible(true);
        return (Object[]) method.invoke(service, prompt, navigationTools, editorTools, articleActionTools);
    }

    private ToolCallback[] invokeBuildToolCallbacks(AiPrompt prompt) throws Exception {
        Method method = AiModelServiceImpl.class.getDeclaredMethod("buildToolCallbacks", AiPrompt.class);
        method.setAccessible(true);
        return (ToolCallback[]) method.invoke(service, prompt);
    }
}
