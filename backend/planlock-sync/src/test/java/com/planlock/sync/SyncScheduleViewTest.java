package com.planlock.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.sync.dto.SyncScheduleView;
import com.planlock.sync.mapper.SyncMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyncScheduleViewTest {
    @Test void pullProjectionAndJsonRetainCreationTimestamp() throws Exception {
        // SQL projection and JSON are the two public boundaries that must preserve this field.
        var sql = String.join(" ", SyncMapper.class.getMethod("pullSchedules", Long.class)
                .getAnnotation(Select.class).value());
        assertTrue(sql.contains("created_at"));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var view = mapper.readValue("{\"createdAt\":\"2026-09-24T08:30:00\"}", SyncScheduleView.class);
        assertFalse(mapper.valueToTree(view).path("createdAt").isMissingNode());
        assertFalse(mapper.valueToTree(view).path("createdAt").isNull());
    }
}
