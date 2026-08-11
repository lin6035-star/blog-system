package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.entity.AiEditorCommand;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AiWorkflowAdvanceResult {

    private AiWorkflowRun run;
    /**
     * 需要前端执行的编辑器动作。
     *
     * 只有 Workflow 走到 FILL_ARTICLE 时才会有值。
     */
    private AiEditorCommand editorAction;

    public static AiWorkflowAdvanceResult of(AiWorkflowRun run){
        return new AiWorkflowAdvanceResult(run,null);
    }

    public static AiWorkflowAdvanceResult withEditorAction(
            AiWorkflowRun run,
            AiEditorCommand editorAction
    ){
        return new AiWorkflowAdvanceResult(run, editorAction);
    }
}
