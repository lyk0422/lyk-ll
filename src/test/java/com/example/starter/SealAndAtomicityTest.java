package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 日结封存终态、迟到回报，以及复电确认＋负荷恢复记录的事务原子性。
 */
class SealAndAtomicityTest extends BaseIntegrationTest {

    private String createDispatchedOrder(String prefix) {
        String no = orderNo(require2xx(post("/reports", Map.of(
                "requestId", prefix + "-rep", "feederCode", "F103",
                "faultPoint", prefix + "-P", "affectedUsers", 6,
                "outageTime", T0.toString()))));
        require2xx(post("/orders/" + no + "/assess", Map.of("requestId", prefix + "-ass")));
        require2xx(post("/dispatches",
                Map.of("requestId", prefix + "-dis", "orderNo", no, "crewCode", "C01")));
        require2xx(post("/orders/" + no + "/repair-start",
                Map.of("requestId", prefix + "-rs")));
        return no;
    }

    private void dualConfirm(String no, String prefix, String load, int users) {
        require2xx(post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", prefix + "-f", "actor", "FIELD", "actorName", "现场",
                "restoredLoadKw", 0, "recoveredUsers", 0)));
        require2xx(post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", prefix + "-d", "actor", "DISPATCHER", "actorName", "调度",
                "restoredLoadKw", new BigDecimal(load), "recoveredUsers", users)));
    }

    @Test
    void sealTerminal_repeatSealIdempotent_lateReportOnlyAppendsEvent() {
        String no = createDispatchedOrder("s1");

        // 未复电封存被拒绝
        assertEquals(422, post("/orders/" + no + "/seal", Map.of("requestId", "s1-seal-x"))
                .getStatusCode().value());

        dualConfirm(no, "s1", "60", 6);

        var seal1 = require2xx(post("/orders/" + no + "/seal", Map.of("requestId", "s1-seal")));
        assertEquals("SEALED", seal1.get("status"));

        // 同 requestId 重复封存：幂等成功
        var seal2 = require2xx(post("/orders/" + no + "/seal", Map.of("requestId", "s1-seal")));
        assertEquals("SEALED", seal2.get("status"));
        assertEquals(Boolean.TRUE, seal2.get("idempotentReplayed"));

        // 不同 requestId 重复封存：终态幂等成功，状态不变
        var seal3 = require2xx(post("/orders/" + no + "/seal", Map.of("requestId", "s1-seal-again")));
        assertEquals("SEALED", seal3.get("status"));
        assertEquals(Boolean.TRUE, seal3.get("alreadySealed"));

        // 封存后复电确认被拒绝
        var confirmAfterSeal = post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", "s1-late-c", "actor", "DISPATCHER", "actorName", "调度",
                "restoredLoadKw", 1, "recoveredUsers", 1));
        assertEquals(422, confirmAfterSeal.getStatusCode().value());

        // 封存后迟到回报：仅记事件，状态不变
        var late = require2xx(post("/orders/" + no + "/late-report", Map.of(
                "requestId", "s1-late", "actorName", "巡视张三",
                "detail", "补充现场照片，确认杆号#6")));
        assertEquals(Boolean.TRUE, late.get("accepted"));
        assertEquals(Boolean.FALSE, late.get("stateChanged"));

        var detail = require2xx(get("/orders/" + no));
        assertEquals("SEALED", detail.get("status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = rest.getForObject(url("/orders/" + no + "/events"), List.class);
        long lateCount = events.stream().filter(e -> "LATE_AFTER_SEAL".equals(e.get("eventType"))).count();
        long sealCount = events.stream().filter(e -> "SEAL".equals(e.get("eventType"))).count();
        assertEquals(1, lateCount, "迟到回报只记一条迟到事件");
        assertEquals(1, sealCount, "重复封存不重复写封存事件");
    }

    @Test
    void lateReportBeforeSealRejected() {
        String no = createDispatchedOrder("s2");
        var resp = post("/orders/" + no + "/late-report", Map.of(
                "requestId", "s2-late", "actorName", "甲", "detail", "迟到内容"));
        assertEquals(422, resp.getStatusCode().value());
        assertEquals(0, countSql("SELECT COUNT(*) FROM order_event WHERE event_type = 'LATE_AFTER_SEAL'"));
    }

    /**
     * 复电原子性：第二确认齐备后，若负荷恢复记录写入失败（超出 DECIMAL(12,2) 精度，
     * H2 抛数值越界），同一事务内复电确认与工单状态全部回滚，不留“已复电无负荷”数据。
     */
    @Test
    void restoreRollsBackWhenLoadInsertFails() {
        String no = createDispatchedOrder("s3");
        require2xx(post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", "s3-f", "actor", "FIELD", "actorName", "现场",
                "restoredLoadKw", 0, "recoveredUsers", 0)));

        // 13 位整数超出 DECIMAL(12,2)（整数位最多 10 位），负荷插入必然失败
        var failing = post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", "s3-d-bad", "actor", "DISPATCHER", "actorName", "调度",
                "restoredLoadKw", new BigDecimal("1234567890123.45"), "recoveredUsers", 6));
        assertTrue(failing.getStatusCode().is5xxServerError(),
                "负荷写入失败应整体回滚，实际状态码=" + failing.getStatusCode());

        var detail = require2xx(get("/orders/" + no));
        assertEquals("REPAIRING", detail.get("status"), "工单状态回滚，不得变为 RESTORED");
        assertEquals(0, countSql("SELECT COUNT(*) FROM load_recovery"));
        Integer completed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM restore_confirm WHERE completed_time IS NOT NULL", Integer.class);
        assertEquals(0, completed, "复电完成时间必须回滚");
        // 单方确认记录仍不应产生 completed（字段确认本身已回滚 UPDATE 为初始 NULL）
        Integer disp = jdbc.queryForObject(
                "SELECT COUNT(*) FROM restore_confirm WHERE dispatcher_confirm_time IS NOT NULL", Integer.class);
        assertEquals(0, disp, "同一事务内的调度确认也应回滚");
        assertEquals(1, countSql("SELECT COUNT(*) FROM crew_execution"), "班组占用未释放");
    }
}
