package com.planlock.sync;

import com.planlock.sync.dto.SyncPushRequest;
import com.planlock.sync.mapper.SyncMapper;
import com.planlock.sync.service.SyncService;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncScheduleTest {
    private final SyncMapper mapper = mock(SyncMapper.class);
    private final SyncService service = new SyncService(mapper, new com.fasterxml.jackson.databind.ObjectMapper());

    private SyncPushRequest.ScheduleItem timed() {
        var item = new SyncPushRequest.ScheduleItem();
        item.setTitle("meeting");
        item.setStartDate(LocalDate.of(2026, 9, 24));
        item.setEndDate(item.getStartDate());
        item.setStartTime(LocalTime.of(20, 0));
        item.setEndTime(LocalTime.of(21, 0));
        item.setAllDay(false);
        return item;
    }

    private void rejects(SyncPushRequest.ScheduleItem item) {
        var request = new SyncPushRequest();
        request.setSchedules(List.of(item));
        var result = service.push(1L, request).getResults().getFirst();
        assertFalse(result.isOk());
        assertEquals(1002, result.getCode());
        verifyNoInteractions(mapper);
    }

    @Test void rejectsBackwardTimeBeforeInsert() {
        var item = timed(); item.setEndTime(LocalTime.of(19, 0)); rejects(item);
    }
    @Test void rejectsBackwardDateBeforeUpdate() {
        var item = timed(); item.setId(5L); item.setBaseVersion(0L);
        item.setEndDate(item.getStartDate().minusDays(1)); rejects(item);
    }
    @Test void rejectsAllDayWithTimes() {
        var item = timed(); item.setAllDay(true); rejects(item);
    }
    @Test void rejectsMissingDateAsItemFailure() {
        var item = timed(); item.setStartDate(null); rejects(item);
    }
    @Test void rejectsUnsupportedRepeat() {
        var item = timed(); item.setRepeatRule("{\"type\":\"monthly\"}"); rejects(item);
    }
    @Test void rejectsWeeklyWithoutDays() {
        var item = timed(); item.setRepeatRule("{\"type\":\"weekly\"}"); rejects(item);
    }
    @Test void invalidItemDoesNotPreventValidCrossMidnightItem() {
        var bad = timed(); bad.setEndTime(LocalTime.of(19, 0));
        var valid = timed(); valid.setEndDate(valid.getStartDate().plusDays(1));
        valid.setEndTime(LocalTime.of(1, 0)); valid.setRepeatRule("{\"type\":\"daily\"}");
        var request = new SyncPushRequest(); request.setSchedules(List.of(bad, valid));
        var results = service.push(1L, request).getResults();
        assertFalse(results.get(0).isOk()); assertTrue(results.get(1).isOk());
        verify(mapper).insertSchedule(1L, valid);
        verify(mapper).scheduleIdentity(1L, null);
        verifyNoMoreInteractions(mapper);
    }
}
