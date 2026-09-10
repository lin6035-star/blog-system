package com.hailin.blogsystem.ai.agent;

/**
 * 证据收敛门（V3.11 Evidence Verifier）的判定结果。
 *
 * 三态语义：
 * - SUFFICIENT：证据足以支撑回答草案，放行收尾
 * - NEED_MORE：证据不足，拦截回循环补查（missingEvidence 指引补查方向）
 * - ASK_USER：缺的是用户才知道的信息，转追问（question 为追问正文，必须非空）
 *
 * Verifier 只评不选：判定结果不携带任何动作选择，补查动作仍由决策器决定。
 */
public record Verdict(
        VerdictType type,
        String missingEvidence,
        String reason,
        String question
) {

    /** 语义闸让 LLM 输出的证据缺口代码（缺哪类证据 → 决策器据此选补查动作）。 */
    public enum VerdictType {
        SUFFICIENT, NEED_MORE, ASK_USER
    }

    /** 缺失证据类别（LLM 输出代码，后端映射中文标签；未知值归一 OTHER）。 */
    public enum MissingEvidence {
        ARTICLE_CONTENT("文章正文细节"),
        MEMORY("你的记忆信息"),
        RAG("站内文章知识"),
        OTHER("其他依据");

        private final String label;

        MissingEvidence(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static MissingEvidence from(String code) {
            if (code == null || code.isBlank()) {
                return OTHER;
            }
            try {
                return valueOf(code.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return OTHER;
            }
        }
    }

    public static Verdict sufficient() {
        return new Verdict(VerdictType.SUFFICIENT, null, null, null);
    }

    public static Verdict needMore(MissingEvidence missing, String reason) {
        return new Verdict(VerdictType.NEED_MORE, missing == null ? null : missing.name(), reason, null);
    }

    public static Verdict askUser(String question, String reason) {
        return new Verdict(VerdictType.ASK_USER, null, reason, question);
    }

    /** 拦截提示里给决策器看的缺口中文标签（reason 为空时兜底）。 */
    public String missingEvidenceLabel() {
        MissingEvidence missing = MissingEvidence.from(missingEvidence);
        return missing == MissingEvidence.OTHER && (reason == null || reason.isBlank())
                ? "关键证据"
                : missing.label();
    }
}
