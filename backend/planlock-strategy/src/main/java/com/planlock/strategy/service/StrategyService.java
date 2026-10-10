package com.planlock.strategy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.strategy.dto.AppItem;
import com.planlock.strategy.dto.StrategyResponse;
import com.planlock.strategy.dto.StrategyUpsertRequest;
import com.planlock.strategy.entity.PlanStrategy;
import com.planlock.strategy.mapper.PlanStrategyMapper;
import com.planlock.strategy.mapper.StrategyPlanRefMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 执行策略服务（strategy 模块）。
 */
@Service
@RequiredArgsConstructor
public class StrategyService {

    private final PlanStrategyMapper strategyMapper;
    private final StrategyPlanRefMapper planRefMapper;
    private final ObjectMapper objectMapper;

    public StrategyResponse get(Long userId, Long planId) {
        requireOwnedPlan(userId, planId);
        PlanStrategy strategy = strategyMapper.findByPlanId(planId);
        if (strategy == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "执行策略不存在");
        }
        return toResponse(strategy);
    }

    @Transactional
    public StrategyResponse upsert(Long userId, Long planId, StrategyUpsertRequest req, Long baseVersion) {
        requireOwnedPlan(userId, planId);
        PlanStrategy existing = strategyMapper.findByPlanId(planId);
        if (existing == null) {
            PlanStrategy strategy = new PlanStrategy();
            strategy.setPlanId(planId);
            apply(strategy, req);
            strategy.setVersion(0L);
            strategyMapper.insert(strategy);
            return toResponse(strategyMapper.findByPlanId(planId));
        }
        if (baseVersion == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "缺少 baseVersion");
        }
        PlanStrategy strategy = new PlanStrategy();
        strategy.setPlanId(planId);
        apply(strategy, req);
        strategy.setVersion(baseVersion);
        int updated = strategyMapper.updateWithVersion(strategy);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        return toResponse(strategyMapper.findByPlanId(planId));
    }

    private void requireOwnedPlan(Long userId, Long planId) {
        if (planRefMapper.countOwnedPlan(planId, userId) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在");
        }
    }

    private void apply(PlanStrategy strategy, StrategyUpsertRequest req) {
        strategy.setBlacklistApps(writeJson(req.getBlacklistApps()));
        strategy.setWhitelistApps(writeJson(req.getWhitelistApps()));
        strategy.setAutoDnd(req.getAutoDnd());
        strategy.setNotifySilence(req.getNotifySilence());
    }

    private StrategyResponse toResponse(PlanStrategy s) {
        return StrategyResponse.builder()
                .id(s.getId())
                .planId(s.getPlanId())
                .blacklistApps(readAppList(s.getBlacklistApps()))
                .whitelistApps(readAppList(s.getWhitelistApps()))
                .autoDnd(s.getAutoDnd())
                .notifySilence(s.getNotifySilence())
                .version(s.getVersion())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "JSON 序列化失败");
        }
    }

    private List<AppItem> readAppList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AppItem>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "JSON 解析失败");
        }
    }
}
