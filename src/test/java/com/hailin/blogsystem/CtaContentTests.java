package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.CtaKind;
import com.hailin.blogsystem.service.impl.AiMessageServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CTA 用户文案测试（V4.x）。
 *
 * 背景：CTA 文案曾把 AgentDecision.reason（开发者诊断串）直接拼给用户看——
 * 「分类器置信度不足，降级 CTA」「LLM 建议启动 Workflow，但后端规则未命中」这类内部术语
 * 会出现在聊天窗口里，而且改 reason 措辞会静默改掉用户看到的字
 * （文案此前无任何测试覆盖，所以这个泄漏长期没被发现）。
 *
 * 修法：reason 与用户文案解耦——reason 留作诊断（进 AgentDecisionTrace / 评测门断言），
 * 用户文案按 decision.ctaKind 分类渲染。
 *
 * 这组测试锁两件事：
 * 1. 用户文案里不得出现内部术语——将来若有人把 reason 拼回去，这里会拦住
 * 2. 「系统不确定」按 intent 给确认式引导，而不是一句无信息的兜底
 *
 * 注：被测算方法是 private（只在本类内使用），用反射调用，不改生产代码可见性。
 */
class CtaContentTests {

    /** 不该出现在用户可见文案里的内部术语 */
    private static final List<String> INTERNAL_TERMS = List.of(
            "LLM", "Workflow", "workflow", "CTA", "降级", "置信度", "后端规则",
            "分类器", "白名单", "intent", "命中", "字段未"
    );

    private static final List<String> KNOWN_INTENTS = List.of(
            "LEARNING_PLAN", "LEARNING_PROGRESS", "LEARNING_ASSIST",
            "CREATE_ARTICLE_WORKFLOW", "OPTIMIZE_ARTICLE_WORKFLOW",
            "ARTICLE_DETAIL_QA", "GENERAL_CHAT");

    private final AiMessageServiceImpl service = mock(AiMessageServiceImpl.class);

    @Test
    void userFacingCtaNeverLeaksInternalTerms() throws Exception {
        Method build = ctaMethod("buildPlannerCtaContent");

        for (CtaKind kind : CtaKind.values()) {
            for (String intent : KNOWN_INTENTS) {
                String content = (String) build.invoke(service, AgentDecision.builder()
                        .action(AgentAction.CTA).intent(intent).ctaKind(kind).build());

                assertThat(content).isNotBlank();
                assertThat(INTERNAL_TERMS)
                        .as("kind=%s intent=%s 的文案泄漏了内部术语", kind, intent)
                        .allSatisfy(term -> assertThat(content).doesNotContain(term));
            }
        }
    }

    @Test
    void systemUncertainGuidesByIntentInsteadOfGenericFallback() throws Exception {
        Method build = ctaMethod("buildPlannerCtaContent");

        // 学习类 → 引导到"学习计划"，把选择权交回用户
        for (String intent : List.of("LEARNING_PLAN", "LEARNING_PROGRESS", "LEARNING_ASSIST")) {
            assertThat(invokePlanner(build, intent, CtaKind.SYSTEM_UNCERTAIN))
                    .contains("学习计划");
        }
        // 文章创作 → 给出主题示例（用户知道该补什么）
        assertThat(invokePlanner(build, "CREATE_ARTICLE_WORKFLOW", CtaKind.SYSTEM_UNCERTAIN))
                .contains("主题");
        // 未知 intent → 兜底文案仍给出可行动的例子，不是一句"我不确定"就完了
        assertThat(invokePlanner(build, "GENERAL_CHAT", CtaKind.SYSTEM_UNCERTAIN))
                .contains("比如");
    }

    @Test
    void guestCtaNeverLeaksInternalTermsAndToleratesNullDecision() throws Exception {
        Method build = ctaMethod("buildGuestCtaContent");

        for (CtaKind kind : CtaKind.values()) {
            String content = (String) build.invoke(service, AgentDecision.builder()
                    .action(AgentAction.CTA).intent("LEARNING_PLAN").ctaKind(kind).build());

            assertThat(INTERNAL_TERMS)
                    .as("游客 kind=%s 的文案泄漏了内部术语", kind)
                    .allSatisfy(term -> assertThat(content).doesNotContain(term));
        }

        // decision 为 null 也不能炸（分类器没有返回有效意图的老路径）
        assertThat((String) build.invoke(service, new Object[]{null})).isNotBlank();
    }

    private static Method ctaMethod(String name) throws Exception {
        Method method = AiMessageServiceImpl.class.getDeclaredMethod(name, AgentDecision.class);
        method.setAccessible(true);
        return method;
    }

    private String invokePlanner(Method build, String intent, CtaKind kind) throws Exception {
        return (String) build.invoke(service, AgentDecision.builder()
                .action(AgentAction.CTA).intent(intent).ctaKind(kind).build());
    }
}
