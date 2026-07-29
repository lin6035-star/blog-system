package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.AiEditorCommand;
import com.hailin.blogsystem.entity.dto.AiIntent;

//关于编辑器填充的结构化意图（草稿生成器）
public interface AiEditorDraftGenerator {
    AiEditorCommand generateDraft(String message, AiIntent intent);
}
