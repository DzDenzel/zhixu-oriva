package com.planlock.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PlanStatusLogRules 单元测试（纯 JVM，无外部依赖）。
 */
class PlanStatusLogRulesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validateAllowsNullForLegacyFieldOmission() {
        assertThatCode(() -> PlanStatusLogRules.validate(null, objectMapper))
                .doesNotThrowAnyException();
    }

    @Test
    void validateAllowsEmptyArray() {
        assertThatCode(() -> PlanStatusLogRules.validate("[]", objectMapper))
                .doesNotThrowAnyException();
    }

    @Test
    void validateAllowsValidEntriesIncludingLeapDayAndBoundarySizeFifty() {
        String valid = """
                [
                  {"date":"2024-02-29","status":0},
                  {"date":"2026-11-23","status":1}
                ]
                """;
        assertThatCode(() -> PlanStatusLogRules.validate(valid, objectMapper))
                .doesNotThrowAnyException();

        String fiftyEntries = buildEntries(50);
        assertThatCode(() -> PlanStatusLogRules.validate(fiftyEntries, objectMapper))
                .doesNotThrowAnyException();
    }

    @Test
    void validateRejectsMoreThanFiftyEntries() {
        String fiftyOneEntries = buildEntries(51);

        assertInvalid(fiftyOneEntries);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "null",
            "{}",
            "\"[]\"",
            "123",
            "not-json",
            "[1]",
            "[null]",
            "[\"2026-11-23\"]",
            "[{\"date\":\"2026-11-23\"}]",
            "[{\"status\":1}]",
            "[{\"date\":20261123,\"status\":1}]",
            "[{\"date\":\"2026-1-1\",\"status\":1}]",
            "[{\"date\":\"2026/11/23\",\"status\":1}]",
            "[{\"date\":\"2025-02-29\",\"status\":1}]",
            "[{\"date\":\"2026-04-31\",\"status\":0}]",
            "[{\"date\":\"2026-13-01\",\"status\":1}]",
            "[{\"date\":\"2026-11-23\",\"status\":2}]",
            "[{\"date\":\"2026-11-23\",\"status\":-1}]",
            "[{\"date\":\"2026-11-23\",\"status\":\"1\"}]",
            "[{\"date\":\"2026-11-23\",\"status\":1.0}]",
            "[{\"date\":\"2026-11-23\",\"status\":true}]",
            "[{\"date\":\"2026-11-23\",\"status\":999999999999}]",
            "[{\"date\":\"2026-11-23\",\"status\":1}] trailing",
            "[] []"
    })
    void validateRejectsMalformedOrInvalidInputs(String payload) {
        assertInvalid(payload);
    }

    private void assertInvalid(String payload) {
        assertThatThrownBy(() -> PlanStatusLogRules.validate(payload, objectMapper))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM.getCode()));
    }

    private String buildEntries(int count) {
        LocalDate base = LocalDate.of(2026, 1, 1);
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"date\":\"" + base.plusDays(i) + "\",\"status\":" + (i % 2) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
