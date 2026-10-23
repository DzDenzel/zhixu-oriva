package com.planlock.record.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.record.dto.RhythmSummary;
import com.planlock.record.mapper.RhythmMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

@Service
@RequiredArgsConstructor
public class RhythmService {
    private final RhythmMapper mapper;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public RhythmSummary rhythm(Long userId, int days, String timeZone) {
        if (days < 1 || days > 366) throw new BusinessException(ErrorCode.INVALID_PARAM, "days 必须为 1~366");
        final ZoneId zone;
        try { zone = ZoneId.of(timeZone); }
        catch (DateTimeException | NullPointerException invalid) { throw new BusinessException(ErrorCode.INVALID_PARAM, "tz 必须为有效 IANA 时区"); }
        // Execution dates and timestamp columns are user-local wall time (CHANGE-057), not UTC.
        LocalDate today = LocalDate.now(zone);
        return RhythmStats.aggregate(mapper.plans(userId), mapper.executions(userId),
                mapper.behavior(userId, today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay()), today, days, "Synced", json);
    }
}
