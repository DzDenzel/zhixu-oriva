package com.planlock.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.plan.dto.PlanUpsertRequest;
import com.planlock.plan.entity.Plan;
import com.planlock.plan.mapper.PlanMapper;
import com.planlock.plan.service.PlanService;
import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanTypeRestNormalizationTest {
    @Test void validationAcceptsEightPresetsStableCustomKeysAndLegacyEnglishValues() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String type : new String[]{"学习","工作","运动","阅读","休息","生活","兴趣","其他","study","work","sport","sleep","睡眠","custom:ab4d"}) {
                var request = new PlanUpsertRequest();
                request.setName("计划"); request.setType(type); request.setStartTime(LocalTime.of(9,0)); request.setEndTime(LocalTime.of(10,0));
                assertTrue(validator.validate(request).isEmpty(),type);
            }
        }
    }
    @Test void ordinaryPlanCreationCannotResurrectADeletedCustomType() {
        var mapper = mock(PlanMapper.class);
        // The ordinary endpoint must honor the same tombstone as sync writes.
        when(mapper.isDeletedPlanType(1L,"custom:deleted")).thenReturn(1);
        var persisted = new AtomicReference<Plan>();
        when(mapper.insert(any())).thenAnswer(invocation -> { Plan value = invocation.getArgument(0); value.setId(42L); persisted.set(value); return 1; });
        when(mapper.findByIdForUpdate(42L)).thenAnswer(invocation -> persisted.get());
        var request = new PlanUpsertRequest();
        request.setName("写作"); request.setType("custom:deleted"); request.setStartTime(LocalTime.of(9,0)); request.setEndTime(LocalTime.of(10,0));
        var response = new PlanService(mapper,new ObjectMapper()).create(1L,request);
        assertEquals("其他",response.getType());
        assertEquals("写作",response.getName());
    }
}
