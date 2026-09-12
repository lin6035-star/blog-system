package com.hailin.blogsystem;

import com.hailin.blogsystem.utils.MdcContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcContextTests {

    private static final String TEST_HOOK_KEY = "mdc-context-test-hook";

    @AfterEach
    void tearDown() {
        MDC.clear();
        Schedulers.resetOnScheduleHook(TEST_HOOK_KEY);
    }

    @Test
    void wrapRestoresSnapshotDuringRunnableAndRestoresPreviousContextAfterwards() {
        Map<String, String> snapshot = Map.of(
                "traceId", "trace-1",
                "spanId", "span-1"
        );
        MDC.put("traceId", "previous-trace");

        AtomicReference<String> traceInside = new AtomicReference<>();
        AtomicReference<String> spanInside = new AtomicReference<>();

        MdcContext.wrap(snapshot, () -> {
            traceInside.set(MDC.get("traceId"));
            spanInside.set(MDC.get("spanId"));
        }).run();

        assertThat(traceInside.get()).isEqualTo("trace-1");
        assertThat(spanInside.get()).isEqualTo("span-1");
        assertThat(MDC.get("traceId")).isEqualTo("previous-trace");
        assertThat(MDC.get("spanId")).isNull();
    }

    @Test
    void wrapClearsContextDuringRunnableWhenSnapshotIsEmpty() {
        MDC.put("traceId", "previous-trace");

        AtomicReference<String> traceInside = new AtomicReference<>("not-run");

        MdcContext.wrap(Map.of(), () -> traceInside.set(MDC.get("traceId"))).run();

        assertThat(traceInside.get()).isNull();
        assertThat(MDC.get("traceId")).isEqualTo("previous-trace");
    }

    @Test
    void wrapLogContextRestoresMdcDuringRunnableAndRestoresPreviousContextAfterwards() {
        MdcContext.LogContext snapshot = new MdcContext.LogContext(
                Map.of(
                        "traceId", "trace-2",
                        "spanId", "span-2",
                        "workflowRunId", "run-1"
                ),
                null
        );
        MDC.put("traceId", "previous-trace");

        AtomicReference<String> traceInside = new AtomicReference<>();
        AtomicReference<String> spanInside = new AtomicReference<>();
        AtomicReference<String> workflowInside = new AtomicReference<>();

        MdcContext.wrap(null, snapshot, () -> {
            traceInside.set(MDC.get("traceId"));
            spanInside.set(MDC.get("spanId"));
            workflowInside.set(MDC.get("workflowRunId"));
        }).run();

        assertThat(traceInside.get()).isEqualTo("trace-2");
        assertThat(spanInside.get()).isEqualTo("span-2");
        assertThat(workflowInside.get()).isEqualTo("run-1");
        assertThat(MDC.get("traceId")).isEqualTo("previous-trace");
        assertThat(MDC.get("spanId")).isNull();
        assertThat(MDC.get("workflowRunId")).isNull();
    }

    @Test
    void captureOrPrefersCurrentContextWhenAvailable() {
        Map<String, String> earlySnapshot = Map.of("traceId", "early");
        MDC.put("traceId", "current");

        assertThat(MdcContext.captureOr(earlySnapshot))
                .containsEntry("traceId", "current");
    }

    @Test
    void captureOrFallsBackWhenCurrentContextIsEmpty() {
        Map<String, String> earlySnapshot = Map.of("traceId", "early");

        assertThat(MdcContext.captureOr(earlySnapshot))
                .containsEntry("traceId", "early");
    }

    @Test
    void explicitWrapStillRestoresContextWhenReactorHookCapturedEmptyContext() throws Exception {
        Schedulers.onScheduleHook(TEST_HOOK_KEY, runnable -> {
            Map<String, String> hookSnapshot = MDC.getCopyOfContextMap();
            return () -> {
                if (hookSnapshot == null || hookSnapshot.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(hookSnapshot);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        });

        Map<String, String> explicitSnapshot = Map.of("traceId", "explicit");
        MDC.clear();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> traceInside = new AtomicReference<>();

        Schedulers.boundedElastic().schedule(MdcContext.wrap(explicitSnapshot, () -> {
            traceInside.set(MDC.get("traceId"));
            latch.countDown();
        }));

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(traceInside.get()).isEqualTo("explicit");
    }
}
