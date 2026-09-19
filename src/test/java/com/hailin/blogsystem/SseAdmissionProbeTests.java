package com.hailin.blogsystem;

import com.hailin.blogsystem.security.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSE 拒绝行为的实测结论（AI 长任务准入那一刀的实施依据）。
 *
 * <p>设计稿 {@code docs/agent/agent-long-task-admission-design.md} §3.4 的阻塞点是：
 * 「准入拒绝能不能返回 HTTP 429/503，还是只能退化成流内错误？」
 * 本类用真实容器（RANDOM_PORT + JDK HttpClient）把它测死了。
 *
 * <h3>结论</h3>
 * <table>
 *   <tr><th>形状</th><th>结果</th></tr>
 *   <tr><td>返回 Flux <b>之前</b>抛</td><td>429 + JSON ✅</td></tr>
 *   <tr><td>订阅 lambda 内抛，<b>之前没发过任何事件</b></td><td><b>429 + JSON ✅</b></td></tr>
 *   <tr><td>先发一个事件再抛</td><td>200 已提交 → 改不动，客户端只会看到连接中断 ❌</td></tr>
 *   <tr><td>用 {@code sink.error} 而非抛异常</td><td>429 + JSON ✅</td></tr>
 * </table>
 *
 * <p><b>直接后果</b>：只有「先发前置事件」的路径（聊天里创建 Workflow 是
 * {@code Flux.concat(understanding, ...)}）才必须把准入前移；
 * 其余路径原地把 {@code schedule(...)} 换成准入提交即可——不必做前置检查。
 *
 * <h3>踩过的三个坑（都导致过假结论，留档）</h3>
 * <ol>
 *   <li><b>用 MockMvc 测不出来</b>：本类测的是容器的响应提交时机（{@code response.isCommitted()}），
 *       {@code MockHttpServletResponse} 与真实 Tomcat 不保证一致。</li>
 *   <li><b>{@code Accept} 头必须跟前端一致</b>：前端是 {@code fetch(...)}，headers 里只有
 *       {@code Content-Type} 与 {@code Authorization}，<b>没有 Accept</b>，浏览器发默认的
 *       {@code &#42;/&#42;}。这里最初想当然设了 {@code text/event-stream}，测出一堆假的 500——
 *       那个 Accept 下 Spring 找不到能写 {@code Result} JSON 的 converter，
 *       抛 {@code HttpMediaTypeNotAcceptableException}。真实前端根本不走这条分支。</li>
 *   <li><b>不要自己造异常 + 自己写 advice</b>：生产的 {@code GlobalExceptionHandler} 有
 *       {@code @ExceptionHandler(Exception.class)} 兜底，且<b>跨 advice 按 advice 顺序匹配、
 *       不按异常具体度</b>，自造异常会被它抢先，永远只能看到 500。
 *       所以本类复用生产里已有 429 handler 的 {@link RateLimitExceededException}；
 *       这也顺带确认：<b>新的拒绝异常必须加在同一个 {@code GlobalExceptionHandler} 里</b>。</li>
 * </ol>
 *
 * <p>顺带记录一个观察：{@code GlobalExceptionHandler:56} 那个
 * {@code accept.contains("text/event-stream")} 分支，对聊天流是<b>死代码</b>——
 * 前端 fetch 不带这个 Accept。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SseAdmissionProbeTests.ProbeConfig.class)
class SseAdmissionProbeTests {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** ① 前置检查的形状：请求还没进 `Flux` 就拒绝。 */
    @Test
    void rejectionBeforeFluxReturnsHttpStatus() throws Exception {
        assertThat(probe("/probe/reject-before-flux").statusCode()).isEqualTo(429);
    }

    /**
     * ② 原地拒绝的形状：在 {@code Flux.create} 的订阅 lambda 内抛，之前没发过任何事件。
     *
     * <p><b>这是本探针的核心结论</b>——响应此刻尚未提交，状态码改得动。
     */
    @Test
    void rejectionInSubscribeLambdaStillSetsHttpStatus() throws Exception {
        ProbeResult r = probe("/probe/reject-in-subscribe");
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.contentType()).contains("application/json");
        assertThat(r.body()).contains("USER_LIMIT");
    }

    /** ④ 用 {@code sink.error} 而不是抛异常，同样能返回状态码。 */
    @Test
    void sinkErrorAlsoSetsHttpStatus() throws Exception {
        assertThat(probe("/probe/error-in-subscribe").statusCode()).isEqualTo(429);
    }

    /**
     * ③ 已经发过首个事件 → 响应按 200 SSE 提交 → 状态码再也改不动。
     *
     * <p>异常只能表现为「连接中断」：客户端拿到 200 后读流失败。
     * <b>这正是设计稿里"必须发生在任何 SSE 事件之前"的实测依据</b>——
     * 聊天创建 Workflow（{@code Flux.concat(understanding, ...)}）走的就是这条。
     */
    @Test
    void rejectionAfterFirstEventCannotChangeStatusCode() {
        assertThatThrownBy(() -> probe("/probe/reject-after-prefix-event"))
                .isInstanceOf(IOException.class);
    }

    /** ⑤ 对照组：正常流确实是 SSE，确认探针本身没坏。 */
    @Test
    void normalStreamIsSse() throws Exception {
        ProbeResult r = probe("/probe/normal");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.contentType()).contains("text/event-stream");
    }

    // ---------- 探针基础设施 ----------

    private record ProbeResult(int statusCode, String contentType, String body) {
    }

    /**
     * 发探测请求。刻意<b>不设 Accept 头</b>——模拟前端 {@code fetch} 的默认行为，
     * 理由见类注释「坑 2」。
     */
    private ProbeResult probe(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return new ProbeResult(
                resp.statusCode(),
                resp.headers().firstValue("Content-Type").orElse(""),
                resp.body() == null ? "" : resp.body());
    }

    // ---------- 探针桩（测试内配置，不进生产代码） ----------

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        /** ① 在 controller 方法返回前抛。 */
        @GetMapping(value = "/probe/reject-before-flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<String> rejectBeforeFlux() {
            throw rejected();
        }

        /** ② 订阅 lambda 内抛，之前没发过任何事件。 */
        @GetMapping(value = "/probe/reject-in-subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<String> rejectInSubscribe() {
            return Flux.create(sink -> {
                throw rejected();
            });
        }

        /** ③ 先发一个事件（Flux.just 订阅即发），再进抛异常的 create。 */
        @GetMapping(value = "/probe/reject-after-prefix-event", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<String> rejectAfterPrefixEvent() {
            Flux<String> prefix = Flux.just("event: CHAT_STATUS\ndata: understanding\n\n");
            Flux<String> body = Flux.create(sink -> {
                throw rejected();
            });
            return Flux.concat(prefix, body);
        }

        /** ④ 不抛异常，改用 sink.error。 */
        @GetMapping(value = "/probe/error-in-subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<String> errorInSubscribe() {
            return Flux.create(sink -> sink.error(rejected()));
        }

        /** ⑤ 对照组。 */
        @GetMapping(value = "/probe/normal", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<String> normal() {
            return Flux.just("event: CHAT_STATUS\ndata: ok\n\n");
        }

        private static RateLimitExceededException rejected() {
            return new RateLimitExceededException(30, "USER_LIMIT");
        }
    }
}
