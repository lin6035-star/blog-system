package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文章域只读动作执行器（V2.5）。
 *
 * - QUERY_ARTICLE：查库验归属 + 输出文章结构分析（标题/状态/字数/段落/小标题/正文预览）；
 *   V3.11 起支持 input.focus 聚焦正文片段
 * - QUERY_MEMORY / SEARCH_RAG：与学习域同构，复用现有检索链路
 *
 * articleId 只当线索：优先 decision.input.articleId（V3.8 后端决议目标注入，V3.11 P0 修复），
 * 其次页面上下文（PageContextDTO）兜底，最终查库校验存在 + 归属，不通过抛异常
 * （FAILED step，循环继续，不猜）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticleAgentActionExecutorImpl implements ArticleAgentActionExecutor {

    private static final int TITLE_MAX = 40;
    private static final int TEXT_MAX = 200;
    private static final int PREVIEW_MAX = 600;

    private final ArticlesService articlesService;
    private final AiMemoryRetrieveService aiMemoryRetrieveService;
    private final AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private final ArticleRagSearchService articleRagSearchService;
    private final BlogAiProperties blogAiProperties;
    /** V3.12 手测修正：读权限单点（他人公开文章可读），与 QA 的 loadReadable 同口径。 */
    private final ArticleSessionAnchorService anchorService;

    @Override
    public String execute(AgentStepDecision decision, Long userId, PageContextDTO pageContext) {
        if (userId == null) {
            return "当前未登录，无法分析文章。";
        }
        return switch (decision.actionType()) {
            case QUERY_ARTICLE -> queryArticle(decision, userId, pageContext);
            case QUERY_MEMORY -> queryMemory(decision, userId);
            case SEARCH_RAG -> searchRag(decision);
            case QUERY_LEARNING_DASHBOARD ->
                    throw new UnsupportedOperationException("学习域动作不经过文章域执行器");
            case ASK_USER, FINAL_ANSWER, SUGGEST_WORKFLOW, SUGGEST_WRITE ->
                    throw new UnsupportedOperationException("终态动作不经过执行器");
        };
    }

    /**
     * 查询当前文章 + 结构分析。
     *
     * V3.11 P0 定位优先级修复：decision.input.articleId（Runtime 决议目标，V3.8 prepareStepDecision
     * 后端注入——决策器 prompt 禁止 LLM 自填 articleId）优先；pageContext 降为无决议目标时的兜底线索。
     * 修复前 pageContext 优先会在跨页场景错读（B 页指会话锚 A 时读到当前页 B）。
     * 校验失败（缺失 / 非数字 / 不存在 / 无读权限）抛异常，
     * 由 Runtime 记 FAILED step + 失败 observation，循环继续由下一轮决策收尾。
     *
     * **读权限（2026-09-10 老大手测修正）**：本站是公开博客，游客都能读任何已发布文章，
     * Agent 凭什么不能？改为判「可读」而非「归属」——他人公开文章照常分析。
     * 归属只影响**能力边界**：非本人文章不产出写动作提案（写路径另有归属校验兜底），
     * 且要把这点写进观察，让 LLM 知道只能给建议、不能提议修改。
     * 修复前用归属当读门槛，用户在别人文章页问「这篇写得怎么样」会被回
     * 「这篇文章不是你的」——只是想问问，并不想改。
     */
    private String queryArticle(AgentStepDecision decision, Long userId, PageContextDTO pageContext) {
        String clue = text(decision.input(), "articleId");
        if (clue == null || clue.isBlank()) {
            clue = pageContext != null ? pageContext.getArticleId() : null;
        }
        if (clue == null || !clue.matches("\\d+")) {
            throw new IllegalArgumentException("缺少当前文章 ID，不猜测文章。");
        }

        Articles article = articlesService.getById(Long.valueOf(clue));
        if (article == null) {
            throw new ArticleNotOwnedException("这篇文章不存在或已删除，无法为你分析。");
        }
        if (!anchorService.isReadable(article, userId)) {
            throw new ArticleNotOwnedException("这篇文章不是公开的，也不是你写的，无法查看。");
        }
        boolean owned = userId.equals(article.getAuthorId());
        // V3.11：input.focus（可选）→ 聚焦正文片段（证据收敛门 NEED_MORE 后补查的「新材料」来源）。
        // 聚焦未命中明说（不静默回退结构摘要——回退会造成「读过但没读到」的假成功）；
        // 无 focus → 原结构摘要。
        String focus = text(decision.input(), "focus");
        if (focus != null && !focus.isBlank()) {
            if (isStructureOverviewFocus(focus)) {
                return buildStructureSummary(article) + ownershipNote(owned);
            }
            String snippet = extractFocusSnippet(article, focus);
            if (snippet != null) {
                return appendOwnershipNote(snippet, owned);
            }
            return "未能从这篇文章中找到与「" + limit(focus, 40)
                    + "」相关的内容，请换个说法，或确认文章是否真的包含这部分。";
        }
        return buildStructureSummary(article) + ownershipNote(owned);
    }

    /**
     * 归属边界说明（LLM 可见）：非本人文章仍可分析，但不能提议修改。
     * 观察里说清楚，避免决策器对他人文章产出注定被拒的写动作提案。
     */
    private String appendOwnershipNote(String snippet, boolean owned) {
        return owned ? snippet : snippet + ownershipNote(false);
    }

    private String ownershipNote(boolean owned) {
        return owned ? "" : "\n- 归属：不是当前用户的文章（只能分析和给建议，不能提议修改）\n";
    }

    // ==================== V3.11 focus 聚焦片段 ====================

    /** focus 净化词表（指示/疑问/虚词/评价后缀；先剔长词避免子串残留）。 */
    private static final List<String> FOCUS_STOPWORDS = List.of(
            "帮我分析一下", "分析一下", "说一说", "讲一讲", "看一看", "帮我看看", "你觉得",
            "那一段", "这一段", "这一篇", "那一节", "那一部分", "这一部分", "那一块",
            "这一小节", "这个小节", "那一小节", "那个小节", "这一节", "那一节", "那节", "这节",
            "那段内容", "这部分内容", "那部分内容", "这个部分", "那个部分",
            "这篇", "那段", "这段", "那个", "哪里", "哪些", "怎么样", "如何", "怎么",
            "讲的", "说说", "看看", "写得", "写的", "讲的", "介绍了", "论述", "讲述",
            "内容", "部分", "方面", "情况", "关于", "里面", "的话", "小节", "段落",
            "和", "与", "以及", "的", "了", "吗", "呢", "啊", "请", "帮我", "里", "中",
            "是", "在", "对", "给", "把", "下", "个", "这段"
    );

    /** 单次聚焦观察内容上限（骨架还会按 MAX_OBSERVATION_CHARS 二次裁剪）。 */
    private static final int FOCUS_SNIPPET_MAX = 1200;

    /**
     * V3.11：按 focus 定位正文片段（纯后端规则，零 LLM）：
     * 1. 净化 focus（剔指示/疑问/虚词）→ 切残余 token（≥2 字）
     * 2. 按小标题（## 行）分节，命中节标题的取命中 token 数最多者
     * 3. 多个小节并列命中 → 返回候选清单（不猜哪一节：猜错 = 评错小节）
     * 4. 无小标题命中 → 段落级命中（空行分块，命中段带前后各一段上下文）
     * 5. 全部未命中 → null（调用方明说）
     * 匹配不完美可接受：最坏多一轮 NEED_MORE，不出错答（设计稿 3.2.1）。
     *
     * 2026-09-10 手测修正：**无正文的纯标题节不参与命中**。切节时首个「## 」之前的内容
     * 会单独成节（本篇即 H1 标题行），其标题命中后只返回标题本身，正文一个字都没有——
     * 动作 SUCCESS 但证据为空，验证器据此反复判 NEED_MORE，决策器重复同一 focus
     * 直到步数耗尽（实测 6 步空转 + 收尾空决策 FAILED）。
     * 同时：焦点词宽泛到命中多个小节时不再取第一个（会评错小节），改为列出候选让决策器指明。
     */
    private String extractFocusSnippet(Articles article, String focus) {
        String content = article.getContent() == null ? "" : article.getContent();
        List<String> tokens = focusTokens(focus);
        if (tokens.isEmpty()) {
            return null;
        }

        // 1) 小标题命中：命中 token 数最多者；并列 → 用正文含量消歧；仍无法区分 → 降级
        List<String> sections = new ArrayList<>();
        for (String section : splitSections(content)) {
            if (!sectionBody(section).isBlank()) {
                sections.add(section);   // 纯标题节（无正文可评）不参与
            }
        }
        List<String> headingHits = pickTopBy(sections, s -> countMatches(sectionHeading(s), tokens));
        if (headingHits.size() == 1) {
            return focusSnippet(headingHits.get(0));
        }
        if (headingHits.size() > 1) {
            // 标题并列（如主题词贯穿全篇）→ 退一步看哪一节正文真的在讲它；能定则定，不定则降级
            List<String> bodyResolved =
                    pickTopBy(headingHits, s -> countMatches(sectionBody(s), tokens));
            if (bodyResolved.size() == 1) {
                return focusSnippet(bodyResolved.get(0));
            }
            // 仍并列 → 明说「无法确定指哪一处」+ 结构摘要（LLM 可据小标题换更精确的词）。
            // 不返回永远无法被证据支撑的「候选清单」（V3.13 定），也不静默回退结构摘要
            // ——静默回退会让模型以为拿到了那节内容 → 凭空评价 → 被证据门拦 → 空转
            return focusAmbiguous(focus, article);
        }

        // 2) 段落级命中（无小标题或节内未命中时逐段查，带上下文一段）
        List<String> paragraphs = new ArrayList<>();
        for (String block : content.split("\\n\\s*\\n")) {
            if (!block.isBlank()) {
                paragraphs.add(block.trim());
            }
        }
        List<String> paragraphHits = pickTopBy(paragraphs, p -> countMatches(p, tokens));
        if (paragraphHits.isEmpty()) {
            return null;
        }
        if (paragraphHits.size() > 1) {
            // 多段并列且无法进一步区分 → 同小节分支：明说无法确定 + 结构摘要
            return focusAmbiguous(focus, article);
        }
        int bestIndex = paragraphs.indexOf(paragraphHits.get(0));

        StringBuilder sb = new StringBuilder("聚焦片段：\n");
        if (bestIndex > 0) {
            sb.append("（上文）").append(limit(paragraphs.get(bestIndex - 1), 200)).append('\n');
        }
        sb.append(paragraphs.get(bestIndex)).append('\n');
        if (bestIndex + 1 < paragraphs.size()) {
            sb.append("（下文）").append(limit(paragraphs.get(bestIndex + 1), 200));
        }
        String snippet = sb.toString().trim();
        if (snippet.length() <= FOCUS_SNIPPET_MAX) {
            return snippet;
        }
        return snippet.substring(0, FOCUS_SNIPPET_MAX);
    }

    /**
     * 结构/摘要/小标题这类 focus 不是正文定位词，而是结构摘要已有能力。
     * 若继续走精确片段匹配，会因为正文里没有“摘要部分”这几个字而误报未找到。
     */
    private boolean isStructureOverviewFocus(String focus) {
        for (String token : focusTokens(focus)) {
            String normalized = token.replaceAll("\\s+", "");
            if (normalized.equals("摘要")
                    || normalized.equals("导语")
                    || normalized.equals("开头")
                    || normalized.equals("标题")
                    || normalized.equals("小标题")
                    || normalized.equals("大纲")
                    || normalized.equals("目录")
                    || normalized.equals("层次")) {
                return true;
            }
            if ((normalized.contains("文章") || normalized.contains("整体"))
                    && (normalized.contains("结构")
                    || normalized.contains("布局")
                    || normalized.contains("层次"))) {
                return true;
            }
            if (normalized.contains("小标题") || normalized.contains("标题结构")) {
                return true;
            }
            if (normalized.contains("段落")
                    && (normalized.contains("结构") || normalized.contains("数量") || normalized.equals("段落"))) {
                return true;
            }
        }
        return false;
    }

    /** 净化 focus → 切残余 token（≥2 字）。净化后为空 → 空列表（调用方放弃聚焦）。 */
    private List<String> focusTokens(String focus) {
        String purified = focus;
        for (String word : FOCUS_STOPWORDS) {
            purified = purified.replace(word, " ");
        }
        List<String> tokens = new ArrayList<>();
        for (String token : purified.split("[\\s,，。;；:：、!！?？·\\-—]+")) {
            String t = token.trim();
            if (t.length() >= 2) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /**
     * 取得分最高的元素集合（并列全部返回；全为 0 分则返回空）。
     * 命中判定统一走这里，避免「取第一个」——正文里同一说法出现多次是常态，
     * 取第一个等于替用户猜，猜错就是评错段落。
     */
    private List<String> pickTopBy(List<String> candidates, java.util.function.ToIntFunction<String> scorer) {
        List<String> best = new ArrayList<>();
        int bestScore = 0;
        for (String candidate : candidates) {
            int score = scorer.applyAsInt(candidate);
            if (score == 0) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                best.clear();
                best.add(candidate);
            } else if (score == bestScore) {
                best.add(candidate);
            }
        }
        return best;
    }

    /** 节的小标题文本（首个 ## 行；无标题行返回空串）。 */
    private String sectionHeading(String section) {
        for (String line : section.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                return trimmed;
            }
        }
        return "";
    }

    /** 节的正文（剔除标题行与空行；用于判定纯标题节）。 */
    private String sectionBody(String section) {
        StringBuilder sb = new StringBuilder();
        for (String line : section.split("\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank() && !trimmed.startsWith("## ")) {
                sb.append(trimmed);
            }
        }
        return sb.toString();
    }

    /**
     * 聚焦并列、无法定位到唯一目标时的返回：**明说「无法确定指哪一处」**，再附结构摘要。
     *
     * 为什么不静默回退结构摘要（2026-09-11 实测教训）：静默回退 + 「聚焦片段：」前缀会让模型
     * 以为拿到了那节内容，于是凭空评价 → 被证据收敛门拦 → 重试同一个 focus → 被重复拦截 →
     * 步数用满。实测用户问的「缓存穿透」这一节文章里根本没有，系统走了 6 步才放弃、
     * 且全程没告诉任何人「没这一节」。
     *
     * 仍不返回候选清单（V3.13 定：候选清单不是正文，证据闸永远判不了）——
     * 结构摘要的作用是让模型看见小标题，从而换一个更精确的定位词。
     */
    private String focusAmbiguous(String focus, Articles article) {
        return "未能确定「" + limit(focus, 40) + "」指的是文章中的哪一处（有多个位置都提到了它）。"
                + "如需读取具体内容，请用更精确的定位词（例如某个小标题的原话）；"
                + "若文章确实没有这部分内容，直接说明即可。\n"
                + buildStructureSummary(article);
    }

    /** 单节聚焦输出（限长）。 */
    private String focusSnippet(String section) {
        String trimmed = section.trim();
        if (trimmed.length() <= FOCUS_SNIPPET_MAX) {
            return "聚焦片段：\n" + trimmed;
        }
        return "聚焦片段：\n" + trimmed.substring(0, FOCUS_SNIPPET_MAX);
    }

    /** 文本命中 token 数（空白归一化，与 containsAny 同口径）。 */
    private int countMatches(String text, List<String> tokens) {
        String normalized = text.replaceAll("\\s+", "");
        int count = 0;
        for (String token : tokens) {
            if (normalized.contains(token.replaceAll("\\s+", ""))) {
                count++;
            }
        }
        return count;
    }

    /** 按小标题（## 行）把正文切成节列表（节含标题行；无 ## 时整篇为单节）。 */
    private List<String> splitSections(String content) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\\n")) {
            if (line.trim().startsWith("## ") && current.length() > 0) {
                sections.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            sections.add(current.toString());
        }
        return sections;
    }

    private String buildStructureSummary(Articles article) {
        String content = article.getContent() == null ? "" : article.getContent();
        String statusLabel = switch (article.getStatus() == null ? -1 : article.getStatus()) {
            case 0 -> "草稿";
            case 1 -> "已发布";
            case 2 -> "已隐藏";
            default -> "未知";
        };

        StringBuilder sb = new StringBuilder("当前文章分析：\n");
        sb.append("- 标题：《").append(limit(article.getTitle(), TITLE_MAX)).append("》\n");
        sb.append("- 状态：").append(statusLabel).append('\n');
        sb.append("- 字数：约 ").append(content.length()).append(" 字\n");
        sb.append("- 段落数：约 ").append(countParagraphs(content)).append(" 段\n");

        List<String> headings = extractHeadings(content);
        if (headings.isEmpty()) {
            sb.append("- 小标题结构：（无小标题，可能缺乏层次）\n");
        } else {
            sb.append("- 小标题结构：\n");
            for (String heading : headings) {
                sb.append("  - ").append(limit(heading, TITLE_MAX)).append('\n');
            }
        }
        if (article.getSummary() != null && !article.getSummary().isBlank()) {
            sb.append("- 摘要：").append(limit(article.getSummary(), TEXT_MAX)).append('\n');
        }
        String preview = limit(content.replaceAll("\\s+", " ").trim(), PREVIEW_MAX);
        if (!preview.isBlank()) {
            sb.append("- 正文预览：").append(preview).append('\n');
        }
        return sb.toString();
    }

    private int countParagraphs(String content) {
        if (content.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String block : content.split("\\n\\s*\\n")) {
            if (!block.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private List<String> extractHeadings(String content) {
        List<String> headings = new ArrayList<>();
        for (String line : content.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                headings.add(trimmed);
            }
        }
        return headings;
    }

    /**
     * 用户记忆检索：语义记忆 + 情景记忆，按检索词召回。
     */
    private String queryMemory(AgentStepDecision decision, Long userId) {
        String question = text(decision.input(), "question");
        String keyword = text(decision.input(), "keyword");
        String query = question != null ? question : keyword;
        if (query == null || query.isBlank()) {
            query = "写作偏好";
        }

        StringBuilder sb = new StringBuilder("记忆摘要：\n");

        try {
            List<MemoryRagContext> memories = aiMemoryRetrieveService.retrieve(userId, query);
            for (MemoryRagContext memory : memories) {
                sb.append("- 语义记忆(").append(memory.memoryType()).append(")：")
                        .append(limit(memory.content(), TEXT_MAX)).append('\n');
            }
        } catch (Exception e) {
            log.warn("Agent 语义记忆检索失败，userId={}", userId, e);
            sb.append("- 语义记忆检索失败\n");
        }

        try {
            List<EpisodicMemoryRagContext> memories = aiEpisodicMemoryRetrieveService.retrieveForPrompt(
                    userId, blogAiProperties.getProjectKey(), query
            );
            for (EpisodicMemoryRagContext memory : memories) {
                sb.append("- 情景记忆(").append(memory.memoryType()).append(")：")
                        .append(limit(memory.content(), TEXT_MAX)).append('\n');
            }
        } catch (Exception e) {
            log.warn("Agent 情景记忆检索失败，userId={}", userId, e);
            sb.append("- 情景记忆检索失败\n");
        }

        return sb.toString();
    }

    /**
     * 站内文章知识检索（复用现有混合检索 + rerank 链路）。
     */
    private String searchRag(AgentStepDecision decision) {
        String keyword = text(decision.input(), "keyword");
        if (keyword == null || keyword.isBlank()) {
            return "站内检索缺少关键词，无法检索。";
        }

        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord(keyword);

        ArticleRagSearchResult result = articleRagSearchService.search(keyword, intent);
        if (result.contexts() == null || result.contexts().isEmpty()) {
            return "站内没有检索到与「" + keyword + "」相关的文章知识。";
        }

        StringBuilder sb = new StringBuilder("站内文章知识检索结果（")
                .append(result.strategy()).append("）：\n");
        int index = 1;
        for (ArticleRagContext context : result.contexts()) {
            sb.append(index++).append(". 《").append(limit(context.title(), TITLE_MAX))
                    .append("》(").append(context.articleId()).append(")：")
                    .append(limit(context.content(), TEXT_MAX)).append('\n');
        }
        return sb.toString();
    }

    private String text(Map<String, Object> input, String key) {
        if (input == null) {
            return null;
        }
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String limit(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
