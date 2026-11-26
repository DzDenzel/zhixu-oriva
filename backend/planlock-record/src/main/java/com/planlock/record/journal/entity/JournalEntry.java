package com.planlock.record.journal.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日常记录实体（对应表 journal_entries）。
 */
@Data
public class JournalEntry {
    private Long id;
    private Long userId;
    private String clientId;
    private LocalDate entryDate;
    private String mood;
    private String tags;
    private String content;
    private Integer status;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
