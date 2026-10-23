package com.planlock.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.schedule.dto.ScheduleUpsertRequest;
import java.util.Set;

public final class ScheduleRules {
    private static final Set<String> TYPES = Set.of("daily", "weekly", "monthly", "yearly");
    private ScheduleRules() {}

    public static void validate(ScheduleUpsertRequest req, ObjectMapper mapper) {
        Double lat=req.getLocationLat(), lon=req.getLocationLon();
        if ((lat==null)!=(lon==null) || lat!=null && (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180)) invalid("地点坐标无效");
        if (req.getStartDate() == null || req.getEndDate() == null) invalid("开始和结束日期不能为空");
        if (req.getEndDate().isBefore(req.getStartDate())) invalid("结束日期不能早于开始日期");
        if (Boolean.TRUE.equals(req.getAllDay())) {
            if (req.getStartTime() != null || req.getEndTime() != null) invalid("全天日程不能填写时刻");
        } else {
            if (req.getStartTime() == null || req.getEndTime() == null) invalid("定时日程必须填写开始和结束时刻");
            if (req.getStartDate().equals(req.getEndDate()) && !req.getEndTime().isAfter(req.getStartTime())) {
                invalid("同日定时日程的结束时刻必须晚于开始时刻");
            }
        }
        if (req.getRepeatRule() != null && !req.getRepeatRule().isBlank()) {
            try {
                JsonNode root = mapper.readTree(req.getRepeatRule());
                String type = root.path("type").asText();
                if (!TYPES.contains(type)) invalid("暂不支持该重复规则");
                if ("weekly".equals(type)) {
                    JsonNode days = root.path("weekdays");
                    if (!days.isArray() || days.isEmpty()) invalid("每周重复必须选择星期");
                    for (JsonNode day : days) if (!day.canConvertToInt() || day.asInt() < 1 || day.asInt() > 7) invalid("星期取值必须为 1 至 7");
                }
                if ("monthly".equals(type)) {
                    int day = root.path("day").asInt(0);
                    if (day < 1 || day > 31) invalid("每月重复日期必须为 1 至 31");
                }
                if ("yearly".equals(type)) {
                    int month = root.path("month").asInt(0);
                    int day = root.path("day").asInt(0);
                    if (month < 1 || month > 12 || day < 1 || day > 31) invalid("每年重复日期无效");
                }
            } catch (BusinessException e) { throw e; }
            catch (Exception e) { invalid("重复规则格式无效"); }
        }
    }

    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_PARAM, message); }
}
