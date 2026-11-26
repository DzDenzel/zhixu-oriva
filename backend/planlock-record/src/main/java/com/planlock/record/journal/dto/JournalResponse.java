package com.planlock.record.journal.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 日常记录响应 DTO。
 */
@Data
@Builder
public class JournalResponse {
    private Long id;
    private String clientId;
    private Long userId;
    private LocalDate entryDate;
    private String mood;
    private List<String> tags;
    private String content;
    private Integer status;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
