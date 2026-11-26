package com.planlock.common.web;

import com.planlock.common.ApiResponse;
import com.planlock.common.ErrorCode;
import com.planlock.common.GlobalExceptionHandler;
import com.planlock.common.auth.JwtUtil;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JsonBodyLimitFilterTest {

    private static final String TEST_SECRET = "planlock-test-secret-key-at-least-32-bytes";

    private static JwtUtil jwtUtil() {
        return new JwtUtil(TEST_SECRET, 60L);
    }

    private static String bearerToken() {
        return "Bearer " + jwtUtil().generate(10L, 1L);
    }

    @RestController
    static class EchoController {
        private final AtomicBoolean reached = new AtomicBoolean(false);
        private volatile CountDownLatch onEnterLatch;
        private volatile CountDownLatch releaseLatch;

        @PostMapping("/api/v1/journals")
        ApiResponse<String> journal(@RequestBody Map<String, Object> body) throws InterruptedException {
            reached.set(true);
            if (onEnterLatch != null) {
                onEnterLatch.countDown();
            }
            if (releaseLatch != null) {
                releaseLatch.await(5, TimeUnit.SECONDS);
            }
            return ApiResponse.ok(String.valueOf(body.getOrDefault("content", "ok")));
        }

        @PostMapping("/api/v1/sync/push")
        ApiResponse<String> sync(@RequestBody String body) {
            reached.set(true);
            return ApiResponse.ok("ok");
        }

        @PostMapping("/api/v1/auth/login")
        ApiResponse<String> login(@RequestBody Map<String, Object> body) {
            reached.set(true);
            return ApiResponse.ok("ok");
        }
    }

    @Test
    void unauthenticatedRequestsReadZeroBytesAndFailFastWithUnauthorized() throws Exception {
        var controller = new EchoController();
        var props = new JsonBodyLimitProperties();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JsonBodyLimitFilter(props, jwtUtil()))
                .build();

        byte[] hugePayload = ("{\"journals\":\"" + "x".repeat(4096) + "\"}").getBytes(StandardCharsets.UTF_8);
        AtomicInteger missingHeaderBytesRead = new AtomicInteger(0);
        mvc.perform(req -> {
                    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/sync/push") {
                        @Override
                        public long getContentLengthLong() {
                            return -1L;
                        }

                        @Override
                        public ServletInputStream getInputStream() {
                            return new CountingInputStream(hugePayload, missingHeaderBytesRead);
                        }
                    };
                    r.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    return r;
                })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()));
        assertThat(missingHeaderBytesRead.get()).isZero();
        assertThat(controller.reached).isFalse();

        // Forged JWT must likewise read 0 bytes and allocate 0 buffer.
        AtomicInteger forgedBytesRead = new AtomicInteger(0);
        mvc.perform(req -> {
                    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/journals") {
                        @Override
                        public ServletInputStream getInputStream() {
                            return new CountingInputStream(hugePayload, forgedBytesRead);
                        }
                    };
                    r.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    r.addHeader("Authorization", "Bearer forged.jwt.token");
                    return r;
                })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        assertThat(forgedBytesRead.get()).isZero();
        assertThat(controller.reached).isFalse();
    }

    @Test
    void knownContentLengthOverLimitIsRejectedBeforeControllerAndJackson() throws Exception {
        var controller = new EchoController();
        var props = new JsonBodyLimitProperties();
        props.setMaxJsonRequestBytes(128L);
        props.setJsonRouteLimits(List.of(
                new JsonBodyLimitProperties.RouteLimit("/api/v1/journals/**", 128L),
                new JsonBodyLimitProperties.RouteLimit("/api/v1/sync/push", 512L)
        ));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JsonBodyLimitFilter(props, jwtUtil()))
                .build();

        String oversized = "{\"content\":\"" + "a".repeat(200) + "\"}";
        mvc.perform(post("/api/v1/journals")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));
        assertThat(controller.reached).isFalse();

        // Sync route allows up to 512 bytes when authenticated.
        mvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(controller.reached).isTrue();
    }

    @Test
    void chunkedRequestWithoutContentLengthStopsStreamingReadWhenOverLimit() throws Exception {
        var controller = new EchoController();
        var props = new JsonBodyLimitProperties();
        props.setMaxJsonRequestBytes(64L);
        props.setJsonRouteLimits(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JsonBodyLimitFilter(props, jwtUtil()))
                .build();

        byte[] payload = ("{\"content\":\"" + "b".repeat(128) + "\"}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(req -> {
                    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/journals") {
                        @Override
                        public long getContentLengthLong() {
                            return -1L;
                        }

                        @Override
                        public int getContentLength() {
                            return -1;
                        }

                        @Override
                        public ServletInputStream getInputStream() {
                            return new UnsizedInputStream(payload);
                        }
                    };
                    r.addHeader("Authorization", bearerToken());
                    r.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    return r;
                })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));
        assertThat(controller.reached).isFalse();
    }

    @Test
    void chunkedValidShortJsonFollowedByOversizedUnreadTailRejectsBeforeController() throws Exception {
        var controller = new EchoController();
        var props = new JsonBodyLimitProperties();
        props.setMaxJsonRequestBytes(64L);
        props.setJsonRouteLimits(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JsonBodyLimitFilter(props, jwtUtil()))
                .build();

        byte[] payload = ("{\"ok\":1}" + " ".repeat(4096)).getBytes(StandardCharsets.UTF_8);
        AtomicInteger bytesConsumed = new AtomicInteger(0);
        mvc.perform(req -> {
                    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/journals") {
                        @Override
                        public long getContentLengthLong() {
                            return -1L;
                        }

                        @Override
                        public int getContentLength() {
                            return -1;
                        }

                        @Override
                        public ServletInputStream getInputStream() {
                            return new CountingInputStream(payload, bytesConsumed);
                        }
                    };
                    r.addHeader("Authorization", bearerToken());
                    r.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    return r;
                })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_PARAM.getDefaultMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(controller.reached).isFalse();
        assertThat(bytesConsumed.get()).isEqualTo(65);
    }

    @Test
    void publicEndpointEnforces64KiBLimitAndConcurrentBudgetReleasesCleanly() throws Exception {
        var controller = new EchoController();
        var props = new JsonBodyLimitProperties();
        assertThat(props.getMaxJsonRequestBytes()).isEqualTo(65_536L);
        assertThat(props.getMaxConcurrentJsonBufferBytes()).isEqualTo(67_108_864L);

        var filter = new JsonBodyLimitFilter(props, jwtUtil());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();

        // Public login endpoint allows small body without auth, rejects > 64 KiB body.
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"u\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        controller.reached.set(false);
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + "z".repeat(65_600) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));
        assertThat(controller.reached).isFalse();

        // Test concurrent heap buffer cap and clean finally release using a deterministic latch.
        var tightProps = new JsonBodyLimitProperties();
        tightProps.setMaxConcurrentJsonBufferBytes(100L);
        var tightFilter = new JsonBodyLimitFilter(tightProps, jwtUtil());
        var syncController = new EchoController();
        syncController.onEnterLatch = new CountDownLatch(1);
        syncController.releaseLatch = new CountDownLatch(1);
        MockMvc tightMvc = MockMvcBuilders.standaloneSetup(syncController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(tightFilter)
                .build();

        String body60 = "{\"content\":\"" + "c".repeat(46) + "\"}"; // 60 bytes
        assertThat(body60.getBytes(StandardCharsets.UTF_8).length).isEqualTo(60);

        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstFuture = executor.submit(() -> tightMvc.perform(post("/api/v1/journals")
                            .header("Authorization", bearerToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body60))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").value("c".repeat(46))));

            assertThat(syncController.onEnterLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(tightFilter.currentBufferedBytes()).isEqualTo(60L);

            // Second concurrent request pushing total to 120 > 100 must be rejected cleanly.
            tightMvc.perform(post("/api/v1/journals")
                            .header("Authorization", bearerToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body60))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));

            syncController.releaseLatch.countDown();
            firstFuture.get(5, TimeUnit.SECONDS);
            assertThat(tightFilter.currentBufferedBytes()).isZero();

            // After release, budget is restored and next request succeeds.
            tightMvc.perform(post("/api/v1/journals")
                            .header("Authorization", bearerToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body60))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
            assertThat(tightFilter.currentBufferedBytes()).isZero();
        } finally {
            syncController.releaseLatch.countDown();
            executor.shutdownNow();
        }
    }

    private static final class UnsizedInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        private UnsizedInputStream(byte[] bytes) {
            this.delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }
    }

    private static final class CountingInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;
        private final AtomicInteger counter;

        private CountingInputStream(byte[] bytes, AtomicInteger counter) {
            this.delegate = new ByteArrayInputStream(bytes);
            this.counter = counter;
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }

        @Override
        public int read() throws IOException {
            int next = delegate.read();
            if (next != -1) {
                counter.incrementAndGet();
            }
            return next;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = delegate.read(b, off, len);
            if (count > 0) {
                counter.addAndGet(count);
            }
            return count;
        }
    }
}
