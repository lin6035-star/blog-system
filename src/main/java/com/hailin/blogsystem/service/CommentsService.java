package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.dto.CommentDTO;
import com.hailin.blogsystem.entity.vo.CommentsVO;
import com.hailin.blogsystem.entity.vo.PageVO;

public interface CommentsService extends IService<ArticleComments> {
    PageVO<CommentsVO> getComments(Long articleId, Long page, Long pageSize, String sort);

    PageVO<CommentsVO> queryMoreComments(Long rootId, Long page, Long pageSize);

    void postComment(Long articleId, CommentDTO commentDTO);

    void deleteComment(Long commentId);
}
