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
        try{
            String json = chatClientBuilder.build()
                    .prompt()
                    .system(buildSystemPrompt())
                    .user(buildUserPrompt(message, pageContextDTO))
                    .call()
                    .content();

            String cleanJson = cleanJson(json);
            AiIntent aiIntent = objectMapper.readValue(cleanJson,AiIntent.class);

            if(aiIntent.getIntent() == null || aiIntent.getIntent().isBlank()){
                return generalChat();
            }
            log.info(
                    "AI意图识别结果: intent={}, confidence={}, "
                            + "suggestedAction={}, suggestedWorkflowType={}, "
                            + "risk={}, reason={}, planRef={}, stageRef={}, "
                            + "actionType={}, articleId={}, authorId={}, userId={}",
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
                    aiIntent.getUserId()
            );

            return aiIntent;
        }
        catch (Exception e){
            log.warn("AI意图识别失败，降级为普通聊天", e);
            return generalChat();
        }
    }

    private String buildSystemPrompt(){
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

                                Agent 建议字段规则：
                                - suggestedAction：只能是 CHAT / TOOL / WORKFLOW / CTA
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

                                - 明确表达学习计划、阶段或任务中的困难，
                                  并请求拆解、解释或增加辅助任务：
                                  WORKFLOW，suggestedWorkflowType=LEARNING_ASSIST

                                - 需求模糊、可能需要用户进一步说明：
                                  CTA

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

                                当用户表达某个学习计划、阶段或任务难度过高、
                                卡住、理解困难，并希望解释、拆解或增加辅助任务时：
                                - intent=LEARNING_ASSIST
                                - suggestedAction=WORKFLOW
                                - suggestedWorkflowType=LEARNING_ASSIST

                                例如：
                                "我感觉微服务计划中的阶段二挺难的"
                                "第二阶段有点啃不动，帮我拆小一点"
                                "Redis 计划里的缓存击穿看不懂"

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
                                
                                当用户说自己在做博客项目、文章项目、Agent 项目，并咨询技术方案、架构选型、实现方式时，输出 GENERAL_CHAT。
                                 不要因为问题中出现“博客”“文章”“RAG”就输出 ARTICLE_SEARCH。
                                 只有用户明确要求“找文章 / 搜博客 / 推荐站内文章 / 有没有相关文章”时，才输出 ARTICLE_SEARCH。

                                输出示例：

                                普通聊天：
                                {"intent":"GENERAL_CHAT","confidence":0.98,"learningPlanRef":null,"learningStageRef":null,"actionType":null,"articleId":null,"userId":null,"content":null,"target":null,"param":null,"topic":null,"topicEvidence":null,"categoryName":null,"requirements":null,"suggestedAction":"CHAT","suggestedWorkflowType":null,"risk":"LOW","reason":"普通知识问答"}

                                文章创作：
                                {"intent":"CREATE_ARTICLE_WORKFLOW","confidence":0.95,"learningPlanRef":null,"learningStageRef":null,"actionType":null,"articleId":null,"userId":null,"content":null,"target":null,"param":null,"topic":"Redis缓存","topicEvidence":"Redis 缓存","categoryName":null,"requirements":null,"suggestedAction":"WORKFLOW","suggestedWorkflowType":"CREATE_ARTICLE","risk":"LOW","reason":"用户明确要求创建文章"}

                                文章优化：
                                {"intent":"OPTIMIZE_ARTICLE_WORKFLOW","confidence":0.95,"learningPlanRef":null,"learningStageRef":null,"actionType":null,"articleId":"12","userId":null,"content":null,"target":null,"param":null,"topic":null,"topicEvidence":null,"categoryName":null,"requirements":null,"suggestedAction":"WORKFLOW","suggestedWorkflowType":"OPTIMIZE_ARTICLE","risk":"MEDIUM","reason":"用户明确要求优化当前文章"}

                                文章查询：
                                {"intent":"ARTICLE_SEARCH","confidence":0.96,"learningPlanRef":null,"learningStageRef":null,"actionType":null,"articleId":null,"userId":null,"content":null,"target":null,"param":null,"topic":null,"topicEvidence":null,"categoryName":null,"requirements":null,"suggestedAction":"CHAT","suggestedWorkflowType":null,"risk":"LOW","reason":"用户明确查询站内文章"}

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
        return intent;
    }

}
