package com.hailin.blogsystem.utils;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

public final class MdcContext {

    private MdcContext() {
    }

    public record LogContext(Map<String, String> mdc, TraceContext traceContext) {
    }

    public static Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    public static Map<String, String> captureOr(Map<String, String> fallback) {
        Map<String, String> current = capture();
        return current == null || current.isEmpty() ? fallback : current;
    }

    public static void restore(Map<String, String> contextMap) {
        if (contextMap == null || contextMap.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(contextMap);
        }
    }

    public static Runnable wrap(Map<String, String> contextMap, Runnable runnable) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            restore(contextMap);
            try {
                runnable.run();
            } finally {
                restore(previous);
            }
        };
    }

    public static LogContext captureWithTrace(Tracer tracer, Map<String, String> fallback) {
        Map<String, String> snapshot = captureOr(fallback);
        Span span = tracer == null ? null : tracer.currentSpan();
        TraceContext context = span == null ? null : span.context();
        if ((snapshot == null || snapshot.get("traceId") == null)
                && context != null && context.traceId() != null && !context.traceId().isBlank()) {
            Map<String, String> merged = snapshot == null ? new HashMap<>() : new HashMap<>(snapshot);
            merged.put("traceId", context.traceId());
            if (context.spanId() != null && !context.spanId().isBlank()) {
                merged.put("spanId", context.spanId());
            }
            snapshot = merged;
        }
        return new LogContext(snapshot, context);
    }

    public static Runnable wrap(Tracer tracer, LogContext logContext, Runnable runnable) {
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            CurrentTraceContext.Scope traceScope = null;
            if (tracer != null && logContext != null && logContext.traceContext() != null) {
                traceScope = tracer.currentTraceContext().maybeScope(logContext.traceContext());
            }
            restore(logContext == null ? null : logContext.mdc());
            try {
                runnable.run();
            } finally {
                if (traceScope != null) {
                    traceScope.close();
                }
                restore(previousMdc);
            }
        };
    }
}
