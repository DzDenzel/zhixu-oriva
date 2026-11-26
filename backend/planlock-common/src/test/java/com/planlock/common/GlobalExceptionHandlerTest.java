package com.planlock.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;

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
}
