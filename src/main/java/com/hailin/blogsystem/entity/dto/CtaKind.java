package com.hailin.blogsystem.entity.dto;

/**
 * CTA 的面向用户原因分类（V4.x）。
 *
 * 与 AgentDecision.reason 职责分离：
 * - reason  = 开发者诊断（进 AgentDecisionTrace / 评测门断言），措辞可自由改
 * - ctaKind = 面向用户的解释分类，决定 CTA 文案怎么说话
 *
 * 分开的原因：此前 reason 被直接拼进用户文案，
 * ① 内部术语（LLM / Workflow / 后端规则 / 降级 CTA）会被用户读到；
 * ② 改 reason 措辞会静默改掉用户看到的字（昨天改 Planner reason 时就发生过）。
 *
 * 分流依据是「用户能不能据此行动」——用户做不了什么的原因（系统自身不确定）
 * 不应该被解释，只给中性确认。
 */
public enum CtaKind {

    /** 分类器主动建议澄清：用户补一句信息即可 */
    AMBIGUOUS_REQUEST,

    /** 缺文章上下文：用户去文章详情页，或说清指哪篇 */
    MISSING_ARTICLE_CONTEXT,

    /** 页面上下文不符：目标动作要在别的页面做 */
    PAGE_CONTEXT_MISMATCH,

    /** 会话自动拉起配额用尽：请用户明确说一次 */
    QUOTA_REACHED,

    /**
     * 默认：系统自身不确定（置信不足 / 风险偏高 / 分类器字段不一致 / 白名单外）。
     * 用户无从行动——文案按 intent 给一句确认式引导，不解释内部机制。
     */
    SYSTEM_UNCERTAIN
}
