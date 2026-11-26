package com.planlock.common.web;

import com.planlock.common.ApiResponse;
import com.planlock.common.ErrorCode;
import com.planlock.common.GlobalExceptionHandler;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JsonBodyLimitFilterTest {

    @RestController
    static class EchoController {
        private final AtomicBoolean reached = new AtomicBoolean(false);

        @PostMapping("/api/v1/journals")
        ApiResponse<String> journal(@RequestBody String body) {
            reached.set(true);
            return ApiResponse.ok("ok");
        }

        @PostMapping("/api/v1/sync/push")
        ApiResponse<String> sync(@RequestBody String body) {
            reached.set(true);
            return ApiResponse.ok("ok");
        }
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
                .addFilters(new JsonBodyLimitFilter(props))
                .build();

        String oversized = "{\"content\":\"" + "a".repeat(200) + "\"}";
        mvc.perform(post("/api/v1/journals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));
        assertThat(controller.reached).isFalse();

        // Sync route allows up to 512 bytes in this test config.
        mvc.perform(post("/api/v1/sync/push")
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
                .addFilters(new JsonBodyLimitFilter(props))
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
                    r.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    return r;
                })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));
        assertThat(controller.reached).isFalse();
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
}
