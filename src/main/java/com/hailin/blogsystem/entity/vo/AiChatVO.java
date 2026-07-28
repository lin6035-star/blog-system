package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.AiEditorCommand;
import com.hailin.blogsystem.entity.AiNavigateCommand;
import lombok.Data;

@Data
public class AiChatVO {
    private AiSessionVO session;
    private AiMessageVO userMessage;
    private AiMessageVO assistantMessage;
    private AiNavigateCommand navigate;
    private AiEditorCommand editorAction;
}
