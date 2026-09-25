package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 同故障点＋时段重复接报去重归并，以及窗口外/复电后的新接报口径。
 */
class ReportDedupTest extends BaseIntegrationTest {

    private Map<String, Object> reportReq(String rid, String point, int users) {
        return Map.of(
                "requestId", rid,
                "feederCode", "F103",
                "faultPoint", point,
                "affectedUsers", users,
                "outageTime", T0.toString());
    }

    @Test
    void samePointWithinWindow_mergedToFirstOrder() {
        String first = orderNo(require2xx(post("/reports", reportReq("dd-1", "同一故障点", 10))));

        clock.freezeAt(T0.plusSeconds(29 * 60).toEpochMilli());
        var second = require2xx(post("/reports", reportReq("dd-2", "同一故障点", 10)));
        assertEquals(first, orderNo(second), "窗口内同故障点归并到首单");
        assertEquals(Boolean.TRUE, second.get("dedup"));
        assertEquals(1, countSql("SELECT COUNT(*) FROM fault_order"), "归并不再建工单");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                rest.getForObject(url("/orders/" + first + "/events"), List.class);
        assertTrue(events.stream().anyMatch(e -> "DEDUP_MERGE".equals(e.get("eventType"))),
                "归并在首单留痕");
    }

    @Test
    void samePointOutsideWindow_createsNewOrder() {
        String first = orderNo(require2xx(post("/reports", reportReq("dd-3", "窗口边界点", 10))));

        // 恰满 30 分钟（窗口 [now-30min, now] 闭区间，首单在窗口边界上仍归并）
        clock.freezeAt(T0.plusSeconds(30 * 60).toEpochMilli());
        var atBoundary = require2xx(post("/reports", reportReq("dd-4", "窗口边界点", 10)));
        assertEquals(first, orderNo(atBoundary), "恰满30分钟仍在归并窗口内");

        // 30 分零 1 秒：新故障，新建工单
        clock.freezeAt(T0.plusSeconds(30 * 60 + 1).toEpochMilli());
        var outside = require2xx(post("/reports", reportReq("dd-5", "窗口边界点", 10)));
        assertEquals(2, countSql("SELECT COUNT(*) FROM fault_order"), "窗口外应新建工单");
        assertTrue(!first.equals(orderNo(outside)));
    }

    @Test
    void samePointAfterRestoration_createsNewOrder() {
        String first = orderNo(require2xx(post("/reports", reportReq("dd-6", "复电后再停", 10))));
        require2xx(post("/orders/" + first + "/assess", Map.of("requestId", "dd-7")));
        require2xx(post("/dispatches",
                Map.of("requestId", "dd-8", "orderNo", first, "crewCode", "C01")));
        require2xx(post("/orders/" + first + "/repair-start", Map.of("requestId", "dd-9")));
        require2xx(post("/orders/" + first + "/restore-confirm", Map.of(
                "requestId", "dd-10", "actor", "FIELD", "actorName", "现场",
                "restoredLoadKw", 0, "recoveredUsers", 0)));
        require2xx(post("/orders/" + first + "/restore-confirm", Map.of(
                "requestId", "dd-11", "actor", "DISPATCHER", "actorName", "调度",
                "restoredLoadKw", 50, "recoveredUsers", 10)));

        clock.freezeAt(T0.plusSeconds(10 * 60).toEpochMilli());
        var again = require2xx(post("/reports", reportReq("dd-12", "复电后再停", 10)));
        assertTrue(!first.equals(orderNo(again)), "已复电故障点再次停电应按新故障建档");
        assertEquals(2, countSql("SELECT COUNT(*) FROM fault_order"));
    }
}
