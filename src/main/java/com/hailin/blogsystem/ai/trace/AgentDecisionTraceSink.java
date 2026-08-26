package com.hailin.blogsystem.ai.trace;

/**
 * Agent 决策追踪落点抽象。
 * V1 日志实现；以后接数据库 / OpenTelemetry 只替换实现，调用方不变。
 */
public interface AgentDecisionTraceSink {

    void record(AgentDecisionTrace trace);
}
