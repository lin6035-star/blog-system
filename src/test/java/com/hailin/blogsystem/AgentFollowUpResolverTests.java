package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentFollowUpResolver;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.service.LearningPlansService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 追问续答（2026-09-14）：上一轮 Agent 以 ASK_USER 收尾时，用户的那句"回答"
 * 应该拿去**定位计划、沿用原诉求继续**，而不是被无状态的分类器当成新需求
 * （实测：「Java后端学习路线啊，不是Agent」被判成 LEARNING_PLAN——直接起了新建计划工作流）。
 */
class AgentFollowUpResolverTests {

    private AiAgentRunMapper runMapper;
    private LearningPlansService learningPlansService;
    private AgentFollowUpResolver resolver;

    @BeforeEach
    void setUp() {
        runMapper = mock(AiAgentRunMapper.class);
        learningPlansService = mock(LearningPlansService.class);
        resolver = new AgentFollowUpResolver(runMapper, learningPlansService);
    }

    @Test
    void resolvesWhenLastRunWaitsForUserAndPlanUniquelyMatched() {
        // 落库的 goal 是 effectiveGoal 的结果：原诉求 + 上一轮注入的定位线索
        when(runMapper.selectOne(any())).thenReturn(run("WAITING_USER",
                "帮我分析我的java学习计划\n【会话最近讨论的学习计划】无\n（以上只是定位线索：…）"));
        when(learningPlansService.matchPlansByMessage(100L, "Java后端学习路线啊，不是Agent"))
                .thenReturn(List.of(plan(9L, "Java后端学习路线规划")));

        AgentFollowUpResolver.Resolution r =
                resolver.resolve(200L, 100L, "Java后端学习路线啊，不是Agent");

        assertThat(r).isNotNull();
        assertThat(r.intent().getIntent()).isEqualTo("LEARNING_AGENT");
        assertThat(r.intent().getLearningPlanId()).isEqualTo("9");
        // 必须带上原诉求——本句回答单拎出来没有"要分析"这个诉求，Agent 看不到就会跑偏
        assertThat(r.message())
                .contains("帮我分析我的java学习计划")
                .contains("Java后端学习路线啊，不是Agent")
                // 上一轮的注入段**不能**原样搬过来：冗余，且当轮 effectiveGoal 会按当前锚重新注入
                .doesNotContain("【会话最近讨论的学习计划】")
                .doesNotContain("以上只是定位线索");
    }

    @Test
    void resolvesWithRawGoalWhenNoInjectionWasStored() {
        // 老数据 / 没注入过：原样沿用，不误伤
        when(runMapper.selectOne(any())).thenReturn(run("WAITING_USER", "帮我分析我的java学习计划"));
        when(learningPlansService.matchPlansByMessage(100L, "Java后端学习路线规划"))
                .thenReturn(List.of(plan(9L, "Java后端学习路线规划")));

        AgentFollowUpResolver.Resolution r = resolver.resolve(200L, 100L, "Java后端学习路线规划");

        assertThat(r).isNotNull();
        assertThat(r.message()).startsWith("帮我分析我的java学习计划");
    }

    @Test
    void returnsNullWhenLastRunIsNotWaitingForUser() {
        // 上一轮正常收尾（没在等回答）→ 这句就是新需求，照常走分类器
        when(runMapper.selectOne(any())).thenReturn(run("COMPLETED", "帮我分析我的java学习计划"));

        assertThat(resolver.resolve(200L, 100L, "Java后端学习路线啊")).isNull();
    }

    @Test
    void returnsNullWhenStillAmbiguous() {
        // 仍多命中 → 不猜，交给正常流程（分类器可能再追问一次）
        when(runMapper.selectOne(any())).thenReturn(run("WAITING_USER", "分析我的计划"));
        when(learningPlansService.matchPlansByMessage(anyLong(), any()))
                .thenReturn(List.of(plan(9L, "A计划"), plan(10L, "B计划")));

        assertThat(resolver.resolve(200L, 100L, "我的计划")).isNull();
    }

    @Test
    void returnsNullWhenNothingMatched() {
        // 用户换话题了 → 匹配不上 → 行为与改动前完全一致
        when(runMapper.selectOne(any())).thenReturn(run("WAITING_USER", "分析我的计划"));
        when(learningPlansService.matchPlansByMessage(anyLong(), any())).thenReturn(List.of());

        assertThat(resolver.resolve(200L, 100L, "今天天气不错")).isNull();
    }

    @Test
    void returnsNullWhenNoRunInSession() {
        when(runMapper.selectOne(any())).thenReturn(null);

        assertThat(resolver.resolve(200L, 100L, "随便说点")).isNull();
    }

    @Test
    void returnsNullOnBlankMessage() {
        assertThat(resolver.resolve(200L, 100L, "  ")).isNull();
    }

    private AiAgentRun run(String status, String goal) {
        AiAgentRun r = new AiAgentRun();
        r.setStatus(status);
        r.setGoal(goal);
        return r;
    }

    private LearningPlans plan(Long id, String title) {
        LearningPlans p = new LearningPlans();
        p.setId(id);
        p.setTitle(title);
        return p;
    }
}
