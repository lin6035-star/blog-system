package com.hailin.blogsystem.ai.workflow;

@FunctionalInterface
public interface AiWorkflowStepEmitter {

    void emit(String step,String status,String message);

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
