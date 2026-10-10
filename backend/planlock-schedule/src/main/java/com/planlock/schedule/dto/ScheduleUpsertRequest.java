package com.planlock.schedule.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class ScheduleUpsertRequest {
    @jakarta.validation.constraints.Size(min = 1, max = 64) private String clientId;
    @NotBlank(message = "日程标题不能为空") private String title;
    @NotNull(message = "开始日期不能为空") private LocalDate startDate;
    private LocalTime startTime;
    @NotNull(message = "结束日期不能为空") private LocalDate endDate;
    private LocalTime endTime;
    @NotNull private Boolean allDay = false;
    private String location;
    private String note;
    @Min(0) private Integer remindAdvanceMin;
    private String repeatRule;
    private Long linkedPlanId;
    private String tzId;
}
