package com.hailin.blogsystem.task;

import com.hailin.blogsystem.service.AiUserMemoryCandidateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiUserMemoryCandidateCleanupTask {

    private static final int RETENTION_DAYS = 30;

    private final AiUserMemoryCandidateService aiUserMemoryCandidateService;

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredCandidates() {
        int deletedCount = aiUserMemoryCandidateService.deleteExpiredCandidates(RETENTION_DAYS);
        if (deletedCount > 0) {
            log.info("清理过期记忆候选完成，retentionDays={}, deletedCount={}", RETENTION_DAYS, deletedCount);
        }
    }
}
