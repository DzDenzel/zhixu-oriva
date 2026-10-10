package com.planlock.record.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ViolationAppStat {
    private String packageName;
    private String appName;
    private int count;
    private long totalDurationMs;
    private long approxDurationMs;
}
