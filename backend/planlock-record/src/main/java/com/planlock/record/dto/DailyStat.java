package com.planlock.record.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 单日完成统计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyStat {

    private LocalDate date;
    private int completed;
}
