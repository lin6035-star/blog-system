package com.hailin.blogsystem.ai.tool;

import com.hailin.blogsystem.entity.AiEditorCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@RequiredArgsConstructor
public class AiEditorTools {
    private final String requestId;
    private final AiToolActionRegistry registry;

    @Tool(description = "为写文章页面生成并填充一篇博客草稿。用户要求帮他写一篇文章、生成博文、起草博客时调用。")
    public String fillArticleDraft(
            @ToolParam(description = "文章标题") String title,
            @ToolParam(description = "文章分类名称，只能使用站内已有分类名，无法确定则默认为随笔") String categoryName,
            @ToolParam(description = "文章摘要") String summary,
            @ToolParam(description = "Markdown 格式文章正文") String content
    )
    {
        AiEditorCommand command = new AiEditorCommand();
        command.setType("fillArticle");
        command.setTitle(title);
        command.setCategoryName(categoryName);
        command.setSummary(summary);
        command.setContent(content);

        registry.setEditor(requestId, command);
        return "已生成文章草稿。";
    }


    @Tool(description = "请求前端保存当前编辑器内容为草稿。用户明确说保存草稿时调用。")
    public String requestSaveDraft() {
        AiEditorCommand command = new AiEditorCommand();
        command.setType("saveDraft");
        registry.setEditor(requestId, command);
        return "已准备保存草稿。";
    }


    @Tool(description = "请求前端发布当前编辑器文章。用户明确说发布文章时调用。")
    public String requestPublish() {
        AiEditorCommand command = new AiEditorCommand();
        command.setType("publish");
        registry.setEditor(requestId, command);
        return "已准备发布文章。";
    }
}
