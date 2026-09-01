package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.ai.agent.AgentRunInspectionService;
import com.hailin.blogsystem.ai.agent.AgentRunSuggestionService;
import com.hailin.blogsystem.ai.agent.AgentRunSuggestionView;
import com.hailin.blogsystem.ai.agent.AgentWriteActionService;
import com.hailin.blogsystem.ai.agent.AgentWriteActionView;
import com.hailin.blogsystem.entity.vo.AgentRunDetailVO;
import com.hailin.blogsystem.entity.vo.AgentRunSummaryVO;
import com.hailin.blogsystem.entity.vo.AgentStepVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.utils.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent Run 端点（V2.1 + V2.2）。
 *
 * - V2.1：workflow-suggestion 建议确认 / 取消 / 快照查询（确认后才复用现有 Workflow Runtime）
 * - V2.2：inspection 只读查询（run 详情 / steps / 会话历史列表），只查当前用户自己的 run
 * 限流策略：与 Workflow 确认类操作一致（用户确认动作次数天然受限，不限流）。
 */
@RestController
@RequestMapping("/api/ai/agent-runs")
public class AiAgentRunController {

    private final AgentRunSuggestionService agentRunSuggestionService;
    private final AgentRunInspectionService agentRunInspectionService;
    private final AgentWriteActionService agentWriteActionService;

    public AiAgentRunController(
            AgentRunSuggestionService agentRunSuggestionService,
            AgentRunInspectionService agentRunInspectionService,
            AgentWriteActionService agentWriteActionService
    ) {
        this.agentRunSuggestionService = agentRunSuggestionService;
        this.agentRunInspectionService = agentRunInspectionService;
        this.agentWriteActionService = agentWriteActionService;
    }

    /**
     * 确认 Agent 的 Workflow 建议：启动对应学习类 Workflow 并返回 Workflow 快照。
     * Idempotency-Key 可选（防双击重复创建）。
     */
    @PostMapping("/{id}/workflow-suggestion/confirm")
    public Result<AiWorkflowRunVO> confirm(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return Result.success(agentRunSuggestionService.confirm(id, idempotencyKey));
    }

    /**
     * 取消 Agent 的 Workflow 建议：不启动任何 Workflow。
     */
    @PostMapping("/{id}/workflow-suggestion/cancel")
    public Result<String> cancel(@PathVariable Long id) {
        return Result.success(agentRunSuggestionService.cancel(id));
    }

    /**
     * 查询建议快照（历史消息恢复建议卡）：仅 WAITING_WORKFLOW_CONFIRM 时返回 suggestion。
     */
    @GetMapping("/{id}/workflow-suggestion")
    public Result<AgentRunSuggestionView> suggestion(@PathVariable Long id) {
        return Result.success(agentRunSuggestionService.getSuggestion(id));
    }

    /**
     * V2.4：确认写动作提案（标题匹配 → 快照 → 执行，只读裁判 + 用户确认后才写）。
     */
    @PostMapping("/{id}/write-action/confirm")
    public Result<String> confirmWrite(@PathVariable Long id) {
        return Result.success(agentWriteActionService.confirmWrite(id));
    }

    /**
     * V2.4：取消写动作提案（不执行任何修改）。
     */
    @PostMapping("/{id}/write-action/cancel")
    public Result<String> cancelWrite(@PathVariable Long id) {
        return Result.success(agentWriteActionService.cancelWrite(id));
    }

    /**
     * V2.4：写动作提案快照（历史消息恢复写动作卡）。
     */
    @GetMapping("/{id}/write-action")
    public Result<AgentWriteActionView> writeAction(@PathVariable Long id) {
        return Result.success(agentWriteActionService.getWriteAction(id));
    }

    /**
     * V2.2：单 run 详情（安全摘要）。
     */
    @GetMapping("/{id}")
    public Result<AgentRunDetailVO> detail(@PathVariable Long id) {
        return Result.success(agentRunInspectionService.getDetail(id));
    }

    /**
     * V2.2：run 步骤列表（按 stepNo 升序，observation 摘要）。
     */
    @GetMapping("/{id}/steps")
    public Result<List<AgentStepVO>> steps(@PathVariable Long id) {
        return Result.success(agentRunInspectionService.listSteps(id));
    }

    /**
     * V2.2：会话历史 run 列表（分页；sessionId 可选，缺省查当前用户全部）。
     * pageSize 为项目惯例（与 PageData 一致），size 为兼容别名。
     */
    @GetMapping
    public Result<PageVO<AgentRunSummaryVO>> list(
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false, defaultValue = "1") Long page,
            @RequestParam(required = false, defaultValue = "20") Long pageSize,
            @RequestParam(required = false) Long size
    ) {
        Long effectivePageSize = size != null ? size : pageSize;
        return Result.success(agentRunInspectionService.listRuns(sessionId, page, effectivePageSize));
    }
}
