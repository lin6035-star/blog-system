package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentRuntimeRouteRegistry;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.agent.GeneralAgentRuntime;
import com.hailin.blogsystem.ai.agent.LearningAgentRuntime;
import com.hailin.blogsystem.ai.planner.AgentPlannerSupport;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.service.AiIntentClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent / QA 路由边界基线测试（观测待办 7 · 设计稿第一步）。
 *
 * 目的：动任何代码之前先拿到「同一句话反复发送」的真实分布。
 * 按设计稿要求把「分类器原始输出」和「最终消费路径」分开记录——
 * 否则分不清是分类器没收敛，还是 Planner 把稳定输入转成了别的路径。
 *
 * 本类不改生产代码、不对分布做断言——产出是控制台里的一张分布表。
 * 跑法：./mvnw test -Dtest=AgentRouteBaselineTests -DrunRealLlm=true
 * 次数可用 -DbaselineRuns=N 调整（默认 10）。
 */
@Tag("integration")
@SpringBootTest
@EnabledIfSystemProperty(named = "runRealLlm", matches = "true")
class AgentRouteBaselineTests {

    /**
     * 边界样本（文章详情页）——全部来自观测记录里「疑为同句」的对照：
     *
     * 待办 7（三次记录实为三个长短不同的句子）：
     *   sample#1 = 观测记录表里写的句子
     *   sample#2 = 设计稿 §一 的简写
     *   sample#3 = 数据库里那次真走 Agent 的完整原句（run 2098431487377485826）
     *
     * 待办 2（另两处「同类不一致」）：
     *   sample#4 vs #5 = 第一轮样本 4（某节）vs 样本 5（整篇）
     *   sample#6 vs #7 = 第一轮样本 2（再看看/缓存击穿）vs 样本 6（再检查/分布式锁）
     */
    private static final List<String> MESSAGES = List.of(
            "对比一下缓存击穿和缓存穿透这两节的写法",
            "对比这两节",
            "对比一下缓存击穿和缓存穿透这两节的写法，说说哪节写得更好，再结合我的写作偏好给建议",
            "这篇文章的缓存击穿那一节写得怎么样",
            "整篇文章写得怎么样",
            "先看整体结构，再看看缓存击穿那一节",
            "先看整体结构，再检查分布式锁那一节",
            // 泛化验证（2026-09-12）：以下均为 prompt 中未出现过的新句子——
            // 验证「多子目标 / 多角度 → Agent」是规则泛化，不是单句特判
            "帮我从结构和语言两个角度分析这篇文章",      // 多角度 → 期望 Agent
            "先总结一下文章主题，再挑一段讲讲怎么改进",    // 多子目标 → 期望 Agent
            "先看看开头，再看结尾",                     // 多步骤 → 期望 Agent
            "这篇文章的开头写得怎么样"                  // 单目标评价 → 期望 QA（不得误升级）
    );

    /** 固定环境记录（设计稿 §五 第 1 条）：模型配置变了，基线就不能跨版本比较 */
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String model;

    @Autowired
    private AiIntentClassifier aiIntentClassifier;

    /** mock 掉真 ES vectorStore（只测路由，不依赖本地 ES 可用性） */
    @MockBean
    private VectorStore vectorStore;

    /** Planner 离线直调：与 AgentPlannerSupportTests 同构（mock 掉查库与锚） */
    private AgentPlannerSupport planner;

    @BeforeEach
    void setUp() {
        BlogAiProperties properties = new BlogAiProperties();
        AiWorkflowRunMapper workflowRunMapper = mock(AiWorkflowRunMapper.class);
        when(workflowRunMapper.selectList(any())).thenReturn(List.of());
        ArticleSessionAnchorService anchorService = mock(ArticleSessionAnchorService.class);
        AgentRuntimeRouteRegistry routeRegistry = new AgentRuntimeRouteRegistry(
                mock(LearningAgentRuntime.class),
                mock(ArticleAgentRuntime.class),
                mock(GeneralAgentRuntime.class)
        );
        planner = new AgentPlannerSupport(properties, workflowRunMapper, new ObjectMapper(),
                routeRegistry, anchorService);
    }

    @Test
    void baselineArticleCompareRouting() {
        int runs = Integer.getInteger("baselineRuns", 10);
        // -DbaselineOnly=4,7 → 只跑指定样本（深挖个别句子时省调用次数）
        String only = System.getProperty("baselineOnly", "");

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId("12");

        AiSessions session = new AiSessions();
        session.setId(10L);

        System.out.println("[BASELINE] model=" + model + " pageType=article-detail runs=" + runs);

        int totalSuccess = 0;
        for (int s = 0; s < MESSAGES.size(); s++) {
            String message = MESSAGES.get(s);
            String tag = "sample#" + (s + 1);
            if (!only.isBlank() && !List.of(only.split(",")).contains(String.valueOf(s + 1))) {
                continue;
            }

            Map<String, Integer> rawIntentDist = new LinkedHashMap<>();
            Map<String, Integer> rawActionDist = new LinkedHashMap<>();
            Map<String, Integer> finalPathDist = new LinkedHashMap<>();
            int successCount = 0;

            for (int i = 1; i <= runs; i++) {
                try {
                    // 在线：分类器原始输出（这条链路上唯一的不确定来源）
                    // userId 传 null：基线对照实验不注入学习计划列表——注入会引入库数据依赖，
                    // 且会让「分类器原始输出」这个被观测对象多一个变量
                    AiIntent intent = aiIntentClassifier.classify(message, pageContext, null, null);
                    // 离线：把同一份分类结果喂 Planner，拿最终消费路径
                    AgentDecision decision = planner.decide(message, intent, pageContext, 1L, session);

                    String rawIntent = String.valueOf(intent.getIntent());
                    String rawAction = String.valueOf(intent.getSuggestedAction());
                    String finalPath = decision.getAction() + "/" + decision.getRetrievalMode();

                    rawIntentDist.merge(rawIntent, 1, Integer::sum);
                    rawActionDist.merge(rawAction, 1, Integer::sum);
                    finalPathDist.merge(finalPath, 1, Integer::sum);
                    successCount++;

                    System.out.println("[BASELINE] " + tag + " run=" + i
                            + " | raw: intent=" + rawIntent
                            + " action=" + rawAction
                            + " workflow=" + intent.getSuggestedWorkflowType()
                            + " risk=" + intent.getRisk()
                            + " conf=" + intent.getConfidence()
                            + " reason=" + intent.getReason()
                            + " | final: " + finalPath
                            + " ruleHits=" + decision.getRuleHits());
                } catch (Exception e) {
                    System.out.println("[BASELINE] " + tag + " run=" + i + " ERROR: " + e.getMessage());
                }
            }

            totalSuccess += successCount;
            System.out.println("[BASELINE] " + tag + " rawIntent(" + successCount + "/" + runs + ")=" + rawIntentDist);
            System.out.println("[BASELINE] " + tag + " rawAction=" + rawActionDist);
            System.out.println("[BASELINE] " + tag + " finalPath=" + finalPathDist);
        }

        // 唯一的守卫：全失败说明环境有问题，不当成"分布稳定"
        assertThat(totalSuccess)
                .as("基线采集至少要有一次成功，否则看不出分布")
                .isGreaterThan(0);
    }
}
