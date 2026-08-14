package com.hailin.blogsystem.ai.workflow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * WorkflowHandler 注册中心：自动收集所有 WorkflowHandler，按 workflowType 路由。
 * approve / reject / retry 通过它找到对应 Handler，避免 Service 硬编码某个具体 Handler。
 */
@Component
public class WorkflowHandlerRegistry {

    private final Map<String, WorkflowHandler> handlerMap;

    public WorkflowHandlerRegistry(List<WorkflowHandler> handlers) {
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(
                        WorkflowHandler::workflowType,
                        Function.identity()
                ));
    }

    public WorkflowHandler get(String workflowType) {
        WorkflowHandler handler = handlerMap.get(workflowType);
        if (handler == null) {
            throw new IllegalArgumentException("不支持的工作流类型: " + workflowType);
        }
        return handler;
    }
}
