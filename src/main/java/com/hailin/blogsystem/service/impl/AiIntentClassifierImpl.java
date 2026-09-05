package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.AiIntentClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiIntentClassifierImpl implements AiIntentClassifier
{
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    @Override  //判断分类，用户想要干什么，如果是要生成文章，单独拆一个实现类专门实现
    public AiIntent classify(String message, PageContextDTO pageContextDTO){
        // 首次调用；JSON 解析失败（模型把规则/注释文字混进输出）→ repair prompt 重试一次，
        // 仍失败才降级普通聊天（原来一次失败直接降级——点名单任务等诉求会静默退化成无能力聊天）
        String json = callClassifier(message, pageContextDTO, false);
        AiIntent aiIntent = json == null ? null : tryParse(json);
        if (aiIntent == null && json != null) {
            String repaired = callClassifier(message, pageContextDTO, true);
            if (repaired != null) {
                aiIntent = tryParse(repaired);
                if (aiIntent != null) {
                    log.warn("AI意图识别 repair 成功：首次输出混入非 JSON 文字，已恢复");
                }
            }
        }
        if (aiIntent == null) {
            log.warn("AI意图识别失败，降级为普通聊天");
            return generalChat();
        }
        if (aiIntent.getIntent() == null || aiIntent.getIntent().isBlank()){
            return generalChat();
        }
        log.info(
                "AI意图识别结果: intent={}, confidence={}, "
                        + "suggestedAction={}, suggestedWorkflowType={}, "
                        + "risk={}, reason={}, planRef={}, stageRef={}, "
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
                aiIntent.getActionType(),
                aiIntent.getArticleId(),
                aiIntent.getAuthorId(),
                aiIntent.getUserId(),
                aiIntent.getNeedsThinking(),
                aiIntent.getNeedsThinkingReason()
        );

        return aiIntent;
    }

    private String callClassifier(String message, PageContextDTO pageContextDTO, boolean repair) {
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(buildSystemPrompt(repair))
                    .user(buildUserPrompt(message, pageContextDTO))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("AI意图识别调用失败", e);
            return null;
        }
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
                                
                                ARTICLE_DETAIL_QA 不需要 actionType。
                                如果缺少 articleId，输出 GENERAL_CHAT。

                                当用户询问当前文章内容、总结当前文章、
                                解释当前文章中的某个概念时：

                                - intent=ARTICLE_DETAIL_QA
                                - suggestedAction=CHAT
                                - suggestedWorkflowType=null

                                后端根据 pageContext.articleId 加载当前文章，
                                并使用 CURRENT_ARTICLE 检索模式。
                                如果没有 articleId，不能猜测文章 ID。
                                 
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

                                当用户要求调整、压缩、重排、延长、缩短、
                                重新生成已有学习计划或进度时：
                                - intent=LEARNING_PROGRESS
                                - suggestedAction=WORKFLOW
                                - suggestedWorkflowType=LEARNING_PROGRESS
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

                                明确优化指令（不判 ARTICLE_AGENT，判 OPTIMIZE_ARTICLE_WORKFLOW）：
                                - "把第二段删掉" / "帮我把标题改短" / "把开头重写得更吸引人"
                                - "帮我优化这篇文章"（明确要求优化）

                                如果缺少 articleId，输出 GENERAL_CHAT。
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

    private String buildUserPrompt(String message,PageContextDTO pageContext){
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
