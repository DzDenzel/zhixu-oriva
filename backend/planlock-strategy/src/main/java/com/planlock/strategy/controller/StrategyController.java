package com.planlock.strategy.controller;

import com.planlock.common.ApiResponse;
import com.planlock.common.auth.UserContext;
import com.planlock.strategy.dto.StrategyResponse;
import com.planlock.strategy.dto.StrategyUpsertRequest;
import com.planlock.strategy.service.StrategyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行策略接口（对应 04-技术实现/接口设计.md 2.4）。
 */
@RestController
@RequestMapping("/api/v1/plans/{planId}/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyService strategyService;

    @GetMapping
    public ApiResponse<StrategyResponse> get(@PathVariable Long planId) {
        return ApiResponse.ok(strategyService.get(UserContext.userId(), planId));
    }

    @PutMapping
    public ApiResponse<StrategyResponse> upsert(@PathVariable Long planId,
                                                @RequestParam(required = false) Long baseVersion,
                                                @Valid @RequestBody StrategyUpsertRequest req) {
        return ApiResponse.ok(strategyService.upsert(UserContext.userId(), planId, req, baseVersion));
    }
}
