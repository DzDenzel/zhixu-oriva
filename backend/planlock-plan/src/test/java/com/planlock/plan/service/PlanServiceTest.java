package com.planlock.plan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.plan.dto.PlanResponse;
import com.planlock.plan.dto.PlanUpsertRequest;
import com.planlock.plan.dto.RemindMethods;
import com.planlock.plan.dto.RepeatRule;
import com.planlock.plan.entity.Plan;
import com.planlock.plan.mapper.PlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlanService 单元测试（纯 JVM + Mockito，覆盖创建/更新/删除/暂停/归属越权/跨天 ICS 与规则校验）。
 */
@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock
    private PlanMapper planMapper;

    private ObjectMapper objectMapper;
    private PlanService planService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        planService = new PlanService(planMapper, objectMapper);
    }

    @Test
    void listDeserializesRepeatRuleAndRemindMethods() {
        Plan plan = samplePlan(1L, 10L);
        plan.setRepeatRule("{\"type\":\"weekly\",\"weekdays\":[1,3,5]}");
        plan.setRemindMethods("{\"notification\":true,\"sound\":true,\"multiDevice\":false,\"repeatMultiple\":true}");
        when(planMapper.findByUserId(10L)).thenReturn(List.of(plan));

        List<PlanResponse> list = planService.list(10L);

        assertThat(list).hasSize(1);
        PlanResponse res = list.get(0);
        assertThat(res.getRepeatRule()).isNotNull();
        assertThat(res.getRepeatRule().getType()).isEqualTo("weekly");
        assertThat(res.getRepeatRule().getWeekdays()).containsExactly(1, 3, 5);
        assertThat(res.getRemindMethods()).isNotNull();
        assertThat(res.getRemindMethods().isNotification()).isTrue();
        assertThat(res.getRemindMethods().isSound()).isTrue();
        assertThat(res.getRemindMethods().isRepeatMultiple()).isTrue();
    }

    @Test
    void listThrowsInternalErrorWhenStoredRepeatRuleJsonIsCorrupted() {
        Plan plan = samplePlan(1L, 10L);
        plan.setRepeatRule("{corrupted");
        when(planMapper.findByUserId(10L)).thenReturn(List.of(plan));

        assertThatThrownBy(() -> planService.list(10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR.getCode()));
    }

    @Test
    void createPersistsPlanWithDefaultsAndReturnsReloadedResponse() {
        PlanUpsertRequest req = sampleRequest();
        req.setClientId("a".repeat(64));
        req.setStatusLog("[{\"date\":\"2026-11-23\",\"status\":1}]");

        doAnswer(invocation -> {
            Plan p = invocation.getArgument(0);
            p.setId(500L);
            return 1;
        }).when(planMapper).insert(any(Plan.class));

        Plan reloaded = samplePlan(500L, 10L);
        reloaded.setClientId(req.getClientId());
        reloaded.setRepeatRule("{\"type\":\"daily\",\"weekdays\":null,\"dates\":null}");
        reloaded.setRemindMethods("{\"notification\":true,\"sound\":false,\"multiDevice\":false,\"repeatMultiple\":false}");
        when(planMapper.findByIdForUpdate(500L)).thenReturn(reloaded);

        PlanResponse response = planService.create(10L, req);

        ArgumentCaptor<Plan> captor = ArgumentCaptor.forClass(Plan.class);
        verify(planMapper).insert(captor.capture());
        Plan inserted = captor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(10L);
        assertThat(inserted.getStatus()).isEqualTo(1);
        assertThat(inserted.getVersion()).isEqualTo(0L);
        assertThat(inserted.getType()).isEqualTo("学习");
        assertThat(inserted.getRepeatRule()).contains("\"daily\"");

        assertThat(response.getId()).isEqualTo(500L);
        assertThat(response.getClientId()).hasSize(64);
        assertThat(response.getRepeatRule().getType()).isEqualTo("daily");
    }

    @Test
    void createReassignsDeletedCustomTypeToOtherAndKeepsActiveCustomType() {
        PlanUpsertRequest deletedTypeReq = sampleRequest();
        deletedTypeReq.setType("custom:deleted_key");
        when(planMapper.isDeletedPlanType(10L, "custom:deleted_key")).thenReturn(1);
        doAnswer(inv -> {
            ((Plan) inv.getArgument(0)).setId(501L);
            return 1;
        }).when(planMapper).insert(any(Plan.class));
        Plan saved = samplePlan(501L, 10L);
        saved.setType("其他");
        when(planMapper.findByIdForUpdate(501L)).thenReturn(saved);

        PlanResponse res = planService.create(10L, deletedTypeReq);

        ArgumentCaptor<Plan> captor = ArgumentCaptor.forClass(Plan.class);
        verify(planMapper).insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("其他");
        assertThat(res.getType()).isEqualTo("其他");
    }

    @Test
    void createRejectsInvalidClientIdAndInvalidStatusLog() {
        PlanUpsertRequest blankClientId = sampleRequest();
        blankClientId.setClientId("   ");
        assertBusinessError(() -> planService.create(10L, blankClientId), ErrorCode.INVALID_PARAM);

        PlanUpsertRequest tooLongClientId = sampleRequest();
        tooLongClientId.setClientId("x".repeat(65));
        assertBusinessError(() -> planService.create(10L, tooLongClientId), ErrorCode.INVALID_PARAM);

        PlanUpsertRequest badStatusLog = sampleRequest();
        badStatusLog.setStatusLog("[{\"date\":\"2026-02-30\",\"status\":1}]");
        assertBusinessError(() -> planService.create(10L, badStatusLog), ErrorCode.INVALID_PARAM);

        verify(planMapper, never()).insert(any());
    }

    @Test
    void updateRejectsMissingForeignOwnerMissingVersionOrVersionConflict() {
        when(planMapper.findById(1L)).thenReturn(null);
        assertBusinessError(() -> planService.update(10L, 1L, sampleRequest(), 0L), ErrorCode.NOT_FOUND);

        Plan foreignPlan = samplePlan(2L, 99L);
        when(planMapper.findById(2L)).thenReturn(foreignPlan);
        assertBusinessError(() -> planService.update(10L, 2L, sampleRequest(), 0L), ErrorCode.NOT_FOUND);

        Plan ownPlan = samplePlan(3L, 10L);
        when(planMapper.findById(3L)).thenReturn(ownPlan);
        assertBusinessError(() -> planService.update(10L, 3L, sampleRequest(), null), ErrorCode.INVALID_PARAM);

        when(planMapper.updateWithVersion(any(Plan.class))).thenReturn(0);
        assertBusinessError(() -> planService.update(10L, 3L, sampleRequest(), 1L), ErrorCode.VERSION_CONFLICT);
    }

    @Test
    void updateSucceedsWithMatchingBaseVersion() {
        Plan existing = samplePlan(3L, 10L);
        Plan updated = samplePlan(3L, 10L);
        updated.setName("晚间阅读");
        updated.setVersion(2L);
        when(planMapper.findById(3L)).thenReturn(existing, updated);
        when(planMapper.updateWithVersion(any(Plan.class))).thenReturn(1);

        PlanUpsertRequest req = sampleRequest();
        req.setName("晚间阅读");

        PlanResponse res = planService.update(10L, 3L, req, 1L);

        ArgumentCaptor<Plan> captor = ArgumentCaptor.forClass(Plan.class);
        verify(planMapper).updateWithVersion(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(3L);
        assertThat(captor.getValue().getVersion()).isEqualTo(1L);
        assertThat(res.getName()).isEqualTo("晚间阅读");
        assertThat(res.getVersion()).isEqualTo(2L);
    }

    @Test
    void deleteHandlesIdempotencyOwnershipAndOptimisticLock() {
        when(planMapper.findById(1L)).thenReturn(null);
        planService.delete(10L, 1L);
        verify(planMapper, never()).delete(any(), any());

        when(planMapper.findById(2L)).thenReturn(samplePlan(2L, 99L));
        assertBusinessError(() -> planService.delete(10L, 2L), ErrorCode.NOT_FOUND);

        when(planMapper.findById(3L)).thenReturn(samplePlan(3L, 10L));
        planService.delete(10L, 3L);
        verify(planMapper).delete(3L, 10L);

        when(planMapper.findById(4L)).thenReturn(samplePlan(4L, 10L));
        when(planMapper.deleteWithVersion(4L, 10L, 2L)).thenReturn(0);
        when(planMapper.findByIdForUpdate(4L)).thenReturn(null);
        planService.delete(10L, 4L, 2L);

        when(planMapper.findById(5L)).thenReturn(samplePlan(5L, 10L));
        when(planMapper.deleteWithVersion(5L, 10L, 2L)).thenReturn(0);
        when(planMapper.findByIdForUpdate(5L)).thenReturn(samplePlan(5L, 10L));
        assertBusinessError(() -> planService.delete(10L, 5L, 2L), ErrorCode.VERSION_CONFLICT);
    }

    @Test
    void pauseTogglesStatusAndTrimsStatusLogToFiftyEntries() throws Exception {
        Plan existing = samplePlan(7L, 10L);
        existing.setStatus(1);
        LocalDate start = LocalDate.of(2026, 9, 1);
        String fiftyEntries = IntStream.range(0, 50)
                .mapToObj(i -> "{\"date\":\"" + start.plusDays(i) + "\",\"status\":" + (i % 2) + "}")
                .collect(Collectors.joining(",", "[", "]"));
        existing.setStatusLog(fiftyEntries);

        when(planMapper.findByIdForUpdate(7L)).thenReturn(existing);
        when(planMapper.findById(7L)).thenReturn(existing);

        PlanResponse response = planService.pause(10L, 7L, LocalDate.of(2026, 11, 23));

        verify(planMapper).updatePause(existing);
        assertThat(existing.getStatus()).isEqualTo(0);
        var array = objectMapper.readTree(existing.getStatusLog());
        assertThat(array.size()).isEqualTo(50);
        assertThat(array.get(0).get("date").asText()).isEqualTo("2026-09-02");
        assertThat(array.get(49).get("date").asText()).isEqualTo("2026-11-23");
        assertThat(array.get(49).get("status").asInt()).isEqualTo(0);
        assertThat(response.getStatus()).isEqualTo(0);
    }

    @Test
    void pauseRejectsUnownedOrMissingPlan() {
        when(planMapper.findByIdForUpdate(8L)).thenReturn(samplePlan(8L, 99L));
        assertBusinessError(() -> planService.pause(10L, 8L), ErrorCode.NOT_FOUND);
        verify(planMapper, never()).updatePause(any());
    }

    @Test
    void exportIcsHandlesCrossDayScheduleAndEscapesSpecialCharacters() {
        Plan overnight = samplePlan(9L, 10L);
        overnight.setName("睡眠;专注,测试\\换行\n行二");
        overnight.setType("作息,夜间");
        overnight.setStartTime(LocalTime.of(23, 0));
        overnight.setEndTime(LocalTime.of(8, 0));
        when(planMapper.findByUserId(10L)).thenReturn(List.of(overnight));

        String ics = planService.exportIcs(10L);

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
        assertThat(ics).contains("UID:plan-9@planlock\r\n");
        assertThat(ics).contains("SUMMARY:睡眠\\;专注\\,测试\\\\换行\\n行二\r\n");
        assertThat(ics).contains("CATEGORIES:作息\\,夜间\r\n");
        assertThat(ics).contains("T230000\r\n");
        assertThat(ics).contains("T080000\r\n");
        LocalDate today = LocalDate.now();
        String expectedStart = today.toString().replace("-", "") + "T230000";
        String expectedEnd = today.plusDays(1).toString().replace("-", "") + "T080000";
        assertThat(ics).contains("DTSTART:" + expectedStart + "\r\n");
        assertThat(ics).contains("DTEND:" + expectedEnd + "\r\n");
        assertThat(ics).endsWith("END:VCALENDAR\r\n");
    }

    @Test
    void exportIcsNeutralizesCrAndCrlfAndFoldsLinesAt75Octets() {
        Plan plan = samplePlan(12L, 10L);
        String longTitle = "深度专注\r\nATTENDEE:evil\rSUMMARY:spoof-" + "聚焦".repeat(20) + "\uD83D\uDDD3\uFE0F";
        plan.setName(longTitle);
        plan.setType("工作\r\n状态");
        when(planMapper.findByUserId(10L)).thenReturn(List.of(plan));

        String ics = planService.exportIcs(10L);
        String[] physicalLines = ics.split("\r\n");

        for (String physical : physicalLines) {
            assertThat(physical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .as("Physical ICS line exceeds 75 UTF-8 octets: %s", physical)
                    .isLessThanOrEqualTo(75);
        }

        String unfolded = ics.replace("\r\n ", "");
        assertThat(unfolded).doesNotContain("\r\nATTENDEE:evil");
        assertThat(unfolded).doesNotContain("\r\nSUMMARY:spoof");
        assertThat(unfolded).contains("SUMMARY:深度专注\\nATTENDEE:evil\\nSUMMARY:spoof-" + "聚焦".repeat(20) + "\uD83D\uDDD3\uFE0F\r\n");
        assertThat(unfolded).contains("CATEGORIES:工作\\n状态\r\n");
    }

    private static Plan samplePlan(Long id, Long userId) {
        Plan p = new Plan();
        p.setId(id);
        p.setUserId(userId);
        p.setName("深度专注");
        p.setType("学习");
        p.setStartTime(LocalTime.of(9, 0));
        p.setEndTime(LocalTime.of(10, 30));
        p.setRemindAdvanceMin(15);
        p.setStatus(1);
        p.setVersion(1L);
        return p;
    }

    private static PlanUpsertRequest sampleRequest() {
        PlanUpsertRequest req = new PlanUpsertRequest();
        req.setName("深度专注");
        req.setType("学习");
        req.setStartTime(LocalTime.of(9, 0));
        req.setEndTime(LocalTime.of(10, 30));
        RepeatRule rule = new RepeatRule();
        rule.setType("daily");
        req.setRepeatRule(rule);
        req.setRemindMethods(new RemindMethods());
        return req;
    }

    private static void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(expected.getCode()));
    }
}
