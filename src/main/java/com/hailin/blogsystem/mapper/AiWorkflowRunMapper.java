package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiWorkflowRunMapper extends BaseMapper<AiWorkflowRun> {

    @Update("""
            UPDATE ai_workflow_runs
            SET version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND version = #{version}
            """)
    int claimAction(
            @Param("id") Long id,
            @Param("version") Integer version
    );

}