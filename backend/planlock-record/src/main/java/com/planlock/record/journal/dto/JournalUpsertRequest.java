package com.planlock.record.journal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 日常记录创建/更新请求。
 */
@Data
public class JournalUpsertRequest {
    @Size(min = 1, max = 64)
    private String clientId;

    @NotNull(message = "记录日期不能为空")
    private LocalDate entryDate;

    private String mood;

    private List<String> tags;

    @NotBlank(message = "正文不能为空")
    private String content;
}
