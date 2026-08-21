package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.AiConversationSummaries;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AiConversationSummaryMapper extends BaseMapper<AiConversationSummaries> {

    @Update("""
            UPDATE ai_conversation_summaries
            SET summary = #{summary},
                summary_json = #{summaryJson},
                covered_until_message_id = #{coveredUntilMessageId},
                covered_message_count = #{coveredMessageCount},
                version = version + 1,
                last_compressed_at = #{now},
                compressing = 0,
                updated_at = #{now}
            WHERE session_id = #{sessionId}
              AND version = #{oldVersion}
            """)
    int updateWithVersion(
            @Param("sessionId") Long sessionId,
            @Param("oldVersion") Integer oldVersion,
            @Param("summary") String summary,
            @Param("summaryJson") String summaryJson,
            @Param("coveredUntilMessageId") Long coveredUntilMessageId,
            @Param("coveredMessageCount") Integer coveredMessageCount,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_conversation_summaries
            SET compressing = 1,
                updated_at = #{now}
            WHERE session_id = #{sessionId}
              AND compressing = 0
            """)
    int tryMarkCompressing(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_conversation_summaries
            SET compressing = 0,
                updated_at = #{now}
            WHERE session_id = #{sessionId}
            """)
    int clearCompressing(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);
}