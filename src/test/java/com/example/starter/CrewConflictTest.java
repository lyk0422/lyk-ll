package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 班组唯一执行单：资源冲突挂单等待，班组释放后按队列优先级自动承接；
 * 并以真实并发验证 crew_execution 主键约束下同一班组至多一张执行中工单。
 */
class CrewConflictTest extends BaseIntegrationTest {

    private String reportAssess(String ridPrefix, String point, int users) {
        String no = orderNo(require2xx(post("/reports", Map.of(
                "requestId", ridPrefix + "-rep", "feederCode", "F103",
                "faultPoint", point, "affectedUsers", users,
                "outageTime", T0.toString()))));
        require2xx(post("/orders/" + no + "/assess", Map.of("requestId", ridPrefix + "-ass")));
        return no;
    }

    private void fullRestore(String no, String ridPrefix) {
        require2xx(post("/orders/" + no + "/repair-start", Map.of("requestId", ridPrefix + "-rep-start")));
        require2xx(post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", ridPrefix + "-field", "actor", "FIELD", "actorName", "现场甲",
                "restoredLoadKw", 100, "recoveredUsers", 10)));
        require2xx(post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", ridPrefix + "-disp", "actor", "DISPATCHER", "actorName", "调度乙",
                "restoredLoadKw", 100, "recoveredUsers", 10)));
    }

    @Test
    void crewSingleExecution_parkedOrderAutoPromotedOnRelease() {
        String first = reportAssess("c1", "CC-1", 50);
        String second = reportAssess("c2", "CC-2", 30);

        var d1 = require2xx(post("/dispatches",
                Map.of("requestId", "c1-dis", "orderNo", first, "crewCode", "C01")));
        assertEquals("DISPATCHED", d1.get("status"));

        var d2 = require2xx(post("/dispatches",
                Map.of("requestId", "c2-dis", "orderNo", second, "crewCode", "C01")));
        assertEquals("WAITING", d2.get("status"), "班组执行中，第二单应挂单等待");
        assertEquals(Boolean.TRUE, d2.get("parked"));
        assertEquals("C01", d2.get("waitCrewCode"));
        assertEquals(1, countSql("SELECT COUNT(*) FROM crew_execution"), "同一班组只有一张执行中工单");
        assertEquals(1, countSql("SELECT COUNT(*) FROM fault_order WHERE status = 'WAITING'"));

        // 第一张工单完成复电释放班组，挂单工单按队列自动承接
        fullRestore(first, "c1");
        var detail = require2xx(get("/orders/" + second));
        assertEquals("DISPATCHED", detail.get("status"), "班组释放后挂单应自动派单");
        assertEquals("C01", detail.get("crewCode"));
        assertEquals(1, countSql("SELECT COUNT(*) FROM crew_execution"), "释放-承接后仍只有一张执行单");
    }

    @Test
    void concurrentDispatchSameCrew_onlyOneOrderOccupies() throws Exception {
        String a = reportAssess("p1", "P-A", 10);
        String b = reportAssess("p2", "P-B", 10);

        int n = 2;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
        List<String> orders = List.of(a, b);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return post("/dispatches", Map.of(
                        "requestId", "p-dis-" + idx,
                        "orderNo", orders.get(idx),
                        "crewCode", "C02"));
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (var f : futures) {
            statuses.add(f.get(20, TimeUnit.SECONDS).getStatusCode().value());
        }
        pool.shutdownNow();
        assertEquals(List.of(200, 200), statuses.stream().sorted().toList(), "两次派单均应成功返回");

        assertEquals(1, countSql("SELECT COUNT(*) FROM crew_execution"), "并发下班组执行占用仍唯一");
        assertEquals(1, countSql("SELECT COUNT(*) FROM fault_order WHERE status = 'DISPATCHED'"));
        assertEquals(1, countSql("SELECT COUNT(*) FROM fault_order WHERE status = 'WAITING'"));

        var dispatched = jdbc.queryForList("SELECT order_no FROM fault_order WHERE status = 'DISPATCHED'");
        assertNotNull(dispatched.get(0).get("order_no"));
    }
}
