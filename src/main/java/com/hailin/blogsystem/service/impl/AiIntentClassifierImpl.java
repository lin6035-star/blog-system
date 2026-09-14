package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.AiIntentClassifier;
import com.hailin.blogsystem.service.LearningPlansService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiIntentClassifierImpl implements AiIntentClassifier
{
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final LearningPlansService learningPlansService;
    private final AiJudgeModelSupport aiJudgeModelSupport;

    @Override  //判断分类，用户想要干什么，如果是要生成文章，单独拆一个实现类专门实现
    public AiIntent classify(String message, PageContextDTO pageContextDTO, Long userId){
        /*
         * 第一段：不带计划列表。
         *
         * 绝大多数消息（闲聊、文章问答、概念问答、创建计划）到此为止——
         * 不查库、user prompt 里也没有计划列表（普通聊天不该背这个包袱）。
         */
        AiIntent aiIntent = callOnce(message, pageContextDTO, List.of());
        if (aiIntent == null || aiIntent.getIntent() == null || aiIntent.getIntent().isBlank()) {
            log.warn("AI意图识别失败，降级为普通聊天");
            return generalChat();
        }

        /*
         * 第二段：只有「意图是调整/攻坚已有计划」且「用户原话点了计划名」才查列表 + 重新调用。
         *
         * 为什么是重新调用而不是事后补一个序号：计划定位要模型「看着选项选」，
         * 后端字符串匹配在同分并列时会退化（"C++" 曾被切成 "c"，与「C语言系统学习计划」同分，
         * 结果弹卡让用户重选——实测踩过）。宁可让这一小撮消息多等一次调用，
         * 也不让每条消息都带上计划列表（后者是"普通聊天也塞进 prompt"）。
         */
        List<LearningPlans> plans = List.of();
        if (needsPlanLocating(aiIntent)) {
            plans = loadActivePlans(userId);
            if (!plans.isEmpty()) {
                log.info("意图命中计划定位，二次分类带列表：planRef={}", aiIntent.getLearningPlanRef());
                AiIntent located = callOnce(message, pageContextDTO, plans);
                if (located != null && located.getIntent() != null && !located.getIntent().isBlank()) {
                    aiIntent = located;
                }
            }
        }

        resolveLearningPlanId(aiIntent, plans);
        log.info(
                "AI意图识别结果: intent={}, confidence={}, "
                        + "suggestedAction={}, suggestedWorkflowType={}, "
                        + "risk={}, reason={}, planRef={}, stageRef={}, "
                        + "planIndex={}, planId={}, "
                        + "actionType={}, articleId={}, authorId={}, userId={}, "
                        + "needsThinking={}, needsThinkingReason={}",
                aiIntent.getIntent(),
                aiIntent.getConfidence(),
                aiIntent.getSuggestedAction(),
                aiIntent.getSuggestedWorkflowType(),
                aiIntent.getRisk(),
                aiIntent.getReason(),
                aiIntent.getLearningPlanRef(),
                aiIntent.getLearningStageRef(),
                aiIntent.getLearningPlanIndex(),
                aiIntent.getLearningPlanId(),
                aiIntent.getActionType(),
                aiIntent.getArticleId(),
                aiIntent.getAuthorId(),
                aiIntent.getUserId(),
                aiIntent.getNeedsThinking(),
                aiIntent.getNeedsThinkingReason()
        );

        return aiIntent;
    }

    private String callClassifier(String message, PageContextDTO pageContextDTO, List<LearningPlans> plans, boolean repair) {
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(buildSystemPrompt(repair))
                    .user(buildUserPrompt(message, pageContextDTO, plans))
                    //判断链：配了 judge-model 用强模型（换模型导致判分界线漂移的实测见 AiJudgeModelSupport）
                    .options(aiJudgeModelSupport.applyTo(OpenAiChatOptions.builder()).build())
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("AI意图识别调用失败", e);
            return null;
        }
    }

    /**
     * 一次完整分类：调用 → 解析 → 解析失败则 repair 重试一次 → 仍失败返回 null。
     *
     * repair 的理由：模型偶尔把规则/注释文字混进输出，一次失败就降级会让
     * 「点名单任务」这类诉求静默退化成无能力聊天。
     */
    private AiIntent callOnce(String message, PageContextDTO pageContextDTO, List<LearningPlans> plans) {
        String json = callClassifier(message, pageContextDTO, plans, false);
        AiIntent intent = json == null ? null : tryParse(json);
        if (intent != null || json == null) {
            return intent;
        }
        String repaired = callClassifier(message, pageContextDTO, plans, true);
        if (repaired == null) {
            return null;
        }
        AiIntent repairedIntent = tryParse(repaired);
        if (repairedIntent != null) {
            log.warn("AI意图识别 repair 成功：首次输出混入非 JSON 文字，已恢复");
        }
        return repairedIntent;
    }

    /**
     * 是否需要"带计划列表二次分类"——即这条消息要不要付出计划列表的代价。
     *
     * 判据：意图是「调整 / 攻坚已有计划」**且**用户原话点了计划名（learningPlanRef 非空）。
     * 任一不满足就不查库、不带列表：
     * - 闲聊 / 文章 / 概念问答 → 与计划无关
     * - LEARNING_PLAN（新建计划）→ 不涉及已有计划
     * - 没点名计划（"帮我调整一下计划"）→ 给了列表也选不出，交给下游追问
     */
    private boolean needsPlanLocating(AiIntent intent) {
        String intentName = intent.getIntent();
        if (!"LEARNING_PROGRESS".equals(intentName) && !"LEARNING_ASSIST".equals(intentName)) {
            return false;
        }
        String planRef = intent.getLearningPlanRef();
        return planRef != null && !planRef.isBlank();
    }

    //该用户的 ACTIVE 计划（分类器只做选择，不创建/修改）。查库失败不阻断分类——退化为按原话摘录。
    //只在"点名了计划"的二次分类里调用（needsPlanLocating），普通聊天不经过这里。
    private List<LearningPlans> loadActivePlans(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            return learningPlansService.listActiveByUserCached(userId);
        } catch (Exception e) {
            log.warn("分类器加载用户学习计划失败，本次不注入计划列表，退化为按原话摘录", e);
            return List.of();
        }
    }

    /**
     * 把模型选中的序号映射为权威 planId（后端独占写权限）。
     *
     * 模型不直接输出 ID：雪花 ID 19 位，模型抄写容易少位/错位；序号短且可校验。
     * 越界或缺失一律丢弃（learningPlanId 保持 null），由调用方落回关键词匹配兜底——后端绝不猜。
     *
     * public static 是为了可单测：这里的两条保障（清空防伪造 / 越界丢弃）是安全边界，
     * 被"优化"掉就是漏洞，必须有测试锁住。
     */
    public static void resolveLearningPlanId(AiIntent aiIntent, List<LearningPlans> plans) {
        // 先清空：即便模型幻觉出 learningPlanId 字段，也不采信——这个字段只能由后端写
        aiIntent.setLearningPlanId(null);
        Integer index = aiIntent.getLearningPlanIndex();
        if (index == null || index < 1 || index > plans.size()) {
            return;
        }
        aiIntent.setLearningPlanId(String.valueOf(plans.get(index - 1).getId()));
    }

    private AiIntent tryParse(String json) {
        try {
            return objectMapper.readValue(cleanJson(json), AiIntent.class);
        } catch (Exception e) {
            log.warn("意图 JSON 解析失败，准备重试: {}", snippet(json));
            return null;
        }
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    private String buildSystemPrompt(boolean repair){
        if (repair) {
            return """
                    你是意图识别器。你上一次的输出不是合法 JSON——混入了注释、规则文字或额外文本。
                    现在重新输出：只能输出一个 JSON 对象，直接以 { 开始、以 } 结束。
                    禁止输出任何解释、注释、规则说明、markdown 代码块标记。
                    字段与第一次要求完全一致，拿不准的字段输出 null。
                    """;
        }
        return """
                你是意图识别器，只能输出 JSON，不能回答用户。
                
                                可选 intent：
                                - GENERAL_CHAT
                                - USER_PROFILE_INSIGHT
                                - ARTICLE_ACTION
                                - NAVIGATE
                                - EDITOR_ACTION
                                - CREATE_ARTICLE_WORKFLOW
                                - OPTIMIZE_ARTICLE_WORKFLOW
                                - ARTICLE_DETAIL_QA
                                - ARTICLE_SEARCH
                                - LEARNING_PLAN_QUERY
                                - LEARNING_PROGRESS
                                - LEARNING_ASSIST
                                - LEARNING_AGENT
                                - ARTICLE_AGENT

                                当用户只是问普通技术问题、闲聊、解释概念时，输出 GENERAL_CHAT。
                
                                当 pageType 是 profile 或 public-profile，且用户询问这个人/自己/我的主页、主要信息、发过什么文章、点赞收藏评论情况时，输出 USER_PROFILE_INSIGHT，并填写 userId（从页面上下文中获取）。
                
                                当 pageType 是 article-detail，且用户要求点赞、取消点赞、收藏、取消收藏、关注作者、取消关注作者、分享、复制链接、跳到评论区时，输出 ARTICLE_ACTION，并填写 actionType。
                                当 pageType 是 article-detail，且用户询问“这篇文章讲了什么”“总结这篇文章”“这篇文章重点是什么”“这篇文章里的某个内容是什么意思”“分析当前文章”时，输出 ARTICLE_DETAIL_QA。
                                当 pageType 是 article-detail，且用户要求回到顶部、回到文章开头、滚动到顶部时，输出 ARTICLE_ACTION，actionType=scrollToTop。
                                注意区分：“看看开头”“先看开头再看结尾”是**阅读文章内容**，不是滚动动作；
                                只有明确要求“回到 / 滚到顶部或开头”才是 scrollToTop。阅读请求按内容意图判
                                （单个目标 -> ARTICLE_DETAIL_QA，多个目标 -> ARTICLE_AGENT）。
                                
                                ARTICLE_DETAIL_QA = 对一篇具体文章的内容问答、评价或比较
                                （讲了什么 / 总结 / 文中概念解释 / 追问文中观点 /
                                某一节写得好不好 / 对比某两节的写法），不需要 actionType。
                                判定不依赖页面（V3.9 跨页指代）：在任意页面或无页面上下文的对话里，
                                用户明确近指一篇本对话中已出现/刚聊过的文章问内容
                                （“这篇文章”“那篇”“刚刚那篇”“你刚才说的那篇”）时，
                                同样输出 ARTICLE_DETAIL_QA：

                                - intent=ARTICLE_DETAIL_QA
                                - suggestedAction=CHAT
                                - suggestedWorkflowType=null

                                后端按 pageContext 文章 → 本会话最近聊过的文章 的顺序定位目标；
                                articleId 允许为空（跨页取不到就不填，禁止编造或猜测 articleId）。
                                区分（务必遵守）：
                                - 无文章指代的纯概念问（“Redis 缓存是什么”“Java 怎么学”）-> GENERAL_CHAT
                                - 找站内有没有某类文章（“有没有/推荐 XX 文章”）-> ARTICLE_SEARCH
                                - 用标题/主题词指某篇文章的内容问答（“Redis 缓存那篇讲了什么”）-> 仍判 ARTICLE_DETAIL_QA：
                                后端按 页面文章 → 本会话最近聊过的文章 定位，定位不到会追问澄清，
                                不要因为不确定目标文章就降级 GENERAL_CHAT
                                - 评价 / 比较文章已有内容（“这一节写得怎么样”“这两节哪个写得好”
                                  “检查一下某一节”）-> 仍判 ARTICLE_DETAIL_QA：
                                这是对已有内容的评价，不是要你改动它；
                                不要因为出现“怎么样 / 检查 / 分析”这类词就升级为 ARTICLE_AGENT
                                - 一句话里包含两个或以上不同的处理动作 / 分析角度 / 待办项
                                  （“先…再…”“从 A 和 B 两方面”“分别看 X 和 Y”）
                                  -> ARTICLE_AGENT。
                                  这条**优先于**上面的“分析 / 总结 / 评价当前文章 -> ARTICLE_DETAIL_QA”：
                                  只要句子里有多个并列的子目标，即使每个子目标单独看都像 QA，也判 ARTICLE_AGENT
                                - 明确要求结合长期记忆 / 写作偏好 -> ARTICLE_AGENT
                                 
                                actionType 可选：likeArticle, unlikeArticle, favoriteArticle, unfavoriteArticle, followAuthor, unfollowAuthor, copyArticleLink, scrollToComments, saveDraft, publish, fillArticle, scrollToTop。

                                当用户要求跳转页面、打开页面、进入页面、回到某页时，输出 NAVIGATE。
                                当 pageType 是 article-detail，且用户要求查看该作者或者进入该作者的主页时，输出 NAVIGATE。
                                
                                 target 可选：
                                 - home：首页
                                 - profile：个人中心/我的主页
                                 - editor：写文章/新建文章/编辑器
                                 - drafts：草稿箱/我的草稿
                                 - hotRank：热门排行/排行榜
                                 - article：文章详情页，需要 param=文章ID
                                 - userProfile：用户主页/作者主页，需要 param=用户ID/authorId
                                
                                当用户询问站内是否有某类文章、查找文章、推荐相关文章、有没有关于某主题的博客时，输出 ARTICLE_SEARCH。
                
                                从用户问题中提取最核心的搜索关键词，填写 keyword。
                                keyword 要短，优先保留技术名词，不要包含“有没有”“文章”“博客”“推荐”等泛词。
               
                                例如：
                                “有没有关于 Java 注解的文章” -> keyword=Java注解
                                “找一下 Redis 缓存相关的博客” -> keyword=Redis 缓存
                                “有没有讲 Spring Boot 配置的内容” -> keyword=Spring Boot 配置
                                
                                当用户明确要求查询、搜索、查找、推荐站内文章时：

                                - intent=ARTICLE_SEARCH
                                - suggestedAction=CHAT
                                - suggestedWorkflowType=null

                                ARTICLE_SEARCH 由后端执行文章 RAG 检索，
                                不是让模型直接决定是否调用文章 Tool。
                                keyword 只能提取用户原话中的核心技术词，
                                不能猜测用户没有提到的主题。

                                如果用户说”打开第 X 篇文章”，但没有明确文章ID，不要猜ID，输出 GENERAL_CHAT。
                                
                                当用户要求帮他写一篇关于特定主题的文章、博客、博文、草稿、大纲时：

                                - intent=CREATE_ARTICLE_WORKFLOW
                                - suggestedAction=WORKFLOW
                                - suggestedWorkflowType=CREATE_ARTICLE

                                这类请求不要求用户必须在编辑器页面。
                                如果用户指定了主题：

                                - topic：主题的规范化名称
                                - topicEvidence：用户原话中连续出现的、代表主题的原始片段

                                topicEvidence 必须逐字摘录用户原话，不能改写、总结、翻译或补充。
                                后端会验证 topicEvidence 是否真实出现在用户原始消息中。

                                用户没有明确给出主题时：

                                - topic=null
                                - topicEvidence=null

                                禁止猜测、联想或编造主题。
                                “文章”“博客”“博文”“草稿”“大纲”“内容”不能作为有效主题。

                                当用户要求“继续学什么 / 下一步学什么 / 今天学什么 /
                                帮我安排今天的学习 / 学习有点乱帮我理一下思路”时：
                                - intent=LEARNING_AGENT
                                - suggestedAction=AGENT
                                - suggestedWorkflowType=null

                                当用户要求**分析、评价自己已有学习计划的内容**（只是要分析结论或建议，
                                **不要求动手改**），例如"帮我分析我的 X 学习计划，看看有没有问题"
                                "我的 X 计划安排得合理吗""这个计划写得怎么样"时：
                                - intent=LEARNING_AGENT
                                - suggestedAction=AGENT
                                - suggestedWorkflowType=null
                                Agent 会读取计划内容后给出分析与改进建议（不改任何数据）。
                                三条分界（易混，务必按判据选）：
                                - 问"有哪些计划 / 进行到哪了" → LEARNING_PLAN_QUERY（查状态）
                                - 问"好不好 / 有没有问题 / 哪里不合理" → 本类（要分析结论）
                                - **要求动手改计划本身** → LEARNING_PROGRESS（要执行），不是本类。
                                  典型措辞：优化 / 调整 / 改一下 / 压缩 / 重排 + 计划，
                                  含"**那就按照你的建议优化一下我的计划**"这类承接上轮建议、要动手执行的说法；
                                  "改"出现在"要你改"里就是 PROGRESS，只有"问你该怎么改"才是本类。
                                句子里出现"我的X学习计划"不代表就是查询。

                                当用户明确要求勾选 / 取消勾选学习计划中的任务、
                                标记任务完成 / 取消完成时（V2.4 受控写动作），
                                或把已有任务改名 / 重命名（V3.3 受控写改名，新名来自用户原话）时：
                                - intent=LEARNING_AGENT
                                - suggestedAction=AGENT
                                - suggestedWorkflowType=null
                                Agent 会先查询计划定位任务，产出写动作提案，用户确认后才执行。

                                这些是 Agent Runtime 学习建议 / 受控写提案请求，
                                不是制定计划，也不是调整计划。
                                Agent 只查询学习进度、记忆、站内知识后给出建议或提案，
                                不会直接创建或修改任何数据（写动作必须用户确认）。

                                边界例子（务必区分）：
                                - “我想学 Redis，帮我制定学习计划” -> LEARNING_PLAN
                                - “帮我调整第二阶段” -> LEARNING_PROGRESS
                                - “Redis 跳表我不懂，帮我拆小一点” -> LEARNING_ASSIST
                                - “帮我分析我的 Agent 学习计划，看看有没有问题” -> LEARNING_AGENT（分析计划内容，不是 PLAN_QUERY）
                                - “我今天继续学 Redis，帮我安排今天学什么” -> LEARNING_AGENT
                                - “我下一步学什么” -> LEARNING_AGENT
                                - “我最近学 Redis 有点乱，帮我理一下” -> LEARNING_AGENT
                                - “帮我把 Redis 计划的缓存击穿任务勾掉 / 标记完成” -> LEARNING_AGENT（受控写提案）
                                - “把那个任务取消勾选” -> LEARNING_AGENT（受控写提案）
                                - “给 Redis 计划第二阶段加一个'缓存雪崩防护'任务” -> LEARNING_AGENT（受控写追加，任务名用户已给）
                                - “把 Redis 计划的缓存击穿任务改名为缓存击穿防护” -> LEARNING_AGENT（受控写改名，旧名新名都来自用户原话）
                                - “第二阶段太难，帮我加几个练习任务” -> LEARNING_ASSIST（任务名要 AI 生成）
                                - “我有几个学习规划” -> LEARNING_PLAN_QUERY（查询已有计划，不是 Agent）
                                - “Redis 是什么” -> GENERAL_CHAT

                                对 LEARNING_AGENT：learningPlanRef 只能摘录用户原话中的计划名称或关键词，
                                不允许猜测、编造计划名或 ID。

                                Agent 建议字段规则：
                                - suggestedAction：只能是 CHAT / TOOL / WORKFLOW / CTA / AGENT
                                - suggestedWorkflowType：只能是 CREATE_ARTICLE / OPTIMIZE_ARTICLE / LEARNING_PLAN / LEARNING_PROGRESS / LEARNING_ASSIST，没有对应 Workflow 时填 null
                                - risk：只能是 LOW / MEDIUM / HIGH
                                - reason：一句简短的分类理由，不要写长篇解释

                                这些字段只是给后端 Planner 的建议，不能代替后端最终裁决。

                                suggestedAction 规则：

                                - 普通知识问答、闲聊、概念解释：CHAT
                                - 站内文章查询、推荐相关文章：CHAT
                                - 当前文章内容问答、总结当前文章：CHAT
                                - 查询已有学习计划、阶段、进度：TOOL

                                - 明确要求创建文章、写文章、写博客、生成文章大纲：
                                  WORKFLOW，suggestedWorkflowType=CREATE_ARTICLE

                                - 明确要求优化、润色、改写、重写当前文章：
                                  WORKFLOW，suggestedWorkflowType=OPTIMIZE_ARTICLE
                                  注意：只要求“对某几节 / 某几处提改进建议”，或“改进”只是
                                  多个子目标之一（如“先总结，再挑一段讲讲怎么改进”）-> ARTICLE_AGENT，
                                  不要升级为 OPTIMIZE_ARTICLE

                                - 明确制定学习计划：
                                  WORKFLOW，suggestedWorkflowType=LEARNING_PLAN

                                - 明确调整已有学习计划或进度：
                                  WORKFLOW，suggestedWorkflowType=LEARNING_PROGRESS

                                - 明确表达学习计划、阶段或任务中的困难，请求拆解、解释，
                                  或请求帮忙加练习任务但未给具体任务名（任务内容由 AI 生成）：
                                  WORKFLOW，suggestedWorkflowType=LEARNING_ASSIST

                                - 用户点名具体任务要求加入某计划/阶段
                                  （任务名来自用户原话，如"给 Redis 计划第二阶段加一个'缓存雪崩防护'任务"）：
                                  AGENT（intent=LEARNING_AGENT，受控写追加，不是 WORKFLOW）

                                - 用户点名已有任务并给出新名要求改名 / 重命名
                                  （如"把缓存击穿改成缓存击穿防护"）：
                                  AGENT（intent=LEARNING_AGENT，受控写改名，不是 WORKFLOW）

                                - 继续学习安排、下一步学什么、理一下学习思路：
                                  AGENT（intent=LEARNING_AGENT）

                                - 文章页对当前文章的模糊优化诉求（无明确指令）：
                                  AGENT（intent=ARTICLE_AGENT）
                                  注意：单纯评价 / 比较已有内容（“这一节写得怎么样”“对比这两节”）
                                  是 ARTICLE_DETAIL_QA，不是优化诉求

                                - 需求模糊、可能需要用户进一步说明：
                                  CTA

                                needsThinking 字段规则（V3 通用思考模式，只对 GENERAL_CHAT 生效）：
                                - needsThinking 含义：这句话是否需要结合用户记忆、前文状态或站内上下文，先查再答
                                - 只对 GENERAL_CHAT 有意义：其他 intent 一律输出 false
                                - 默认 false：只有明确的上下文依赖信号才判 true，吃不准就 false
                                - 判定标准是语义，不是关键词：判断这句话是否指向前文、
                                  是否依赖用户记忆/历史状态、是否存在信息缺口
                                - 判 true 的例子：
                                  - "你觉得我现在这套方案还有什么问题"（依赖前文方案）
                                  - "结合我现在的情况，给个建议"（依赖用户记忆/状态）
                                  - "刚才那个方向还能继续吗"（指向前文）
                                  - "帮我总结一下我最近的学习进展"（依赖记忆与历史）
                                - 判 false 的例子：
                                  - "什么是缓存穿透"（纯概念问答，不依赖上下文）
                                  - "你好"（寒暄）
                                  - 明确指令或已有专属 intent 的诉求（如"帮我优化这篇文章"、学习类指令）
                                - needsThinkingReason：判定依据的一句话，仅用于后端抽样排查，
                                  不进入任何用户可见界面，不影响路由结果
                                - 两个字段都必须输出，needsThinking 不能省略，
                                  needsThinkingReason 没有依据时输出 null

                                risk 规则：
                                - 用户意图清晰，且自动进入学习流程的错误代价较低：LOW
                                - 用户表达有歧义、计划对象不清楚、可能需要澄清：MEDIUM
                                - 涉及高风险写入、越权、敏感操作或无法确认目标：HIGH

                                注意：
                                - confidence 表示"意图判断有多确定"
                                - risk 表示"判断错误后的代价"
                                - confidence 和 risk 是两个独立字段

                                当用户表达学习某技术的想法或规划诉求
                                （例如"我想学XX""不知道怎么开始""帮我规划学习路线"）时：
                                - intent=LEARNING_PLAN
                                - suggestedAction=WORKFLOW
                                - suggestedWorkflowType=LEARNING_PLAN

                                是否真正创建 Workflow 由后端 Planner 决定。
                                分类器只负责识别学习意图，不直接创建 Workflow。

                                如果用户只是询问知识、了解概念
                                （例如"Redis 是什么"），输出：
                                - intent=GENERAL_CHAT
                                - suggestedAction=CHAT
                                - suggestedWorkflowType=null

                                当用户询问或查看自己已有的学习计划、阶段或进度
                                （例如"我有几个学习规划""我的 MySQL 学习计划学到哪了"）时：
                                - intent=LEARNING_PLAN_QUERY
                                - suggestedAction=TOOL
                                - suggestedWorkflowType=null
                                边界：本类只处理「有哪些计划 / 某个计划进行到哪了」这类**列表与状态查询**。
                                用户要求对计划**内容**做分析、评价、挑问题、给改进建议时，
                                即使句子里出现"我的X学习计划"，也**不是**本类 → 走 LEARNING_AGENT（见上）。

                                当用户要求调整、压缩、重排、延长、缩短、
                                重新生成已有学习计划或进度时：
                                - intent=LEARNING_PROGRESS
                                - suggestedAction=WORKFLOW
                                - suggestedWorkflowType=LEARNING_PROGRESS
                                （本节优先于 LEARNING_AGENT 的"分析计划"：**只要用户是要你动手改计划**，
                                哪怕话里带"建议 / 分析"字样（如"按你刚才的建议优化一下我的计划"），
                                也归本类——分析是手段，执行才是诉求。）
                                （注意：仅把某个已有任务改名 / 重命名且新名来自用户原话 →
                                不是 LEARNING_PROGRESS，走 LEARNING_AGENT 受控写改名提案；
                                “调整、压缩、重排”整段/整体结构仍属本类）

                                当用户表达某个学习计划、阶段或任务难度过高、
                                卡住、理解困难，并希望解释、拆解，
                                或请求帮忙加辅助任务但未给出具体任务名（任务内容由 AI 生成）时：
                                - intent=LEARNING_ASSIST
                                - suggestedAction=WORKFLOW
                                - suggestedWorkflowType=LEARNING_ASSIST
                                （用户已点名具体任务要求直接加入计划 → 不是 LEARNING_ASSIST，
                                走 LEARNING_AGENT 受控写追加）

                                例如：
                                "我感觉微服务计划中的阶段二挺难的"
                                "第二阶段有点啃不动，帮我拆小一点"
                                "Redis 计划里的缓存击穿看不懂"
                                "第二阶段太难，帮我加几个练习任务"（任务名由 AI 生成）
                                （对照："给 Redis 计划第二阶段加一个'缓存雪崩防护'任务"——
                                任务名用户已给 → LEARNING_AGENT 受控写追加，不是 LEARNING_ASSIST）

                                对 LEARNING_PROGRESS / LEARNING_ASSIST：
                                - learningPlanRef 只能摘录用户原话中的计划名称或关键词
                                - learningStageRef 只能摘录用户原话中的阶段或任务名称
                                - 不允许猜测、编造计划名、阶段名或 ID

                                learningPlanIndex（该用户的学习计划列表会在用户问题前给出，按序号编号）：
                                - 当用户指代了列表里的某个计划时，输出它在列表中的序号（从 1 开始）。
                                  简称、别名、口语说法都算指代，例如用户说"c++ 计划"或"C加加那个"，
                                  而列表里有「C++ 系统学习与工程化实践计划」，就输出它的序号
                                - 用户没提到任何计划，或者你无法确定指代哪一个 → 输出 null
                                - 列表里没有用户说的那个计划 → 输出 null，不要退而求其次挑一个最接近的
                                - 序号必须真实存在于列表中，禁止编造；不要输出计划 ID
                                - 只对 LEARNING_ASSIST / LEARNING_PROGRESS 输出；其他意图一律输出 null
                                - learningPlanIndex 字段必须输出，没有值时输出 null

                                对模糊学习表达：
                                例如"我最近学 Redis 有点乱"
                                可以输出：
                                - intent=GENERAL_CHAT 或相关学习意图
                                - suggestedAction=CTA
                                - risk=MEDIUM
                                - reason=需要进一步确认用户是想咨询知识还是调整学习计划

                                对所有意图输出 confidence，范围为 0 到 1，表示本次意图判断置信度。
                                如果用户指定了分类，填写 categoryName，默认为随笔分类。
                                如果用户有额外要求，填写 requirements。
                                不要在意图识别阶段生成完整正文。

                                当 pageType 是 article-detail，
                                且用户要求优化、改进、润色、重写当前文章时：

                                - intent=OPTIMIZE_ARTICLE_WORKFLOW
                                - suggestedAction=WORKFLOW
                                - suggestedWorkflowType=OPTIMIZE_ARTICLE
                                - articleId 从页面上下文中获取

                                例外：当用户原话给出完整新标题要求改名（"把标题改成 XX" / "标题改名为 XX"）时，
                                不是 OPTIMIZE——新标题已由用户指定、无需 AI 生成改法，这是受控写改标题，判 ARTICLE_AGENT（见下）。
                                例外（V3.7）：用户要求隐藏 / 公开 / 取消隐藏当前文章（状态操作，不涉及内容）时，
                                也不是 OPTIMIZE——状态操作不走内容优化 Workflow，判 ARTICLE_AGENT 受控写（见下）。

                                这类请求不需要用户在编辑器页面。
                                如果缺少 articleId，输出 GENERAL_CHAT。
                                不要在意图识别阶段生成优化方案。
                                是否真正启动 Workflow，由后端 Planner 决定。

                                当 pageType 是 article-detail，
                                且用户对当前文章表达模糊的优化诉求（没有明确改哪里、怎么改）时：

                                - intent=ARTICLE_AGENT
                                - suggestedAction=AGENT
                                - suggestedWorkflowType=null
                                - articleId 从页面上下文中获取

                                模糊诉求例子：
                                - "帮我看看这篇文章" / "这篇文章写得怎么样"
                                - "这篇文章还能怎么改" / "感觉写得不太好，帮我分析一下"
                                - "帮我看看这篇文章有什么问题"（没有具体指令）

                                例外（V3.4 受控写改标题）：当用户原话给出完整新标题要求改当前文章标题
                                （"把标题改成 XX" / "标题改名为 XX"，XX 完整出现在用户原话里）时，
                                判 ARTICLE_AGENT（Agent 提案改标题 → 后端弹确认卡 → 用户确认后才执行，
                                后端只改标题不动其他内容），不要判 OPTIMIZE_ARTICLE_WORKFLOW。

                                例外（V3.7 受控写可见性）：当用户要求隐藏 / 公开 / 取消隐藏当前文章
                                （"把这篇隐藏了" / "设为隐藏" / "把这篇公开" / "取消隐藏"）时，
                                判 ARTICLE_AGENT（Agent 提案隐藏/公开 → 后端弹确认卡 → 确认后执行），
                                不要判 OPTIMIZE_ARTICLE_WORKFLOW——状态操作不涉及内容修改，不属于文章优化。

                                例外（V3.8 跨页文章操作）：当用户不在文章详情页（首页/其他页），
                                但原话里明确要对某篇文章做受控操作（改标题/改名/隐藏/公开/取消隐藏）时，
                                不管用近指（"帮我把刚刚那篇文章隐藏了"）还是标题/主题词指代
                                （"把我 Redis 那篇隐藏了""把 JVM 那篇公开"），都判 ARTICLE_AGENT（suggestedAction=AGENT），
                                articleId 置空不填、不要编造——后端用本会话最近聊过的文章兜底定位并校验归属，
                                定位不到会追问澄清，不要因为不确定目标文章就降级 GENERAL_CHAT。
                                只有原话没有文章操作/内容问答意图时（如"Redis 那篇写得不错"），维持 GENERAL_CHAT。

                                明确优化指令（不判 ARTICLE_AGENT，判 OPTIMIZE_ARTICLE_WORKFLOW）：
                                - "把第二段删掉" / "帮我把标题改短" / "把开头重写得更吸引人"
                                - "帮我优化这篇文章"（明确要求优化）
                                注意："帮我把标题改短"这类没有给出具体新标题的仍走 Workflow，与上面的改名例外不冲突。

                                articleId 只从页面上下文获取；页面上下文缺失时按上方 V3.8 例外处理
                                （受控操作仍判 ARTICLE_AGENT 并留空 articleId，后端会话锚兜底）。
                                Agent 只查询文章、记忆、站内知识后给出优化建议或建议启动优化流程，
                                不会直接修改文章（写动作必须用户确认）。

                                当用户说自己在做博客项目、文章项目、Agent 项目，并咨询技术方案、架构选型、实现方式时，输出 GENERAL_CHAT。
                                 不要因为问题中出现“博客”“文章”“RAG”就输出 ARTICLE_SEARCH。
                                 只有用户明确要求“找文章 / 搜博客 / 推荐站内文章 / 有没有相关文章”时，才输出 ARTICLE_SEARCH。

                                输出示例：

                                普通聊天（直达）：
                                {"intent":"GENERAL_CHAT","confidence":0.98,"learningPlanRef":null,"learningStageRef":null,"actionType":null,"articleId":null,"userId":null,"content":null,"target":null,"param":null,"topic":null,"topicEvidence":null,"categoryName":null,"requirements":null,"suggestedAction":"CHAT","suggestedWorkflowType":null,"risk":"LOW","reason":"普通知识问答","needsThinking":false,"needsThinkingReason":null}

                                普通聊天（需思考，进通用 Agent）：
                                {"intent":"GENERAL_CHAT","confidence":0.85,"learningPlanRef":null,"learningStageRef":null,"actionType":null,"articleId":null,"userId":null,"content":null,"target":null,"param":null,"topic":null,"topicEvidence":null,"categoryName":null,"requirements":null,"suggestedAction":"CHAT","suggestedWorkflowType":null,"risk":"LOW","reason":"依赖前文方案状态","needsThinking":true,"needsThinkingReason":"问题指向前文讨论的方案，需结合记忆与上下文先查再答"}

                                文章创作：
                                {"intent":"CREATE_ARTICLE_WORKFLOW","confidence":0.95,"learningPlanRef":null,"learningStageRef":null,"actionType":null,"articleId":null,"userId":null,"content":null,"target":null,"param":null,"topic":"Redis缓存","topicEvidence":"Redis 缓存","categoryName":null,"requirements":null,"suggestedAction":"WORKFLOW","suggestedWorkflowType":"CREATE_ARTICLE","risk":"LOW","reason":"用户明确要求创建文章","needsThinking":false,"needsThinkingReason":null}

                                文章优化：
                                {"intent":"OPTIMIZE_ARTICLE_WORKFLOW","confidence":0.95,"learningPlanRef":null,"learningStageRef":null,"actionType":null,"articleId":"12","userId":null,"content":null,"target":null,"param":null,"topic":null,"topicEvidence":null,"categoryName":null,"requirements":null,"suggestedAction":"WORKFLOW","suggestedWorkflowType":"OPTIMIZE_ARTICLE","risk":"MEDIUM","reason":"用户明确要求优化当前文章","needsThinking":false,"needsThinkingReason":null}

                                文章页模糊优化（Agent 建议）：
                                {"intent":"ARTICLE_AGENT","confidence":0.9,"learningPlanRef":null,"learningStageRef":null,"actionType":null,"articleId":"12","userId":null,"content":null,"target":null,"param":null,"topic":null,"topicEvidence":null,"categoryName":null,"requirements":null,"suggestedAction":"AGENT","suggestedWorkflowType":null,"risk":"LOW","reason":"用户对当前文章表达模糊优化诉求","needsThinking":false,"needsThinkingReason":null}

                                文章查询：
                                {"intent":"ARTICLE_SEARCH","confidence":0.96,"learningPlanRef":null,"learningStageRef":null,"actionType":null,"articleId":null,"userId":null,"content":null,"target":null,"param":null,"topic":null,"topicEvidence":null,"categoryName":null,"requirements":null,"suggestedAction":"CHAT","suggestedWorkflowType":null,"risk":"LOW","reason":"用户明确查询站内文章","needsThinking":false,"needsThinkingReason":null}

                                topicEvidence 字段必须输出。
                                没有明确主题时必须输出 null。
                                所有字段都必须输出。
                                没有值的字段必须输出 null。
                                suggestedAction、suggestedWorkflowType、risk、reason 不能省略。

                                页面动作规则：

                                - ARTICLE_ACTION、NAVIGATE、EDITOR_ACTION 都属于客户端页面动作。
                                - 这三类意图的 suggestedAction 固定输出 CHAT。
                                - 这三类意图的 suggestedWorkflowType 必须输出 null。
                                - 是否真正执行，由后端 AgentPlannerSupport 决定。
                                - 后端不会因为 actionType、target 缺失而猜测字段。

                                当用户在写文章页面要求保存草稿、存草稿、保存文章时，输出 EDITOR_ACTION，actionType=saveDraft。
                
                                当用户在写文章页面要求发布文章、发布这篇、直接发布时，输出 EDITOR_ACTION，actionType=publish。
                
                                只有 pageType 是 editor-new 或 editor-edit 时，才输出 EDITOR_ACTION。
                                如果用户不在编辑器页面却要求保存或发布，仍然输出 EDITOR_ACTION，让后端/前端提示用户先进入编辑器。
                                
                                只输出纯 JSON，禁止 markdown、代码块或任何额外文本。
                                规则、示例、注释这些说明性文字一律禁止出现在输出里。
                                输出必须直接以 { 开始，不能有任何前缀文字。
               """;
    }

    private String buildUserPrompt(String message, PageContextDTO pageContext, List<LearningPlans> plans) {
        StringBuilder sb = new StringBuilder();
        sb.append("页面上下文：\n");

        if (pageContext == null) {
            sb.append("无\n");
        } else {
            sb.append("pageType=").append(pageContext.getPageType()).append("\n");
            sb.append("path=").append(pageContext.getPath()).append("\n");
            sb.append("articleId=").append(pageContext.getArticleId()).append("\n");
            sb.append("authorId=").append(pageContext.getAuthorId()).append("\n");
            sb.append("userId=").append(pageContext.getUserId()).append("\n");
        }

        // 该用户真实的 ACTIVE 计划（仅标题 + 序号，不给 ID）：模型据此输出 learningPlanIndex。
        // 只有"点名了计划"的消息才会带列表上来（见 needsPlanLocating）——普通聊天不含这一段。
        if (plans != null && !plans.isEmpty()) {
            sb.append("\n该用户的学习计划列表（按顺序编号）：\n");
            for (int i = 0; i < plans.size(); i++) {
                sb.append(i + 1).append(". ").append(plans.get(i).getTitle()).append("\n");
            }
        } else {
            // 显式说明"这次没有列表"：避免模型硬编序号，同时提醒它计划名/阶段名照常按原话摘录
            sb.append("\n（本次未提供学习计划列表，learningPlanIndex 输出 null；"
                    + "learningPlanRef / learningStageRef 仍按用户原话摘录）\n");
        }

        sb.append("\n用户问题：\n").append(message);
        return sb.toString();
    }

    private String cleanJson(String raw){
        if (raw == null) {
            return "{}";
        }
        return raw
                .replace("```json", "")
                .replace("```", "")
                .trim();
    }

    private AiIntent generalChat() {
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(0.0);
        intent.setSuggestedAction("CHAT");
        intent.setSuggestedWorkflowType(null);
        intent.setRisk("LOW");
        intent.setReason("分类器调用失败，降级为普通聊天");
        intent.setNeedsThinking(false);
        intent.setNeedsThinkingReason("分类器调用失败，降级为普通聊天");
        return intent;
    }

}
