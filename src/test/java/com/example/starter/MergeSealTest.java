package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.DispatchService;
import com.example.starter.service.FaultReportService;
import com.example.starter.service.RestoreService;
import com.example.starter.service.WorkOrderService;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.MergeRequest;
import com.example.starter.web.dto.MergeResponse;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.RestoreConfirmResponse;
import com.example.starter.web.dto.SealResponse;
import com.example.starter.web.dto.WorkOrderView;

/**
 * 工单合并与日结封存：合并轨迹、封存终态幂等、封存后迟到回报只记事件。
 */
class MergeSealTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private FaultReportService reports;

    @Autowired
    private DispatchService dispatch;

    @Autowired
    private RestoreService restore;

    @Autowired
    private WorkOrderService workOrders;

    private long report(String requestId, String feeder, String point, int users, LocalDateTime at) {
        return reports.report(new FaultReportRequest(requestId, feeder, point, users, null, at))
                .workOrderId();
    }

    @Test
    void 工单合并后原单引用新单并保留轨迹() {
        long a = report("m-1", "F01", "点R", 100, T0);
        long b = report("m-2", "F01", "点S", 150, T0.plusMinutes(2));
        MergeResponse merged = workOrders.merge(new MergeRequest(List.of(a, b)));

        WorkOrderView newOrder = workOrders.view(merged.newOrderId());
        assertEquals("PENDING", newOrder.status());
        assertEquals(250, newOrder.affectedUsers());
        assertEquals(T0, newOrder.outageStartAt());
        assertTrue(newOrder.events().stream().anyMatch(e -> e.startsWith("CREATED_BY_MERGE")));

        WorkOrderView source = workOrders.view(a);
        assertEquals("MERGED", source.status());
        assertEquals(merged.newOrderId(), source.mergedIntoId());
        assertTrue(source.events().stream().anyMatch(e -> e.startsWith("MERGED")));
    }

    @Test
    void 跨馈线合并被拒绝() {
        long a = report("m-3", "F01", "点T", 100, T0);
        long b = report("m-4", "F02", "点U", 150, T0);
        assertThrows(ConflictException.class, () -> workOrders.merge(new MergeRequest(List.of(a, b))));
        assertEquals("PENDING", orderStatus(a));
        assertEquals("PENDING", orderStatus(b));
    }

    @Test
    void 日结封存为终态重复封存幂等成功且迟到回报只记事件() {
        long order = report("s-1", "F01", "点V", 100, T0);
        dispatch.dispatch(new DispatchRequest("s-d-1", order, teamId("抢修一班")));
        restore.confirm(new RestoreConfirmRequest("s-c-1", order, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("s-c-2", order, "FIELD", "现场员乙",
                new BigDecimal("600")));

        SealResponse sealed = workOrders.seal(order);
        assertFalse(sealed.replayed());
        assertEquals("SEALED", orderStatus(order));

        SealResponse again = workOrders.seal(order);
        assertTrue(again.replayed());
        assertEquals("SEALED", again.status());

        RestoreConfirmResponse late = restore.confirm(new RestoreConfirmRequest(
                "s-c-3", order, "FIELD", "现场员丙", new BigDecimal("50")));
        assertTrue(late.late());
        assertFalse(late.restored());
        assertEquals("SEALED", orderStatus(order));
        assertEquals(1, count("late_report_event",
                "work_order_id = ? AND event_type = 'LATE_RESTORE_CONFIRM'", order));
        assertEquals(1, count("load_recovery", "work_order_id = ?", order));
    }

    @Test
    void 未复电工单不可封存() {
        long order = report("s-2", "F01", "点W", 100, T0);
        assertThrows(ConflictException.class, () -> workOrders.seal(order));
        assertEquals("PENDING", orderStatus(order));
    }
}
