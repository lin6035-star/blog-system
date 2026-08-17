package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiWorkflowCreateArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningAssistDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningPlanDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningProgressDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowStepLogVO;

import java.util.List;

public interface AiWorkflowRunService extends IService<AiWorkflowRun> {

    AiWorkflowRunVO createArticleWorkflow(AiWorkflowCreateArticleDTO dto);

    AiWorkflowRunVO createArticleWorkflow(AiWorkflowCreateArticleDTO dto, AiWorkflowStepEmitter emitter);

    AiWorkflowRunVO createArticleOptimizeWorkflow(AiWorkflowOptimizeArticleDTO dto);

    AiWorkflowRunVO createArticleOptimizeWorkflow(AiWorkflowOptimizeArticleDTO dto, AiWorkflowStepEmitter emitter);

    AiWorkflowRunVO createLearningPlanWorkflow(AiWorkflowLearningPlanDTO dto);

    AiWorkflowRunVO createLearningPlanWorkflow(AiWorkflowLearningPlanDTO dto, AiWorkflowStepEmitter emitter);

    AiWorkflowRunVO createLearningProgressWorkflow(AiWorkflowLearningProgressDTO dto);

    AiWorkflowRunVO createLearningProgressWorkflow(AiWorkflowLearningProgressDTO dto, AiWorkflowStepEmitter emitter);

    AiWorkflowRunVO createLearningAssistWorkflow(AiWorkflowLearningAssistDTO dto);

    AiWorkflowRunVO createLearningAssistWorkflow(AiWorkflowLearningAssistDTO dto, AiWorkflowStepEmitter emitter);

    AiWorkflowRunVO getWorkflowRun(Long id);

    AiWorkflowRunVO approve(Long id);

    AiWorkflowRunVO reject(Long id, String feedback);

    void cancel(Long id);

    List<AiWorkflowStepLogVO> listStepLogs(Long id);

    AiWorkflowRunVO retry(Long id);

    AiWorkflowRunVO approve(Long id, AiWorkflowStepEmitter emitter);

    AiWorkflowRunVO reject(Long id, String feedback, AiWorkflowStepEmitter emitter);

    AiWorkflowRunVO retry(Long id, AiWorkflowStepEmitter emitter);
}