package com.planlock.record.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.record.mapper.RhythmMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RhythmServiceTest {
    @Test void invalidTimezoneAndUnboundedDaysAreRejectedBeforeReadingAnyOwnerData() {
        var mapper = mock(RhythmMapper.class);
        var service = new RhythmService(mapper, new ObjectMapper());
        assertThatThrownBy(() -> service.rhythm(1L, 30, "not-a-zone")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.rhythm(1L, 367, "UTC")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }
}
