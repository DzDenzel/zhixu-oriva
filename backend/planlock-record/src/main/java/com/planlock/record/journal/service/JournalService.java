package com.planlock.record.journal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.record.journal.JournalRules;
import com.planlock.record.journal.dto.JournalResponse;
import com.planlock.record.journal.dto.JournalUpsertRequest;
import com.planlock.record.journal.entity.JournalEntry;
import com.planlock.record.journal.mapper.JournalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 日常记录服务：CRUD、所有权隔离、乐观锁、clientId 幂等与 Markdown 导出。
 */
@Service
@RequiredArgsConstructor
public class JournalService {

    private final JournalMapper journalMapper;
    private final ObjectMapper objectMapper;

    public List<JournalResponse> list(Long userId, LocalDate from, LocalDate to, String mood) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "开始日期不能晚于结束日期");
        }
        String normalizedMood = JournalRules.normalizeMood(mood);
        return journalMapper.findByUserIdFiltered(userId, from, to, normalizedMood)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public JournalResponse create(Long userId, JournalUpsertRequest req) {
        JournalRules.validateClientId(req.getClientId());
        JournalEntry entry = new JournalEntry();
        entry.setUserId(userId);
        entry.setClientId(req.getClientId());
        applyValidated(entry, req);
        entry.setStatus(1);
        entry.setVersion(0L);
        journalMapper.insert(entry);
        return toResponse(journalMapper.findByIdForUpdate(entry.getId()));
    }

    @Transactional
    public JournalResponse update(Long userId, Long id, Long baseVersion, JournalUpsertRequest req) {
        if (baseVersion == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "缺少 baseVersion");
        }
        JournalEntry existing = journalMapper.findById(id);
        if (existing == null || !existing.getUserId().equals(userId) || Integer.valueOf(0).equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "日常记录不存在");
        }
        JournalRules.validateClientId(req.getClientId());
        JournalEntry next = new JournalEntry();
        next.setId(id);
        next.setUserId(userId);
        next.setVersion(baseVersion);
        applyValidated(next, req);

        if (journalMapper.updateWithVersion(next) == 0) {
            JournalEntry current = journalMapper.findByIdForUpdate(id);
            if (current == null || !current.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "日常记录不存在");
            }
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        return toResponse(journalMapper.findById(id));
    }

    @Transactional
    public void delete(Long userId, Long id, Long baseVersion) {
        JournalEntry existing = journalMapper.findById(id);
        if (existing == null) {
            return;
        }
        if (!existing.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "日常记录不存在");
        }
        if (Integer.valueOf(0).equals(existing.getStatus())) {
            return;
        }
        if (baseVersion == null) {
            journalMapper.softDelete(id, userId);
        } else if (journalMapper.softDeleteWithVersion(id, userId, baseVersion) == 0) {
            JournalEntry current = journalMapper.findByIdForUpdate(id);
            if (current == null || Integer.valueOf(0).equals(current.getStatus())) {
                return;
            }
            if (!current.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "日常记录不存在");
            }
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    public String exportMarkdown(Long userId, String format) {
        if (format != null && !"markdown".equalsIgnoreCase(format.trim())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "仅支持 markdown 导出");
        }
        List<JournalEntry> entries = journalMapper.findAllActiveByUserId(userId);
        return JournalRules.exportMarkdown(userId, entries, objectMapper, LocalDateTime.now());
    }

    private void applyValidated(JournalEntry target, JournalUpsertRequest req) {
        if (req.getEntryDate() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "记录日期不能为空");
        }
        target.setEntryDate(req.getEntryDate());
        target.setMood(JournalRules.normalizeMood(req.getMood()));
        List<String> normalizedTags = JournalRules.normalizeTags(req.getTags());
        target.setTags(JournalRules.serializeTags(normalizedTags, objectMapper));
        target.setContent(JournalRules.normalizeContent(req.getContent()));
    }

    private JournalResponse toResponse(JournalEntry e) {
        return JournalResponse.builder()
                .id(e.getId())
                .clientId(e.getClientId())
                .userId(e.getUserId())
                .entryDate(e.getEntryDate())
                .mood(e.getMood())
                .tags(JournalRules.parseTags(e.getTags(), objectMapper))
                .content(e.getContent())
                .status(e.getStatus())
                .version(e.getVersion())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
