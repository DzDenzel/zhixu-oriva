package com.planlock.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.ErrorCode;
import com.planlock.record.journal.entity.JournalEntry;
import com.planlock.sync.dto.SyncItemResult;
import com.planlock.sync.dto.SyncPushRequest;
import com.planlock.sync.mapper.SyncMapper;
import com.planlock.sync.service.SyncService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SyncJournalTest {

    private final SyncMapper mapper = mock(SyncMapper.class);
    private final SyncService service = new SyncService(mapper, new ObjectMapper());

    @Test
    void pushWithoutJournalsRemainsBackwardCompatible() {
        var req = new SyncPushRequest();
        var res = service.push(1L, req);
        assertThat(res.getResults()).isEmpty();
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void pullMapsJournalEntriesWithParsedTags() {
        JournalEntry entry = new JournalEntry();
        entry.setId(5L);
        entry.setUserId(1L);
        entry.setClientId("c-5");
        entry.setEntryDate(LocalDate.of(2026, 11, 25));
        entry.setMood("平静");
        entry.setTags("[\"散步\",\"复盘\"]");
        entry.setContent("晚间散步");
        entry.setStatus(1);
        entry.setVersion(2L);
        when(mapper.pullJournalEntries(1L)).thenReturn(List.of(entry));

        var pull = service.pull(1L);
        assertThat(pull.getJournals()).hasSize(1);
        assertThat(pull.getJournals().getFirst().getTags()).containsExactly("散步", "复盘");
        assertThat(pull.getJournals().getFirst().getVersion()).isEqualTo(2L);
    }

    @Test
    void pushJournalHandlesCreateIdempotencyConflictAndSoftDelete() {
        var create = new SyncPushRequest.JournalItem();
        create.setClientId("client-create-1");
        create.setEntryDate(LocalDate.of(2026, 11, 25));
        create.setMood("开心");
        create.setTags(List.of("测试", "测试"));
        create.setContent("第一条");

        doAnswer(inv -> {
            SyncPushRequest.JournalItem j = inv.getArgument(1);
            j.setId(101L);
            return 1;
        }).when(mapper).insertJournal(eq(1L), eq(create), eq("[\"测试\"]"));
        SyncItemResult createdIdentity = new SyncItemResult();
        createdIdentity.setId(101L);
        createdIdentity.setVersion(0L);
        createdIdentity.setClientId("client-create-1");
        when(mapper.journalIdentity(1L, 101L)).thenReturn(createdIdentity);

        var conflict = new SyncPushRequest.JournalItem();
        conflict.setId(202L);
        conflict.setBaseVersion(0L);
        conflict.setEntryDate(LocalDate.of(2026, 11, 25));
        conflict.setContent("并发编辑");
        when(mapper.updateJournal(eq(1L), eq(conflict), any())).thenReturn(0);
        when(mapper.countOwnedJournal(202L, 1L)).thenReturn(1);

        var foreign = new SyncPushRequest.JournalItem();
        foreign.setId(303L);
        foreign.setBaseVersion(0L);
        foreign.setEntryDate(LocalDate.of(2026, 11, 25));
        foreign.setContent("越权编辑");
        when(mapper.updateJournal(eq(1L), eq(foreign), any())).thenReturn(0);
        when(mapper.countOwnedJournal(303L, 1L)).thenReturn(0);

        var softDelete = new SyncPushRequest.JournalItem();
        softDelete.setId(404L);
        softDelete.setBaseVersion(1L);
        softDelete.setEntryDate(LocalDate.of(2026, 11, 25));
        softDelete.setContent("删除");
        softDelete.setStatus(0);
        when(mapper.updateJournal(eq(1L), eq(softDelete), any())).thenReturn(1);
        SyncItemResult deletedIdentity = new SyncItemResult();
        deletedIdentity.setId(404L);
        deletedIdentity.setVersion(2L);
        when(mapper.journalIdentity(1L, 404L)).thenReturn(deletedIdentity);

        var req = new SyncPushRequest();
        req.setJournals(List.of(create, conflict, foreign, softDelete));
        var results = service.push(1L, req).getResults();

        assertThat(results).hasSize(4);
        assertThat(results.get(0).isOk()).isTrue();
        assertThat(results.get(0).getId()).isEqualTo(101L);
        assertThat(results.get(1).isOk()).isFalse();
        assertThat(results.get(1).getCode()).isEqualTo(ErrorCode.VERSION_CONFLICT.getCode());
        assertThat(results.get(2).isOk()).isFalse();
        assertThat(results.get(2).getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
        assertThat(results.get(3).isOk()).isTrue();
        assertThat(results.get(3).getVersion()).isEqualTo(2L);
        verify(mapper).insertJournal(eq(1L), eq(create), eq("[\"测试\"]"));
    }
}
