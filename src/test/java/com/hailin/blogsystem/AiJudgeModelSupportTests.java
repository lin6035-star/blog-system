package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判断链模型配置（2026-09-13）：换模型导致分类判分界线漂移的实测见 AiJudgeModelSupport 类注释。
 * 这里锁两条：**没配时零行为变化**（默认路径必须和以前完全一样）、配了才覆盖 model。
 */
class AiJudgeModelSupportTests {

    @Test
    void blankConfigLeavesBuilderUntouched() {
        OpenAiChatOptions options = new AiJudgeModelSupport("")
                .applyTo(OpenAiChatOptions.builder().temperature(0.2))
                .build();

        assertThat(options.getModel()).isNull();
        assertThat(options.getTemperature()).isEqualTo(0.2);
    }

    @Test
    void nullConfigTreatedAsBlank() {
        OpenAiChatOptions options = new AiJudgeModelSupport(null)
                .applyTo(OpenAiChatOptions.builder().temperature(0.2))
                .build();

        assertThat(options.getModel()).isNull();
    }

    @Test
    void configuredModelOverridesWhileKeepingOtherOptions() {
        AiJudgeModelSupport support = new AiJudgeModelSupport("qwen3.6-plus-2026-04-02");
        assertThat(support.isConfigured()).isTrue();

        OpenAiChatOptions options = support.applyTo(OpenAiChatOptions.builder().temperature(0.2)).build();

        assertThat(options.getModel()).isEqualTo("qwen3.6-plus-2026-04-02");
        //只覆盖 model，温度等既有设置不能丢
        assertThat(options.getTemperature()).isEqualTo(0.2);
    }

    @Test
    void surroundingWhitespaceTolerated() {
        OpenAiChatOptions options = new AiJudgeModelSupport("  qwen-flash  ")
                .applyTo(OpenAiChatOptions.builder())
                .build();

        assertThat(options.getModel()).isEqualTo("qwen-flash");
    }
}
