package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentRunInspectionService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.controller.AgentRunDevController;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V4 第一刀：开发者面板门禁测试。
 *
 * 这条通道会给出**未脱敏的原始列**（step 的 inputJson / outputJson、run 的 contextJson），
 * 比用户侧 inspection 更敏感，所以门禁必须 fail-closed：
 * 面板关闭 / 未登录 / 不在白名单 —— 三种情况一律 403，且**不触达数据层**。
 */
class AgentRunDevControllerTests {

    private AgentRunInspectionService inspectionService;
    private BlogAiProperties properties;
    private AgentRunDevController controller;

    @BeforeEach
    void setUp() {
        inspectionService = mock(AgentRunInspectionService.class);
        properties = new BlogAiProperties();
        controller = new AgentRunDevController(inspectionService, properties);

        when(inspectionService.listRuns(any(), any(), any()))
                .thenReturn(new PageVO<>(List.of(), 0L, 1L, 20L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAsWhitelisted() {
        properties.getInspection().setEnabled(true);
        properties.getInspection().setAllowedUserIds(List.of(1L));
        UserContext.set(1L);
    }

    @Test
    void deniedWhenPanelDisabled() {
        properties.getInspection().setEnabled(false);
        properties.getInspection().setAllowedUserIds(List.of(1L));
        UserContext.set(1L);

        var result = controller.list(null, 1L, 20L, null);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).contains("已关闭");
        verify(inspectionService, never()).listRuns(any(), any(), any());
    }

    @Test
    void deniedWhenUserNotInWhitelist() {
        properties.getInspection().setEnabled(true);
        properties.getInspection().setAllowedUserIds(List.of(1L));
        UserContext.set(99L);

        var result = controller.list(null, 1L, 20L, null);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).contains("无权");
        verify(inspectionService, never()).listRuns(any(), any(), any());
    }

    @Test
    void deniedWhenNotLoggedIn() {
        properties.getInspection().setEnabled(true);
        properties.getInspection().setAllowedUserIds(List.of(1L));
        // 不设置 UserContext = 未登录

        Result<?> result = controller.detail(123L);

        assertThat(result.getCode()).isEqualTo(403);
        verify(inspectionService, never()).getDevDetail(any());
    }

    @Test
    void deniedByDefaultBecauseWhitelistIsEmpty() {
        // 配置漏配时 fail-closed：空名单 = 谁都不放行，而不是全放行
        UserContext.set(1L);

        Result<?> result = controller.steps(123L);

        assertThat(result.getCode()).isEqualTo(403);
        verify(inspectionService, never()).listRawSteps(any());
    }

    @Test
    void allowedWhenUserInWhitelist() {
        loginAsWhitelisted();

        var result = controller.list(null, 1L, 20L, null);

        assertThat(result.getCode()).isZero();
        verify(inspectionService).listRuns(null, 1L, 20L);
    }
}
