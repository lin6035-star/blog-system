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
            log.info("AI意图识别结果: intent={}, actionType={}, articleId={}, authorId={}, userId={}",
                    aiIntent.getIntent(), aiIntent.getActionType(), aiIntent.getArticleId(), aiIntent.getAuthorId(), aiIntent.getUserId());

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
                
                                当用户只是问普通技术问题、闲聊、解释概念时，输出 GENERAL_CHAT。
                
                                当 pageType 是 profile 或 public-profile，且用户询问这个人/自己/我的主页、主要信息、发过什么文章、点赞收藏评论情况时，输出 USER_PROFILE_INSIGHT，并填写 userId（从页面上下文中获取）。
                
                                当 pageType 是 article-detail，且用户要求点赞、取消点赞、收藏、取消收藏、关注作者、取消关注作者、分享、复制链接、跳到评论区时，输出 ARTICLE_ACTION，并填写 actionType。
                                当 pageType 是 article-detail，且用户询问“这篇文章讲了什么”“总结这篇文章”“这篇文章重点是什么”“这篇文章里的某个内容是什么意思”“分析当前文章”时，输出 ARTICLE_DETAIL_QA。
                                当 pageType 是 article-detail，且用户要求回到顶部、回到文章开头、滚动到顶部时，输出 ARTICLE_ACTION，actionType=scrollToTop。       
                                
                                ARTICLE_DETAIL_QA 不需要 actionType。
                                如果缺少 articleId，输出 GENERAL_CHAT。
                                 
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
                                
                                如果用户说“打开第 X 篇文章”，但没有明确文章ID，不要猜ID，输出 GENERAL_CHAT。
                                
                                当用户要求帮他写一篇关于特定主题的文章、博客、博文、草稿、大纲时，输出 CREATE_ARTICLE_WORKFLOW。
                                这类请求不要求用户必须在编辑器页面。
                                如果用户指定了主题，填写 topic。
                                topic 只能从用户原话中提取；用户没有明确给出主题时，topic 必须为 null，禁止猜测、联想或编造主题。
                                如果用户指定了分类，填写 categoryName，默认为随笔分类。
                                如果用户有额外要求，填写 requirements。
                                不要在意图识别阶段生成完整正文。

                                当 pageType 是 article-detail，且用户要求优化、改进、润色、重写当前文章时，输出 OPTIMIZE_ARTICLE_WORKFLOW，并填写 articleId（从页面上下文中获取）。
                                这类请求不需要用户在编辑器页面。
                                如果缺少 articleId，输出 GENERAL_CHAT。
                                不要在意图识别阶段生成优化方案。

                                输出格式：
                                {"intent":"GENERAL_CHAT","actionType":null,"articleId":null,"userId":null,"content":null,"target":null,"param":null,"topic":null,"categoryName":null,"requirements":null}
               
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
        return intent;
    }

}
