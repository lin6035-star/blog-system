package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AiWorkflowRunVersionTests {

    @Autowired
    private AiWorkflowRunMapper aiWorkflowRunMapper;

    private Long runId;

    @AfterEach
    void cleanUp() {
        if (runId != null) {
            aiWorkflowRunMapper.deleteById(runId);
        }
    }

    @Test
    void claimActionIncrementsVersionWhenExpectedVersionMatches() {
        AiWorkflowRun run = createRun(0);
        aiWorkflowRunMapper.insert(run);
        runId = run.getId();

        int affectedRows =
                aiWorkflowRunMapper.claimAction(run.getId(), 0);

        AiWorkflowRun saved =
                aiWorkflowRunMapper.selectById(run.getId());

        assertThat(affectedRows).isEqualTo(1);
        assertThat(saved.getVersion()).isEqualTo(1);
    }

    @Test
    void claimActionRejectsStaleVersion() {
        AiWorkflowRun run = createRun(1);
        aiWorkflowRunMapper.insert(run);
        runId = run.getId();

        int affectedRows =
                aiWorkflowRunMapper.claimAction(run.getId(), 0);

        AiWorkflowRun saved =
                aiWorkflowRunMapper.selectById(run.getId());

        assertThat(affectedRows).isZero();
        assertThat(saved.getVersion()).isEqualTo(1);
    }

    private AiWorkflowRun createRun(Integer version) {
        AiWorkflowRun run = new AiWorkflowRun();
        run.setUserId(101L);
        run.setWorkflowType("CREATE_ARTICLE");
        run.setWorkflowVersion("1.0");
        run.setStatus("WAITING_OUTLINE_CONFIRM");
        run.setCurrentStep("GENERATE_OUTLINE");
        run.setContextJson("{}");
        run.setRetryCount(0);
        run.setInputTokens(0);
        run.setOutputTokens(0);
        run.setTotalTokens(0);
        run.setVersion(version);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        return run;
    }
}