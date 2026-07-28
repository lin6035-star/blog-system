package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.service.AiConversationMemoryService;
import com.hailin.blogsystem.utils.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class AiConversationMemoryServiceImpl implements AiConversationMemoryService {

    @Autowired
    private AiMessageMapper aiMessageMapper;
    @Autowired
    private AiSessionMapper aiSessionMapper;

    @Override
    public List<AiMessages> getRecentMessages(Long sessionId, int maxMessages) {
        Long userId = UserContext.get();
        if(userId == null){
            throw new IllegalArgumentException("请先登录");
        }
        AiSessions aiSession = aiSessionMapper.selectById(sessionId);
        if(aiSession == null){
            throw new IllegalArgumentException("该会话不存在");
        }
        if(!userId.equals(aiSession.getUserId())){
            throw new IllegalArgumentException("暂时没有权限");
        }
        LambdaQueryWrapper<AiMessages> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(AiMessages::getSessionId,sessionId)
                .orderByDesc(AiMessages::getCreatedAt)
                .last("LIMIT " + maxMessages);

        List<AiMessages> aiMessages = aiMessageMapper.selectList(lambdaQueryWrapper);
        Collections.reverse(aiMessages);

        return aiMessages;
    }
}
