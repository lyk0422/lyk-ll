package com.example.starter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧手工维保入口兼容：合规/逾期手工登记将对应未完成义务结清一次并终止残留尝试；
 * 迟到事件不结清；结清后旧尝试迟到回报不登记维保记录。
 */
class ManualMaintenanceSettlementTest extends AbstractIntegrationTest {

    private static final LocalDate COMMISSIONED = LocalDate.of(2026, 1, 1);
    private static final Instant DISPATCHABLE = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant W_START = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant W_END = Instant.parse("2026-01-16T12:00:00Z");

    @Test
    @DisplayName("手工合规维保结清对应义务一次：尝试终止、人员释放、义务标记 MANUAL；迟到手工事件不结清")
    void manualMaintenanceSettlesOpenObligationOnce() throws Exception {
        long e1 = createElevator("MS-A", COMMISSIONED);
        long e2 = createElevator("MS-B", COMMISSIONED);
        long s1 = createStaff("C-MS-1", true);
        long s2 = createStaff("C-MS-2", true);
        planScan("MS-scan", DISPATCHABLE);
        long obId = firstObligationId(e1);
        long ob2 = firstObligationId(e2);

        long attemptId = planDispatch("MS-d", e1, obId, List.of(s1, s2), twoItems(),
                W_START, W_END, DISPATCHABLE.plusSeconds(60)).id();
        assertEquals(200, reportItem(attemptId, "MS-i1", "I-1", true, 1, DISPATCHABLE.plusSeconds(120)).status());

        // 手工合规维保（恰第 15 日，与义务到期日一致）
        Resp manual = maintenance("MS-manual", e1, LocalDate.of(2026, 1, 16), null);
        assertEquals(200, manual.status());
        assertEquals("COMPLIANT", manual.text("result"));

        Resp ob = planObligation(obId);
        assertEquals("FULFILLED", ob.text("status"));
        assertEquals("MANUAL", ob.text("settleSource"), "须由手工入口结清");
        assertTrue(ob.body().get("completedAttemptId").isNull(), "手工结清不指向尝试");
        assertEquals("ABORTED", planAttempt(attemptId).text("status"), "残留尝试须终止释放人员");
        assertTrue(planTimeline(attemptId).body().findValuesAsText("event").contains("ABORTED"));

        // 旧尝试迟到回报：只记迟到，不登记新维保记录
        Resp late = planComplete(attemptId, "MS-late", List.of(s1, s2), 2, DISPATCHABLE.plusSeconds(300));
        assertEquals(200, late.status());
        assertEquals("ABORTED", late.text("status"));
        assertTrue(planTimeline(attemptId).body().findValuesAsText("event").contains("LATE_REPORT"));
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, records, "仅手工登记一条记录，迟到回报不多记");

        // 结清一次：再次手工维保（乱序迟到事件）不改变义务
        Resp again = maintenance("MS-manual-2", e1, LocalDate.of(2026, 1, 10), null);
        assertEquals(200, again.status());
        assertEquals("LATE_EVENT", again.text("result"));
        assertEquals("FULFILLED", planObligation(obId).text("status"));

        // 人员已释放：s1/s2 可在重叠窗为另一电梯执行尝试
        Resp other = planDispatch("MS-other", e2, ob2, List.of(s1, s2), twoItems(),
                W_START, W_END, DISPATCHABLE.plusSeconds(60));
        assertEquals(200, other.status(), "尝试终止后人员不再占用");
    }
}
