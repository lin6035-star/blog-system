package com.hailin.blogsystem.ai.trace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingAgentDecisionTraceSink
        implements AgentDecisionTraceSink {

    @Override
    public void record(AgentDecisionTrace trace) {
        if (trace == null) {
            return;
        }

        log.info(
                "AgentDecisionTrace requestId={}, userId={}, sessionId={}, "
                        + "message={}, intent={}, confidence={}, "
                        + "suggestedAction={}, suggestedWorkflow={}, risk={}, "
                        + "action={}, workflowType={}, toolName={}, "
                        + "ruleHits={}, reason={}",
                trace.getRequestId(),
                trace.getUserId(),
                trace.getSessionId(),
                trace.getMessagePreview(),
                trace.getIntent(),
                trace.getConfidence(),
                trace.getSuggestedAction(),
                trace.getSuggestedWorkflowType(),
                trace.getRisk(),
                trace.getAction(),
                trace.getWorkflowType(),
                trace.getToolName(),
                trace.getRuleHits(),
                trace.getReason()
        );
    }
}
