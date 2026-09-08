package com.hailin.blogsystem.ai.qa;

import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * QA 目标决议器（V3.9）：普通 QA 管道没有决策器可输出 anchorMode，指代消解用确定性后端规则。
 *
 * 职责 = 候选决议 + 可读加载合一，单次请求 resolve 一次，结果分发正文注入与来源卡两消费点：
 * - PC 候选：pageContext.articleId 经 loadReadable 加载成功才成立（脏 ID / 他人隐藏 / 已删除 → 无 PC）
 * - AC 候选：resolveReadable（会话归属 + 公开可读或本人全状态，锚里可含他人公开文章）
 * - 措辞信号（闭合词集，只判"消息指哪个方向的候选"，不做文章定位/不比标题）→ 决议矩阵：
 *   强会话近指(刚刚/刚才+文章指代) → 锚优先；强当前页(这篇系) → 当前页优先；
 *   弱指代(那篇)双候选 → 追问态不猜；唯一候选 → 直接用；其余 → 说明态
 * intent.articleId 全程不参与（LLM 输出不可信，V3.8「模型不输出 ID」安全资产延续）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleQaTargetResolver {

    /** 决议输出：target = 本请求要读的文章（已验证可读，含正文）；promptNote = 追问/说明文案。 */
    public record QaTarget(Articles article, String promptNote) {

        public static QaTarget target(Articles article) {
            return new QaTarget(article, null);
        }

        public static QaTarget promptNote(String note) {
            return new QaTarget(null, note);
        }

        public boolean hasArticle() {
            return article != null;
        }
    }

    /** 措辞信号档位：只回答"消息指哪个方向的候选"。 */
    public enum Signal {
        /** 强当前页：「这篇 / 这篇文章 / 当前文章 / 它」（无时间近指）。 */
        S1_STRONG_PAGE,
        /** 强会话近指：时间近指（刚刚/刚才）∧ 文章指代同现。 */
        S2_STRONG_RECENT,
        /** 弱指代：「那篇」（无时间词，可能是当前页口语也可能是会话里的）。 */
        S3_WEAK,
        /** 无指代词（详情页大多数问句，语境隐含当前页）。 */
        S0_NONE
    }

    private final ArticleSessionAnchorService anchorService;

    /**
     * 单请求决议入口。页面/会话候选 + 用户措辞 → 目标文章或提示文案。
     */
    public QaTarget resolve(String message, PageContextDTO pageContext, Long sessionId, Long userId) {
        Articles pageArticle = resolvePageCandidate(pageContext, userId);
        Articles anchorArticle = anchorService.resolveReadable(sessionId, userId);
        // 游客无归属会话：没有"会话最近文章"概念，会话近指无从解析 → 信号降级，
        // 避免详情页问"刚才那篇"（大概率是当前页口语）被澄清规则误伤
        Signal signal = sessionId == null ? Signal.S0_NONE : classifySignal(message);
        boolean hasPage = pageArticle != null;
        boolean hasAnchor = anchorArticle != null;

        // 强会话近指：锚优先；锚无 → 说明态（即使是详情页也不许硬套当前页——V3.8 老大实测场景）
        if (signal == Signal.S2_STRONG_RECENT) {
            if (hasAnchor) {
                return QaTarget.target(anchorArticle);
            }
            return QaTarget.promptNote(noteRecentNoRecord(hasPage ? pageArticle.getTitle() : null));
        }

        // 双候选都存在 + 弱指代 → 追问态（带权威标题，不猜）
        if (signal == Signal.S3_WEAK && hasPage && hasAnchor) {
            return QaTarget.promptNote(askWhich(pageArticle.getTitle(), anchorArticle.getTitle()));
        }

        // 其余：页面优先（唯一候选直接使用，弱指代不打断），页面无 → 锚唯一候选，都无 → 说明态
        if (hasPage) {
            return QaTarget.target(pageArticle);
        }
        if (hasAnchor) {
            return QaTarget.target(anchorArticle);
        }
        return QaTarget.promptNote(noteNoArticleAtAll());
    }

    /**
     * 措辞信号分类（词集语义闭合：时间近指表达与文章指代词有限，可评测）。
     * 时间近指必须与文章指代同现才算强会话近指——「刚才说的观点再展开」是对话内容追问，不算。
     */
    static Signal classifySignal(String message) {
        boolean timeRecent = containsAny(message, "刚刚", "刚才");
        boolean articleDeixis = containsAny(message, "那篇", "这篇", "文章", "它");
        if (timeRecent && articleDeixis) {
            return Signal.S2_STRONG_RECENT;
        }
        if (!timeRecent && containsAny(message, "这篇", "当前文章", "它")) {
            return Signal.S1_STRONG_PAGE;
        }
        if (!timeRecent && containsAny(message, "那篇")) {
            return Signal.S3_WEAK;
        }
        return Signal.S0_NONE;
    }

    private static boolean containsAny(String message, String... words) {
        if (message == null || message.isBlank()) {
            return false;
        }
        for (String word : words) {
            if (message.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * PC 候选：页面 ID 经读权限加载成功才算候选（先加载后成候选，防"决议选 PC、加载却失败"割裂）。
     */
    private Articles resolvePageCandidate(PageContextDTO pageContext, Long userId) {
        if (pageContext == null) {
            return null;
        }
        String articleId = pageContext.getArticleId();
        if (articleId == null || articleId.isBlank()) {
            return null;
        }
        Long id;
        try {
            id = Long.valueOf(articleId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return anchorService.loadReadable(id, userId);
    }

    private static String askWhich(String pageTitle, String anchorTitle) {
        return "用户用\"那篇\"指代一篇文章，但当前可能指：当前页《" + safe(pageTitle)
                + "》或本会话最近聊过的《" + safe(anchorTitle)
                + "》。先用一句话向用户确认指哪一篇，确认前不要回答文章内容。";
    }

    private static String noteRecentNoRecord(String pageTitle) {
        String note = "用户用\"刚刚/刚才\"指代本会话早前聊过的文章，但本会话没有可读的文章记录";
        if (pageTitle != null) {
            note += "；即使当前页是《" + safe(pageTitle) + "》也不要把这篇当\"刚刚那篇\"";
        }
        return note + "。如实向用户说明无法定位\"刚刚那篇\"，并引导打开目标文章详情页后再问。";
    }

    private static String noteNoArticleAtAll() {
        return "用户询问某篇文章内容，但既没有当前页面文章、本会话也没有最近聊过的文章记录。"
                + "向用户说明情况，并引导打开文章详情页后再问。";
    }

    private static String safe(String title) {
        return title == null ? "" : title;
    }
}
