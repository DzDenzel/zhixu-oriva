package com.planlock.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.schedule.dto.ScheduleUpsertRequest;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleRulesTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private ScheduleUpsertRequest timed(){var r=new ScheduleUpsertRequest();r.setTitle("复盘");r.setStartDate(LocalDate.of(2026,9,23));r.setEndDate(LocalDate.of(2026,9,23));r.setStartTime(LocalTime.of(20,0));r.setEndTime(LocalTime.of(21,0));r.setAllDay(false);return r;}
    @Test void acceptsCrossMidnightUsingDates(){var r=timed();r.setEndDate(r.getStartDate().plusDays(1));r.setEndTime(LocalTime.of(1,0));assertDoesNotThrow(()->ScheduleRules.validate(r,mapper));}
    @Test void rejectsSameDayBackwardTime(){var r=timed();r.setEndTime(LocalTime.of(19,0));assertThrows(BusinessException.class,()->ScheduleRules.validate(r,mapper));}
    @Test void allDayUsesClosedDatesWithoutTimes(){var r=timed();r.setAllDay(true);r.setStartTime(null);r.setEndTime(null);assertDoesNotThrow(()->ScheduleRules.validate(r,mapper));}
    @Test void rejectsUnsupportedRepeatRule(){var r=timed();r.setRepeatRule("{\"type\":\"rrule\"}");assertThrows(BusinessException.class,()->ScheduleRules.validate(r,mapper));}
    @Test void acceptsMonthlyAndYearlyRules(){
        var monthly=timed(); monthly.setRepeatRule("{\"type\":\"monthly\",\"day\":15}"); assertDoesNotThrow(()->ScheduleRules.validate(monthly,mapper));
        var yearly=timed(); yearly.setRepeatRule("{\"type\":\"yearly\",\"month\":3,\"day\":5}"); assertDoesNotThrow(()->ScheduleRules.validate(yearly,mapper));
    }
    @Test void rejectsOutOfRangeCalendarRules(){
        var monthly=timed(); monthly.setRepeatRule("{\"type\":\"monthly\",\"day\":0}"); assertThrows(BusinessException.class,()->ScheduleRules.validate(monthly,mapper));
        var yearly=timed(); yearly.setRepeatRule("{\"type\":\"yearly\",\"month\":13,\"day\":5}"); assertThrows(BusinessException.class,()->ScheduleRules.validate(yearly,mapper));
    }
}
