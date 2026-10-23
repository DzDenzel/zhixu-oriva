package com.planlock.plan.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;
import java.time.LocalDate;

/**
 * 创建 / 更新计划请求（PUT 更新时另行携带 baseVersion 查询参数）。
 */
@Data
public class PlanUpsertRequest {

    @NotBlank(message = "计划名不能为空")
    private String name;

    private LocalDate endedOccurrenceDate;
    private String statusLog;

    @jakarta.validation.constraints.Size(min = 1, max = 64)
    private String clientId;

    @NotBlank(message = "计划类型不能为空")
    @jakarta.validation.constraints.Size(max = 64, message = "计划类型值过长")
    private String type;

    @NotNull(message = "开始时间不能为空")
    private LocalTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalTime endTime;

    private RepeatRule repeatRule;

    private String color;

    private String icon;

    @Min(value = 0, message = "提醒提前量不能为负")
    private Integer remindAdvanceMin = 15;

    private RemindMethods remindMethods;
}
