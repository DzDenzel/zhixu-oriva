package com.planlock.common.web;

import com.planlock.common.ApiResponse;
import com.planlock.common.ErrorCode;
import com.planlock.common.GlobalExceptionHandler;
import com.planlock.common.auth.JwtUtil;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
            return ApiResponse.ok(body);
        }

        @PostMapping("/api/v1/auth/login")
        ApiResponse<String> login(@RequestBody Map<String, Object> body) {
            reached.set(true);
            return ApiResponse.ok("ok");
        }

        @PostMapping("/api/v1/plans")
        ApiResponse<String> plans(@RequestBody Map<String, Object> body) {
            reached.set(true);
            return ApiResponse.ok("ok");
        }
    }

    @Test
    void unauthenticatedRequestsReadZeroBytesAndFailFastWithUnauthorized() throws Exception {
        var controller = new EchoController();
        var props = new JsonBodyLimitProperties();
        var filter = new JsonBodyLimitFilter(props, jwtUtil());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
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
        assertThat(filter.getActiveBufferedBytes()).isZero();
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
        assertThat(filter.getActiveBufferedBytes()).isZero();
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
        var filter = new JsonBodyLimitFilter(props, jwtUtil());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();

        String oversized = "{\"content\":\"" + "a".repeat(200) + "\"}";
        mvc.perform(post("/api/v1/journals")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));
        assertThat(controller.reached).isFalse();
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // Sync route allows up to 512 bytes when authenticated.
        mvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(controller.reached).isTrue();
        assertThat(filter.getActiveBufferedBytes()).isZero();
    }

    @Test
    void chunkedRequestWithoutContentLengthStopsStreamingReadWhenOverLimit() throws Exception {
        var controller = new EchoController();
        var props = new JsonBodyLimitProperties();
        props.setMaxJsonRequestBytes(64L);
        props.setJsonRouteLimits(List.of());
        var filter = new JsonBodyLimitFilter(props, jwtUtil());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
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
        assertThat(filter.getActiveBufferedBytes()).isZero();
    }

    @Test
    void chunkedValidShortJsonFollowedByOversizedUnreadTailRejectsBeforeController() throws Exception {
        var controller = new EchoController();
        var props = new JsonBodyLimitProperties();
        props.setMaxJsonRequestBytes(64L);
        props.setJsonRouteLimits(List.of());
        var filter = new JsonBodyLimitFilter(props, jwtUtil());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
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
        assertThat(filter.getActiveBufferedBytes()).isZero();
    }

    @Test
    void publicEndpointEnforces64KiBLimitAndConcurrentBudgetReleasesCleanly() throws Exception {
        var controller = new EchoController();
        var props = new JsonBodyLimitProperties();
        assertThat(props.getMaxJsonRequestBytes()).isEqualTo(65_536L);
        assertThat(props.getMaxConcurrentJsonBufferBytes()).isEqualTo(67_108_864L);
        assertThat(props.getSmallPayloadReserveBytes()).isEqualTo(4_194_304L);

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
        // With 100 B total budget < 4 MiB default reserve, smallPayloadReserve degenerates to 0 cleanly.
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

    @Test
    void concurrentDeclaredLargeContentLengthBlockingStreamsRejectBeforeAllocationAndReleaseCleanly() throws Exception {
        long sixteenMiB = 16L * 1024L * 1024L;
        var props = new JsonBodyLimitProperties();
        props.setMaxConcurrentJsonBufferBytes(32L * 1024L * 1024L);
        props.setSmallPayloadReserveBytes(0L);
        var filter = new JsonBodyLimitFilter(props, jwtUtil());

        var readStartedLatch = new CountDownLatch(2);
        var releaseStreamsLatch = new CountDownLatch(1);
        var controllerReached = new AtomicBoolean(false);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var req1Future = executor.submit(() -> {
                MockHttpServletRequest req = blockingDeclaredLengthRequest(
                        "/api/v1/sync/push", sixteenMiB, readStartedLatch, releaseStreamsLatch, false);
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(req, res, (r, s) -> controllerReached.set(true));
                return res;
            });
            var req2Future = executor.submit(() -> {
                MockHttpServletRequest req = blockingDeclaredLengthRequest(
                        "/api/v1/sync/push", sixteenMiB, readStartedLatch, releaseStreamsLatch, true);
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(req, res, (r, s) -> controllerReached.set(true));
                return res;
            });

            // Both requests have reserved 16 MiB BEFORE blocking on stream read (0 bytes delivered yet).
            assertThat(readStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(filter.getActiveBufferedBytes()).isEqualTo(32L * 1024L * 1024L);

            // 3rd request declaring 16 MiB must be rejected BEFORE calling getInputStream() or allocating buffer.
            AtomicBoolean thirdStreamTouched = new AtomicBoolean(false);
            MockHttpServletRequest thirdReq = new MockHttpServletRequest("POST", "/api/v1/sync/push") {
                @Override
                public long getContentLengthLong() {
                    return sixteenMiB;
                }

                @Override
                public int getContentLength() {
                    return (int) sixteenMiB;
                }

                @Override
                public ServletInputStream getInputStream() {
                    thirdStreamTouched.set(true);
                    throw new AssertionError("getInputStream must not be called when pre-allocation budget fails");
                }
            };
            thirdReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
            thirdReq.addHeader("Authorization", bearerToken());
            MockHttpServletResponse thirdRes = new MockHttpServletResponse();

            filter.doFilter(thirdReq, thirdRes, (r, s) -> controllerReached.set(true));

            assertThat(thirdRes.getStatus()).isEqualTo(200);
            assertThat(thirdRes.getContentAsString()).contains("\"code\":" + ErrorCode.INVALID_PARAM.getCode());
            assertThat(thirdStreamTouched.get()).isFalse();
            assertThat(filter.getActiveBufferedBytes()).isEqualTo(32L * 1024L * 1024L);

            // Release the two blocked streams (req1 EOF, req2 throws IOException) and verify full release to 0.
            releaseStreamsLatch.countDown();
            req1Future.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> req2Future.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IOException.class);
            assertThat(filter.getActiveBufferedBytes()).isZero();
        } finally {
            releaseStreamsLatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void chunkedMultiSlabStreamReservesBeforeAllocationAndReplaysAccurately() throws Exception {
        var controller = new EchoController();
        var props = new JsonBodyLimitProperties();
        props.setMaxJsonRequestBytes(32_768L);
        props.setMaxConcurrentJsonBufferBytes(24_576L);
        props.setSmallPayloadReserveBytes(0L);
        props.setJsonRouteLimits(List.of());
        var filter = new JsonBodyLimitFilter(props, jwtUtil());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();

        // 20 KiB valid JSON delivered in small 1000-byte chunks spans multiple 8 KiB slabs.
        String payload20KiB = "{\"data\":\"" + "m".repeat(20_000) + "\"}";
        byte[] bytes20KiB = payload20KiB.getBytes(StandardCharsets.UTF_8);
        mvc.perform(req -> {
                    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/sync/push") {
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
                            return new ChunkedDeliveryInputStream(bytes20KiB, 1000);
                        }
                    };
                    r.addHeader("Authorization", bearerToken());
                    r.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    r.addHeader("Transfer-Encoding", "chunked");
                    return r;
                })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(payload20KiB));
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 28 KiB <= 32 KiB route limit, but > 24 KiB concurrent cap: rejected mid-stream before over-allocating.
        controller.reached.set(false);
        byte[] bytes28KiB = ("{\"data\":\"" + "n".repeat(28_000) + "\"}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(req -> {
                    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/sync/push") {
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
                            return new ChunkedDeliveryInputStream(bytes28KiB, 2048);
                        }
                    };
                    r.addHeader("Authorization", bearerToken());
                    r.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    r.addHeader("Transfer-Encoding", "chunked");
                    return r;
                })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));
        assertThat(controller.reached).isFalse();
        assertThat(filter.getActiveBufferedBytes()).isZero();
    }

    @Test
    void downstreamRuntimeExceptionAndStreamIoExceptionReleaseBudgetStrictlyToZero() {
        var props = new JsonBodyLimitProperties();
        var filter = new JsonBodyLimitFilter(props, jwtUtil());

        // 1. Downstream throws RuntimeException on known-length payload.
        byte[] body = "{\"content\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest reqDownstream = new MockHttpServletRequest("POST", "/api/v1/journals");
        reqDownstream.setContentType(MediaType.APPLICATION_JSON_VALUE);
        reqDownstream.addHeader("Authorization", bearerToken());
        reqDownstream.setContent(body);
        MockHttpServletResponse resDownstream = new MockHttpServletResponse();
        AtomicLong activeInsideChain = new AtomicLong(-1L);

        assertThatThrownBy(() -> filter.doFilter(reqDownstream, resDownstream, (r, s) -> {
            activeInsideChain.set(filter.getActiveBufferedBytes());
            throw new IllegalStateException("downstream failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(activeInsideChain.get()).isEqualTo(body.length);
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 2. Downstream throws ServletException on chunked payload.
        MockHttpServletRequest reqChunkedErr = new MockHttpServletRequest("POST", "/api/v1/journals") {
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
                return new UnsizedInputStream(body);
            }
        };
        reqChunkedErr.setContentType(MediaType.APPLICATION_JSON_VALUE);
        reqChunkedErr.addHeader("Authorization", bearerToken());
        assertThatThrownBy(() -> filter.doFilter(reqChunkedErr, new MockHttpServletResponse(), (r, s) -> {
            assertThat(filter.getActiveBufferedBytes()).isGreaterThanOrEqualTo(body.length);
            throw new ServletException("servlet failure");
        })).isInstanceOf(ServletException.class);
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 3. InputStream throws IOException mid-read on known-length 1 MiB pre-allocated request.
        long oneMiB = 1024L * 1024L;
        MockHttpServletRequest reqKnownIo = new MockHttpServletRequest("POST", "/api/v1/sync/push") {
            @Override
            public long getContentLengthLong() {
                return oneMiB;
            }

            @Override
            public int getContentLength() {
                return (int) oneMiB;
            }

            @Override
            public ServletInputStream getInputStream() {
                return new FailingMidStreamInputStream(1024);
            }
        };
        reqKnownIo.setContentType(MediaType.APPLICATION_JSON_VALUE);
        reqKnownIo.addHeader("Authorization", bearerToken());
        assertThatThrownBy(() -> filter.doFilter(reqKnownIo, new MockHttpServletResponse(), (r, s) -> {
            throw new AssertionError("chain must not be invoked on stream IOException");
        })).isInstanceOf(IOException.class);
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 4. InputStream throws IOException mid-read on chunked multi-slab request.
        MockHttpServletRequest reqChunkedIo = new MockHttpServletRequest("POST", "/api/v1/sync/push") {
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
                return new FailingMidStreamInputStream(12_000);
            }
        };
        reqChunkedIo.setContentType(MediaType.APPLICATION_JSON_VALUE);
        reqChunkedIo.addHeader("Authorization", bearerToken());
        assertThatThrownBy(() -> filter.doFilter(reqChunkedIo, new MockHttpServletResponse(), (r, s) -> {
            throw new AssertionError("chain must not be invoked on chunked stream IOException");
        })).isInstanceOf(IOException.class);
        assertThat(filter.getActiveBufferedBytes()).isZero();
    }

    @Test
    void largePoolExhaustionStillAllowsSmallPublicAndStandardRequestsViaReservedFloor() throws Exception {
        long sixteenMiB = 16L * 1024L * 1024L;
        long fourMiB = 4L * 1024L * 1024L;
        var props = new JsonBodyLimitProperties();
        props.setMaxConcurrentJsonBufferBytes(36L * 1024L * 1024L); // 32 MiB large pool + 4 MiB small reserve floor
        props.setSmallPayloadReserveBytes(fourMiB);
        var filter = new JsonBodyLimitFilter(props, jwtUtil());

        assertThat(filter.resolveEffectiveConcurrentLimit(props.getMaxJsonRequestBytes()))
                .isEqualTo(36L * 1024L * 1024L);
        assertThat(filter.resolveEffectiveConcurrentLimit(sixteenMiB))
                .isEqualTo(32L * 1024L * 1024L);

        var readStartedLatch = new CountDownLatch(2);
        var releaseStreamsLatch = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var controller = new EchoController();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();

        try {
            var big1 = executor.submit(() -> {
                MockHttpServletRequest req = blockingDeclaredLengthRequest(
                        "/api/v1/sync/push", sixteenMiB, readStartedLatch, releaseStreamsLatch, false);
                filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> { });
                return null;
            });
            var big2 = executor.submit(() -> {
                MockHttpServletRequest req = blockingDeclaredLengthRequest(
                        "/api/v1/sync/push", sixteenMiB, readStartedLatch, releaseStreamsLatch, false);
                filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> { });
                return null;
            });

            assertThat(readStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(filter.getActiveBufferedBytes()).isEqualTo(32L * 1024L * 1024L);

            // Large-route request (/api/v1/journals with 512 KiB limit) is rejected because 32 MiB large pool is full.
            controller.reached.set(false);
            mvc.perform(post("/api/v1/journals")
                            .header("Authorization", bearerToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"blocked-by-large-pool\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));
            assertThat(controller.reached).isFalse();

            // Public small-route request (/api/v1/auth/login <= 64 KiB) STILL succeeds via 4 MiB reserved floor.
            controller.reached.set(false);
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"account\":\"owner@example.com\",\"password\":\"secret123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
            assertThat(controller.reached).isTrue();

            // Protected standard small-route request (/api/v1/plans <= 64 KiB) ALSO succeeds via reserved floor.
            controller.reached.set(false);
            mvc.perform(post("/api/v1/plans")
                            .header("Authorization", bearerToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Morning Focus\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
            assertThat(controller.reached).isTrue();

            releaseStreamsLatch.countDown();
            big1.get(5, TimeUnit.SECONDS);
            big2.get(5, TimeUnit.SECONDS);
            assertThat(filter.getActiveBufferedBytes()).isZero();
        } finally {
            releaseStreamsLatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void publicApiPatternsMatrixParamsTrailingSlashesAndEncodedPathsNeverBypassAuthOrAmplifyLimit() throws Exception {
        var props = new JsonBodyLimitProperties();
        var filter = new JsonBodyLimitFilter(props, jwtUtil());
        long defaultLimit = JsonBodyLimitProperties.DEFAULT_MAX_JSON_REQUEST_BYTES;

        // Exact public routes are non-protected and capped at 64 KiB.
        for (String publicPath : List.of(
                "/api/v1/auth/device",
                "/api/v1/health",
                "/api/v1/setup",
                "/api/v1/auth/code/send",
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/me/backup-password/reset",
                "/api/v1/plugins/catalog/official")) {
            assertThat(filter.isProtectedApiRoute(publicPath)).as("public path %s", publicPath).isFalse();
            assertThat(filter.resolveLimit(publicPath)).as("limit for %s", publicPath).isEqualTo(defaultLimit);
        }

        // Protected routes + adversarial variants (trailing slash, matrix params, percent-encoding, dot-segments)
        // must ALWAYS be classified as protected (fail-closed) and never amplify unauthenticated limits.
        List<String> mustRequirePreAuth = List.of(
                "/api/v1/auth/merge",
                "/api/v1/auth/login/",
                "/api/v1/auth/login;jsessionid=deadbeef",
                "/api/v1/auth/%6cogin",
                "/api/v1/sync/push/",
                "/api/v1/sync/push;jsessionid=deadbeef",
                "/api/v1/sync/%70ush",
                "/api/v1/journals/",
                "/api/v1/journals;jsessionid=deadbeef",
                "/api/v1/plugins/catalog/official;jsessionid=deadbeef",
                "/api/v1/plugins/../sync/push",
                "/api/v1/plugins/..;/journals",
                "/api/v1/plugins/%2e%2e/sync/push",
                "/api/v1/plugins\\..\\sync\\push",
                "/api/v1/auth\\login",
                "/api/v1/plugins/catalog\\official"
        );
        for (String variant : mustRequirePreAuth) {
            assertThat(filter.isProtectedApiRoute(variant)).as("must protect %s", variant).isTrue();
            if (!variant.equals("/api/v1/journals/")) {
                assertThat(filter.resolveLimit(variant)).as("no limit amplification for %s", variant)
                        .isEqualTo(defaultLimit);
            }
        }

        // End-to-end Filter check: unauthenticated requests to adversarial variants read 0 bytes and fail with 1001.
        byte[] probeBody = "{\"test\":\"payload\"}".getBytes(StandardCharsets.UTF_8);
        for (String variant : List.of(
                "/api/v1/auth/merge",
                "/api/v1/auth/login/",
                "/api/v1/auth/login;jsessionid=1",
                "/api/v1/sync/push;jsessionid=1",
                "/api/v1/sync/push/",
                "/api/v1/plugins/../sync/push",
                "/api/v1/plugins/%2e%2e/journals",
                "/api/v1/plugins\\..\\sync\\push",
                "/api/v1/auth\\login")) {
            AtomicInteger bytesRead = new AtomicInteger(0);
            MockHttpServletRequest req = new MockHttpServletRequest("POST", variant) {
                @Override
                public ServletInputStream getInputStream() {
                    return new CountingInputStream(probeBody, bytesRead);
                }
            };
            req.setContentType(MediaType.APPLICATION_JSON_VALUE);
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicBoolean chainReached = new AtomicBoolean(false);

            filter.doFilter(req, res, (r, s) -> chainReached.set(true));

            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(res.getContentAsString()).contains("\"code\":" + ErrorCode.UNAUTHORIZED.getCode());
            assertThat(bytesRead.get()).as("0 bytes read for %s", variant).isZero();
            assertThat(chainReached.get()).isFalse();
            assertThat(filter.getActiveBufferedBytes()).isZero();
        }
    }

    @Test
    void nonPositiveConcurrentBudgetKeepsCounterStrictlyZeroForEmptyKnownAndChunkedRequests() throws Exception {
        for (long disabledBudget : List.of(0L, -1L, -1024L)) {
            var props = new JsonBodyLimitProperties();
            props.setMaxConcurrentJsonBufferBytes(disabledBudget);
            var filter = new JsonBodyLimitFilter(props, jwtUtil());

            // 1. Explicit empty body (Content-Length: 0)
            MockHttpServletRequest emptyReq = new MockHttpServletRequest("POST", "/api/v1/journals");
            emptyReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
            emptyReq.addHeader("Authorization", bearerToken());
            emptyReq.setContent(new byte[0]);
            AtomicLong activeDuringEmpty = new AtomicLong(-1L);
            filter.doFilter(emptyReq, new MockHttpServletResponse(), (r, s) -> activeDuringEmpty.set(filter.getActiveBufferedBytes()));
            assertThat(activeDuringEmpty.get()).isZero();
            assertThat(filter.getActiveBufferedBytes()).isZero();

            // 2. Known-length normal payload
            byte[] knownBody = "{\"content\":\"known-body\"}".getBytes(StandardCharsets.UTF_8);
            MockHttpServletRequest knownReq = new MockHttpServletRequest("POST", "/api/v1/journals");
            knownReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
            knownReq.addHeader("Authorization", bearerToken());
            knownReq.setContent(knownBody);
            AtomicLong activeDuringKnown = new AtomicLong(-1L);
            filter.doFilter(knownReq, new MockHttpServletResponse(), (r, s) -> activeDuringKnown.set(filter.getActiveBufferedBytes()));
            assertThat(activeDuringKnown.get()).isZero();
            assertThat(filter.getActiveBufferedBytes()).isZero();

            // 3. Chunked multi-slab payload (12 KiB)
            byte[] chunkedBody = ("{\"data\":\"" + "k".repeat(12_000) + "\"}").getBytes(StandardCharsets.UTF_8);
            MockHttpServletRequest chunkedReq = new MockHttpServletRequest("POST", "/api/v1/sync/push") {
                @Override public long getContentLengthLong() { return -1L; }
                @Override public int getContentLength() { return -1; }
                @Override public ServletInputStream getInputStream() { return new ChunkedDeliveryInputStream(chunkedBody, 1024); }
            };
            chunkedReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
            chunkedReq.addHeader("Authorization", bearerToken());
            AtomicLong activeDuringChunked = new AtomicLong(-1L);
            filter.doFilter(chunkedReq, new MockHttpServletResponse(), (r, s) -> activeDuringChunked.set(filter.getActiveBufferedBytes()));
            assertThat(activeDuringChunked.get()).isZero();
            assertThat(filter.getActiveBufferedBytes()).isZero();
        }
    }

    @Test
    void tight100ByteBudgetHasNoHiddenScratchBufferAndReleasesEmptyPreallocatedStreamsBeforeChain() throws Exception {
        var props = new JsonBodyLimitProperties();
        props.setMaxConcurrentJsonBufferBytes(100L);
        var filter = new JsonBodyLimitFilter(props, jwtUtil());

        // 1. Empty chunked stream enters chain with 0 bytes reserved and 0 allocated.
        MockHttpServletRequest emptyChunked = new MockHttpServletRequest("POST", "/api/v1/journals") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() { return new UnsizedInputStream(new byte[0]); }
        };
        emptyChunked.setContentType(MediaType.APPLICATION_JSON_VALUE);
        emptyChunked.addHeader("Authorization", bearerToken());
        AtomicLong activeOnEmptyChunked = new AtomicLong(-1L);
        filter.doFilter(emptyChunked, new MockHttpServletResponse(), (r, s) -> activeOnEmptyChunked.set(filter.getActiveBufferedBytes()));
        assertThat(activeOnEmptyChunked.get()).isZero();
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 2. Declared Content-Length: 60 that immediately EOFs (0 bytes) releases budget BEFORE invoking chain.
        MockHttpServletRequest declaredEmpty = new MockHttpServletRequest("POST", "/api/v1/journals") {
            @Override public long getContentLengthLong() { return 60L; }
            @Override public int getContentLength() { return 60; }
            @Override public ServletInputStream getInputStream() { return new UnsizedInputStream(new byte[0]); }
        };
        declaredEmpty.setContentType(MediaType.APPLICATION_JSON_VALUE);
        declaredEmpty.addHeader("Authorization", bearerToken());
        AtomicLong activeOnDeclaredEmpty = new AtomicLong(-1L);
        filter.doFilter(declaredEmpty, new MockHttpServletResponse(), (r, s) -> activeOnDeclaredEmpty.set(filter.getActiveBufferedBytes()));
        assertThat(activeOnDeclaredEmpty.get()).isZero();
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 3. Chunked stream > 100 B (within 64 KiB route limit) is strictly bounded to <= 100 B with zero 8 KiB scratch.
        AtomicInteger maxReadLenObserved = new AtomicInteger(0);
        byte[] payload150 = ("{\"v\":\"" + "x".repeat(140) + "\"}").getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest chunkedOver100 = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() {
                return new ObservingMaxLenInputStream(payload150, maxReadLenObserved);
            }
        };
        chunkedOver100.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse resOver100 = new MockHttpServletResponse();
        AtomicBoolean reachedOver100 = new AtomicBoolean(false);
        filter.doFilter(chunkedOver100, resOver100, (r, s) -> reachedOver100.set(true));
        assertThat(resOver100.getContentAsString()).contains("\"code\":" + ErrorCode.INVALID_PARAM.getCode());
        assertThat(reachedOver100.get()).isFalse();
        // Read array length passed into in.read(b, off, len) must never exceed 100 B (no hidden 8 KiB scratch!).
        assertThat(maxReadLenObserved.get()).isLessThanOrEqualTo(100);
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 4. Blocking chunked stream reserves <= 100 B via atomic CAS before blocking on first read, then releases on EOF.
        var blockedLatch = new CountDownLatch(1);
        var releaseLatch = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                MockHttpServletRequest stalledChunked = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
                    @Override public long getContentLengthLong() { return -1L; }
                    @Override public int getContentLength() { return -1; }
                    @Override public ServletInputStream getInputStream() {
                        return new BlockingServletInputStream(blockedLatch, releaseLatch, false);
                    }
                };
                stalledChunked.setContentType(MediaType.APPLICATION_JSON_VALUE);
                filter.doFilter(stalledChunked, new MockHttpServletResponse(), (r, s) -> { });
                return null;
            });
            assertThat(blockedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(filter.getActiveBufferedBytes()).isBetween(1L, 100L);
            releaseLatch.countDown();
            future.get(5, TimeUnit.SECONDS);
            assertThat(filter.getActiveBufferedBytes()).isZero();
        } finally {
            releaseLatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void errorThrownDownstreamReleasesBudgetStrictlyToZero() {
        var props = new JsonBodyLimitProperties();
        var filter = new JsonBodyLimitFilter(props, jwtUtil());
        byte[] payload = "{\"content\":\"error-path\"}".getBytes(StandardCharsets.UTF_8);

        // Known-length Error path
        MockHttpServletRequest knownReq = new MockHttpServletRequest("POST", "/api/v1/journals");
        knownReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
        knownReq.addHeader("Authorization", bearerToken());
        knownReq.setContent(payload);
        assertThatThrownBy(() -> filter.doFilter(knownReq, new MockHttpServletResponse(), (r, s) -> {
            assertThat(filter.getActiveBufferedBytes()).isEqualTo(payload.length);
            throw new AssertionError("simulated fatal Error");
        })).isInstanceOf(AssertionError.class);
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // Chunked Error path
        MockHttpServletRequest chunkedReq = new MockHttpServletRequest("POST", "/api/v1/journals") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() { return new UnsizedInputStream(payload); }
        };
        chunkedReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
        chunkedReq.addHeader("Authorization", bearerToken());
        assertThatThrownBy(() -> filter.doFilter(chunkedReq, new MockHttpServletResponse(), (r, s) -> {
            assertThat(filter.getActiveBufferedBytes()).isGreaterThanOrEqualTo(payload.length);
            throw new AssertionError("simulated chunked Error");
        })).isInstanceOf(AssertionError.class);
        assertThat(filter.getActiveBufferedBytes()).isZero();
    }

    @Test
    void segmentedReplaySupportsMixedSingleMultiZeroLengthReadsEofAndRepeatedStreams() throws Exception {
        var props = new JsonBodyLimitProperties();
        var filter = new JsonBodyLimitFilter(props, jwtUtil());

        // 20 KiB payload including high-bit UTF-8 Chinese characters spanning >= 3 slabs (8 KiB each).
        String text = "{\"note\":\"" + "知序ORIVA-".repeat(2200) + "\"}";
        byte[] expected = text.getBytes(StandardCharsets.UTF_8);
        assertThat(expected.length).isGreaterThan(JsonBodyLimitFilter.CHUNK_SIZE * 2);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/journals") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() { return new ChunkedDeliveryInputStream(expected, 1300); }
        };
        req.setContentType(MediaType.APPLICATION_JSON_VALUE);
        req.addHeader("Authorization", bearerToken());

        filter.doFilter(req, new MockHttpServletResponse(), (wrappedReq, s) -> {
            HttpServletRequest httpReq = (HttpServletRequest) wrappedReq;
            assertThat(httpReq.getContentLength()).isEqualTo(expected.length);
            assertThat(httpReq.getContentLengthLong()).isEqualTo(expected.length);

            // First pass: mixed single-byte read(), zero-length read(b, 0, 0), and multi-byte reads across slab boundaries.
            ServletInputStream in1 = httpReq.getInputStream();
            assertThat(in1.isReady()).isTrue();
            assertThat(in1.isFinished()).isFalse();
            assertThat(in1.available()).isEqualTo(expected.length);

            ByteArrayOutputStream collected = new ByteArrayOutputStream(expected.length);
            byte[] buf = new byte[500];
            assertThat(in1.read(buf, 0, 0)).isZero();

            // Read 10 single bytes (tests unsigned & 0xFF semantics)
            for (int i = 0; i < 10; i++) {
                int b = in1.read();
                assertThat(b).isBetween(0, 255);
                collected.write(b);
            }
            assertThat(in1.available()).isEqualTo(expected.length - 10);
            assertThat(in1.isFinished()).isFalse();

            // Read remaining in 500-byte chunks interleaved with single-byte reads across slab boundaries
            while (true) {
                int n = in1.read(buf, 0, buf.length);
                if (n == -1) {
                    break;
                }
                collected.write(buf, 0, n);
                int single = in1.read();
                if (single != -1) {
                    collected.write(single);
                }
            }
            assertThat(in1.available()).isZero();
            assertThat(in1.isFinished()).isTrue();
            assertThat(in1.read()).isEqualTo(-1);
            assertThat(in1.read(buf, 0, 10)).isEqualTo(-1);
            assertThat(in1.read(buf, 0, 0)).isZero();
            assertThat(collected.toByteArray()).isEqualTo(expected);

            // Second pass: fresh getInputStream() replays exact bytes from offset 0.
            ServletInputStream in2 = httpReq.getInputStream();
            assertThat(in2.available()).isEqualTo(expected.length);
            assertThat(in2.readAllBytes()).isEqualTo(expected);
            assertThat(in2.isFinished()).isTrue();
        });
        assertThat(filter.getActiveBufferedBytes()).isZero();
    }

    @Test
    void routeLimitsExceedingIntegerMaxAreClampedWithoutOverflow() throws Exception {
        var props = new JsonBodyLimitProperties();
        props.setMaxJsonRequestBytes(Long.MAX_VALUE);
        props.setJsonRouteLimits(List.of(
                new JsonBodyLimitProperties.RouteLimit("/api/v1/sync/push", (long) Integer.MAX_VALUE + 4096L)
        ));
        var filter = new JsonBodyLimitFilter(props, jwtUtil());

        assertThat(filter.resolveBoundedLimit("/api/v1/auth/login"))
                .isEqualTo(JsonBodyLimitFilter.MAX_BUFFER_ARRAY_BYTES);
        assertThat(filter.resolveBoundedLimit("/api/v1/sync/push"))
                .isEqualTo(JsonBodyLimitFilter.MAX_BUFFER_ARRAY_BYTES);

        // Content-Length: Long.MAX_VALUE and Integer.MAX_VALUE are rejected cleanly before allocation.
        for (long hugeLength : List.of(Long.MAX_VALUE, (long) Integer.MAX_VALUE)) {
            AtomicBoolean streamCalled = new AtomicBoolean(false);
            MockHttpServletRequest hugeReq = new MockHttpServletRequest("POST", "/api/v1/sync/push") {
                @Override public long getContentLengthLong() { return hugeLength; }
                @Override public ServletInputStream getInputStream() {
                    streamCalled.set(true);
                    throw new AssertionError("stream must not be touched for > MAX_BUFFER_ARRAY_BYTES");
                }
            };
            hugeReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
            hugeReq.addHeader("Authorization", bearerToken());
            MockHttpServletResponse res = new MockHttpServletResponse();

            filter.doFilter(hugeReq, res, (r, s) -> {
                throw new AssertionError("chain must not be invoked");
            });
            assertThat(res.getContentAsString()).contains("\"code\":" + ErrorCode.INVALID_PARAM.getCode());
            assertThat(streamCalled.get()).isFalse();
            assertThat(filter.getActiveBufferedBytes()).isZero();
        }

        // Normal request under clamped Long.MAX_VALUE limit still works without overflow.
        byte[] okBody = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest normalReq = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        normalReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
        normalReq.setContent(okBody);
        AtomicBoolean normalReached = new AtomicBoolean(false);
        filter.doFilter(normalReq, new MockHttpServletResponse(), (r, s) -> normalReached.set(true));
        assertThat(normalReached.get()).isTrue();
        assertThat(filter.getActiveBufferedBytes()).isZero();
    }

    @Test
    void chunkedRequestWhenBudgetExhaustedRejectsBeforeGetInputStreamWhilePreservingSmallRouteReserve() throws Exception {
        long sixteenMiB = 16L * 1024L * 1024L;
        long fourMiB = 4L * 1024L * 1024L;
        var props = new JsonBodyLimitProperties();
        props.setMaxConcurrentJsonBufferBytes(36L * 1024L * 1024L); // 32 MiB large pool + 4 MiB small reserve floor
        props.setSmallPayloadReserveBytes(fourMiB);
        var filter = new JsonBodyLimitFilter(props, jwtUtil());

        var readStartedLatch = new CountDownLatch(2);
        var releaseStreamsLatch = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var big1 = executor.submit(() -> {
                MockHttpServletRequest req = blockingDeclaredLengthRequest(
                        "/api/v1/sync/push", sixteenMiB, readStartedLatch, releaseStreamsLatch, false);
                filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> { });
                return null;
            });
            var big2 = executor.submit(() -> {
                MockHttpServletRequest req = blockingDeclaredLengthRequest(
                        "/api/v1/sync/push", sixteenMiB, readStartedLatch, releaseStreamsLatch, false);
                filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> { });
                return null;
            });

            assertThat(readStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(filter.getActiveBufferedBytes()).isEqualTo(32L * 1024L * 1024L);

            // Chunked large-route request MUST be rejected before touching getInputStream() or read().
            AtomicBoolean largeStreamTouched = new AtomicBoolean(false);
            AtomicBoolean largeChainReached = new AtomicBoolean(false);
            MockHttpServletRequest chunkedLarge = new MockHttpServletRequest("POST", "/api/v1/journals") {
                @Override public long getContentLengthLong() { return -1L; }
                @Override public int getContentLength() { return -1; }
                @Override public ServletInputStream getInputStream() {
                    largeStreamTouched.set(true);
                    throw new AssertionError("getInputStream must not be called when concurrent budget is exhausted");
                }
            };
            chunkedLarge.setContentType(MediaType.APPLICATION_JSON_VALUE);
            chunkedLarge.addHeader("Authorization", bearerToken());
            MockHttpServletResponse chunkedLargeRes = new MockHttpServletResponse();
            filter.doFilter(chunkedLarge, chunkedLargeRes, (r, s) -> largeChainReached.set(true));
            assertThat(largeStreamTouched.get()).isFalse();
            assertThat(largeChainReached.get()).isFalse();
            assertThat(chunkedLargeRes.getContentAsString()).contains("\"code\":" + ErrorCode.INVALID_PARAM.getCode());
            assertThat(filter.getActiveBufferedBytes()).isEqualTo(32L * 1024L * 1024L);

            // Public <= 64 KiB chunked route (/api/v1/auth/login) STILL succeeds via the 4 MiB reserve floor.
            byte[] smallPayload = "{\"account\":\"owner@example.com\",\"password\":\"secret123\"}".getBytes(StandardCharsets.UTF_8);
            AtomicBoolean smallChainReached = new AtomicBoolean(false);
            MockHttpServletRequest chunkedSmall = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
                @Override public long getContentLengthLong() { return -1L; }
                @Override public int getContentLength() { return -1; }
                @Override public ServletInputStream getInputStream() {
                    return new ChunkedDeliveryInputStream(smallPayload, 7);
                }
            };
            chunkedSmall.setContentType(MediaType.APPLICATION_JSON_VALUE);
            filter.doFilter(chunkedSmall, new MockHttpServletResponse(), (r, s) -> {
                smallChainReached.set(true);
                byte[] seen = r.getInputStream().readAllBytes();
                assertThat(seen).isEqualTo(smallPayload);
            });
            assertThat(smallChainReached.get()).isTrue();
            assertThat(filter.getActiveBufferedBytes()).isEqualTo(32L * 1024L * 1024L);

            releaseStreamsLatch.countDown();
            big1.get(5, TimeUnit.SECONDS);
            big2.get(5, TimeUnit.SECONDS);
            assertThat(filter.getActiveBufferedBytes()).isZero();
        } finally {
            releaseStreamsLatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentChunkedRaceNearBudgetCeilingOnlyAllowsSingleCasWinnerIntoInputStream() throws Exception {
        var props = new JsonBodyLimitProperties();
        props.setMaxConcurrentJsonBufferBytes(JsonBodyLimitFilter.CHUNK_SIZE); // Exact 1 slab budget (8 KiB)
        props.setSmallPayloadReserveBytes(0L);
        var filter = new JsonBodyLimitFilter(props, jwtUtil());

        int threadCount = 4;
        var startGate = new CountDownLatch(1);
        var winnerBlocked = new CountDownLatch(1);
        var releaseWinner = new CountDownLatch(1);
        var streamCalls = new AtomicInteger(0);
        var chainCalls = new AtomicInteger(0);
        var executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
                        @Override public long getContentLengthLong() { return -1L; }
                        @Override public int getContentLength() { return -1; }
                        @Override public ServletInputStream getInputStream() {
                            streamCalls.incrementAndGet();
                            return new BlockingServletInputStream(winnerBlocked, releaseWinner, false);
                        }
                    };
                    req.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    MockHttpServletResponse res = new MockHttpServletResponse();
                    filter.doFilter(req, res, (r, s) -> chainCalls.incrementAndGet());
                    return res;
                }));
            }

            startGate.countDown();
            assertThat(winnerBlocked.await(5, TimeUnit.SECONDS)).isTrue();

            // Wait briefly for losing threads to return; only the CAS winner may touch getInputStream().
            int rejectedCount = 0;
            for (Future<MockHttpServletResponse> future : futures) {
                if (future.isDone()) {
                    MockHttpServletResponse r = future.get();
                    assertThat(r.getContentAsString()).contains("\"code\":" + ErrorCode.INVALID_PARAM.getCode());
                    rejectedCount++;
                }
            }
            // Wait until all 3 losers have completed rejection before unblocking the winner.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (rejectedCount < threadCount - 1 && System.nanoTime() < deadline) {
                rejectedCount = 0;
                for (Future<MockHttpServletResponse> future : futures) {
                    if (future.isDone()) {
                        rejectedCount++;
                    }
                }
                if (rejectedCount < threadCount - 1) {
                    Thread.sleep(10L);
                }
            }
            assertThat(rejectedCount).isEqualTo(threadCount - 1);
            assertThat(streamCalls.get()).isEqualTo(1);
            assertThat(filter.getActiveBufferedBytes()).isEqualTo(JsonBodyLimitFilter.CHUNK_SIZE);

            releaseWinner.countDown();
            int succeededCount = 0;
            int finalRejectedCount = 0;
            for (Future<MockHttpServletResponse> future : futures) {
                MockHttpServletResponse r = future.get(5, TimeUnit.SECONDS);
                if (r.getContentAsString().contains("\"code\":" + ErrorCode.INVALID_PARAM.getCode())) {
                    finalRejectedCount++;
                } else {
                    succeededCount++;
                }
            }
            assertThat(succeededCount).isEqualTo(1);
            assertThat(finalRejectedCount).isEqualTo(threadCount - 1);
            assertThat(streamCalls.get()).isEqualTo(1);
            assertThat(chainCalls.get()).isEqualTo(1);
            assertThat(filter.getActiveBufferedBytes()).isZero();
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void chunkedSubsequentSlabEofReleasesUnusedReservationAndPreservesExactMaxLimitProbe() throws Exception {
        var props = new JsonBodyLimitProperties();
        var filter = new JsonBodyLimitFilter(props, jwtUtil());
        int smallRouteLimit = (int) props.getMaxJsonRequestBytes(); // 64 KiB
        assertThat(smallRouteLimit).isEqualTo(64 * 1024);

        // 1. Payload exactly equals CHUNK_SIZE (8 KiB) (< maxLimit 64 KiB): next-slab probe hits EOF and releases slab 2 reservation immediately.
        byte[] exactSlabPayload = ("{\"p\":\"" + "a".repeat(JsonBodyLimitFilter.CHUNK_SIZE - 8) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(exactSlabPayload.length).isEqualTo(JsonBodyLimitFilter.CHUNK_SIZE);
        AtomicLong activeInsideExactSlabChain = new AtomicLong(-1L);
        MockHttpServletRequest exactSlabReq = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() {
                return new UnsizedInputStream(exactSlabPayload);
            }
        };
        exactSlabReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
        filter.doFilter(exactSlabReq, new MockHttpServletResponse(), (r, s) -> {
            activeInsideExactSlabChain.set(filter.getActiveBufferedBytes());
            assertThat(r.getInputStream().readAllBytes()).isEqualTo(exactSlabPayload);
        });
        assertThat(activeInsideExactSlabChain.get()).isEqualTo(JsonBodyLimitFilter.CHUNK_SIZE);
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 2. Chunked stream at exact maxLimit (64 KiB) succeeds via 1-byte EOF probe without extra reservation.
        byte[] exactMaxPayload = ("{\"p\":\"" + "b".repeat(smallRouteLimit - 8) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(exactMaxPayload.length).isEqualTo(smallRouteLimit);
        AtomicLong activeInsideMaxChain = new AtomicLong(-1L);
        MockHttpServletRequest exactMaxReq = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() {
                return new ChunkedDeliveryInputStream(exactMaxPayload, 1024);
            }
        };
        exactMaxReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
        filter.doFilter(exactMaxReq, new MockHttpServletResponse(), (r, s) -> {
            activeInsideMaxChain.set(filter.getActiveBufferedBytes());
            assertThat(r.getInputStream().readAllBytes()).isEqualTo(exactMaxPayload);
        });
        assertThat(activeInsideMaxChain.get()).isEqualTo(smallRouteLimit);
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 3. Chunked stream at maxLimit + 1 byte is rejected as payload_limit.
        byte[] overMaxPayload = ("{\"p\":\"" + "c".repeat(smallRouteLimit - 7) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(overMaxPayload.length).isEqualTo(smallRouteLimit + 1);
        MockHttpServletRequest overMaxReq = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() {
                return new ChunkedDeliveryInputStream(overMaxPayload, 1024);
            }
        };
        overMaxReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse overMaxRes = new MockHttpServletResponse();
        AtomicBoolean overMaxReached = new AtomicBoolean(false);
        filter.doFilter(overMaxReq, overMaxRes, (r, s) -> overMaxReached.set(true));
        assertThat(overMaxReached.get()).isFalse();
        assertThat(overMaxRes.getContentAsString()).contains("\"code\":" + ErrorCode.INVALID_PARAM.getCode());
        assertThat(filter.getActiveBufferedBytes()).isZero();
    }

    @Test
    void admittedFullBudgetKnownLengthAndChunkedStreamsAcceptCleanEofWhileRejectingExtraByteAndUnadmittedRequests() throws Exception {
        var props = new JsonBodyLimitProperties();
        props.setMaxConcurrentJsonBufferBytes(100L);
        props.setMaxJsonRequestBytes(200L);
        props.setSmallPayloadReserveBytes(0L);
        var filter = new JsonBodyLimitFilter(props, jwtUtil());

        byte[] exact100 = ("{\"body\":\"" + "z".repeat(89) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(exact100.length).isEqualTo(100);

        // 1. Fixed Content-Length = 100 (== concurrent budget < 200 routeMaxBytes) reaches EOF cleanly -> 200 OK & full 100 B replay.
        MockHttpServletRequest knownExactReq = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        knownExactReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
        knownExactReq.setContent(exact100);
        MockHttpServletResponse knownExactRes = new MockHttpServletResponse();
        AtomicLong knownActiveInside = new AtomicLong(-1L);
        AtomicBoolean knownReached = new AtomicBoolean(false);
        filter.doFilter(knownExactReq, knownExactRes, (r, s) -> {
            knownReached.set(true);
            knownActiveInside.set(filter.getActiveBufferedBytes());
            assertThat(r.getInputStream().readAllBytes()).isEqualTo(exact100);
        });
        assertThat(knownReached.get()).isTrue();
        assertThat(knownExactRes.getStatus()).isEqualTo(200);
        assertThat(knownExactRes.getContentAsString()).isEmpty();
        assertThat(knownActiveInside.get()).isEqualTo(100L);
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 2. Chunked 100 B (== concurrent budget < 200 routeMaxBytes) across multi-chunks reaches EOF cleanly -> 200 OK & full 100 B replay.
        MockHttpServletRequest chunkedExactReq = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() {
                return new ChunkedDeliveryInputStream(exact100, 13);
            }
        };
        chunkedExactReq.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse chunkedExactRes = new MockHttpServletResponse();
        AtomicLong chunkedActiveInside = new AtomicLong(-1L);
        AtomicBoolean chunkedReached = new AtomicBoolean(false);
        filter.doFilter(chunkedExactReq, chunkedExactRes, (r, s) -> {
            chunkedReached.set(true);
            chunkedActiveInside.set(filter.getActiveBufferedBytes());
            assertThat(r.getInputStream().readAllBytes()).isEqualTo(exact100);
        });
        assertThat(chunkedReached.get()).isTrue();
        assertThat(chunkedExactRes.getStatus()).isEqualTo(200);
        assertThat(chunkedExactRes.getContentAsString()).isEmpty();
        assertThat(chunkedActiveInside.get()).isEqualTo(100L);
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 3a. Chunked 101 B (1 byte over concurrent budget 100 B < 200 routeMaxBytes) -> SAFE_ERROR_JSON, no extra array, counter == 0.
        byte[] over101 = ("{\"body\":\"" + "z".repeat(90) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(over101.length).isEqualTo(101);
        AtomicInteger maxObservedChunkedArray = new AtomicInteger(0);
        MockHttpServletRequest chunked101Req = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() {
                return new ObservingMaxLenInputStream(over101, maxObservedChunkedArray);
            }
        };
        chunked101Req.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse chunked101Res = new MockHttpServletResponse();
        AtomicBoolean chunked101Reached = new AtomicBoolean(false);
        filter.doFilter(chunked101Req, chunked101Res, (r, s) -> chunked101Reached.set(true));
        assertThat(chunked101Reached.get()).isFalse();
        assertThat(chunked101Res.getContentAsString()).contains("\"code\":" + ErrorCode.INVALID_PARAM.getCode());
        assertThat(maxObservedChunkedArray.get()).isLessThanOrEqualTo(100);
        assertThat(filter.getActiveBufferedBytes()).isZero();

        // 3b. Fixed Content-Length = 100 whose stream still delivers a 101st byte -> SAFE_ERROR_JSON, no extra array, counter == 0.
        AtomicInteger maxObservedKnownArray = new AtomicInteger(0);
        MockHttpServletRequest known101Req = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override public long getContentLengthLong() { return 100L; }
            @Override public int getContentLength() { return 100; }
            @Override public ServletInputStream getInputStream() {
                return new ObservingMaxLenInputStream(over101, maxObservedKnownArray);
            }
        };
        known101Req.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse known101Res = new MockHttpServletResponse();
        AtomicBoolean known101Reached = new AtomicBoolean(false);
        filter.doFilter(known101Req, known101Res, (r, s) -> known101Reached.set(true));
        assertThat(known101Reached.get()).isFalse();
        assertThat(known101Res.getContentAsString()).contains("\"code\":" + ErrorCode.INVALID_PARAM.getCode());
        assertThat(maxObservedKnownArray.get()).isLessThanOrEqualTo(100);
        assertThat(filter.getActiveBufferedBytes()).isZero();
    }

    private static MockHttpServletRequest blockingDeclaredLengthRequest(
            String uri, long declaredLength, CountDownLatch readStarted, CountDownLatch release, boolean throwOnRelease) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return declaredLength;
            }

            @Override
            public int getContentLength() {
                return (int) declaredLength;
            }

            @Override
            public ServletInputStream getInputStream() {
                return new BlockingServletInputStream(readStarted, release, throwOnRelease);
            }
        };
        req.setContentType(MediaType.APPLICATION_JSON_VALUE);
        req.addHeader("Authorization", bearerToken());
        return req;
    }

    private static final class BlockingServletInputStream extends ServletInputStream {
        private final CountDownLatch readStarted;
        private final CountDownLatch release;
        private final boolean throwOnRelease;
        private boolean finished;

        private BlockingServletInputStream(CountDownLatch readStarted, CountDownLatch release, boolean throwOnRelease) {
            this.readStarted = readStarted;
            this.release = release;
            this.throwOnRelease = throwOnRelease;
        }

        @Override
        public boolean isFinished() {
            return finished;
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
            return blockAndFinish();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return blockAndFinish();
        }

        private int blockAndFinish() throws IOException {
            readStarted.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            finished = true;
            if (throwOnRelease) {
                throw new IOException("simulated client disconnect");
            }
            return -1;
        }
    }

    private static final class ChunkedDeliveryInputStream extends ServletInputStream {
        private final byte[] data;
        private final int maxChunk;
        private int pos;

        private ChunkedDeliveryInputStream(byte[] data, int maxChunk) {
            this.data = data;
            this.maxChunk = maxChunk;
        }

        @Override
        public boolean isFinished() {
            return pos >= data.length;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }

        @Override
        public int read() {
            if (pos >= data.length) {
                return -1;
            }
            return data[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            int step = Math.min(Math.min(len, maxChunk), data.length - pos);
            System.arraycopy(data, pos, b, off, step);
            pos += step;
            return step;
        }
    }

    private static final class FailingMidStreamInputStream extends ServletInputStream {
        private final int bytesBeforeFailure;
        private int delivered;

        private FailingMidStreamInputStream(int bytesBeforeFailure) {
            this.bytesBeforeFailure = bytesBeforeFailure;
        }

        @Override
        public boolean isFinished() {
            return false;
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
            if (delivered >= bytesBeforeFailure) {
                throw new IOException("simulated mid-stream read error");
            }
            delivered++;
            return 'a';
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (delivered >= bytesBeforeFailure) {
                throw new IOException("simulated mid-stream read error");
            }
            int step = Math.min(len, bytesBeforeFailure - delivered);
            for (int i = 0; i < step; i++) {
                b[off + i] = 'a';
            }
            delivered += step;
            return step;
        }
    }

    private static final class ObservingMaxLenInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;
        private final AtomicInteger maxLenObserved;

        private ObservingMaxLenInputStream(byte[] bytes, AtomicInteger maxLenObserved) {
            this.delegate = new ByteArrayInputStream(bytes);
            this.maxLenObserved = maxLenObserved;
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
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            maxLenObserved.accumulateAndGet(b.length, Math::max);
            return delegate.read(b, off, len);
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
