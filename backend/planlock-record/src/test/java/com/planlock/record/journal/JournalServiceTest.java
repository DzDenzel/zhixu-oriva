package com.planlock.record.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.record.journal.dto.JournalUpsertRequest;
import com.planlock.record.journal.entity.JournalEntry;
import com.planlock.record.journal.mapper.JournalMapper;
import com.planlock.record.journal.service.JournalService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JournalServiceTest {

    private final JournalMapper mapper = mock(JournalMapper.class);
    private final JournalService service = new JournalService(mapper, new ObjectMapper());

    @Test
    void createUsesClientIdIdempotencyAndReturnsPersistedRow() {
        JournalUpsertRequest req = new JournalUpsertRequest();
        req.setClientId("j-client-1");
        req.setEntryDate(LocalDate.of(2026, 11, 25));
        req.setMood("专注");
        req.setTags(List.of("编码", "编码"));
        req.setContent("完成了核心模块");

        JournalEntry stored = new JournalEntry();
        stored.setId(88L);
        stored.setUserId(1L);
        stored.setClientId("j-client-1");
        stored.setEntryDate(req.getEntryDate());
        stored.setMood("专注");
        stored.setTags("[\"编码\"]");
        stored.setContent("完成了核心模块");
        stored.setStatus(1);
        stored.setVersion(0L);

        doAnswer(inv -> {
            JournalEntry arg = inv.getArgument(0);
            arg.setId(88L);
            return 1;
        }).when(mapper).insert(any());
        when(mapper.findByIdForUpdate(88L)).thenReturn(stored);

        var response = service.create(1L, req);
        assertThat(response.getId()).isEqualTo(88L);
        assertThat(response.getTags()).containsExactly("编码");
        assertThat(response.getVersion()).isEqualTo(0L);
    }

    @Test
    void updateEnforcesOwnershipAndOptimisticLock() {
        JournalEntry foreign = new JournalEntry();
        foreign.setId(10L);
        foreign.setUserId(99L);
        foreign.setStatus(1);
        when(mapper.findById(10L)).thenReturn(foreign);

        JournalUpsertRequest req = new JournalUpsertRequest();
        req.setEntryDate(LocalDate.of(2026, 11, 25));
        req.setContent("越权尝试");

        assertThatThrownBy(() -> service.update(1L, 10L, 0L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.NOT_FOUND.getCode());
        verify(mapper, never()).updateWithVersion(any());

        JournalEntry owned = new JournalEntry();
        owned.setId(11L);
        owned.setUserId(1L);
        owned.setStatus(1);
        owned.setVersion(1L);
        when(mapper.findById(11L)).thenReturn(owned);
        when(mapper.updateWithVersion(any())).thenReturn(0);
        when(mapper.findByIdForUpdate(11L)).thenReturn(owned);

        assertThatThrownBy(() -> service.update(1L, 11L, 0L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.VERSION_CONFLICT.getCode());
    }

    @Test
    void softDeleteRejectsForeignUserAndDetectsVersionConflict() {
        JournalEntry foreign = new JournalEntry();
        foreign.setId(20L);
        foreign.setUserId(2L);
        foreign.setStatus(1);
        when(mapper.findById(20L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.delete(1L, 20L, 0L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.NOT_FOUND.getCode());

        JournalEntry owned = new JournalEntry();
        owned.setId(21L);
        owned.setUserId(1L);
        owned.setStatus(1);
        owned.setVersion(2L);
        when(mapper.findById(21L)).thenReturn(owned);
        when(mapper.softDeleteWithVersion(21L, 1L, 1L)).thenReturn(0);
        when(mapper.findByIdForUpdate(21L)).thenReturn(owned);

        assertThatThrownBy(() -> service.delete(1L, 21L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.VERSION_CONFLICT.getCode());
    }

    @Test
    void listValidatesDateRange() {
        assertThatThrownBy(() -> service.list(
                1L, LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 25), null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_PARAM.getCode());
    }
}
