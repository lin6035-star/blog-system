package com.hailin.blogsystem.ai.agent;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 会话文章锚（V3.8）：ai_sessions.last_article_* 单点状态的读写。
 *
 * 写点（受控，两类事件，闲聊/纯浏览不写）：
 * - 文章域 Agent 完成一次定位（source=AGENT_RUN）
 * - 明确围绕当前文章的页面意图（source=PAGE_QA / PAGE_ACTION）
 *
 * 定位以 last_article_id 为本体；last_article_title 只是快照（候选注入参考），
 * 权威标题 resolve 时查库现取——改名不漂。
 *
 * V3.9 读语义按域拆分（锚存储通用——写点不验归属，锚里可含他人公开文章）：
 * - resolve（owned）：存在 + 归属本人。文章域 Agent / 写动作用（只能动自己的文章）
 * - resolveReadable（readable）：存在 + 公开可读或本人全状态。QA 问答用（他人公开文章也能续问）
 * - loadReadable：读权限 SQL/过滤单点，两个 resolve 与 QA 决议共用同一条口径
 * 锚失效（不满足条件）返回 null，宁可不猜。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleSessionAnchorService {

    /** 锚读取结果：文章 ID（本体）+ 数据库权威标题。 */
    public record ArticleAnchor(Long articleId, String title) {
    }

    /**
     * 结论锚读取结果（V3.12）：结论正文（已按注入上限截断）+ 来源信息。
     *
     * text 在此截断而非调用方截断——存储侧 2000 字，注入侧 500 字，收口一处防两处漂移。
     */
    public record ConclusionAnchor(
            Long articleId,
            String text,
            Long sourceRunId,
            String sourceType,
            LocalDateTime updatedAt
    ) {
    }

    /** 结论锚来源类型（V3.12）：审计 + 评测标签，**不影响 prompt 文案**（两条来源共用同一套弱参考措辞）。 */
    public static final String CONCLUSION_SOURCE_FINAL_ANSWER = "ARTICLE_AGENT_FINAL_ANSWER";
    public static final String CONCLUSION_SOURCE_WORKFLOW_CONFIRMED = "WORKFLOW_SUGGESTION_CONFIRMED";

    /** 结论锚存储上限（对齐 ai_sessions.last_conclusion_text VARCHAR(2000)）。 */
    private static final int CONCLUSION_STORE_MAX = 2000;
    /** 结论锚注入上限（进 Workflow prompt 的节选长度）——finalAnswer 常上千字，全量注入会压过「用户优化要求」。 */
    public static final int CONCLUSION_INJECT_MAX = 500;

    public static final String SOURCE_AGENT_RUN = "AGENT_RUN";
    public static final String SOURCE_PAGE_QA = "PAGE_QA";
    public static final String SOURCE_PAGE_ACTION = "PAGE_ACTION";

    private final AiSessionService aiSessionService;
    private final ArticlesService articlesService;

    /**
     * 写点：覆盖式更新会话文章锚。标题取数据库权威，文章已删时容忍 null（本体 id 仍可定位，resolve 会判失效）。
     */
    public void mark(Long sessionId, Long articleId, String source) {
        if (sessionId == null || articleId == null || source == null) {
            return;
        }
        AiSessions session = aiSessionService.getById(sessionId);
        if (session == null) {
            return;
        }
        Articles article = articlesService.getById(articleId);

        AiSessions update = new AiSessions();
        update.setId(sessionId);
        update.setLastArticleId(articleId);
        update.setLastArticleTitle(article == null ? null : article.getTitle());
        update.setLastArticleSource(source);
        update.setLastArticleUpdatedAt(LocalDateTime.now());
        aiSessionService.updateById(update);
        log.debug("会话文章锚更新: sessionId={}, articleId={}, source={}", sessionId, articleId, source);
    }

    /**
     * 读点（owned，文章域 Agent 用）：返回有效锚（会话存在 + 归属当前用户 + 有锚 + 文章存在 +
     * 作者 = 当前用户），任一不满足返回 null。标题为查库权威现取。
     *
     * V3.9：补 session.userId == userId 校验（锚定位入口防御，不依赖调用链纪律）。
     */
    public ArticleAnchor resolve(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return null;
        }
        AiSessions session = aiSessionService.getById(sessionId);
        if (session == null || session.getUserId() == null
                || !session.getUserId().equals(userId)) {
            return null;
        }
        if (session.getLastArticleId() == null) {
            return null;
        }
        Articles article = articlesService.getById(session.getLastArticleId());
        if (article == null || !userId.equals(article.getAuthorId())) {
            return null;
        }
        return new ArticleAnchor(article.getId(), article.getTitle());
    }

    /**
     * 读权限单点（V3.9）：公开可读 + 本人全状态可读（对齐文章域 Agent「归属是唯一门槛」）。
     * 他人非公开文章一律不可读（防隐私洞）。userId 为 null（游客）→ 只读公开。
     * 返回实体（含正文，QA 决议/加载合一用）或 null。
     */
    public Articles loadReadable(Long articleId, Long userId) {
        if (articleId == null) {
            return null;
        }
        Articles article = articlesService.getById(articleId);
        return isReadable(article, userId) ? article : null;
    }

    /**
     * 读权限判据单点（V3.12 手测修正）：公开可读 + 本人全状态可读，他人非公开一律不可读。
     *
     * 独立暴露给已持有实体的调用方（文章域只读执行器已查过库，再调 loadReadable 会重复查询）；
     * 语义与 loadReadable 严格一致，两条路径不会漂移。
     *
     * 分工：**读**用本判据（他人公开文章可读），**写**仍走归属校验——问「这篇写得怎么样」
     * 不该被「不是你的文章」拦住（2026-09-10 老大手测）。
     */
    public boolean isReadable(Articles article, Long userId) {
        if (article == null) {
            return false;
        }
        if (article.getStatus() != null
                && article.getStatus() == BlogConstants.ArticlesStatus.PUBLISHED) {
            return true;
        }
        return userId != null && userId.equals(article.getAuthorId());
    }

    // ==================== V3.12 结论锚 ====================

    /**
     * 写点：覆盖式更新会话结论锚（V3.12）。
     *
     * **并发保护（设计稿 §3.1）**：仅当新 run 的雪花 ID 大于锚里已有的 sourceRunId 才允许覆盖——
     * 防「同一 session 并发两个 Agent 请求时，较早创建的 run 较晚结束，反而覆盖较新的结论」。
     * 依赖 AiAgentRun.id（ASSIGN_ID）单实例单调递增；多实例部署需换乐观锁列。
     *
     * 影响行数 0 = 已有更新的结论，本次放弃覆盖（正常分支，非错误）。
     *
     * 用非 lambda UpdateWrapper（字符串列名）：纯 mock 单测环境无 MyBatis-Plus TableInfo 缓存，
     * lambda wrapper 会炸（V2.1/V3.8 已踩，见 AgentRunSuggestionService 同款写法）。
     */
    public void markConclusion(
            Long sessionId,
            Long articleId,
            String text,
            Long sourceRunId,
            String sourceType
    ) {
        if (sessionId == null || articleId == null || sourceRunId == null
                || sourceType == null || text == null || text.isBlank()) {
            return;
        }
        AiSessions session = aiSessionService.getById(sessionId);
        if (session == null) {
            return;
        }
        String stored = text.length() <= CONCLUSION_STORE_MAX
                ? text
                : text.substring(0, CONCLUSION_STORE_MAX);

        int updated = aiSessionService.getBaseMapper().update(null,
                new UpdateWrapper<AiSessions>()
                        .eq("id", sessionId)
                        .and(w -> w.isNull("last_conclusion_source_run_id")
                                .or().lt("last_conclusion_source_run_id", sourceRunId))
                        .set("last_conclusion_article_id", articleId)
                        .set("last_conclusion_text", stored)
                        .set("last_conclusion_source_run_id", sourceRunId)
                        .set("last_conclusion_source_type", sourceType)
                        .set("last_conclusion_updated_at", LocalDateTime.now()));
        if (updated == 0) {
            log.info("结论锚覆盖被拒（已有更新的结论）: sessionId={}, sourceRunId={}", sessionId, sourceRunId);
            return;
        }
        log.info("结论锚更新: sessionId={}, articleId={}, sourceRunId={}, sourceType={}",
                sessionId, articleId, sourceRunId, sourceType);
    }

    /**
     * 读点：返回可用结论锚（V3.12），任一校验不满足返回 null（宁可不注入）。
     *
     * 校验（设计稿 §3.3）：
     * - 会话存在 + 归属当前用户（防越权读他人会话锚）
     * - 锚 articleId 与目标文章一致（防串文章：上轮聊 A 这篇优化 B）
     * - 结论正文非空
     * - 文章仍存在且**可读**（对齐 V3.12 读权限语义——他人公开文章的分析结论也能继承；
     *   写路径的归属校验不受影响，别人的文章本来就创建不了 OPTIMIZE Workflow）
     * - 非公开的他人文章一律失效（防隐私洞）
     *
     * **不设时间窗口**：lastConclusionUpdatedAt 仅审计，不参与判断（设计稿 §四）。
     */
    public ConclusionAnchor resolveConclusion(Long sessionId, Long userId, Long targetArticleId) {
        if (sessionId == null || userId == null || targetArticleId == null) {
            return null;
        }
        AiSessions session = aiSessionService.getById(sessionId);
        if (session == null || session.getUserId() == null
                || !session.getUserId().equals(userId)) {
            return null;
        }
        if (session.getLastConclusionArticleId() == null
                || !session.getLastConclusionArticleId().equals(targetArticleId)) {
            return null;
        }
        String text = session.getLastConclusionText();
        if (text == null || text.isBlank()) {
            return null;
        }
        Articles article = articlesService.getById(targetArticleId);
        if (!isReadable(article, userId)) {
            return null;
        }
        return new ConclusionAnchor(
                targetArticleId,
                text.length() <= CONCLUSION_INJECT_MAX ? text : text.substring(0, CONCLUSION_INJECT_MAX),
                session.getLastConclusionSourceRunId(),
                session.getLastConclusionSourceType(),
                session.getLastConclusionUpdatedAt()
        );
    }

    /**
     * 读点（readable，QA 问答用）：返回有效锚文章实体（会话存在 + 归属当前用户 + 有锚 +
     * loadReadable 可读），任一不满足返回 null。与 owned resolve 的区别：他人公开文章也命中。
     */
    public Articles resolveReadable(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return null;
        }
        AiSessions session = aiSessionService.getById(sessionId);
        if (session == null || session.getUserId() == null
                || !session.getUserId().equals(userId)) {
            return null;
        }
        if (session.getLastArticleId() == null) {
            return null;
        }
        return loadReadable(session.getLastArticleId(), userId);
    }
}
