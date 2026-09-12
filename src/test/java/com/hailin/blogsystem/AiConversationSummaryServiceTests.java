package com.hailin.blogsystem;

import com.hailin.blogsystem.mapper.AiConversationSummaryMapper;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.service.impl.AiConversationSummaryServiceImpl;
import com.hailin.blogsystem.utils.UserContext;
import com.hailin.blogsystem.entity.AiSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiConversationSummaryServiceTests {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void statusMarksShortConversationIneligibleWithoutQueryingSummaryRow() {
        AiMessageMapper messageMapper = mock(AiMessageMapper.class);
        AiSessionMapper sessionMapper = mock(AiSessionMapper.class);
        AiConversationSummaryMapper summaryMapper = mock(AiConversationSummaryMapper.class);
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(sessionMapper.selectOne(any())).thenReturn(new AiSessions());
        when(messageMapper.selectCount(any())).thenReturn(10L);

        AiConversationSummaryServiceImpl service = new AiConversationSummaryServiceImpl(
                messageMapper,
                sessionMapper,
                chatClientBuilder,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "baseMapper", summaryMapper);
        UserContext.set(2085218095032741891L);

        var status = service.getSummaryStatus(2098070945928396802L);

        assertThat(status.eligible()).isFalse();
        assertThat(status.compressing()).isFalse();
        assertThat(status.coveredMessageCount()).isZero();
        verify(summaryMapper, never()).selectList(any());
    }

}
