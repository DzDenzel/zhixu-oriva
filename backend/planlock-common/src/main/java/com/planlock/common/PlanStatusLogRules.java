package com.planlock.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Shared input contract for CRUD and sync; null means legacy field omission. */
public final class PlanStatusLogRules {
    private PlanStatusLogRules() { }

    public static void validate(String statusLog, ObjectMapper json) {
        if (statusLog == null) return;
        try {
            var entries = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(statusLog);
            if (entries == null || !entries.isArray() || entries.size() > 50) throw invalid();
            for (var entry : entries) {
                var date = entry.path("date");
                var status = entry.path("status");
                if (!entry.isObject() || !date.isTextual() || !date.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                        || !status.isIntegralNumber() || !status.canConvertToInt()
                        || (status.intValue() != 0 && status.intValue() != 1)) throw invalid();
                LocalDate.parse(date.asText());
            }
        } catch (JsonProcessingException | DateTimeParseException failure) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_PARAM, "statusLog 须为最多50条有效date和status(0/1)的JSON数组");
    }
}
