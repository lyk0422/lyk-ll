package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.DispatchService;
import com.example.starter.service.FaultReportService;
import com.example.starter.service.RestoreService;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.RestoreConfirmResponse;

/**
 * 复电确认：双确认生效、单人不生效、负荷恢复同事务入账、挂单自动提升、幂等与迟到回报。
 */
class RestoreServiceTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private FaultReportService reports;

    @Autowired
    private DispatchService dispatch;

    @Autowired
    private RestoreService restore;

    private long executingOrder(String requestId, String point) {
        long order = reports.report(new FaultReportRequest(
                requestId, "F01", point, 100, null, T0)).workOrderId();
        dispatch.dispatch(new DispatchRequest("dp-" + requestId, order, teamId("抢修一班")));
        return order;
    }

    @Test
    void 单角色确认不生效双确认后复电且负荷同事务入账() {
        long order = executingOrder("rc-1", "点L");
        RestoreConfirmResponse first = restore.confirm(new RestoreConfirmRequest(
                "c-1", order, "DISPATCHER", "调度员甲", null));
        assertFalse(first.restored());
        assertEquals("EXECUTING", orderStatus(order));
        assertEquals(0, count("load_recovery", "work_order_id = ?", order));

        RestoreConfirmResponse second = restore.confirm(new RestoreConfirmRequest(
                "c-2", order, "FIELD", "现场员乙", new BigDecimal("1250.50")));
        assertTrue(second.restored());
        assertEquals("RESTORED", orderStatus(order));
        assertEquals(1, count("load_recovery", "work_order_id = ? AND load_kw = 1250.50", order));
        assertEquals(2, count("restore_confirmation", "work_order_id = ?", order));
    }

    @Test
    void 同一确认人不可完成双角色() {
        long order = executingOrder("rc-2", "点M");
        restore.confirm(new RestoreConfirmRequest("c-3", order, "DISPATCHER", "张三", null));
        assertThrows(ConflictException.class, () -> restore.confirm(new RestoreConfirmRequest(
                "c-4", order, "FIELD", "张三", new BigDecimal("100"))));
        assertEquals("EXECUTING", orderStatus(order));
        assertEquals(0, count("load_recovery", "work_order_id = ?", order));
    }

    @Test
    void 现场确认必须携带正负荷否则400且状态不变() {
        long order = executingOrder("rc-3", "点N");
        assertThrows(IllegalArgumentException.class, () -> restore.confirm(new RestoreConfirmRequest(
                "c-5", order, "FIELD", "现场员乙", null)));
        assertEquals("EXECUTING", orderStatus(order));
        assertEquals(0, count("restore_confirmation", "work_order_id = ?", order));
    }

    @Test
    void 复电后班组挂单自动提升为执行中() {
        long team = teamId("抢修一班");
        long first = executingOrder("rc-4", "点O");
        long second = reports.report(new FaultReportRequest(
                "rc-5", "F01", "点P", 200, null, T0.plusMinutes(1))).workOrderId();
        dispatch.dispatch(new DispatchRequest("dp-rc-5", second, team));
        assertEquals("QUEUED", orderStatus(second));

        restore.confirm(new RestoreConfirmRequest("c-6", first, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("c-7", first, "FIELD", "现场员乙",
                new BigDecimal("800")));
        assertEquals("RESTORED", orderStatus(first));
        assertEquals("EXECUTING", orderStatus(second));
        assertEquals(1, count("work_order", "executing_team_id = ? AND status = 'EXECUTING'", team));
    }

    @Test
    void 复电确认幂等重放不重复入账() {
        long order = executingOrder("rc-6", "点Q");
        restore.confirm(new RestoreConfirmRequest("c-8", order, "DISPATCHER", "调度员甲", null));
        RestoreConfirmResponse done = restore.confirm(new RestoreConfirmRequest(
                "c-9", order, "FIELD", "现场员乙", new BigDecimal("500")));
        assertTrue(done.restored());
        RestoreConfirmResponse replay = restore.confirm(new RestoreConfirmRequest(
                "c-9", order, "FIELD", "现场员乙", new BigDecimal("500")));
        assertTrue(replay.replayed());
        assertEquals(1, count("load_recovery", "work_order_id = ?", order));
        assertEquals(2, count("restore_confirmation", "work_order_id = ?", order));
        assertThrows(ConflictException.class, () -> restore.confirm(new RestoreConfirmRequest(
                "c-9", order, "FIELD", "现场员乙", new BigDecimal("999"))));
    }
}
