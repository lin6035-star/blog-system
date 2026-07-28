package com.hailin.blogsystem.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPrompt {
    /** 完整拼接好的 prompt 文本（含历史 + 页面上下文 + 用户问题） */
    private String finalPromptContext;
    //原始用户消息
    private String userMessage;
}
