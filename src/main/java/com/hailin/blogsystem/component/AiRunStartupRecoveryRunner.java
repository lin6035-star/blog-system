package com.hailin.blogsystem.component;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动恢复：把遗留的 {@code RUNNING} 长任务标记为中断。
 *
 * <p><b>为什么需要这一刀</b>：run 状态在 DB，而 AI 长任务准入的并发计数在内存。
 * 进程崩溃 / 重启后内存计数自然归零、DB 里的 {@code RUNNING} 却原样留着——
 * 两边语义直接对不上（DB 说 3 个在跑、准入说 0 个在跑），
 * 用户界面会一直显示「正在运行」，而且准入那句「系统知道现在有几个任务在跑」也就成了假的。
 *
 * <p><b>⚠️ 最大的坑：不能把「未完成」等同于 {@code RUNNING}</b>。
 * 清理条件必须是 {@code status = 'RUNNING'}，<b>绝不能</b>写成 {@code status NOT IN (终态)}——
 * {@code WAITING_*_CONFIRM} 是「等用户点确认」的<b>合法状态</b>：流程是 DB 驱动 + handler
 * 按状态分发，重启后用户回来点「确认」照样能继续推进。按「非终态」写会
 * <b>误杀所有等确认的 Workflow</b>，用户没提交的方案凭空消失。
 * {@code ai_agent_runs} 的 {@code WAITING_USER} / {@code WAITING_WORKFLOW_CONFIRM} /
 * {@code WAITING_WRITE_CONFIRM} 同理。
 *
 * <p><b>恢复到 {@code FAILED} 而不是 {@code CANCELLED}</b>：后者是「用户主动取消」的语义，
 * 进程崩溃不是用户的选择，混用会让取消率这类统计失真。
 *
 * <p><b>为什么不做实例判据</b>：单实例部署下，「启动时把所有 {@code RUNNING} 标中断」
 * <b>就是精确解</b>，不是简化版——本实例刚启动，不可能有自己创建的 {@code RUNNING}，
 * 全量标记不会误伤任何东西。加 {@code instanceId} 需要 run 表加列 + 启动写标识 +
 * 只清理非本实例的，单实例下这些代码全部空转、正确性增量是零
 * （同类教训见 Redis 五刀第 2 刀：定时任务互斥被推翻，为不存在的问题写代码）。
 * <b>触发条件</b>：多实例部署 / 滚动发布时判据才变成真问题，那时再加。
 *
 * <p><b>不删记录</b>：只改状态，run / step / trace 全部保留供排查。
 *
 * <p><b>session 锚点不用专门清</b>：{@code WorkflowRunManager.checkActiveWorkflowConflict}
 * 早已带脏绑定自愈——run 不存在或已终态时会自动清掉 {@code activeWorkflowRunId} 并放行。
 * 只要这里把 run 标成终态，锚点自己就会走上那条路。
 *
 * <p>失败不阻断应用启动（与 {@code SeckillPreheatRunner} 同风格）：恢复失败只是界面难看，
 * 不该让整个博客起不来；下次重启会再试。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AiRunStartupRecoveryRunner implements ApplicationRunner {

    /** 中断原因。与「用户主动取消」用不同文案，排查和统计时才分得开。 */
    public static final String INTERRUPTED_MESSAGE = "应用重启导致任务中断，请重新发起";

    private final AiWorkflowRunMapper aiWorkflowRunMapper;
    private final AiAgentRunMapper aiAgentRunMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int workflows = markWorkflowRunsInterrupted();
            int agentRuns = markAgentRunsInterrupted();
            if (workflows + agentRuns > 0) {
                log.warn("[AI-RECOVERY] 启动恢复：遗留 RUNNING 已标记为中断 workflowRuns={} agentRuns={}",
                        workflows, agentRuns);
            }
        } catch (RuntimeException e) {
            log.error("[AI-RECOVERY] 启动恢复失败。DB 里会继续留着 RUNNING 记录"
                    + "（界面显示「一直在运行」），下次重启会再试一次", e);
        }
    }

    // 两张表都要处理：漏一张，那张表的 run 就会永远停在 RUNNING。

    private int markWorkflowRunsInterrupted() {
        return aiWorkflowRunMapper.update(null,
                new LambdaUpdateWrapper<AiWorkflowRun>()
                        .eq(AiWorkflowRun::getStatus, AiWorkflowStatus.RUNNING.name())
                        .set(AiWorkflowRun::getStatus, AiWorkflowStatus.FAILED.name())
                        .set(AiWorkflowRun::getErrorMessage, INTERRUPTED_MESSAGE)
                        .set(AiWorkflowRun::getUpdatedAt, LocalDateTime.now()));
    }

    private int markAgentRunsInterrupted() {
        return aiAgentRunMapper.update(null,
                new LambdaUpdateWrapper<AiAgentRun>()
                        .eq(AiAgentRun::getStatus, AiAgentRunStatus.RUNNING.name())
                        .set(AiAgentRun::getStatus, AiAgentRunStatus.FAILED.name())
                        .set(AiAgentRun::getErrorMessage, INTERRUPTED_MESSAGE)
                        .set(AiAgentRun::getUpdatedAt, LocalDateTime.now()));
    }
}
