package com.hailin.blogsystem.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口耗时统计。
 *
 * <p><b>和 traceId 的关系</b>：traceId（V4⑥）能串起一次请求的全部日志，但**没有耗时维度**——
 * 「这个接口慢不慢、哪一段是长尾」只能人肉翻日志。压测和排查需要的正是这个维度。
 *
 * <p><b>三个设计点</b>：
 * <ul>
 *   <li><b>开始时间存 request attribute，不存实例字段</b>——拦截器是单例，
 *       实例字段会被并发请求互相覆盖，量出来的耗时会串到别的请求上。</li>
 *   <li><b>SSE 流单独标记、不参与慢请求判定</b>：流式响应的 {@code afterCompletion}
 *       在**流结束**时才触发，耗时天然是「这个流持续了多久」（几十秒很正常）。
 *       把它混进慢请求统计，会把阈值彻底带偏、WARN 里全是正常的长回答。
 *       但它本身值得记（INFO 级），因为「流挂了多久」是排查 AI 问题的一手数据。</li>
 *   <li><b>只有慢请求打 WARN</b>，其余 DEBUG——高 QPS 下逐条 INFO 会把日志刷爆。</li>
 * </ul>
 *
 * <p>注册位置见 {@code WebMvcConfig}：**必须排在拦截器链最前面**，
 * 否则量到的是「扣掉前面拦截器耗时」的片段，而不是端到端时间。
 */
@Component
@Slf4j
public class RequestTimingInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = "blog.reqStartMs";

    /** 慢请求阈值：超过就打 WARN。默认 1 秒。 */
    private final long slowThresholdMs;

    public RequestTimingInterceptor(
            @Value("${blog.observability.slow-request-ms:1000}") long slowThresholdMs
    ) {
        this.slowThresholdMs = slowThresholdMs;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        request.setAttribute(START_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object start = request.getAttribute(START_ATTR);
        if (!(start instanceof Long startMs)) {
            // 没经过 preHandle（理论上不会发生）——不猜耗时，直接跳过
            return;
        }

        long costMs = System.currentTimeMillis() - startMs;
        String method = request.getMethod();
        String uri = request.getRequestURI();
        int status = response.getStatus();

        if (isStreaming(response)) {
            // 流式：耗时就是流的持续时间，单独记，不参与慢请求判定
            log.info("[PERF-HTTP] stream_end method={} uri={} durationMs={} status={}",
                    method, uri, costMs, status);
            return;
        }

        if (costMs >= slowThresholdMs) {
            log.warn("[PERF-HTTP] slow method={} uri={} costMs={} status={} thresholdMs={}",
                    method, uri, costMs, status, slowThresholdMs);
        } else {
            log.debug("[PERF-HTTP] method={} uri={} costMs={} status={}",
                    method, uri, costMs, status);
        }
    }

    /** 流式响应（SSE）：耗时语义与普通请求不同，见类注释。 */
    private boolean isStreaming(HttpServletResponse response) {
        String contentType = response.getContentType();
        return contentType != null && contentType.contains("text/event-stream");
    }
}
