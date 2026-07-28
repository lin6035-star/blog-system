package com.hailin.blogsystem.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiEditorCommand {

    private String type;
    private String title;
    private String categoryName;
    private String summary;
    private String content;

}
