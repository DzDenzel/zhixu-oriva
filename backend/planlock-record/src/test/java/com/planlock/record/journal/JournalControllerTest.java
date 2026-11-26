package com.planlock.record.journal;

import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.common.GlobalExceptionHandler;
import com.planlock.common.auth.UserContext;
import com.planlock.record.journal.controller.JournalController;
import com.planlock.record.journal.dto.JournalResponse;
import com.planlock.record.journal.service.JournalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JournalControllerTest {

    private final JournalService service = mock(JournalService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserContext.set(42L, 1L);
        mockMvc = MockMvcBuilders.standaloneSetup(new JournalController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listCreateUpdateDeleteAndExportEndpointsFollowContract() throws Exception {
        JournalResponse item = JournalResponse.builder()
                .id(7L)
                .userId(42L)
                .clientId("cid-7")
                .entryDate(LocalDate.of(2026, 11, 25))
                .mood("平静")
                .tags(List.of("散步"))
                .content("夜风很好")
                .status(1)
                .version(0L)
                .build();
        when(service.list(42L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "平静"))
                .thenReturn(List.of(item));
        when(service.create(eq(42L), any())).thenReturn(item);
        when(service.update(eq(42L), eq(7L), eq(0L), any()))
                .thenThrow(new BusinessException(ErrorCode.VERSION_CONFLICT));
        when(service.exportMarkdown(42L, "markdown"))
                .thenReturn("# 知序 · 日常记录导出\n\n## 2026-11-25\n\n夜风很好\n");

        mockMvc.perform(get("/api/v1/journals")
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-30")
                        .param("mood", "平静"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(7));

        mockMvc.perform(post("/api/v1/journals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid-7\",\"entryDate\":\"2026-11-25\",\"mood\":\"平静\",\"tags\":[\"散步\"],\"content\":\"夜风很好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.clientId").value("cid-7"));

        mockMvc.perform(put("/api/v1/journals/7")
                        .param("baseVersion", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryDate\":\"2026-11-25\",\"content\":\"修改\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.VERSION_CONFLICT.getCode()));

        mockMvc.perform(delete("/api/v1/journals/7").param("baseVersion", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/v1/journals/export").param("format", "markdown"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("夜风很好")));

        mockMvc.perform(post("/api/v1/journals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryDate\":\"2026-11-25\",\"content\":\"" + "x".repeat(20_001) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));
    }
}
