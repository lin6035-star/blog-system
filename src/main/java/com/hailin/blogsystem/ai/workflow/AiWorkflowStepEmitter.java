package com.hailin.blogsystem.ai.workflow;

@FunctionalInterface
public interface AiWorkflowStepEmitter {

    void emit(String step,String status,String message);

    /**
     * 带结构化元数据的步骤事件：耗时 + token。
     *
     * 默认降级为不带元数据的旧签名——不关心这些信息的实现（noop、测试桩）无需改动。
     *
     * 为什么需要它：前端实时步骤日志原先只有 `message`，于是把「步骤完成，耗时 61515ms」
     * 这串文本塞进 inputSummary，耗时栏显示 `—`、token 行根本不渲染，要等 SSE STOP
     * 拼上数据库日志才正常。把耗时与用量作为**结构化字段**发出去，实时即可正确展示。
     *
     * @param durationMs   本步耗时；null = 不适用（如 RUNNING 事件）
     * @param inputTokens  本步 LLM 输入 token；null = 不适用
     * @param outputTokens 本步 LLM 输出 token；null = 不适用
     */
    default void emit(String step, String status, String message,
                      Long durationMs, Integer inputTokens, Integer outputTokens) {
        emit(step, status, message);
    }

    /**
     * Workflow run 入库后回填真实 ID，供 SSE 事件关联前端卡片。
     * 非 SSE 调用方不需要实现。
     */
    default void bindWorkflowRunId(Long workflowRunId) {
    }

    default void emitContent(String step, String field, String delta) {
    }

    static AiWorkflowStepEmitter noop(){
        return (step,status,message) -> {

        };
    }
}
