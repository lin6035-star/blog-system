package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;

public interface AiIntentClassifier {
    /**
     * @param userId 当前登录用户 ID（游客为 null）——用于给分类器注入该用户真实的学习计划列表，
     *               让它「从列表里选」而不是「凭原话猜」。注意不能用 pageContext.userId 代替：
     *               那个字段在他人主页场景下是「被访问用户」，不是登录用户。
     */
    AiIntent classify(String message, PageContextDTO pageContext, Long userId);
}
