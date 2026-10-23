package com.planlock.sync;

import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import java.util.Set;

public final class PlanTypeRules {
    private static final Set<String> COLORS = Set.of("study", "work", "sport", "sleep", "rose", "amber", "hobby", "other");
    private PlanTypeRules() {}
    public static String name(String input) {
        String value = input == null ? "" : input.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 6 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "类型名称须为1至6字");
        }
        return value;
    }
    public static void validate(String key, String label, String color, String mutationId) {
        if (key == null || !key.matches("custom:[A-Za-z0-9-]{1,57}") || !COLORS.contains(color == null ? "" : color)
                || mutationId == null || mutationId.isBlank() || mutationId.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "无效自定义类型");
        }
        name(label);
    }
}
