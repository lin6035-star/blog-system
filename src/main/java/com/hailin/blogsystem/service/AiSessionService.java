package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.dto.AiCreateSessionDTO;
import com.hailin.blogsystem.entity.vo.AiSessionVO;
import com.hailin.blogsystem.entity.vo.PageVO;

public interface AiSessionService extends IService<AiSessions> {
    AiSessionVO createSession(AiCreateSessionDTO aiCreateSessionDTO);

    PageVO<AiSessionVO> getHistoricalSession(Long page, Long pageSize);

    void deleteSession(String id);
}
