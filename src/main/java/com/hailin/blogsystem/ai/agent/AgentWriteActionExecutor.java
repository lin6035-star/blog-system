package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.AiAgentRun;

import java.util.Map;

/**
 * Agent 写动作执行器（V3.6，写动作分发注册表）。
 *
 * 壳（AgentWriteActionService）保留公共确认骨架（锁 / 状态 CAS / 归属 / 留痕），
 * 域执行器各自内聚「校验 → 定位 → 快照 → 域 service → 载荷组装」——
 * 新写动作 = 域执行器加 supports/分支 + 域 service 调用，不再串改公共类。
 *
 * 执行器不依赖壳（防循环依赖）；域执行异常照抛（BusinessException 等），
 * 由壳 @Transactional 整体回滚（run 状态回 WAITING_WRITE_CONFIRM 可重试）。
 */
public interface AgentWriteActionExecutor {

    /** 注册判定：本执行器是否处理该写动作类型（Type_* 常量见 AgentWriteProposal） */
    boolean supports(String actionType);

    /** 域执行：返回成功文案 + 留痕载荷（域字段 + before/after；actionType/executedAt 由壳统一补） */
    WriteActionResult execute(AiAgentRun run, AgentWriteProposal proposal, Long userId);

    /** 执行结果：message 直接作为 confirm 成功返回文案；auditPayload 写入 context_json.writeActionResult */
    record WriteActionResult(String message, Map<String, Object> auditPayload) {
    }
}
