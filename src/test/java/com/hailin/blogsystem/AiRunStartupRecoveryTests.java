package com.hailin.blogsystem;

import com.hailin.blogsystem.component.AiRunStartupRecoveryRunner;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动恢复：把遗留的 {@code RUNNING} 标成中断，**但不能碰 {@code WAITING_*}**。
 *
 * <p>连真 DB：本次恢复就是两条 UPDATE 的<b>匹配条件</b>，条件写错（比如写成「非终态」）
 * 用 mock 完全测不出来——而那正是这个功能最容易犯的错。
 *
 * <p>用例对应设计稿 §5.3 的排除清单：
 * <ul>
 *   <li>{@code ai_workflow_runs}：6 个 {@code WAITING_*_CONFIRM} + {@code PAUSED} 不能动</li>
 *   <li>{@code ai_agent_runs}：{@code WAITING_USER} / {@code WAITING_WORKFLOW_CONFIRM} /
 *       {@code WAITING_WRITE_CONFIRM} 不能动</li>
 * </ul>
 */
@SpringBootTest
class AiRunStartupRecoveryTests {

    @Autowired
    private AiRunStartupRecoveryRunner runner;

    @Autowired
    private AiWorkflowRunMapper aiWorkflowRunMapper;

    @Autowired
    private AiAgentRunMapper aiAgentRunMapper;

    private final List<Long> insertedWorkflowIds = new ArrayList<>();
    private final List<Long> insertedAgentRunIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        insertedWorkflowIds.forEach(aiWorkflowRunMapper::deleteById);
        insertedAgentRunIds.forEach(aiAgentRunMapper::deleteById);
        insertedWorkflowIds.clear();
        insertedAgentRunIds.clear();
    }

    @Test
    void marksRunningRunsAsFailedOnBothTables() {
        Long workflowId = insertWorkflowRun(AiWorkflowStatus.RUNNING.name());
        Long agentRunId = insertAgentRun(AiAgentRunStatus.RUNNING.name());

        runner.run(null);

        AiWorkflowRun workflow = aiWorkflowRunMapper.selectById(workflowId);
        assertThat(workflow.getStatus()).isEqualTo(AiWorkflowStatus.FAILED.name());
        assertThat(workflow.getErrorMessage()).isEqualTo(AiRunStartupRecoveryRunner.INTERRUPTED_MESSAGE);

        AiAgentRun agentRun = aiAgentRunMapper.selectById(agentRunId);
        assertThat(agentRun.getStatus()).isEqualTo(AiAgentRunStatus.FAILED.name());
        assertThat(agentRun.getErrorMessage()).isEqualTo(AiRunStartupRecoveryRunner.INTERRUPTED_MESSAGE);
    }

    /**
     * 本功能最关键的一条。
     *
     * <p>{@code WAITING_*} 与 {@code PAUSED} 都是**合法状态**——前者等用户点确认、
     * 后者等恢复，流程是 DB 驱动 + handler 按状态分发，**重启后照样能继续**。
     * 一旦把清理条件写成「非终态」，这些 run 会被全部杀掉，用户没提交的方案凭空消失。
     */
    @Test
    void doesNotTouchWaitingOrPausedWorkflowRuns() {
        List<String> untouched = List.of(
                AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name(),
                AiWorkflowStatus.WAITING_OUTLINE_CONFIRM.name(),
                AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name(),
                AiWorkflowStatus.WAITING_PLAN_CONFIRM.name(),
                AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM.name(),
                AiWorkflowStatus.WAITING_FILL_CONFIRM.name(),
                AiWorkflowStatus.PAUSED.name()
        );
        List<Long> ids = untouched.stream().map(this::insertWorkflowRun).toList();

        runner.run(null);

        for (int i = 0; i < untouched.size(); i++) {
            assertThat(aiWorkflowRunMapper.selectById(ids.get(i)).getStatus())
                    .as("状态 %s 不该被启动恢复改动", untouched.get(i))
                    .isEqualTo(untouched.get(i));
        }
    }

    /** Agent 侧的等确认态同理——它们也是「用户点一下就能继续」的合法状态。 */
    @Test
    void doesNotTouchWaitingAgentRuns() {
        List<String> untouched = List.of(
                AiAgentRunStatus.WAITING_USER.name(),
                AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM.name(),
                AiAgentRunStatus.WAITING_WRITE_CONFIRM.name()
        );
        List<Long> ids = untouched.stream().map(this::insertAgentRun).toList();

        runner.run(null);

        for (int i = 0; i < untouched.size(); i++) {
            assertThat(aiAgentRunMapper.selectById(ids.get(i)).getStatus())
                    .as("状态 %s 不该被启动恢复改动", untouched.get(i))
                    .isEqualTo(untouched.get(i));
        }
    }

    /** 终态本来就不该动（幂等：重复启动不会把 FAILED 又改一遍、覆盖掉真实失败原因）。 */
    @Test
    void leavesFinishedRunsUntouched() {
        Long failedId = insertWorkflowRun(AiWorkflowStatus.FAILED.name());
        AiWorkflowRun withRealError = new AiWorkflowRun();
        withRealError.setId(failedId);
        withRealError.setErrorMessage("原本的失败原因");
        aiWorkflowRunMapper.updateById(withRealError);
        Long completedId = insertWorkflowRun(AiWorkflowStatus.COMPLETED.name());

        runner.run(null);

        assertThat(aiWorkflowRunMapper.selectById(failedId).getErrorMessage())
                .as("已失败的不该被覆盖成「应用重启」")
                .isEqualTo("原本的失败原因");
        assertThat(aiWorkflowRunMapper.selectById(completedId).getStatus())
                .isEqualTo(AiWorkflowStatus.COMPLETED.name());
    }

    // ---------- 造数据 ----------

    private Long insertWorkflowRun(String status) {
        AiWorkflowRun run = new AiWorkflowRun();
        run.setUserId(999_999L);
        run.setWorkflowType("CREATE_ARTICLE");
        run.setWorkflowVersion("1.0");
        run.setStatus(status);
        run.setCurrentStep("REQUIREMENT_ANALYZE");
        run.setContextJson("{}");   // NOT NULL
        run.setRetryCount(0);
        run.setVersion(0);
        run.setInputTokens(0);
        run.setOutputTokens(0);
        run.setTotalTokens(0);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        aiWorkflowRunMapper.insert(run);
        insertedWorkflowIds.add(run.getId());
        return run.getId();
    }

    private Long insertAgentRun(String status) {
        AiAgentRun run = new AiAgentRun();
        run.setUserId(999_999L);
        run.setSessionId(999_999L);
        run.setGoal("启动恢复测试用 run");
        run.setStatus(status);
        run.setCurrentStep(0);
        run.setMaxSteps(5);
        run.setUsedSteps(0);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        aiAgentRunMapper.insert(run);
        insertedAgentRunIds.add(run.getId());
        return run.getId();
    }
}
