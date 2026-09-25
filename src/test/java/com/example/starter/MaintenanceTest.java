package com.example.starter;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维保周期：15 日（UTC 日界），恰第 15 日合规；逾期锁定并列入重点监察；
 * 电梯级复检合格后同事务复梯。
 */
class MaintenanceTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("维保恰第15日完成合规，电梯保持运行")
    void maintenanceOnDay15IsCompliant() throws Exception {
        long e1 = createElevator("MT15-A", LocalDate.of(2026, 1, 1));
        Resp r = maintenance("MT15-m1", e1, LocalDate.of(2026, 1, 16), null);
        assertEquals(200, r.status());
        assertEquals("COMPLIANT", r.text("result"), "恰第15日完成应合规");
        assertEquals("2026-01-16", r.text("dueDay"));
        assertEquals("RUNNING", elevator(e1).text("status"));
        assertFalse(elevator(e1).bool("keySupervision"));
        assertEquals("2026-01-16", elevator(e1).text("lastMaintenanceDay"));

        // 下一周期仍恰第 15 日：2026-01-16 + 15 = 2026-01-31
        Resp r2 = maintenance("MT15-m2", e1, LocalDate.of(2026, 1, 31), null);
        assertEquals(200, r2.status());
        assertEquals("COMPLIANT", r2.text("result"));
        assertEquals("RUNNING", elevator(e1).text("status"));
    }

    @Test
    @DisplayName("维保第16日逾期：锁定电梯、列入重点监察，复检合格后同事务复梯")
    void maintenanceOverdueLocksElevator() throws Exception {
        long e1 = createElevator("MT16-A", LocalDate.of(2026, 1, 1));
        Resp r = maintenance("MT16-m1", e1, LocalDate.of(2026, 1, 17), null);
        assertEquals(200, r.status());
        assertEquals("OVERDUE", r.text("result"), "第16日完成应判逾期");
        assertEquals("2026-01-16", r.text("dueDay"));

        assertEquals("STOPPED", elevator(e1).text("status"), "逾期应锁定电梯");
        assertTrue(elevator(e1).bool("keySupervision"), "逾期应列入重点监察");
        assertTrue(chainHas(e1, "STOP", "MAINTENANCE_OVERDUE"));
        assertEquals("2026-01-17", elevator(e1).text("lastMaintenanceDay"), "逾期维保事实仍入账");

        Resp failed = elevatorRecheck(e1, false);
        assertEquals(422, failed.status(), "复检不合格禁止复梯");
        assertEquals("STOPPED", elevator(e1).text("status"));

        Resp ok = elevatorRecheck(e1, true);
        assertEquals(200, ok.status());
        assertEquals("RUNNING", ok.text("status"), "复检合格与复梯同事务完成");
        assertTrue(chainHas(e1, "RESUME", "RECHECK_PASS"));
    }

    @Test
    @DisplayName("乱序补录的维保记录只记迟到事件，不锁定不回退台账")
    void outOfOrderMaintenanceIsLateEvent() throws Exception {
        long e1 = createElevator("MTLO-A", LocalDate.of(2026, 1, 1));
        assertEquals("COMPLIANT", maintenance("MTLO-m1", e1, LocalDate.of(2026, 1, 10), null).text("result"));
        Resp late = maintenance("MTLO-m2", e1, LocalDate.of(2026, 1, 5), null);
        assertEquals(200, late.status());
        assertEquals("LATE_EVENT", late.text("result"));
        assertEquals("2026-01-10", elevator(e1).text("lastMaintenanceDay"), "迟到事件不得回退台账");
        assertEquals("RUNNING", elevator(e1).text("status"));
    }

    @Test
    @DisplayName("维保登记同键重放幂等，异参409")
    void maintenanceIdempotency() throws Exception {
        long e1 = createElevator("MTID-A", LocalDate.of(2026, 1, 1));
        Resp first = maintenance("MTID-m1", e1, LocalDate.of(2026, 1, 10), null);
        assertEquals(200, first.status());
        Resp replay = maintenance("MTID-m1", e1, LocalDate.of(2026, 1, 10), null);
        assertEquals(200, replay.status());
        assertEquals(om.writeValueAsString(first.body()), om.writeValueAsString(replay.body()));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, count, "同键重放不得重复入账");

        Resp conflict = maintenance("MTID-m1", e1, LocalDate.of(2026, 1, 11), null);
        assertEquals(409, conflict.status());
        assertEquals("2026-01-10", elevator(e1).text("lastMaintenanceDay"), "异参重放不得改变台账");
    }
}
