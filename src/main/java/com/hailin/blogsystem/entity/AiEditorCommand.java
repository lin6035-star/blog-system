package com.hailin.blogsystem.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
//command是后端发给前端执行的命令
public class AiEditorCommand {

    private String type;
    private String title;
    private String categoryName;
    private String summary;
    private String content;
    private Long articleId;

}
