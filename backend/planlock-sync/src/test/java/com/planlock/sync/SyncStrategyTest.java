package com.planlock.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.sync.dto.SyncItemResult;
import com.planlock.sync.dto.SyncPushRequest;
import com.planlock.sync.mapper.SyncMapper;
import com.planlock.sync.service.SyncService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SyncStrategyTest {
    private final SyncMapper mapper = mock(SyncMapper.class);
    private final SyncService service = new SyncService(mapper, new ObjectMapper());

    @Test
    void insertNormalizesOmittedFlagsToFalseInsteadOfNull() {
        when(mapper.countOwnedPlan(2L, 1L)).thenReturn(1);
        when(mapper.countStrategy(2L)).thenReturn(0);
        when(mapper.insertStrategy(any())).thenReturn(1);
        when(mapper.strategyIdentity(1L, 2L)).thenReturn(SyncItemResult.builder().id(10L).version(0L).build());

        var item = new SyncPushRequest.StrategyItem();
        item.setPlanId(2L);
        item.setNotifySilence(true);

        var req = new SyncPushRequest();
        req.setStrategies(List.of(item));
        var results = service.push(1L, req).getResults();

        assertTrue(results.getFirst().isOk());
        assertEquals(Boolean.FALSE, item.getAutoDnd());
        assertEquals(Boolean.TRUE, item.getNotifySilence());
        verify(mapper).insertStrategy(item);
    }

    @Test
    void insertNormalizesBothOmittedFlagsToFalse() {
        when(mapper.countOwnedPlan(3L, 1L)).thenReturn(1);
        when(mapper.countStrategy(3L)).thenReturn(0);
        when(mapper.insertStrategy(any())).thenReturn(1);
        when(mapper.strategyIdentity(1L, 3L)).thenReturn(SyncItemResult.builder().id(11L).version(0L).build());

        var item = new SyncPushRequest.StrategyItem();
        item.setPlanId(3L);

        var req = new SyncPushRequest();
        req.setStrategies(List.of(item));
        var results = service.push(1L, req).getResults();

        assertTrue(results.getFirst().isOk());
        assertEquals(Boolean.FALSE, item.getAutoDnd());
        assertEquals(Boolean.FALSE, item.getNotifySilence());
    }

    @Test
    void updatePassesNullSwitchThroughForCoalescePreservation() {
        when(mapper.countOwnedPlan(2L, 1L)).thenReturn(1);
        when(mapper.countStrategy(2L)).thenReturn(1);
        when(mapper.updateStrategy(any())).thenReturn(1);
        when(mapper.strategyIdentity(1L, 2L)).thenReturn(SyncItemResult.builder().id(10L).version(1L).build());

        var item = new SyncPushRequest.StrategyItem();
        item.setPlanId(2L);
        item.setBaseVersion(0L);
        item.setNotifySilence(false);

        var req = new SyncPushRequest();
        req.setStrategies(List.of(item));
        var results = service.push(1L, req).getResults();

        assertTrue(results.getFirst().isOk());
        assertNull(item.getAutoDnd());
        assertEquals(Boolean.FALSE, item.getNotifySilence());
        verify(mapper).updateStrategy(item);
    }
}
