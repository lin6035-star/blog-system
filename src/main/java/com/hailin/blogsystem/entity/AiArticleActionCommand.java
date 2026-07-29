package com.hailin.blogsystem.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
//command是后端发给前端执行的命令
public class AiArticleActionCommand {
    private String type;  // likeArticle / unlikeArticle / favoriteArticle / unfavoriteArticle
    private String articleId;
    private String content;

}
