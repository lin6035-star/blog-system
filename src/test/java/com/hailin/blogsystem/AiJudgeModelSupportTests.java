package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判断链 options 配置。
 *
 * <p>锁两条契约：
 * <ol>
 *   <li><b>temperature 恒为 0</b>——判断型任务要的是确定性。不设它会走服务端默认（1.0），
 *       实测后果：40 个并发发同一句话只有 15 个判对；评测集 37 例 8 败。</li>
 *   <li><b>配了 judge-model 才覆盖 model</b>，没配则不动 model，其他选项一律不动。</li>
 * </ol>
 *
 * <p>⚠️ 契约在 2026-09-19 变过一次：原先 {@code applyTo} 只做「换模型」，
 * 不配 judge-model 时<b>完全不动 builder</b>（因此 temperature 随服务端默认）——
 * 这条「零行为变化」当初是当作优点写进测试的，事后看正是它的代价。
 * 现在它升级为「判断链参数配置」：**temperature 与是否配模型无关，永远压到 0**。
 */
class AiJudgeModelSupportTests {

    @Test
    void blankConfigLeavesModelUntouchedButForcesZeroTemperature() {
        OpenAiChatOptions options = new AiJudgeModelSupport("")
                .applyTo(OpenAiChatOptions.builder().temperature(0.2))
                .build();

        assertThat(options.getModel()).isNull();
        assertThat(options.getTemperature())
                .as("判断链必须确定性：调用方就算自己设了 0.2，也要被压成 0")
                .isEqualTo(0.0);
    }

    @Test
    void nullConfigTreatedAsBlank() {
        OpenAiChatOptions options = new AiJudgeModelSupport(null)
                .applyTo(OpenAiChatOptions.builder())
                .build();

        assertThat(options.getModel()).isNull();
        assertThat(options.getTemperature()).isEqualTo(0.0);
    }

    @Test
    void configuredModelOverridesWhileKeepingOtherOptions() {
        AiJudgeModelSupport support = new AiJudgeModelSupport("qwen3.6-plus-2026-04-02");
        assertThat(support.isConfigured()).isTrue();

        OpenAiChatOptions options = support
                .applyTo(OpenAiChatOptions.builder().maxTokens(500))
                .build();

        assertThat(options.getModel()).isEqualTo("qwen3.6-plus-2026-04-02");
        assertThat(options.getMaxTokens())
                .as("只覆盖 model 与 temperature，其他既有设置不能丢")
                .isEqualTo(500);
        assertThat(options.getTemperature()).isEqualTo(0.0);
    }

    @Test
    void surroundingWhitespaceTolerated() {
        OpenAiChatOptions options = new AiJudgeModelSupport("  qwen-flash  ")
                .applyTo(OpenAiChatOptions.builder())
                .build();

        assertThat(options.getModel()).isEqualTo("qwen-flash");
    }
}
