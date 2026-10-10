package com.planlock.record.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EarlyEnding {
    private LocalDateTime at;
    private Long planId;
    private String method;
}
