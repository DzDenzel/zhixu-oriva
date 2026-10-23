package com.planlock.sync;

import com.planlock.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanTypeRulesTest {
    @Test void rejectsInvalidKeyNameAndColorBeforePersistence() {
        assertEquals("写作", PlanTypeRules.name(" 写作 "));
        assertEquals("😀😀😀😀😀😀", PlanTypeRules.name("😀😀😀😀😀😀"));
        for (String name : new String[]{"", "一二三四五六七", "学\n习"}) {
            assertThrows(BusinessException.class, () -> PlanTypeRules.name(name));
        }
        assertThrows(BusinessException.class, () -> PlanTypeRules.validate("study", "写作", "rose", "mutation"));
        assertThrows(BusinessException.class, () -> PlanTypeRules.validate("custom:123", "写作", "unknown", "mutation"));
        assertDoesNotThrow(() -> PlanTypeRules.validate("custom:123", "写作", "rose", "mutation"));
        assertDoesNotThrow(() -> PlanTypeRules.validate("custom:123", "兴趣", "hobby", "mutation"));
        assertDoesNotThrow(() -> PlanTypeRules.validate("custom:123", "其他", "other", "mutation"));
    }
}
