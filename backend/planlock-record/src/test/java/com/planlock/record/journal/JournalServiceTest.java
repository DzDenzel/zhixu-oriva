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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JournalServiceTest {

    private final JournalMapper mapper = mock(JournalMapper.class);
    private final JournalService service = new JournalService(mapper, new ObjectMapper());

    @Test
    void createUsesClientIdIdempotencyAndUserScopedReadback() {
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
        when(mapper.findByIdForUpdate(1L, 88L)).thenReturn(stored);

        var response = service.create(1L, req);
        assertThat(response.getId()).isEqualTo(88L);
        assertThat(response.getTags()).containsExactly("编码");
        assertThat(response.getVersion()).isEqualTo(0L);
        verify(mapper).findByIdForUpdate(1L, 88L);
    }

    @Test
    void updateEnforcesUserScopedOwnershipAndOptimisticLock() {
        JournalUpsertRequest req = new JournalUpsertRequest();
        req.setEntryDate(LocalDate.of(2026, 11, 25));
        req.setContent("越权尝试");

        when(mapper.updateWithVersion(any())).thenReturn(0);
        when(mapper.findStateForUpdate(1L, 10L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(1L, 10L, 0L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.NOT_FOUND.getCode());

        JournalEntry owned = new JournalEntry();
        owned.setId(11L);
        owned.setUserId(1L);
        owned.setStatus(1);
        owned.setVersion(1L);
        when(mapper.findStateForUpdate(1L, 11L)).thenReturn(owned);

        assertThatThrownBy(() -> service.update(1L, 11L, 0L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.VERSION_CONFLICT.getCode());
    }

    @Test
    void softDeleteReturnsIdenticalNotFoundForMissingAndForeignUserIdsAndIdempotentForOwnDeleted() {
        // Missing ID -> NOT_FOUND
        when(mapper.findStateForUpdate(1L, 999L)).thenReturn(null);
        assertThatThrownBy(() -> service.delete(1L, 999L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.NOT_FOUND.getCode());

        // Foreign user's active or deleted ID -> SQL scopes by userId -> null -> identical NOT_FOUND
        when(mapper.findStateForUpdate(2L, 20L)).thenReturn(null);
        assertThatThrownBy(() -> service.delete(2L, 20L, 0L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.NOT_FOUND.getCode());

        // Own already soft-deleted record -> idempotent success
        JournalEntry ownDeleted = new JournalEntry();
        ownDeleted.setId(21L);
        ownDeleted.setUserId(1L);
        ownDeleted.setStatus(0);
        ownDeleted.setVersion(1L);
        when(mapper.findStateForUpdate(1L, 21L)).thenReturn(ownDeleted);
        service.delete(1L, 21L, 0L);

        // Own active record with stale baseVersion -> VERSION_CONFLICT
        JournalEntry ownActive = new JournalEntry();
        ownActive.setId(22L);
        ownActive.setUserId(1L);
        ownActive.setStatus(1);
        ownActive.setVersion(2L);
        when(mapper.findStateForUpdate(1L, 22L)).thenReturn(ownActive);
        when(mapper.softDeleteWithVersion(22L, 1L, 1L)).thenReturn(0);
        assertThatThrownBy(() -> service.delete(1L, 22L, 1L))
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
