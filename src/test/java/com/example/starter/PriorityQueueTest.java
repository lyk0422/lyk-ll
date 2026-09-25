package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 同一馈线多点故障排序：影响户数降序，户数恰同按接报先后；
 * 特级/重要用户整体优先；派单队列可复算。
 */
class PriorityQueueTest extends BaseIntegrationTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queue() {
        return rest.getForObject(url("/queue"), List.class);
    }

    @Test
    void sameFeederOrdersSortByAffectedUsersDescThenReportTime() {
        // 馈线 F103 无重要用户（NORMAL），三点故障：20 户(后报)、30 户、20 户(先报)
        Instant base = T0;
        String a = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "q-1", "feederCode", "F103",
                "faultPoint", "P-20-late", "affectedUsers", 20,
                "outageTime", base.toString()))));
        clock.freezeAt(base.plusSeconds(60).toEpochMilli());
        String b = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "q-2", "feederCode", "F103",
                "faultPoint", "P-30", "affectedUsers", 30,
                "outageTime", base.plusSeconds(60).toString()))));
        clock.freezeAt(base.plusSeconds(120).toEpochMilli());
        String c = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "q-3", "feederCode", "F103",
                "faultPoint", "P-20-early", "affectedUsers", 20,
                "outageTime", base.plusSeconds(120).toString()))));

        require2xx(post("/orders/" + a + "/assess", Map.of("requestId", "q-4")));
        require2xx(post("/orders/" + b + "/assess", Map.of("requestId", "q-5")));
        require2xx(post("/orders/" + c + "/assess", Map.of("requestId", "q-6")));

        List<Map<String, Object>> queue = queue();
        assertEquals(3, queue.size());
        // 期望顺序：30 户 → 20 户先接报(P-20-late, q-1) → 20 户后接报(P-20-early, q-3)
        assertEquals(b, queue.get(0).get("orderNo"), "影响户数最多者优先");
        assertEquals(a, queue.get(1).get("orderNo"), "户数恰同，接报在前者优先");
        assertEquals(c, queue.get(2).get("orderNo"));
        assertEquals(1, queue.get(0).get("rank"));
    }

    @Test
    void specialGradeOrdersAheadOfNormalRegardlessOfUsers() {
        // F102 含特级用户，5 户；F103 无重要用户，999 户
        String special = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "g-1", "feederCode", "F102",
                "faultPoint", "滨河二线#1杆", "affectedUsers", 5,
                "outageTime", T0.toString()))));
        String normal = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "g-2", "feederCode", "F103",
                "faultPoint", "开发区三线#9杆", "affectedUsers", 999,
                "outageTime", T0.toString()))));
        require2xx(post("/orders/" + special + "/assess", Map.of("requestId", "g-3")));
        require2xx(post("/orders/" + normal + "/assess", Map.of("requestId", "g-4")));

        List<Map<String, Object>> queue = queue();
        assertEquals(special, queue.get(0).get("orderNo"), "特级工单排在 999 户普通工单之前");
        assertEquals(normal, queue.get(1).get("orderNo"));
    }

    @Test
    void queueRecomputableAfterDispatchAndSeal() {
        String a = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "rc-1", "feederCode", "F103",
                "faultPoint", "RC-1", "affectedUsers", 10, "outageTime", T0.toString()))));
        String b = orderNo(require2xx(post("/reports", Map.of(
                "requestId", "rc-2", "feederCode", "F103",
                "faultPoint", "RC-2", "affectedUsers", 20,
                "outageTime", T0.toString()))));
        require2xx(post("/orders/" + a + "/assess", Map.of("requestId", "rc-3")));
        require2xx(post("/orders/" + b + "/assess", Map.of("requestId", "rc-4")));
        assertEquals(2, queue().size());

        // 派单后 b 离开队列；队列复算只剩 a
        require2xx(post("/dispatches", Map.of("requestId", "rc-5", "orderNo", b, "crewCode", "C01")));
        List<Map<String, Object>> queueAfterDispatch = queue();
        assertEquals(1, queueAfterDispatch.size());
        assertEquals(a, queueAfterDispatch.get(0).get("orderNo"));
        assertTrue(queueAfterDispatch.get(0).containsKey("rank"));
    }
}
