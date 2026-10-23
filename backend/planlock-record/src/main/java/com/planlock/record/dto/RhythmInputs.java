package com.planlock.record.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Read-only inputs: no ownership is accepted from the HTTP caller. */
public final class RhythmInputs {
    private RhythmInputs() {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Plan(long id, String name, String type, String repeatRule, Integer status, String statusLog) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Execution(Long id, long planId, LocalDate planDate, Integer status, LocalDateTime startedAt, LocalDateTime endedAt, String clientId) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Behavior(Long id, String clientId, String appName, String packageName, LocalDateTime startedAt, LocalDateTime endedAt, Long durationMs) {}
}
