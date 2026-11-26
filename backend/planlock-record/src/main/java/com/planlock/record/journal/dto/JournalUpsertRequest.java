package com.planlock.record.journal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 日常记录创建/更新请求（含前置边界约束）。
 */
@Data
public class JournalUpsertRequest {
    @Size(min = 1, max = 64, message = "clientId 长度须为 1 至 64")
    private String clientId;

    @NotNull(message = "记录日期不能为空")
    private LocalDate entryDate;

    @Size(max = 64, message = "心情长度超出限制")
    private String mood;

    @Size(max = 32, message = "标签列表项数超出限制")
    private List<@Size(max = 64, message = "单个标签长度超出限制") String> tags;

    @NotBlank(message = "正文不能为空")
    @Size(max = 20000, message = "正文长度超出限制")
    private String content;
}
