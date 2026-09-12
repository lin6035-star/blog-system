package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.ai.agent.AgentRunInspectionService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.vo.AgentRunDevDetailVO;
import com.hailin.blogsystem.entity.vo.AgentRunSummaryVO;
import com.hailin.blogsystem.entity.vo.AgentStepRawVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 开发者只读面板端点（V4 第一刀）。
 *
 * 与 {@code /api/ai/agent-runs} 的分工：
 * - 那条是**用户侧**通道，返回安全摘要（{@code AgentStepVO} 刻意不返回 inputJson）；
 * - 本条是**开发者**通道，额外给出 inputJson / outputJson / contextJson 原始列。
 *
 * 门禁：{@code blog.ai.inspection.enabled} + {@code allowed-user-ids} 白名单
 * （项目无角色体系，沿用 ArticleRagController 的既有范式）。
 * 数据范围仍限「当前用户自己的 run」——白名单只是额外的一道门，不放大可见范围。
 *
 * 边界：**只读**。不接管 Runtime、不改状态、不触发 retry。
 */
@RestController
@RequestMapping("/api/ai/dev/agent-runs")
@RequiredArgsConstructor
public class AgentRunDevController {

    private final AgentRunInspectionService agentRunInspectionService;
    private final BlogAiProperties blogAiProperties;

    /** 开发者通道：run 列表（自己的，分页）。 */
    @GetMapping
    public Result<PageVO<AgentRunSummaryVO>> list(
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false, defaultValue = "1") Long page,
            @RequestParam(required = false, defaultValue = "20") Long pageSize,
            @RequestParam(required = false) Long size
    ) {
        Result<PageVO<AgentRunSummaryVO>> deny = denyIfNoDevAccess();
        if (deny != null) {
            return deny;
        }
        Long effectivePageSize = size != null ? size : pageSize;
        return Result.success(agentRunInspectionService.listRuns(sessionId, page, effectivePageSize));
    }

    /** 开发者通道：run 详情（安全摘要 + 原始 contextJson）。 */
    @GetMapping("/{id}")
    public Result<AgentRunDevDetailVO> detail(@PathVariable Long id) {
        Result<AgentRunDevDetailVO> deny = denyIfNoDevAccess();
        if (deny != null) {
            return deny;
        }
        return Result.success(agentRunInspectionService.getDevDetail(id));
    }

    /** 开发者通道：完整 step 列表（含 inputJson / outputJson 原始列）。 */
    @GetMapping("/{id}/steps")
    public Result<List<AgentStepRawVO>> steps(@PathVariable Long id) {
        Result<List<AgentStepRawVO>> deny = denyIfNoDevAccess();
        if (deny != null) {
            return deny;
        }
        return Result.success(agentRunInspectionService.listRawSteps(id));
    }

    /** 返回 null 表示放行；否则返回可直接透出的 403。 */
    private <T> Result<T> denyIfNoDevAccess() {
        var inspection = blogAiProperties.getInspection();
        if (!inspection.isEnabled()) {
            return Result.error(403, "开发者面板已关闭");
        }
        Long currentUserId = UserContext.get();
        if (currentUserId == null || !inspection.getAllowedUserIds().contains(currentUserId)) {
            return Result.error(403, "无权访问");
        }
        return null;
    }
}
