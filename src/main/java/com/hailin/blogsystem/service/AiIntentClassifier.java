package com.hailin.blogsystem.service;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;

public interface AiIntentClassifier {
    /**
     * @param userId 当前登录用户 ID（游客为 null）——用于给分类器注入该用户真实的学习计划列表，
     *               让它「从列表里选」而不是「凭原话猜」。注意不能用 pageContext.userId 代替：
     *               那个字段在他人主页场景下是「被访问用户」，不是登录用户。
     * @param usage  出参：本次分类的 LLM 用量累加到这里，**含 repair 重试与「点名计划」的二次分类**。
     *               分类器 system prompt 有 500+ 行规则且每条消息都要跑，是「这条消息」成本里
     *               占比不小的一块——不统计就等于漏账。
     *               允许为 null（不需要统计的调用方，如单测里的 mock）。
     */
    AiIntent classify(String message, PageContextDTO pageContext, Long userId,
                      TokenUsageAccumulator usage);
}
