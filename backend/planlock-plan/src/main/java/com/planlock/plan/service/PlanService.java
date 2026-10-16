package com.planlock.plan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.plan.dto.PlanResponse;
import com.planlock.plan.dto.PlanUpsertRequest;
import com.planlock.plan.dto.RemindMethods;
import com.planlock.plan.dto.RepeatRule;
import com.planlock.plan.entity.Plan;
import com.planlock.plan.mapper.PlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 计划服务（plan 模块）。
 */
@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanMapper planMapper;
    private final ObjectMapper objectMapper;

    public List<PlanResponse> list(Long userId) {
        return planMapper.findByUserId(userId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public PlanResponse create(Long userId, PlanUpsertRequest req) {
        Plan plan = new Plan();
        plan.setUserId(userId);
        validateClientId(req.getClientId());
        plan.setClientId(req.getClientId());
        apply(plan, req);
        plan.setStatus(1);
        plan.setVersion(0L);
        planMapper.insert(plan);
        return toResponse(planMapper.findByIdForUpdate(plan.getId()));
    }

    @Transactional
    public PlanResponse update(Long userId, Long id, PlanUpsertRequest req, Long baseVersion) {
        Plan existing = planMapper.findById(id);
        if (existing == null || !existing.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在");
        }
        if (baseVersion == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "缺少 baseVersion");
        }
        Plan plan = new Plan();
        plan.setId(id);
        plan.setUserId(userId);
        validateClientId(req.getClientId());
        plan.setClientId(req.getClientId());
        apply(plan, req);
        plan.setVersion(baseVersion);
        int updated = planMapper.updateWithVersion(plan);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        return toResponse(planMapper.findById(id));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        delete(userId, id, null);
    }

    @Transactional
    public void delete(Long userId, Long id, Long baseVersion) {
        Plan existing = planMapper.findById(id);
        if (existing == null) return;
        if (!existing.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在");
        }
        if (baseVersion == null) {
            planMapper.delete(id, userId);
        } else if (planMapper.deleteWithVersion(id, userId, baseVersion) == 0) {
            // A locking read sees concurrent commits, unlike the transaction's earlier snapshot.
            Plan current = planMapper.findByIdForUpdate(id);
            if (current == null) return;
            if (!current.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在");
            }
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    @Transactional
    public PlanResponse pause(Long userId, Long id) {
        Plan existing = planMapper.findById(id);
        if (existing == null || !existing.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在");
        }
        planMapper.togglePause(id, userId);
        return toResponse(planMapper.findById(id));
    }

    /**
     * 计划导出 ICS（V0.1 简版：每个计划一个 VEVENT，不含完整 RRULE，见 04-技术实现/实现记录）。
     */
    public String exportIcs(Long userId) {
        List<Plan> plans = planMapper.findByUserId(userId);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//PlanLock//V0.1//CN\r\n");
        for (Plan p : plans) {
            LocalDate base = LocalDate.now();
            LocalDateTime start = LocalDateTime.of(base, p.getStartTime());
            LocalDateTime end = LocalDateTime.of(base, p.getEndTime());
            if (!end.isAfter(start)) {
                end = end.plusDays(1);
            }
            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:plan-").append(p.getId()).append("@planlock\r\n");
            sb.append("DTSTAMP:").append(LocalDateTime.now().format(dtf)).append("\r\n");
            sb.append("DTSTART:").append(start.format(dtf)).append("\r\n");
            sb.append("DTEND:").append(end.format(dtf)).append("\r\n");
            sb.append("SUMMARY:").append(icsText(p.getName())).append("\r\n");
            sb.append("CATEGORIES:").append(icsText(p.getType())).append("\r\n");
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private void validateClientId(String clientId) {
        if (clientId != null && (clientId.isBlank() || clientId.length() > 64))
            throw new BusinessException(ErrorCode.INVALID_PARAM, "clientId 长度须为 1 至 64");
    }

    private String icsText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace(";", "\\;")
                .replace(",", "\\,").replace("\n", "\\n");
    }

    private void apply(Plan plan, PlanUpsertRequest req) {
        plan.setName(req.getName());
        plan.setType(req.getType());
        plan.setStartTime(req.getStartTime());
        plan.setEndTime(req.getEndTime());
        plan.setRepeatRule(writeJson(req.getRepeatRule()));
        plan.setColor(req.getColor());
        plan.setIcon(req.getIcon());
        plan.setRemindAdvanceMin(req.getRemindAdvanceMin());
        plan.setRemindMethods(writeJson(req.getRemindMethods()));
    }

    private PlanResponse toResponse(Plan p) {
        return PlanResponse.builder()
                .id(p.getId()).clientId(p.getClientId())
                .userId(p.getUserId())
                .name(p.getName())
                .type(p.getType())
                .startTime(p.getStartTime())
                .endTime(p.getEndTime())
                .repeatRule(readJson(p.getRepeatRule(), RepeatRule.class))
                .color(p.getColor())
                .icon(p.getIcon())
                .remindAdvanceMin(p.getRemindAdvanceMin())
                .remindMethods(readJson(p.getRemindMethods(), RemindMethods.class))
                .status(p.getStatus())
                .version(p.getVersion())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
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

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "JSON 解析失败");
        }
    }
}
