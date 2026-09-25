package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * requestId 幂等：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参 409 且不改变工单状态。
 */
class IdempotencyTest extends BaseIntegrationTest {

    private Map<String, Object> reportReq(String rid, String point, int users) {
        return Map.of(
                "requestId", rid,
                "feederCode", "F103",
                "faultPoint", point,
                "affectedUsers", users,
                "outageTime", T0.toString());
    }

    @Test
    void sameKeySameParams_returnsFirstResult() {
        var first = require2xx(post("/reports", reportReq("idem-1", "IDEM-P", 11)));
        String firstNo = orderNo(first);
        assertEquals(Boolean.FALSE, first.get("idempotentReplayed"));

        var replay = require2xx(post("/reports", reportReq("idem-1", "IDEM-P", 11)));
        assertEquals(firstNo, orderNo(replay), "重放返回首次工单号");
        assertEquals(Boolean.TRUE, replay.get("idempotentReplayed"));
        assertEquals(1, countSql("SELECT COUNT(*) FROM fault_order WHERE fault_point = 'IDEM-P'"),
                "重放不得新建工单");
    }

    @Test
    void sameKeyDifferentParams_returns409AndKeepsState() {
        var first = require2xx(post("/reports", reportReq("idem-2", "IDEM-X", 11)));
        String firstNo = orderNo(first);

        var conflict = post("/reports", reportReq("idem-2", "IDEM-X", 22));
        assertEquals(409, conflict.getStatusCode().value());
        var after = require2xx(get("/orders/" + firstNo));
        assertEquals(11, ((Number) after.get("affectedUsers")).intValue(), "409 不得改变业务字段");
        assertEquals("REPORTED", after.get("status"));
        assertEquals(1, countSql("SELECT COUNT(*) FROM fault_order"));
    }

    @Test
    void dispatchIdempotency_sameKeySameParamsReplays() {
        String no = orderNo(require2xx(post("/reports", reportReq("idem-3", "IDEM-D", 5))));
        require2xx(post("/orders/" + no + "/assess", Map.of("requestId", "idem-3a")));

        var d1 = require2xx(post("/dispatches",
                Map.of("requestId", "idem-3d", "orderNo", no, "crewCode", "C01")));
        var d2 = require2xx(post("/dispatches",
                Map.of("requestId", "idem-3d", "orderNo", no, "crewCode", "C01")));
        assertEquals(d1.get("status"), d2.get("status"));
        assertEquals(Boolean.TRUE, d2.get("idempotentReplayed"));
        assertEquals(1, countSql("SELECT COUNT(*) FROM crew_execution"));
    }

    @Test
    void restoreConfirmIdempotency_chainRecomputable() {
        String no = orderNo(require2xx(post("/reports", reportReq("idem-4", "IDEM-R", 7))));
        require2xx(post("/orders/" + no + "/assess", Map.of("requestId", "idem-4a")));
        require2xx(post("/dispatches",
                Map.of("requestId", "idem-4d", "orderNo", no, "crewCode", "C01")));
        require2xx(post("/orders/" + no + "/repair-start", Map.of("requestId", "idem-4rs")));

        var f1 = require2xx(post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", "idem-4f", "actor", "FIELD", "actorName", "现场",
                "restoredLoadKw", 0, "recoveredUsers", 0)));
        var f1Replay = require2xx(post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", "idem-4f", "actor", "FIELD", "actorName", "现场",
                "restoredLoadKw", 0, "recoveredUsers", 0)));
        assertEquals(Boolean.TRUE, f1Replay.get("idempotentReplayed"));
        assertEquals(Boolean.FALSE, f1Replay.get("restored"));
        assertEquals(f1.get("status"), f1Replay.get("status"));

        var d = require2xx(post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", "idem-4dp", "actor", "DISPATCHER", "actorName", "调度",
                "restoredLoadKw", 88.8, "recoveredUsers", 7)));
        assertEquals(Boolean.TRUE, d.get("restored"));
        assertEquals(1, countSql("SELECT COUNT(*) FROM load_recovery"));

        // 复电链可复算：详情含双方确认与完成时刻
        var detail = require2xx(get("/orders/" + no));
        @SuppressWarnings("unchecked")
        Map<String, Object> chain = (Map<String, Object>) detail.get("restoreConfirmChain");
        assertEquals(Boolean.TRUE, chain.get("dispatcherConfirmed"));
        assertEquals(Boolean.TRUE, chain.get("fieldConfirmed"));
        assertNotNull(chain.get("completedTime"));
    }

    @Test
    void concurrentSameKeySameParams_onlyOneOrderCreated() throws Exception {
        int n = 4;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<org.springframework.http.ResponseEntity<Map<String, Object>>>> futures =
                new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return post("/reports", reportReq("idem-conc", "CONC-P", 9));
            }));
        }
        start.countDown();
        java.util.List<String> orderNos = new java.util.ArrayList<>();
        for (var f : futures) {
            var resp = f.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, resp.getStatusCode().value());
            orderNos.add(orderNo(resp.getBody()));
        }
        pool.shutdownNow();
        assertEquals(1, orderNos.stream().distinct().count(), "并发同键同参必须返回同一工单");
        assertEquals(1, countSql("SELECT COUNT(*) FROM fault_order WHERE fault_point = 'CONC-P'"));
        assertEquals(1, countSql("SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'idem-conc'"));
    }

    private static void assertNotNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNotNull(o);
    }
}
