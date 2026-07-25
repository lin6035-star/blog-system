package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.dto.AiCreateSessionDTO;
import com.hailin.blogsystem.entity.vo.AiSessionVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiSessionServiceImpl extends ServiceImpl<AiSessionMapper, AiSessions>
implements AiSessionService {

    private final AiMessageMapper aiMessageMapper;
    private static final String DEFAULT_TITLE = "新对话";

    @Override  //1.新建会话
    public AiSessionVO createSession(AiCreateSessionDTO aiCreateSessionDTO) {
        Long userId = UserContext.get();

        String title = DEFAULT_TITLE;
        if (aiCreateSessionDTO != null && aiCreateSessionDTO.getTitle() != null) {
            String trimmedTitle = aiCreateSessionDTO.getTitle().trim();
            if (!trimmedTitle.isEmpty()) {
                title = trimmedTitle;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        AiSessions aiSessions = new AiSessions();
        aiSessions.setUserId(userId);
        aiSessions.setTitle(title);
        aiSessions.setCreatedAt(now);
        aiSessions.setUpdatedAt(now);

        save(aiSessions);

        return AiSessionVO.from(aiSessions);
    }


    @Override  //2.查询该用户的历史会话
    public PageVO<AiSessionVO> getHistoricalSession(Long page, Long pageSize) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        Page<AiSessions> pageResult = lambdaQuery()
                .eq(AiSessions::getUserId, userId)
                .orderByDesc(AiSessions::getUpdatedAt)
                .page(new Page<>(page, pageSize));

        List<AiSessionVO> list = pageResult.getRecords()
                .stream()
                .map(AiSessionVO::from)
                .toList();

        return new PageVO<>(
                list,
                pageResult.getTotal(),
                page,
                pageSize
        );
    }

    @Override  //3.删除会话
    @Transactional
    public void deleteSession(String id) {
        Long userId = UserContext.get();
        Long sessionId = Long.valueOf(id);

        boolean exists = lambdaQuery()
                .eq(AiSessions::getId, sessionId)
                .eq(AiSessions::getUserId, userId)
                .exists();

        if(exists == false){
            throw new IllegalArgumentException("该会话不存在");
        }

        aiMessageMapper.delete(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId,sessionId));
        removeById(sessionId);

    }

}
