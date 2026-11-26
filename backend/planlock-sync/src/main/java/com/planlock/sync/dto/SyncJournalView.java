package com.planlock.sync.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步拉取中的日常记录视图。
 */
@Data
public class SyncJournalView {
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
