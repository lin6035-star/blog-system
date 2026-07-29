package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class AiIntent {
    private String intent;  //大类，比如 general_chat / user_profile_insight / article_action
    private String actionType;  //细分动作，比如 likeArticle / followAuthor
    private String articleId;  //文章ID
    private String authorId;
    private String userId;
    private String content;  //评论内容,补充文本
    private String target;
    private String param;

    private String topic;  //写作主题
    private String categoryName;  //文章指定类型，默认为随笔
    private String requirements;  //用户补充要求

    private String keyWord;
}
