package com.planlock.record.journal.controller;

import com.planlock.common.ApiResponse;
import com.planlock.common.auth.UserContext;
import com.planlock.record.journal.dto.JournalResponse;
import com.planlock.record.journal.dto.JournalUpsertRequest;
import com.planlock.record.journal.service.JournalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 日常记录 REST 接口。
 */
@RestController
@RequestMapping("/api/v1/journals")
@RequiredArgsConstructor
public class JournalController {

    private static final MediaType MARKDOWN_UTF8 = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final JournalService journalService;

    @GetMapping
    public ApiResponse<List<JournalResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String mood) {
        return ApiResponse.ok(journalService.list(UserContext.userId(), from, to, mood));
    }

    @PostMapping
    public ApiResponse<JournalResponse> create(@Valid @RequestBody JournalUpsertRequest req) {
        return ApiResponse.ok(journalService.create(UserContext.userId(), req));
    }

    @PutMapping("/{id}")
    public ApiResponse<JournalResponse> update(@PathVariable Long id,
                                               @RequestParam Long baseVersion,
                                               @Valid @RequestBody JournalUpsertRequest req) {
        return ApiResponse.ok(journalService.update(UserContext.userId(), id, baseVersion, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestParam(required = false) Long baseVersion) {
        journalService.delete(UserContext.userId(), id, baseVersion);
        return ApiResponse.ok();
    }

    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam(defaultValue = "markdown") String format) {
        String body = journalService.exportMarkdown(UserContext.userId(), format);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MARKDOWN_UTF8)
                .body(body);
    }
}
