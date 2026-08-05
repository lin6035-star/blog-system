package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.AiUserMemoryCandidate;
import com.hailin.blogsystem.entity.dto.AiUserMemorySaveDTO;
import com.hailin.blogsystem.entity.vo.AiUserMemoryCandidateVO;
import com.hailin.blogsystem.mapper.AiUserMemoryCandidateMapper;
import com.hailin.blogsystem.service.AiUserMemoryCandidateService;
import com.hailin.blogsystem.service.AiUserMemoryService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiUserMemoryCandidateServiceImpl
        extends ServiceImpl<AiUserMemoryCandidateMapper, AiUserMemoryCandidate>
        implements AiUserMemoryCandidateService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String SOURCE_USER_CONFIRMED = "USER_CONFIRMED";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_MERGE = "MERGE";
    private static final String ACTION_IGNORE = "IGNORE";

    private final AiUserMemoryService aiUserMemoryService;

    @Override  //查当前登录用户待确认记忆
    public List<AiUserMemoryCandidateVO> listPendingCandidates() {
        Long userId = requireLogin();

        return lambdaQuery()
                .eq(AiUserMemoryCandidate::getUserId, userId)
                .eq(AiUserMemoryCandidate::getStatus, STATUS_PENDING)
                .orderByDesc(AiUserMemoryCandidate::getImportance)
                .orderByDesc(AiUserMemoryCandidate::getCreatedAt)
                .list()
                .stream()
                .map(AiUserMemoryCandidateVO::from)
                .toList();
    }

    @Override  //确认候选，写入正式记忆
    @Transactional
    public void confirmCandidate(Long id) {
        confirmCandidate(id, null);
    }

    @Override
    @Transactional
    public void confirmCandidate(Long id, String overrideContent) {
        Long userId = requireLogin();

        AiUserMemoryCandidate candidate = getPendingCandidate(id,userId);

        // 如果用户编辑了内容，先更新候选记录
        if (overrideContent != null && !overrideContent.isBlank()) {
            candidate.setContent(overrideContent.trim());
        }

        AiUserMemorySaveDTO dto = new AiUserMemorySaveDTO();
        dto.setMemoryType(candidate.getMemoryType());
        dto.setMemoryKey(candidate.getMemoryKey());
        dto.setContent(resolveConfirmedContent(candidate));
        dto.setSource(SOURCE_USER_CONFIRMED);
        dto.setConfidence(candidate.getConfidence());
        dto.setImportance(candidate.getImportance());

        // IGNORE 表示用户确认”这条不进入正式记忆”；UPDATE / MERGE 必须有明确目标记忆
        if (ACTION_IGNORE.equals(candidate.getCandidateAction())) {
            // 不写正式记忆
        } else if ((ACTION_UPDATE.equals(candidate.getCandidateAction())
                || ACTION_MERGE.equals(candidate.getCandidateAction()))
                && candidate.getTargetMemoryId() != null) {
            aiUserMemoryService.updateMemoryById(candidate.getTargetMemoryId(), dto);
        } else {
            aiUserMemoryService.saveOrUpdateMemory(dto);
        }

        //插入正式记忆后，修改状态
        candidate.setStatus(STATUS_CONFIRMED);
        candidate.setDecidedAt(LocalDateTime.now());
        candidate.setUpdatedAt(LocalDateTime.now());
        updateById(candidate);
    }

    @Override  //拒绝候选，只改状态
    @Transactional
    public void rejectCandidate(Long id) {

        Long userId = requireLogin();

        AiUserMemoryCandidate candidate = getPendingCandidate(id, userId);
        candidate.setStatus(STATUS_REJECTED);
        candidate.setDecidedAt(LocalDateTime.now());
        candidate.setUpdatedAt(LocalDateTime.now());

        updateById(candidate);
    }

    @Override
    @Transactional  //清理过期或者明确被拒绝的候选记忆
    public int deleteExpiredCandidates(int retentionDays) {
        int safeRetentionDays = Math.max(retentionDays, 1);
        LocalDateTime expireBefore = LocalDateTime.now().minusDays(safeRetentionDays);

        return baseMapper.delete(new LambdaQueryWrapper<AiUserMemoryCandidate>()
                .and(wrapper -> wrapper
                        .eq(AiUserMemoryCandidate::getStatus, STATUS_PENDING)
                        .lt(AiUserMemoryCandidate::getCreatedAt, expireBefore)
                        .or()
                        .eq(AiUserMemoryCandidate::getStatus, STATUS_REJECTED)
                        .lt(AiUserMemoryCandidate::getDecidedAt, expireBefore)
                ));
    }


    private AiUserMemoryCandidate getPendingCandidate(Long id,Long userId){
        if (id == null) {
            throw new IllegalArgumentException("候选记忆ID不能为空");
        }

        AiUserMemoryCandidate candidate = lambdaQuery()
                .eq(AiUserMemoryCandidate::getId, id)
                .eq(AiUserMemoryCandidate::getUserId, userId)
                .eq(AiUserMemoryCandidate::getStatus, STATUS_PENDING)
                .one();

        if (candidate == null) {
            throw new IllegalArgumentException("候选记忆不存在");
        }

        return candidate;
    }

    private String resolveConfirmedContent(AiUserMemoryCandidate candidate) {
        if (ACTION_MERGE.equals(candidate.getCandidateAction())
                && candidate.getMergedContent() != null
                && !candidate.getMergedContent().isBlank()) {
            return candidate.getMergedContent().trim();
        }
        return candidate.getContent();
    }

    private Long requireLogin() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }
}
