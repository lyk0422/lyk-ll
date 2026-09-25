package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.FaultReportService;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.FaultReportResponse;

/**
 * 故障接报与研判：建单、幂等重放、同键异参 409、故障点+时段归并。
 */
class FaultReportServiceTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private FaultReportService service;

    @Test
    void 接报后自动研判建单并解析重要用户等级() {
        FaultReportResponse resp = service.report(new FaultReportRequest(
                "r-1", "F01", "10kV支线#12杆", 120, "市一医院", T0));
        assertFalse(resp.merged());
        assertFalse(resp.replayed());
        assertEquals("PENDING", resp.status());
        String level = jdbc.queryForObject(
                "SELECT important_level FROM work_order WHERE id = ?", String.class, resp.workOrderId());
        assertEquals("SPECIAL", level);
        assertEquals(1, count("work_order_event", "work_order_id = ? AND event_type = 'ASSESSED'",
                resp.workOrderId()));
    }

    @Test
    void 同键同参重放返回首次结果() {
        FaultReportResponse first = service.report(new FaultReportRequest(
                "r-2", "F01", "10kV支线#13杆", 80, null, T0));
        FaultReportResponse replay = service.report(new FaultReportRequest(
                "r-2", "F01", "10kV支线#13杆", 80, null, T0));
        assertTrue(replay.replayed());
        assertEquals(first.reportId(), replay.reportId());
        assertEquals(first.workOrderId(), replay.workOrderId());
        assertEquals(1, count("work_order", "id = ?", first.workOrderId()));
        assertEquals(1, count("fault_report", "request_id = 'r-2'"));
    }

    @Test
    void 同键异参返回409且工单状态不变() {
        FaultReportResponse first = service.report(new FaultReportRequest(
                "r-3", "F01", "10kV支线#14杆", 80, null, T0));
        assertThrows(ConflictException.class, () -> service.report(new FaultReportRequest(
                "r-3", "F01", "10kV支线#14杆", 999, null, T0)));
        int affected = jdbc.queryForObject(
                "SELECT affected_users FROM work_order WHERE id = ?", Integer.class, first.workOrderId());
        assertEquals(80, affected);
        assertEquals("PENDING", orderStatus(first.workOrderId()));
    }

    @Test
    void 同故障点同时段重复接报归并并累计影响用户() {
        FaultReportResponse first = service.report(new FaultReportRequest(
                "r-4a", "F02", "环网柜A", 100, null, T0));
        FaultReportResponse dup = service.report(new FaultReportRequest(
                "r-4b", "F02", "环网柜A", 60, null, T0.plusMinutes(20)));
        assertTrue(dup.merged());
        assertEquals(first.workOrderId(), dup.workOrderId());
        int affected = jdbc.queryForObject(
                "SELECT affected_users FROM work_order WHERE id = ?", Integer.class, first.workOrderId());
        assertEquals(160, affected);
        assertEquals(1, count("work_order", "feeder_id = 'F02'"));
    }

    @Test
    void 跨时段同故障点不归并() {
        FaultReportResponse first = service.report(new FaultReportRequest(
                "r-5a", "F02", "环网柜B", 100, null, T0));
        FaultReportResponse later = service.report(new FaultReportRequest(
                "r-5b", "F02", "环网柜B", 60, null, T0.plusHours(2)));
        assertFalse(later.merged());
        assertTrue(later.workOrderId() != first.workOrderId());
    }
}
