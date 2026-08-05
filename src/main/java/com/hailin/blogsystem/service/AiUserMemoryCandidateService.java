package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.AiUserMemoryCandidate;
import com.hailin.blogsystem.entity.vo.AiUserMemoryCandidateVO;

import java.util.List;

public interface AiUserMemoryCandidateService extends IService<AiUserMemoryCandidate> {

    List<AiUserMemoryCandidateVO> listPendingCandidates();

    void confirmCandidate(Long id);

    void confirmCandidate(Long id, String overrideContent);

    void rejectCandidate(Long id);


    int deleteExpiredCandidates(int retentionDays);
}
