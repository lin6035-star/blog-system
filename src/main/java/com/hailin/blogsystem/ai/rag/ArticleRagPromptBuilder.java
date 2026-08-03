package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
//RAG的增强环节  把检索结果和原始问题拼成一个完整 Prompt
public class ArticleRagPromptBuilder {

    public String buildPrompt(String originalPrompt, List<ArticleRagContext> contexts){
        if (contexts == null || contexts.isEmpty()) {
            return originalPrompt;
        }

        StringBuilder builder = new StringBuilder();

        //原始用户问题
        builder.append(originalPrompt == null ? "" : originalPrompt.trim());

        builder.append("\n\n");
        builder.append("## 站内文章知识库检索结果\n");
        builder.append("下面是从海林Blog已发布文章中检索到的相关片段。");
        builder.append("只要下面存在片段，就表示站内已经找到了相关文章，不能回答“没有找到相关文章”。");
        builder.append("本轮已经完成站内文章检索，不要再调用 searchPublishedArticles 进行同类搜索。");
        builder.append("回答时必须先告诉用户找到了哪些相关文章，并优先使用文章标题。");
        builder.append("回答中的关键结论后面必须使用来源编号，例如 [1]、[2]。");
        builder.append("来源编号必须对应下面的“来源 [1]”“来源 [2]”，不要使用不存在的编号。");
        builder.append("如果片段内容只能部分回答用户问题，请说明“找到相关文章，但内容只覆盖部分问题”。");
        builder.append("不要编造片段之外的内容。\n");
        builder.append("检索命中片段数：").append(contexts.size()).append("\n");

        for (int i = 0; i < contexts.size(); i++) {
            //拼接每个片段：标题 + 片段序号 + 内容
            ArticleRagContext context = contexts.get(i);

            builder.append("\n");
            builder.append("### 来源 [").append(i + 1).append("]\n");
            builder.append("文章标题：").append(nullToEmpty(context.title())).append("\n");
            builder.append("片段序号：").append(context.chunkIndex() == null ? "" : context.chunkIndex()).append("\n");
            builder.append("片段内容：\n");
            builder.append(nullToEmpty(context.content())).append("\n");
        }

        return builder.toString();
    }

    private String nullToEmpty(String value){
        return value == null ? "" : value;
    }



    /*
    用户问：Redis 能做什么？

        ## 站内文章知识库检索结果
            下面是从海林Blog已发布文章中检索到的相关片段。

        ### 片段 1
            文章标题：Redis 实战入门
            片段序号：0
            片段内容：标题：Redis 实战入门  摘要：本文介绍 Redis 的常见使用场景... Redis 可以用来做缓存、排行榜、点赞状态和浏览量统计...

       ### 片段 2
             ...

            如果用户问题涉及博客已有内容，必须优先基于这些片段回答；如果片段不足以回答，请明确说明站内资料不足，不要编造。

            然后整个 Prompt 发给 LLM，模型就能基于你博客的真实内容回答了。
            */
}
