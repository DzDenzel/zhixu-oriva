package com.planlock.sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.schedule.ScheduleRules;
import com.planlock.schedule.dto.ScheduleUpsertRequest;
import com.planlock.sync.dto.SyncItemResult;
import com.planlock.sync.dto.SyncPullResponse;
import com.planlock.sync.dto.SyncPushRequest;
import com.planlock.sync.dto.SyncPushResponse;
import com.planlock.sync.mapper.SyncMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Full snapshot sync with optimistic conflicts and persistent creation identities. */
@Service
@RequiredArgsConstructor
public class SyncService {

    private final SyncMapper syncMapper;
    private final ObjectMapper objectMapper;

    public SyncPullResponse pull(Long userId) {
        return SyncPullResponse.builder()
                .plans(syncMapper.pullPlans(userId))
                .schedules(syncMapper.pullSchedules(userId))
                .strategies(syncMapper.pullStrategies(userId))
                .recordCounts(Map.of(
                        "execution", syncMapper.countExecutions(userId),
                        "violation", syncMapper.countViolations(userId),
                        "unlock", syncMapper.countUnlocks(userId)))
                .build();
    }

    @Transactional
    public SyncPushResponse push(Long userId, SyncPushRequest req) {
        List<SyncItemResult> results = new ArrayList<>();
        if (req.getPlans() != null) {
            for (var item : req.getPlans()) {
                String key = "plan:" + item.getId();
                try {
                    pushPlan(userId, item);
                    results.add(ok(key, syncMapper.planIdentity(userId, item.getId())));
                } catch (BusinessException e) {
                    results.add(fail(key, e, item.getClientId()));
                }
            }
        }
        if (req.getStrategies() != null) {
            for (var item : req.getStrategies()) {
                String key = "strategy:" + item.getPlanId();
                try {
                    pushStrategy(userId, item);
                    results.add(ok(key, syncMapper.strategyIdentity(userId, item.getPlanId())));
                } catch (BusinessException e) {
                    results.add(fail(key, e, null));
                }
            }
        }
        if (req.getSchedules() != null) {
            for (var item : req.getSchedules()) {
                String key = "schedule:" + item.getId();
                try {
                    pushSchedule(userId, item);
                    results.add(ok(key, syncMapper.scheduleIdentity(userId, item.getId())));
                } catch (BusinessException e) {
                    results.add(fail(key, e, item.getClientId()));
                }
            }
        }
        return new SyncPushResponse(results);
    }

    private void validateClientId(String clientId) {
        if (clientId != null && (clientId.isBlank() || clientId.length() > 64)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "clientId 长度须为 1 至 64");
        }
    }

    private void pushSchedule(Long userId, SyncPushRequest.ScheduleItem item) {
        validateClientId(item.getClientId());
        ScheduleUpsertRequest input = new ScheduleUpsertRequest();
        input.setStartDate(item.getStartDate());
        input.setEndDate(item.getEndDate());
        input.setStartTime(item.getStartTime());
        input.setEndTime(item.getEndTime());
        input.setAllDay(item.getAllDay());
        input.setRepeatRule(item.getRepeatRule());
        ScheduleRules.validate(input, objectMapper);

        if (item.getStatus() == null) {
            item.setStatus(1);
        }
        if (item.getId() == null) {
            syncMapper.insertSchedule(userId, item);
            return;
        }
        if (item.getBaseVersion() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "缺少 baseVersion");
        }
        if (syncMapper.updateSchedule(userId, item) == 0) {
            if (syncMapper.countOwnedSchedule(item.getId(), userId) == 0) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "日程不存在");
            }
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private void pushPlan(Long userId, SyncPushRequest.PlanItem item) {
        validateClientId(item.getClientId());
        if (item.getRemindAdvanceMin() == null) {
            item.setRemindAdvanceMin(15);
        }
        if (item.getStatus() == null) {
            item.setStatus(1);
        }
        if (item.getId() == null) {
            syncMapper.insertPlan(userId, item);
            return;
        }
        if (item.getBaseVersion() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "缺少 baseVersion");
        }
        if (syncMapper.updatePlan(userId, item) == 0) {
            if (syncMapper.countOwnedPlan(item.getId(), userId) == 0) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在");
            }
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private void pushStrategy(Long userId, SyncPushRequest.StrategyItem item) {
        if (syncMapper.countOwnedPlan(item.getPlanId(), userId) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在");
        }
        if (syncMapper.countStrategy(item.getPlanId()) == 0) {
            syncMapper.insertStrategy(item);
            return;
        }
        if (item.getBaseVersion() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "缺少 baseVersion");
        }
        if (syncMapper.updateStrategy(item) == 0) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private SyncItemResult ok(String key, SyncItemResult identity) {
        if (identity == null) {
            identity = new SyncItemResult();
        }
        identity.setKey(key);
        identity.setOk(true);
        identity.setCode(0);
        identity.setMessage("ok");
        return identity;
    }

    private SyncItemResult fail(String key, BusinessException e, String clientId) {
        return SyncItemResult.builder()
                .key(key).ok(false).code(e.getCode()).message(e.getMessage()).clientId(clientId)
                .build();
    }
}
