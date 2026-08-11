package com.hailin.blogsystem.ai.workflow;

@FunctionalInterface
public interface AiWorkflowStepEmitter {

    void emit(String step,String status,String message);

    default void emitContent(String step, String field, String delta) {
    }

    static AiWorkflowStepEmitter noop(){
        return (step,status,message) -> {

        };
    }
}
