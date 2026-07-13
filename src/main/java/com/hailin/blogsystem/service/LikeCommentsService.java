package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.CommentLikes;

public interface LikeCommentsService extends IService<CommentLikes> {
    void likeComment(Long commentId);

    void cancelLike(Long commentId);
}
