package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 工单合并：原单引用新单、双方轨迹留痕、合计影响户数与最早接报时刻。
 */
class OrderMergeTest extends BaseIntegrationTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(String no) {
        return rest.getForObject(url("/orders/" + no + "/events"), List.class);
    }

    @Test
    void mergeOrders_sourceReferencesTargetAndTracksKept() {
        String a = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "m-1", "feederCode", "F103",
                "faultPoint", "M-A", "description", "A 段",
                "affectedUsers", 12, "outageTime", T0.toString()))));
        clock.freezeAt(T0.plusSeconds(120).toEpochMilli());
        String b = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "m-2", "feederCode", "F103",
                "faultPoint", "M-B", "description", "B 段",
                "affectedUsers", 8,
                "outageTime", T0.plusSeconds(120).toString()))));
        // 一张已研判、一张未研判，均允许合并
        require2xx(post("/orders/" + b + "/assess", Map.of("requestId", "m-3")));

        var merged = require2xx(post("/merges", Map.of(
                "requestId", "m-4",
                "sourceOrderNos", List.of(a, b),
                "description", "同区段多点故障合并抢修")));
        String newNo = orderNo(merged);
        assertEquals(20, merged.get("affectedUsers"), "影响户数合计");
        assertEquals("ASSESSED", merged.get("status"), "合并新单直接进入待派单状态");
        assertEquals(T0.toString(), merged.get("reportTime"), "接报时间取最早");
        assertEquals(newNo, merged.get("orderNo"));

        var detailA = require2xx(get("/orders/" + a));
        assertEquals("MERGED", detailA.get("status"));
        assertEquals(newNo, detailA.get("mergedIntoOrderNo"), "原单引用新单");
        var detailB = require2xx(get("/orders/" + b));
        assertEquals(newNo, detailB.get("mergedIntoOrderNo"));

        List<Map<String, Object>> eventsA = events(a);
        assertTrue(eventsA.stream().anyMatch(e -> "MERGE_OUT".equals(e.get("eventType"))
                && newNo.equals(e.get("relatedOrderNo"))), "原单保留 MERGE_OUT 轨迹且关联新单");
        List<Map<String, Object>> eventsNew = events(newNo);
        assertTrue(eventsNew.stream().anyMatch(e -> "MERGE_IN".equals(e.get("eventType"))),
                "新单保留 MERGE_IN 轨迹");
        assertNotNull(eventsNew);
    }

    @Test
    void mergeRejectsDispatchedOrder() {
        String a = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "md-1", "feederCode", "F103",
                "faultPoint", "MD-A", "affectedUsers", 1, "outageTime", T0.toString()))));
        String b = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "md-2", "feederCode", "F103",
                "faultPoint", "MD-B", "affectedUsers", 1,
                "outageTime", T0.toString()))));
        require2xx(post("/orders/" + a + "/assess", Map.of("requestId", "md-3")));
        require2xx(post("/orders/" + b + "/assess", Map.of("requestId", "md-4")));
        require2xx(post("/dispatches", Map.of("requestId", "md-5", "orderNo", a, "crewCode", "C01")));

        var resp = post("/merges", Map.of(
                "requestId", "md-6", "sourceOrderNos", List.of(a, b)));
        assertEquals(422, resp.getStatusCode().value(), "已派单工单不允许合并");
        assertEquals(0, countSql("SELECT COUNT(*) FROM fault_order WHERE status = 'MERGED'"));
    }
}
