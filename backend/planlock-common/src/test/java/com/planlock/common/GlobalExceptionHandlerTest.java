package com.planlock.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.MappingMatch;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void exceptionHandlersNeverLogPrivatePayloadText() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            GlobalExceptionHandler handler = new GlobalExceptionHandler();
            String secret = "SECRET_DIARY_BODY_TOKEN_987654";
            handler.handleBadPayload(new HttpMessageNotReadableException("JSON parse error: " + secret));
            handler.handleOther(new RuntimeException("DB error with value: " + secret));

            assertThat(appender.list).isNotEmpty();
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(secret);
                assertThat(event.getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void notFoundHandlerClassifiesPrefixMappedMountRootAsPlain404AndPrefixedApiAsJson1003() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        // 1) Prefix-mapped /api/* hitting mount root (pathInfo == null, MappingMatch.PATH) -> plain empty HTTP 404
        MockHttpServletRequest mountRoot = new MockHttpServletRequest("GET", "/ctx/api");
        mountRoot.setContextPath("/ctx");
        mountRoot.setServletPath("/api");
        mountRoot.setPathInfo(null);
        mountRoot.setHttpServletMapping(new MockHttpServletMapping("", "/api/*", "dispatcher", MappingMatch.PATH));
        ResponseEntity<?> mountRootRes = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/ctx/api", HttpHeaders.EMPTY), mountRoot);
        assertThat(mountRootRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mountRootRes.getBody()).isNull();
        assertThat(mountRootRes.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(mountRootRes.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        // 2) Prefix-mapped /api/* hitting mount root with malformed % -> fallback strips /api, still plain empty HTTP 404
        MockHttpServletRequest malformedMountRoot = new MockHttpServletRequest("GET", "/ctx/api;bad=%ZZ");
        malformedMountRoot.setContextPath("/ctx");
        malformedMountRoot.setServletPath("/api");
        malformedMountRoot.setPathInfo(null);
        malformedMountRoot.setHttpServletMapping(new MockHttpServletMapping("", "/api/*", "dispatcher", MappingMatch.PATH));
        ResponseEntity<?> malformedMountRootRes = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/ctx/api;bad=%ZZ", HttpHeaders.EMPTY), malformedMountRoot);
        assertThat(malformedMountRootRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(malformedMountRootRes.getBody()).isNull();
        assertThat(malformedMountRootRes.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(malformedMountRootRes.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        // 3) Prefix-mapped /portal/* targeting /api/v1/missing -> 404 JSON ApiResponse(1003)
        MockHttpServletRequest prefixedApi = new MockHttpServletRequest("GET", "/ctx/portal/api/v1/missing");
        prefixedApi.setContextPath("/ctx");
        prefixedApi.setServletPath("/portal");
        prefixedApi.setPathInfo("/api/v1/missing");
        prefixedApi.setHttpServletMapping(new MockHttpServletMapping("api/v1/missing", "/portal/*", "dispatcher", MappingMatch.PATH));
        ResponseEntity<?> prefixedApiRes = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/ctx/portal/api/v1/missing", HttpHeaders.EMPTY), prefixedApi);
        assertThat(prefixedApiRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(prefixedApiRes.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(prefixedApiRes.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(prefixedApiRes.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(prefixedApiRes.getBody()).isInstanceOf(ApiResponse.class);
        assertThat(((ApiResponse<?>) prefixedApiRes.getBody()).getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());

        // 4) Prefix-mapped /portal/* targeting malformed /api/%ZZ -> fallback still classifies as API -> 404 JSON 1003
        MockHttpServletRequest malformedPrefixedApi = new MockHttpServletRequest("GET", "/ctx/portal/api/%ZZ");
        malformedPrefixedApi.setContextPath("/ctx");
        malformedPrefixedApi.setServletPath("/portal");
        malformedPrefixedApi.setPathInfo("/api/%ZZ");
        malformedPrefixedApi.setHttpServletMapping(new MockHttpServletMapping("api/%ZZ", "/portal/*", "dispatcher", MappingMatch.PATH));
        ResponseEntity<?> malformedPrefixedApiRes = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/ctx/portal/api/%ZZ", HttpHeaders.EMPTY), malformedPrefixedApi);
        assertThat(malformedPrefixedApiRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(malformedPrefixedApiRes.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(malformedPrefixedApiRes.getBody()).isInstanceOf(ApiResponse.class);
        assertThat(((ApiResponse<?>) malformedPrefixedApiRes.getBody()).getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }
}
