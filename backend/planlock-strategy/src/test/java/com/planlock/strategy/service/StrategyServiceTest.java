package com.planlock.strategy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.strategy.dto.AppItem;
import com.planlock.strategy.dto.StrategyResponse;
import com.planlock.strategy.dto.StrategyUpsertRequest;
import com.planlock.strategy.entity.PlanStrategy;
import com.planlock.strategy.mapper.PlanStrategyMapper;
import com.planlock.strategy.mapper.StrategyPlanRefMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StrategyService 单元测试（纯 JVM + Mockito，覆盖权限归属、名单 JSON 编解码与乐观锁）。
 */
@ExtendWith(MockitoExtension.class)
class StrategyServiceTest {

    @Mock
    private PlanStrategyMapper strategyMapper;

    @Mock
    private StrategyPlanRefMapper planRefMapper;

    private ObjectMapper objectMapper;
    private StrategyService strategyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        strategyService = new StrategyService(strategyMapper, planRefMapper, objectMapper);
    }

    @Test
    void getParsesBlacklistAndWhitelistApps() {
        when(planRefMapper.countOwnedPlan(10L, 1L)).thenReturn(1);
        PlanStrategy stored = new PlanStrategy();
        stored.setId(100L);
        stored.setPlanId(10L);
        stored.setBlacklistApps("[{\"name\":\"短视频\",\"packageName\":\"com.example.video\"}]");
        stored.setWhitelistApps("[{\"name\":\"电话\",\"packageName\":\"com.android.dialer\"}]");
        stored.setAutoDnd(true);
        stored.setNotifySilence(false);
        stored.setVersion(3L);
        stored.setCreatedAt(LocalDateTime.of(2026, 11, 20, 8, 0));
        stored.setUpdatedAt(LocalDateTime.of(2026, 11, 20, 9, 0));
        when(strategyMapper.findByPlanId(10L)).thenReturn(stored);

        StrategyResponse response = strategyService.get(1L, 10L);

        assertThat(response.getId()).isEqualTo(100L);
        assertThat(response.getPlanId()).isEqualTo(10L);
        assertThat(response.getAutoDnd()).isTrue();
        assertThat(response.getNotifySilence()).isFalse();
        assertThat(response.getVersion()).isEqualTo(3L);
        assertThat(response.getBlacklistApps()).hasSize(1);
        assertThat(response.getBlacklistApps().get(0).getName()).isEqualTo("短视频");
        assertThat(response.getBlacklistApps().get(0).getPackageName()).isEqualTo("com.example.video");
        assertThat(response.getWhitelistApps()).hasSize(1);
        assertThat(response.getWhitelistApps().get(0).getPackageName()).isEqualTo("com.android.dialer");
    }

    @Test
    void getHandlesNullAndBlankAppListsGracefully() {
        when(planRefMapper.countOwnedPlan(11L, 1L)).thenReturn(1);
        PlanStrategy stored = new PlanStrategy();
        stored.setId(101L);
        stored.setPlanId(11L);
        stored.setBlacklistApps(null);
        stored.setWhitelistApps("   ");
        stored.setVersion(0L);
        when(strategyMapper.findByPlanId(11L)).thenReturn(stored);

        StrategyResponse response = strategyService.get(1L, 11L);

        assertThat(response.getBlacklistApps()).isNull();
        assertThat(response.getWhitelistApps()).isNull();
    }

    @Test
    void getThrowsNotFoundWhenPlanNotOwnedByUser() {
        when(planRefMapper.countOwnedPlan(10L, 2L)).thenReturn(0);

        assertThatThrownBy(() -> strategyService.get(2L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND.getCode()));
        verify(strategyMapper, never()).findByPlanId(any());
    }

    @Test
    void getThrowsNotFoundWhenStrategyDoesNotExist() {
        when(planRefMapper.countOwnedPlan(10L, 1L)).thenReturn(1);
        when(strategyMapper.findByPlanId(10L)).thenReturn(null);

        assertThatThrownBy(() -> strategyService.get(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND.getCode()));
    }

    @Test
    void getThrowsInternalErrorWhenStoredJsonCorrupted() {
        when(planRefMapper.countOwnedPlan(10L, 1L)).thenReturn(1);
        PlanStrategy corrupted = new PlanStrategy();
        corrupted.setId(102L);
        corrupted.setPlanId(10L);
        corrupted.setWhitelistApps("{not-an-array");
        when(strategyMapper.findByPlanId(10L)).thenReturn(corrupted);

        assertThatThrownBy(() -> strategyService.get(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR.getCode()));
    }

    @Test
    void upsertInsertsNewStrategyWithVersionZeroWhenNotExisting() {
        when(planRefMapper.countOwnedPlan(20L, 1L)).thenReturn(1);
        PlanStrategy reloaded = new PlanStrategy();
        reloaded.setId(200L);
        reloaded.setPlanId(20L);
        reloaded.setBlacklistApps("[{\"name\":\"游戏\",\"packageName\":\"com.example.game\"}]");
        reloaded.setWhitelistApps("[]");
        reloaded.setAutoDnd(true);
        reloaded.setNotifySilence(true);
        reloaded.setVersion(0L);
        when(strategyMapper.findByPlanId(20L)).thenReturn(null, reloaded);
        when(strategyMapper.insert(any(PlanStrategy.class))).thenReturn(1);

        StrategyUpsertRequest req = new StrategyUpsertRequest();
        req.setBlacklistApps(List.of(appItem("游戏", "com.example.game")));
        req.setWhitelistApps(List.of());
        req.setAutoDnd(true);
        req.setNotifySilence(true);

        StrategyResponse response = strategyService.upsert(1L, 20L, req, null);

        ArgumentCaptor<PlanStrategy> captor = ArgumentCaptor.forClass(PlanStrategy.class);
        verify(strategyMapper).insert(captor.capture());
        PlanStrategy inserted = captor.getValue();
        assertThat(inserted.getPlanId()).isEqualTo(20L);
        assertThat(inserted.getVersion()).isEqualTo(0L);
        assertThat(inserted.getBlacklistApps()).contains("com.example.game");
        assertThat(inserted.getWhitelistApps()).isEqualTo("[]");
        assertThat(inserted.getAutoDnd()).isTrue();
        assertThat(inserted.getNotifySilence()).isTrue();

        assertThat(response.getId()).isEqualTo(200L);
        assertThat(response.getBlacklistApps()).hasSize(1);
        assertThat(response.getWhitelistApps()).isEmpty();
    }

    @Test
    void upsertInsertThrowsNotFoundWhenParentPlanDisappeared() {
        when(planRefMapper.countOwnedPlan(21L, 1L)).thenReturn(1);
        when(strategyMapper.findByPlanId(21L)).thenReturn(null);
        when(strategyMapper.insert(any(PlanStrategy.class))).thenReturn(0);

        StrategyUpsertRequest req = new StrategyUpsertRequest();

        assertThatThrownBy(() -> strategyService.upsert(1L, 21L, req, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND.getCode()));
    }

    @Test
    void upsertUpdateRejectsMissingBaseVersion() {
        when(planRefMapper.countOwnedPlan(30L, 1L)).thenReturn(1);
        PlanStrategy existing = new PlanStrategy();
        existing.setId(300L);
        existing.setPlanId(30L);
        existing.setVersion(2L);
        when(strategyMapper.findByPlanId(30L)).thenReturn(existing);

        assertThatThrownBy(() -> strategyService.upsert(1L, 30L, new StrategyUpsertRequest(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM.getCode()));
        verify(strategyMapper, never()).updateWithVersion(any());
    }

    @Test
    void upsertUpdateThrowsVersionConflictWhenOptimisticLockFails() {
        when(planRefMapper.countOwnedPlan(30L, 1L)).thenReturn(1);
        PlanStrategy existing = new PlanStrategy();
        existing.setId(300L);
        existing.setPlanId(30L);
        existing.setVersion(2L);
        when(strategyMapper.findByPlanId(30L)).thenReturn(existing);
        when(strategyMapper.updateWithVersion(any(PlanStrategy.class))).thenReturn(0);

        assertThatThrownBy(() -> strategyService.upsert(1L, 30L, new StrategyUpsertRequest(), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VERSION_CONFLICT.getCode()));
    }

    @Test
    void upsertUpdateSucceedsWithMatchingBaseVersion() {
        when(planRefMapper.countOwnedPlan(30L, 1L)).thenReturn(1);
        PlanStrategy existing = new PlanStrategy();
        existing.setId(300L);
        existing.setPlanId(30L);
        existing.setVersion(2L);

        PlanStrategy updated = new PlanStrategy();
        updated.setId(300L);
        updated.setPlanId(30L);
        updated.setWhitelistApps("[{\"name\":\"微信\",\"packageName\":\"com.tencent.mm\"}]");
        updated.setAutoDnd(false);
        updated.setNotifySilence(true);
        updated.setVersion(3L);

        when(strategyMapper.findByPlanId(30L)).thenReturn(existing, updated);
        when(strategyMapper.updateWithVersion(any(PlanStrategy.class))).thenReturn(1);

        StrategyUpsertRequest req = new StrategyUpsertRequest();
        req.setWhitelistApps(List.of(appItem("微信", "com.tencent.mm")));
        req.setAutoDnd(false);
        req.setNotifySilence(true);

        StrategyResponse response = strategyService.upsert(1L, 30L, req, 2L);

        ArgumentCaptor<PlanStrategy> captor = ArgumentCaptor.forClass(PlanStrategy.class);
        verify(strategyMapper).updateWithVersion(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(2L);
        assertThat(captor.getValue().getBlacklistApps()).isNull();
        assertThat(captor.getValue().getWhitelistApps()).contains("com.tencent.mm");
        assertThat(response.getVersion()).isEqualTo(3L);
        assertThat(response.getWhitelistApps()).hasSize(1);
    }

    @Test
    void upsertRejectsUnownedPlan() {
        when(planRefMapper.countOwnedPlan(99L, 2L)).thenReturn(0);

        assertThatThrownBy(() -> strategyService.upsert(2L, 99L, new StrategyUpsertRequest(), 0L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND.getCode()));
        verify(strategyMapper, never()).findByPlanId(any());
    }

    @Test
    void upsertThrowsInvalidParamWhenSerializationFails() throws Exception {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("boom") {
                });
        StrategyService brokenService = new StrategyService(strategyMapper, planRefMapper, brokenMapper);
        when(planRefMapper.countOwnedPlan(40L, 1L)).thenReturn(1);
        when(strategyMapper.findByPlanId(40L)).thenReturn(null);

        StrategyUpsertRequest req = new StrategyUpsertRequest();
        req.setBlacklistApps(List.of(appItem("A", "com.a")));

        assertThatThrownBy(() -> brokenService.upsert(1L, 40L, req, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM.getCode()));
    }

    @Test
    void upsertDefaultsNullFlagsToFalseOnInsert() {
        when(planRefMapper.countOwnedPlan(50L, 1L)).thenReturn(1);
        PlanStrategy reloaded = new PlanStrategy();
        reloaded.setId(500L);
        reloaded.setPlanId(50L);
        reloaded.setAutoDnd(false);
        reloaded.setNotifySilence(true);
        reloaded.setVersion(0L);
        when(strategyMapper.findByPlanId(50L)).thenReturn(null, reloaded);
        when(strategyMapper.insert(any(PlanStrategy.class))).thenReturn(1);

        StrategyUpsertRequest req = new StrategyUpsertRequest();
        req.setNotifySilence(true);

        strategyService.upsert(1L, 50L, req, null);

        ArgumentCaptor<PlanStrategy> captor = ArgumentCaptor.forClass(PlanStrategy.class);
        verify(strategyMapper).insert(captor.capture());
        assertThat(captor.getValue().getAutoDnd()).isFalse();
        assertThat(captor.getValue().getNotifySilence()).isTrue();
    }

    @Test
    void upsertPreservesExistingFlagsWhenOmittedOnUpdate() {
        when(planRefMapper.countOwnedPlan(51L, 1L)).thenReturn(1);
        PlanStrategy existing = new PlanStrategy();
        existing.setId(501L);
        existing.setPlanId(51L);
        existing.setAutoDnd(true);
        existing.setNotifySilence(false);
        existing.setVersion(1L);
        PlanStrategy reloaded = new PlanStrategy();
        reloaded.setId(501L);
        reloaded.setPlanId(51L);
        reloaded.setAutoDnd(true);
        reloaded.setNotifySilence(true);
        reloaded.setVersion(2L);
        when(strategyMapper.findByPlanId(51L)).thenReturn(existing, reloaded);
        when(strategyMapper.updateWithVersion(any(PlanStrategy.class))).thenReturn(1);

        StrategyUpsertRequest req = new StrategyUpsertRequest();
        req.setNotifySilence(true);

        strategyService.upsert(1L, 51L, req, 1L);

        ArgumentCaptor<PlanStrategy> captor = ArgumentCaptor.forClass(PlanStrategy.class);
        verify(strategyMapper).updateWithVersion(captor.capture());
        assertThat(captor.getValue().getAutoDnd()).isTrue();
        assertThat(captor.getValue().getNotifySilence()).isTrue();
    }

    private static AppItem appItem(String name, String packageName) {
        AppItem item = new AppItem();
        item.setName(name);
        item.setPackageName(packageName);
        return item;
    }
}
