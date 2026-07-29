package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiEditorCommand;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.service.AiEditorDraftGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiEditorDraftGeneratorImpl implements AiEditorDraftGenerator {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;


    @Override
    public AiEditorCommand generateDraft(String message, AiIntent intent) {
        try{
            String json = chatClientBuilder.build()
                    .prompt()
                    .system(
                            """
                                    你是博客草稿生成器，只能输出 JSON。
                                    不要输出 markdown 代码块。
                                    JSON 字段：
                                    type 固定为 fillArticle
                                    title：文章标题
                                    categoryName：分类名称，不确定则为随笔
                                    summary：文章摘要
                                    content：Markdown 格式正文
                                    """
                    )
                    .user(buildUserPrompt(message, intent))
                    .call()
                    .content();

            String cleanJson = cleanJson(json);
            AiEditorCommand command = objectMapper.readValue(cleanJson,AiEditorCommand.class);
            command.setType("fillArticle");
            return command;
        }
        catch (Exception e){
            log.warn("AI文章草稿生成失败",e);
            return null;
        }
    }


    private String buildUserPrompt(String message,AiIntent intent){
        return """
                用户原始需求：
                %s

                识别出的主题：
                %s

                指定分类：
                %s

                额外要求：
                %s
                """.formatted(
                        message,
                intent.getTopic(),
                intent.getCategoryName(),
                intent.getRequirements()
        );
    }

    private String cleanJson(String raw){
        if (raw == null) return "{}";
        return raw.replace("```json", "").replace("```", "").trim();
    }
}
