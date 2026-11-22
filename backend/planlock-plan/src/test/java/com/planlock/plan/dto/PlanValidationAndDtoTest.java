package com.planlock.plan.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanUpsertRequest / RepeatRule / RemindMethods 校验与序列化单元测试。
 */
class PlanValidationAndDtoTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @Test
    void validUpsertRequestHasNoViolationsAndDefaultRemindAdvance() {
        PlanUpsertRequest req = validRequest();

        Set<ConstraintViolation<PlanUpsertRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
        assertThat(req.getRemindAdvanceMin()).isEqualTo(15);
    }

    @Test
    void invalidUpsertRequestFieldsTriggerBeanValidationViolations() {
        PlanUpsertRequest req = new PlanUpsertRequest();
        req.setName("   ");
        req.setType("t".repeat(65));
        req.setClientId("");
        req.setStartTime(null);
        req.setEndTime(null);
        req.setRemindAdvanceMin(-1);

        Set<ConstraintViolation<PlanUpsertRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("name", "type", "clientId", "startTime", "endTime", "remindAdvanceMin");
    }

    @Test
    void repeatRuleSupportsWeeklyAndCustomDatesRoundTrip() throws Exception {
        RepeatRule weekly = new RepeatRule();
        weekly.setType("weekly");
        weekly.setWeekdays(List.of(1, 2, 3, 4, 5));

        String weeklyJson = objectMapper.writeValueAsString(weekly);
        RepeatRule parsedWeekly = objectMapper.readValue(weeklyJson, RepeatRule.class);
        assertThat(parsedWeekly.getType()).isEqualTo("weekly");
        assertThat(parsedWeekly.getWeekdays()).containsExactly(1, 2, 3, 4, 5);
        assertThat(parsedWeekly.getDates()).isNull();

        RepeatRule custom = new RepeatRule();
        custom.setType("custom");
        custom.setDates(List.of("2026-11-23", "2026-12-01"));

        String customJson = objectMapper.writeValueAsString(custom);
        RepeatRule parsedCustom = objectMapper.readValue(customJson, RepeatRule.class);
        assertThat(parsedCustom.getType()).isEqualTo("custom");
        assertThat(parsedCustom.getDates()).containsExactly("2026-11-23", "2026-12-01");
    }

    @Test
    void remindMethodsDefaultsAndJsonRoundTrip() throws Exception {
        RemindMethods defaults = new RemindMethods();
        assertThat(defaults.isNotification()).isTrue();
        assertThat(defaults.isSound()).isFalse();
        assertThat(defaults.isMultiDevice()).isFalse();
        assertThat(defaults.isRepeatMultiple()).isFalse();

        defaults.setSound(true);
        defaults.setMultiDevice(true);
        String json = objectMapper.writeValueAsString(defaults);
        RemindMethods parsed = objectMapper.readValue(json, RemindMethods.class);
        assertThat(parsed).isEqualTo(defaults);
    }

    private static PlanUpsertRequest validRequest() {
        PlanUpsertRequest req = new PlanUpsertRequest();
        req.setName("晨间计划");
        req.setType("工作");
        req.setClientId("client-uuid-001");
        req.setStartTime(LocalTime.of(8, 30));
        req.setEndTime(LocalTime.of(10, 0));
        return req;
    }
}
