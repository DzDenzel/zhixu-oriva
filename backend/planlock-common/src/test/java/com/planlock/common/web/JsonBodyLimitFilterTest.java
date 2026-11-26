package com.planlock.common.web;

import com.planlock.common.ApiResponse;
import com.planlock.common.ErrorCode;
import com.planlock.common.GlobalExceptionHandler;
import com.planlock.common.auth.JwtUtil;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
                "/api/v1/plugins/%2e%2e/sync/push"
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
                "/api/v1/plugins/%2e%2e/journals")) {
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
