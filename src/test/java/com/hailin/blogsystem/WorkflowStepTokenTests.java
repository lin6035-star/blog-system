package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.ai.workflow.WorkflowStepLogRecorder;
import com.hailin.blogsystem.ai.workflow.WorkflowStepRunner;
import com.hailin.blogsystem.ai.workflow.WorkflowTokenRecorder;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.Usage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * workflow 步骤级 token 统计（2026-09-17）。
 *
 * 前端 AiAssistant.vue 早就有 `token X / Y` 那行展示，但后端 buildBaseLog 把它硬编码成 0
 * ——那行永远不会显示。本测试锁住「真实用量能走到 step 日志」这条链路，
 * 以及最容易出错的一点：**每步必须取走缓冲，否则残留会算到下一步头上**。
 */
class WorkflowStepTokenTests {

    private static Usage mockUsage(int prompt, int completion, int total) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(prompt);
        when(usage.getCompletionTokens()).thenReturn(completion);
        when(usage.getTotalTokens()).thenReturn(total);
        return usage;
    }

    /** accumulate 收的是累加器（handler 里传的是 LlmStreamResult.usage()），不是原始 Usage。 */
    private static TokenUsageAccumulator acc(int prompt, int completion, int total) {
        TokenUsageAccumulator accumulator = new TokenUsageAccumulator();
        accumulator.add(mockUsage(prompt, completion, total));
        return accumulator;
    }

    private static AiWorkflowRun run() {
        AiWorkflowRun run = new AiWorkflowRun();
        run.setId(1L);
        return run;
    }

    // ==================== WorkflowTokenRecorder：run 级 + 步骤级缓冲 ====================

    @Test
    void accumulateBuffersCurrentStepUsage() {
        WorkflowTokenRecorder recorder = new WorkflowTokenRecorder();
        AiWorkflowRun run = run();

        recorder.accumulate(run, acc(100, 10, 110));

        assertThat(recorder.drainStepBuffer().getTotalTokens()).isEqualTo(110);
        assertThat(run.getTotalTokens()).isEqualTo(110);
    }

    @Test
    void drainClearsBufferSoNextStepIsNotPolluted() {
        WorkflowTokenRecorder recorder = new WorkflowTokenRecorder();
        AiWorkflowRun run = run();

        recorder.accumulate(run, acc(100, 10, 110));
        recorder.drainStepBuffer();                                  // 第 1 步取走

        recorder.accumulate(run, acc(200, 20, 220));
        assertThat(recorder.drainStepBuffer().getTotalTokens())
                .as("第 2 步只该有自己的用量——取走不清零的话，残留会算错账")
                .isEqualTo(220);

        // run 级是累计，不受步骤级缓冲影响
        assertThat(run.getTotalTokens()).isEqualTo(330);
    }

    @Test
    void emptyUsageDoesNotTouchEitherLevel() {
        WorkflowTokenRecorder recorder = new WorkflowTokenRecorder();
        AiWorkflowRun run = run();

        recorder.accumulate(run, null);
        recorder.accumulate(run, new TokenUsageAccumulator());

        assertThat(run.getTotalTokens()).isNull();
        assertThat(recorder.drainStepBuffer().getTotalTokens()).isZero();
    }

    // ==================== WorkflowStepRunner：把用量传给 step 日志 ====================

    @Test
    void stepRunnerPassesStepUsageToLog() {
        WorkflowStepLogRecorder logRecorder = mock(WorkflowStepLogRecorder.class);
        WorkflowTokenRecorder tokenRecorder = new WorkflowTokenRecorder();
        WorkflowStepRunner runner = new WorkflowStepRunner(logRecorder, tokenRecorder);
        AiWorkflowRun run = run();

        String result = runner.run(1L, AiWorkflowStep.GENERATE_DRAFT, "生成草稿中", () -> {
            tokenRecorder.accumulate(run, acc(300, 40, 340));
            return "草稿正文";
        }, AiWorkflowStepEmitter.noop());

        assertThat(result).isEqualTo("草稿正文");

        ArgumentCaptor<TokenUsageAccumulator> captor =
                ArgumentCaptor.forClass(TokenUsageAccumulator.class);
        verify(logRecorder).recordStep(any(), any(), anyString(), anyLong(), isNull(), captor.capture());
        assertThat(captor.getValue().getTotalTokens())
                .as("本步 LLM 用量要落到 step 日志——前端那行 token 展示靠它")
                .isEqualTo(340);
    }

    @Test
    void successEventCarriesDurationAndTokens() {
        // 前端实时步骤日志原先只有 message，耗时显示 `—`、token 行不渲染，要等 STOP 拼数据库日志。
        // 这条锁住「SUCCESS 事件本身带结构化元数据」
        WorkflowStepLogRecorder logRecorder = mock(WorkflowStepLogRecorder.class);
        WorkflowTokenRecorder tokenRecorder = new WorkflowTokenRecorder();
        WorkflowStepRunner runner = new WorkflowStepRunner(logRecorder, tokenRecorder);
        AiWorkflowRun run = run();

        List<String> emitted = new ArrayList<>();
        AiWorkflowStepEmitter capturing = new AiWorkflowStepEmitter() {
            @Override
            public void emit(String step, String status, String message) {
                emitted.add(status);
            }

            @Override
            public void emit(String step, String status, String message,
                             Long durationMs, Integer inputTokens, Integer outputTokens) {
                emitted.add(status + "|" + durationMs + "|" + inputTokens + "|" + outputTokens);
            }
        };

        runner.run(1L, AiWorkflowStep.GENERATE_DRAFT, "生成草稿中", () -> {
            tokenRecorder.accumulate(run, acc(300, 40, 340));
            return "草稿";
        }, capturing);

        assertThat(emitted).hasSize(2);
        assertThat(emitted.get(0))
                .as("RUNNING 阶段还没有耗时/用量，走不带元数据的旧签名")
                .isEqualTo("RUNNING");
        assertThat(emitted.get(1))
                .as("SUCCESS 事件要带结构化耗时与 token——前端实时展示靠它")
                .matches("SUCCESS\\|\\d+\\|300\\|40");
    }

    @Test
    void failedStepStillDrainsBufferedUsage() {
        // LLM 已经跑完（token 已消耗）才在后续处理里炸掉 → 失败路径也要把用量记上
        WorkflowStepLogRecorder logRecorder = mock(WorkflowStepLogRecorder.class);
        WorkflowTokenRecorder tokenRecorder = new WorkflowTokenRecorder();
        WorkflowStepRunner runner = new WorkflowStepRunner(logRecorder, tokenRecorder);
        AiWorkflowRun run = run();

        assertThatThrownBy(() -> runner.run(1L, AiWorkflowStep.GENERATE_DRAFT, "生成草稿中", () -> {
            tokenRecorder.accumulate(run, acc(300, 40, 340));
            throw new RuntimeException("LLM 之后炸了");
        }, AiWorkflowStepEmitter.noop())).isInstanceOf(RuntimeException.class);

        ArgumentCaptor<TokenUsageAccumulator> captor =
                ArgumentCaptor.forClass(TokenUsageAccumulator.class);
        verify(logRecorder).recordStep(any(), any(), anyString(), anyLong(),
                any(RuntimeException.class), captor.capture());
        assertThat(captor.getValue().getTotalTokens())
                .as("失败步也要记上已经花掉的 token")
                .isEqualTo(340);
    }

    @Test
    void stepWithoutLlmCallLogsZeroAndDoesNotBorrowPreviousStep() {
        // 纯 DB 步骤（无 LLM 调用）：记 0，且绝不能带上一步的残留
        WorkflowStepLogRecorder logRecorder = mock(WorkflowStepLogRecorder.class);
        WorkflowTokenRecorder tokenRecorder = new WorkflowTokenRecorder();
        WorkflowStepRunner runner = new WorkflowStepRunner(logRecorder, tokenRecorder);
        AiWorkflowRun run = run();

        runner.run(1L, AiWorkflowStep.GENERATE_DRAFT, "生成草稿中", () -> {
            tokenRecorder.accumulate(run, acc(300, 40, 340));
            return "草稿";
        }, AiWorkflowStepEmitter.noop());

        runner.run(1L, AiWorkflowStep.FILL_ARTICLE, "填充编辑器", () -> "ok", AiWorkflowStepEmitter.noop());

        ArgumentCaptor<TokenUsageAccumulator> captor =
                ArgumentCaptor.forClass(TokenUsageAccumulator.class);
        verify(logRecorder, times(2))
                .recordStep(any(), any(), anyString(), anyLong(), isNull(), captor.capture());
        assertThat(captor.getAllValues().get(0).getTotalTokens()).isEqualTo(340);
        assertThat(captor.getAllValues().get(1).getTotalTokens())
                .as("无 LLM 调用的步骤记 0——上一步的残留不能串过来")
                .isZero();
    }
}
