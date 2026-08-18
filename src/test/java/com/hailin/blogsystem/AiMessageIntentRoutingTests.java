package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.AiIntentClassifier;
import com.hailin.blogsystem.service.impl.AiMessageServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 学习规划入口路由规则测试（纯规则无 LLM，反射测私有方法）。
 * 背景 bug：入口兜底正则"含'学习规划'字样即进制定 Workflow"把查询句（"我有几个学习规划"）
 * 全部误伤成"制定新规划"CTA。修复后靠查询排除 + 主路由双保险防回归。
 */
@SpringBootTest
class AiMessageIntentRoutingTests {

    @Autowired
    private AiMessageServiceImpl aiMessageService;

    @MockBean
    private AiIntentClassifier aiIntentClassifier;

    private boolean looksLikeLearningPlanQueryRequest(String message) throws Exception {
        Method m = AiMessageServiceImpl.class.getDeclaredMethod("looksLikeLearningPlanQueryRequest", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(aiMessageService, message);
    }

    private boolean looksLikeLearningPlanRequest(String message) throws Exception {
        Method m = AiMessageServiceImpl.class.getDeclaredMethod("looksLikeLearningPlanRequest", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(aiMessageService, message);
    }

    private boolean shouldRoute(String intentType, String message) throws Exception {
        Method m = AiMessageServiceImpl.class.getDeclaredMethod("shouldRouteToLearningPlanWorkflow", AiIntent.class, String.class);
        m.setAccessible(true);
        AiIntent intent = new AiIntent();
        intent.setIntent(intentType);
        return (boolean) m.invoke(aiMessageService, intent, message);
    }

    private boolean shouldRouteProgress(String intentType, double confidence, String message) throws Exception {
        Method m = AiMessageServiceImpl.class.getDeclaredMethod("shouldRouteToLearningProgressWorkflow", AiIntent.class, String.class);
        m.setAccessible(true);
        AiIntent intent = new AiIntent();
        intent.setIntent(intentType);
        intent.setConfidence(confidence);
        return (boolean) m.invoke(aiMessageService, intent, message);
    }

    private boolean looksLikeLearningDifficultyRequest(String message) throws Exception {
        Method m = AiMessageServiceImpl.class.getDeclaredMethod("looksLikeLearningDifficultyRequest", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(aiMessageService, message);
    }

    private boolean isLearningAssistIntent(AiIntent intent) throws Exception {
        Method m = AiMessageServiceImpl.class.getDeclaredMethod("isLearningAssistIntent", AiIntent.class);
        m.setAccessible(true);
        return (boolean) m.invoke(aiMessageService, intent);
    }

    private boolean isLearningProgressIntent(AiIntent intent) throws Exception {
        Method m = AiMessageServiceImpl.class.getDeclaredMethod("isLearningProgressIntent", AiIntent.class);
        m.setAccessible(true);
        return (boolean) m.invoke(aiMessageService, intent);
    }

    private boolean isLearningPlanQueryIntent(AiIntent intent) throws Exception {
        Method m = AiMessageServiceImpl.class.getDeclaredMethod("isLearningPlanQueryIntent", AiIntent.class);
        m.setAccessible(true);
        return (boolean) m.invoke(aiMessageService, intent);
    }

    private boolean hasUsableLearningConfidence(AiIntent intent) throws Exception {
        Method m = AiMessageServiceImpl.class.getDeclaredMethod("hasUsableLearningConfidence", AiIntent.class);
        m.setAccessible(true);
        return (boolean) m.invoke(aiMessageService, intent);
    }

    private AiIntent classifyWithFallback(String message) throws Exception {
        Method m = AiMessageServiceImpl.class.getDeclaredMethod("classifyWithFallback", String.class, PageContextDTO.class);
        m.setAccessible(true);
        return (AiIntent) m.invoke(targetAiMessageService(), message, null);
    }

    private AiMessageServiceImpl targetAiMessageService() throws Exception {
        return AopTestUtils.getTargetObject(aiMessageService);
    }

    //1. 查询句 → 查询排除命中（放行普通聊天走查询 Tool）
    @Test
    void queryRequestsHitQueryRule() throws Exception {
        assertThat(looksLikeLearningPlanQueryRequest("你帮我看看我有几个学习规划")).isTrue();
        assertThat(looksLikeLearningPlanQueryRequest("我的MySQl学习规划学到哪了")).isTrue();
        assertThat(looksLikeLearningPlanQueryRequest("看看我的学习进度")).isTrue();
        assertThat(looksLikeLearningPlanQueryRequest("我有哪些学习计划")).isTrue();
        assertThat(looksLikeLearningPlanQueryRequest("我的学习计划怎么样了")).isTrue();
    }

    //2. 制定/调整/普通句 → 查询排除不命中
    @Test
    void nonQueryRequestsMissQueryRule() throws Exception {
        assertThat(looksLikeLearningPlanQueryRequest("帮我制定一个学习规划")).isFalse();
        assertThat(looksLikeLearningPlanQueryRequest("帮我调整一下学习计划的第二阶段")).isFalse();
        assertThat(looksLikeLearningPlanQueryRequest("我想学Java后端开发")).isFalse();
        assertThat(looksLikeLearningPlanQueryRequest("Redis 是什么")).isFalse();
    }

    //3. 制定兜底仍能接住真制定句
    @Test
    void planRequestsHitPlanRule() throws Exception {
        assertThat(looksLikeLearningPlanRequest("我想学Java")).isTrue();
        assertThat(looksLikeLearningPlanRequest("帮我规划一下学习路线")).isTrue();
        assertThat(looksLikeLearningPlanRequest("打算入门机器学习")).isTrue();
    }

    //4. 回归：不含制定动词的查询句不再被制定兜底拦截（本次 bug 的两句）
    @Test
    void queryRequestsWithoutPlanVerbsMissPlanRule() throws Exception {
        assertThat(looksLikeLearningPlanRequest("我的MySQl学习规划学到哪了")).isFalse();
        assertThat(looksLikeLearningPlanRequest("我有哪些学习计划")).isFalse();
    }

    //5. 主路由组合双保险：查询句无论分类器怎么判都不进学习规划 Workflow
    @Test
    void shouldRouteRejectsQueryRequestsEvenWhenClassifierMisjudges() throws Exception {
        //分类器误判成 LEARNING_PLAN + 查询句 → 仍被排除
        assertThat(shouldRoute("LEARNING_PLAN", "你帮我看看我有几个学习规划")).isFalse();
        //分类器判对 GENERAL_CHAT + 查询句 → 不进
        assertThat(shouldRoute("GENERAL_CHAT", "我的MySQl学习规划学到哪了")).isFalse();
        //分类器判对 GENERAL_CHAT，但制定兜底命中（"帮我+学"）→ 查询排除仍拦下（本次 bug 的典型路径）
        assertThat(shouldRoute("GENERAL_CHAT", "你帮我看看我有几个学习规划")).isFalse();
    }

    //6. 主路由组合：制定/调整句正常进入
    @Test
    void shouldRouteAcceptsPlanAndProgressRequests() throws Exception {
        assertThat(shouldRoute("LEARNING_PLAN", "帮我制定一个学习规划")).isTrue();
        assertThat(shouldRoute("GENERAL_CHAT", "我想学Java")).isTrue();  //分类漏判 → 制定兜底接住
        assertThat(shouldRoute("GENERAL_CHAT", "帮我调整一下学习计划的第二阶段")).isTrue();  //调整句靠兜底进入，分支内再路由 PROGRESS
        assertThat(shouldRoute("GENERAL_CHAT", "Redis 是什么")).isFalse();  //普通聊天不误进
    }

    //7. LLM 已明确判为调整计划时，不依赖旧正则，也必须进入第四个 Workflow。
    @Test
    void shouldRouteAcceptsSemanticProgressIntentWithoutLegacyKeywords() throws Exception {
        assertThat(shouldRouteProgress("LEARNING_PROGRESS", 0.91, "把微服务第二阶段精简一点")).isTrue();
    }

    //8. 规则兜底只能补分类失败，不能覆盖 LLM 已识别的进度调整语义。
    @Test
    void planFallbackDoesNotOverwriteSemanticProgressIntent() throws Exception {
        AiIntent semanticProgress = new AiIntent();
        semanticProgress.setIntent("LEARNING_PROGRESS");
        semanticProgress.setConfidence(0.91);
        when(aiIntentClassifier.classify(any(), any())).thenReturn(semanticProgress);
        ReflectionTestUtils.setField(targetAiMessageService(), "aiIntentClassifier", aiIntentClassifier);

        AiIntent result = classifyWithFallback("我想把学习计划第二阶段调整一下");

        assertThat(result.getIntent()).isEqualTo("LEARNING_PROGRESS");
    }

    //9. 难点攻坚规则：难度词 + 计划词双向语序命中
    @Test
    void difficultyRequestsHitDifficultyRule() throws Exception {
        assertThat(looksLikeLearningDifficultyRequest("Redis 计划看不懂")).isTrue();  //计划词 → 难度词
        assertThat(looksLikeLearningDifficultyRequest("看不懂计划里的缓存击穿")).isTrue();  //难度词 → 计划词
        assertThat(looksLikeLearningDifficultyRequest("这个任务我看不懂")).isTrue();
        assertThat(looksLikeLearningDifficultyRequest("缓存击穿好难，卡在这个阶段了")).isTrue();
        assertThat(looksLikeLearningDifficultyRequest("我总是忘计划里的知识点")).isTrue();
    }

    //10. 难点攻坚规则：调整句 / 查询句 / 普通知识句 / 无计划词的难度句不命中
    @Test
    void nonDifficultyRequestsMissDifficultyRule() throws Exception {
        assertThat(looksLikeLearningDifficultyRequest("帮我压缩 Redis 计划")).isFalse();  //调整句 → 走 PROGRESS
        assertThat(looksLikeLearningDifficultyRequest("我的学习计划怎么样了")).isFalse();  //查询句
        assertThat(looksLikeLearningDifficultyRequest("Redis 是什么")).isFalse();  //普通知识句
        assertThat(looksLikeLearningDifficultyRequest("缓存击穿看不懂")).isFalse();  //难度词但无计划词 → 普通聊天自然讲解
        assertThat(looksLikeLearningDifficultyRequest("帮我优化这篇文章我看不懂")).isFalse();  //文章语境不误伤
    }

    @Test
    void learningIntentCarriesSemanticReferences() {
        AiIntent intent = new AiIntent();
        intent.setIntent("LEARNING_ASSIST");
        intent.setConfidence(0.94);
        intent.setLearningPlanRef("微服务");
        intent.setLearningStageRef("阶段二");

        assertThat(intent.getIntent()).isEqualTo("LEARNING_ASSIST");
        assertThat(intent.getConfidence()).isEqualTo(0.94);
        assertThat(intent.getLearningPlanRef()).isEqualTo("微服务");
        assertThat(intent.getLearningStageRef()).isEqualTo("阶段二");
    }

    @Test
    void semanticLearningIntentsHaveDedicatedRoutes() throws Exception {
        AiIntent assist = new AiIntent();
        assist.setIntent("LEARNING_ASSIST");
        assist.setConfidence(0.94);

        AiIntent progress = new AiIntent();
        progress.setIntent("LEARNING_PROGRESS");
        progress.setConfidence(0.91);

        AiIntent query = new AiIntent();
        query.setIntent("LEARNING_PLAN_QUERY");
        query.setConfidence(0.99);

        AiIntent classifierFailure = new AiIntent();
        classifierFailure.setIntent("GENERAL_CHAT");
        classifierFailure.setConfidence(0.0);

        assertThat(isLearningAssistIntent(assist)).isTrue();
        assertThat(isLearningProgressIntent(progress)).isTrue();
        assertThat(isLearningPlanQueryIntent(query)).isTrue();
        assertThat(isLearningAssistIntent(query)).isFalse();
        assertThat(hasUsableLearningConfidence(assist)).isTrue();
        assertThat(hasUsableLearningConfidence(classifierFailure)).isFalse();
    }
}
