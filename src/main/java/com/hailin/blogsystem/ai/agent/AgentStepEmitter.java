package com.hailin.blogsystem.ai.agent;

/**
 * Agent Loop 步骤事件推送（V2.3 / V3.10）。
 *
 * 与 Workflow 的 AiWorkflowStepEmitter 同思路：Agent 每步执行前后推事件，
 * 前端实时渲染"思考过程"面板（折叠/展开）。
 *
 * status 取值与 AgentStepStatus 一致：RUNNING / SUCCESS / FAILED。
 * thoughtSummary（V3.10，nullable）：该步思考摘要（清洗后），行文本优先于 message；
 * FAILED 事件不携带（传 null）——失败时展示失败文案，不让动机句覆盖失败原因。
 * 非 SSE 调用方（测试）用 noop()。
 */
@FunctionalInterface
public interface AgentStepEmitter {

    void emit(int stepNo, String actionType, String status, String message, String thoughtSummary);

    static AgentStepEmitter noop() {
        return (stepNo, actionType, status, message, thoughtSummary) -> {
        };
    }
}
