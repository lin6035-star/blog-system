package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.workflow.WorkflowActionLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowActionLockTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private WorkflowActionLock workflowActionLock;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        workflowActionLock = new WorkflowActionLock(redisTemplate);
    }

    @Test
    void acquireRejectsWhenSameWorkflowIsAlreadyLocked() {
        when(valueOperations.setIfAbsent(
                anyString(),
                anyString(),
                eq(120L),
                eq(TimeUnit.SECONDS)
        )).thenReturn(false);

        assertThatThrownBy(() -> workflowActionLock.acquireOrThrow(1001L))
                .isInstanceOf(WorkflowActionLock.WorkflowActionBusyException.class)
                .hasMessage("Workflow 正在处理中，请稍后刷新");
    }

    @Test
    void acquireReturnsHandleWhenLockIsAvailable() {
        when(valueOperations.setIfAbsent(
                anyString(),
                anyString(),
                eq(120L),
                eq(TimeUnit.SECONDS)
        )).thenReturn(true);

        WorkflowActionLock.LockHandle handle =
                workflowActionLock.acquireOrThrow(1001L);

        assertThat(handle).isNotNull();
        assertThat(handle.runId()).isEqualTo(1001L);
        assertThat(handle.token()).isNotBlank();
    }
}