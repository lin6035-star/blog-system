package com.hailin.blogsystem.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiArticleActionCommand {
    private String type;  // likeArticle / unlikeArticle / favoriteArticle / unfavoriteArticle
    private String articleId;
    private String content;

}
