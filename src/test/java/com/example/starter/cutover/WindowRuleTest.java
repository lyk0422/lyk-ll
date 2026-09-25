package com.example.starter.cutover;

import com.example.starter.cutover.api.ApplicationResponse;
import com.example.starter.cutover.api.ApplyRequest;
import com.example.starter.cutover.service.CutoverService;
import com.example.starter.cutover.support.BusinessValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 窗口规则：半开区间冲突、端点相接不冲突、优先级抢占拒绝、4 小时恰达合法。
 */
class WindowRuleTest extends AbstractCutoverTest {

    @Autowired
    private CutoverService cutoverService;

    private ApplicationResponse approved(String requestId, String device, String priority,
                                         LocalDateTime start, LocalDateTime end) {
        ApplicationResponse created = cutoverService.apply(
                applyRequest(requestId, device, priority, start, end, defaultSteps()));
        cutoverService.analyze(created.id());
        return cutoverService.approve(created.id());
    }

    @Test
    void halfOpenWindow_endpointTouchingDoesNotConflict() {
        ApplicationResponse first = approved("REQ-A", "DEV-CORE-SW-01", "NORMAL",
                BASE_TIME, BASE_TIME.plusHours(2));
        assertEquals("APPROVED", first.status());

        // 端点相接：B 窗口 [12:00,14:00) 与 A 窗口 [10:00,12:00) 不冲突
        ApplicationResponse touching = approved("REQ-B", "DEV-CORE-SW-01", "NORMAL",
                BASE_TIME.plusHours(2), BASE_TIME.plusHours(4));
        assertEquals("APPROVED", touching.status());

        // 真重叠 [11:00,11:30) 必须拒绝
        ApplicationResponse overlap = approved("REQ-C", "DEV-CORE-SW-01", "NORMAL",
                BASE_TIME.plusHours(1), BASE_TIME.plusHours(1).plusMinutes(30));
        assertEquals("REJECTED", overlap.status());
        assertNotNull(overlap.rejectReason());
    }

    @Test
    void highPriorityChangeRejectsLowerPriorityInSameWindow() {
        ApplicationResponse high = approved("REQ-HIGH", "DEV-CORE-SW-02", "HIGH",
                BASE_TIME, BASE_TIME.plusHours(3));
        assertEquals("APPROVED", high.status());

        ApplicationResponse low = approved("REQ-LOW", "DEV-CORE-SW-02", "LOW",
                BASE_TIME.plusMinutes(30), BASE_TIME.plusMinutes(90));
        assertEquals("REJECTED", low.status());
        assertEquals("同窗口存在高优先级变更，低优先级申请拒绝", low.rejectReason());

        // 影响分析必须标出高优先级重叠变更
        var analysis = cutoverService.viewAnalysis(low.id());
        assertEquals(1, analysis.overlappingChanges().size());
        assertEquals(true, analysis.overlappingChanges().get(0).higherPriority());
        // 影响分析列出全部关联业务（DEV-CORE-SW-02 关联 BIZ-ORDER、BIZ-BI）
        assertEquals(java.util.List.of("BIZ-BI", "BIZ-ORDER"), analysis.affectedBusinesses());
    }

    @Test
    void exactlyFourHoursIsLegalButLongerRejected() {
        ApplicationResponse exactlyFour = approved("REQ-4H", "DEV-FW-01", "NORMAL",
                BASE_TIME, BASE_TIME.plusHours(4));
        assertEquals("APPROVED", exactlyFour.status());

        ApplyRequest tooLong = applyRequest("REQ-4H1M", "DEV-FW-01", "NORMAL",
                BASE_TIME.plusHours(5), BASE_TIME.plusHours(9).plusMinutes(1), defaultSteps());
        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> cutoverService.apply(tooLong));
        assertEquals("割接窗口最长 4 小时，当前为 241 分钟", ex.getMessage());
    }

    @Test
    void differentDevicesDoNotConflict() {
        ApplicationResponse a = approved("REQ-DEV1", "DEV-CORE-SW-01", "NORMAL",
                BASE_TIME, BASE_TIME.plusHours(2));
        ApplicationResponse b = approved("REQ-DEV2", "DEV-FW-01", "NORMAL",
                BASE_TIME, BASE_TIME.plusHours(2));
        assertEquals("APPROVED", a.status());
        assertEquals("APPROVED", b.status());
    }
}
