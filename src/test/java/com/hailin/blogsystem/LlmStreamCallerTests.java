package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.ai.workflow.LlmStreamCaller;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmStreamCallerTests {

    private Scheduler callbackScheduler;

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (callbackScheduler != null) {
            callbackScheduler.dispose();
        }
    }

    @Test
    void propagatesTraceIdIntoReactorResponseCallback() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(request);
        when(request.options(any(OpenAiChatOptions.class))).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.stream()).thenReturn(stream);

        callbackScheduler = Schedulers.newSingle("llm-callback-test");
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok")))
        );
        when(stream.chatResponse()).thenReturn(
                Flux.just(response).publishOn(callbackScheduler)
        );

        AtomicReference<String> callbackTraceId = new AtomicReference<>();
        MDC.put("traceId", "trace-llm");

        LlmStreamCaller caller = new LlmStreamCaller(builder);
        LlmStreamCaller.LlmStreamResult result = caller.call(
                "测试失败：",
                AiWorkflowStep.GENERATE_OUTLINE,
                "outline",
                new AiWorkflowStepEmitter() {
                    @Override
                    public void emit(String step, String status, String message) {
                    }

                    @Override
                    public void emitContent(String step, String field, String delta) {
                        callbackTraceId.set(MDC.get("traceId"));
                    }
                },
                "system",
                "user",
                100
        );

        assertThat(result.content()).isEqualTo("ok");
        assertThat(callbackTraceId).hasValue("trace-llm");
    }

    //JSON Mode：纯 JSON 出口必须把 response_format=json_object 传到 options
    //（实测背景：qwen3.6-plus 在长 prompt + 长 JSON 场景下约 57% 漏掉收尾括号，见 agent-json-mode-fix-design.md）
    @Test
    void jsonModePassesJsonObjectResponseFormat() {
        ChatClient.ChatClientRequestSpec request = mockStreamingClient("{}");

        LlmStreamCaller caller = new LlmStreamCaller(mockBuilderFor(request));
        caller.call(
                "测试失败：",
                AiWorkflowStep.GENERATE_PLAN,
                "plan",
                AiWorkflowStepEmitter.noop(),
                "system",
                "user",
                null,
                true
        );

        ArgumentCaptor<OpenAiChatOptions> captor = ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(request).options(captor.capture());
        assertThat(captor.getValue().getResponseFormat()).isNotNull();
        assertThat(captor.getValue().getResponseFormat().getType())
                .isEqualTo(ResponseFormat.Type.JSON_OBJECT);
    }

    //反向：默认调用（Markdown 输出的步骤）不能带 response_format，否则正文会坏
    @Test
    void defaultCallKeepsResponseFormatUnset() {
        ChatClient.ChatClientRequestSpec request = mockStreamingClient("## 标题");

        LlmStreamCaller caller = new LlmStreamCaller(mockBuilderFor(request));
        caller.call(
                "测试失败：",
                AiWorkflowStep.GENERATE_OUTLINE,
                "outline",
                AiWorkflowStepEmitter.noop(),
                "system",
                "user",
                null
        );

        ArgumentCaptor<OpenAiChatOptions> captor = ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(request).options(captor.capture());
        assertThat(captor.getValue().getResponseFormat()).isNull();
    }

    private ChatClient.Builder mockBuilderFor(ChatClient.ChatClientRequestSpec request) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(request);
        return builder;
    }

    private ChatClient.ChatClientRequestSpec mockStreamingClient(String content) {
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(request.options(any(OpenAiChatOptions.class))).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        when(stream.chatResponse()).thenReturn(Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content)))
        )));
        return request;
    }
}
