package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 主流程：接报→研判→派单→抢修→双确认复电→日结；
 * 并验证特级用户停电恰满 5 分钟（300 秒）启动发电车、4 分 59 秒不启动。
 */
class MainFlowGeneratorTest extends BaseIntegrationTest {

    private Map<String, Object> reportBody(String rid, String point, int users, Instant outage) {
        return Map.of(
                "requestId", rid,
                "feederCode", "F101",
                "faultPoint", point,
                "description", "主干线接地故障",
                "affectedUsers", users,
                "outageTime", outage.toString());
    }

    @Test
    void fullFlow_andGeneratorTriggersAtExactlyFiveMinutes() {
        // 10:00:00（上海时间）接报，特级用户馈线 F101
        clock.freezeAt(T0.toEpochMilli());
        var reportResp = require2xx(post("/reports", reportBody("r-1", "东风一线#12杆", 50, T0)));
        String no = orderNo(reportResp);
        assertEquals("REPORTED", reportResp.get("status"));

        // 研判命中 SPECIAL
        var assessResp = require2xx(post("/orders/" + no + "/assess", Map.of("requestId", "r-2")));
        assertEquals("SPECIAL", assessResp.get("grade"));
        assertEquals("ASSESSED", assessResp.get("status"));

        // 4 分 59 秒：派单，发电车流程尚未启动
        clock.freezeAt(T0.plusSeconds(299).toEpochMilli());
        var dispatchResp = require2xx(post("/dispatches",
                Map.of("requestId", "r-3", "orderNo", no, "crewCode", "C01")));
        assertEquals("DISPATCHED", dispatchResp.get("status"));
        assertEquals(0, countSql("SELECT COUNT(*) FROM generator_truck WHERE order_id ="
                + " (SELECT id FROM fault_order WHERE order_no = ?)", no), "4分59秒不应启动发电车");

        // 恰满 5 分钟（300 秒）：抢修开始触发复算，发电车流程启动，触发时刻=停电+300s
        clock.freezeAt(T0.plusSeconds(300).toEpochMilli());
        var repairResp = require2xx(post("/orders/" + no + "/repair-start", Map.of("requestId", "r-4")));
        assertEquals("REPAIRING", repairResp.get("status"));

        var detail = require2xx(get("/orders/" + no));
        @SuppressWarnings("unchecked")
        Map<String, Object> truck = (Map<String, Object>) detail.get("generatorTruck");
        assertNotNull(truck, "恰满5分钟应启动发电车流程");
        assertEquals(300, ((Number) truck.get("elapsedSecondsAtTrigger")).intValue());
        assertEquals("TRIGGERED", truck.get("status"));
        assertEquals(Instant.parse(truck.get("triggerTime").toString()),
                Instant.parse(detail.get("outageTime").toString()).plusSeconds(300));

        // 现场单方确认：不生效
        var fieldOnly = require2xx(post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", "r-5", "actor", "FIELD", "actorName", "班长王五",
                "restoredLoadKw", 0, "recoveredUsers", 0)));
        assertEquals(Boolean.FALSE, fieldOnly.get("restored"));
        assertEquals(Boolean.TRUE, fieldOnly.get("fieldConfirmed"));
        assertEquals(Boolean.FALSE, fieldOnly.get("dispatcherConfirmed"));
        assertEquals("REPAIRING", fieldOnly.get("status"), "单人确认不改变工单状态");
        assertEquals(0, countSql("SELECT COUNT(*) FROM load_recovery lr"
                + " JOIN fault_order o ON o.id = lr.order_id WHERE o.order_no = ?", no), "单方确认不得写负荷");

        // 调度第二确认：双确认齐备，复电完成，负荷同事务记录，发电车撤回
        var restored = require2xx(post("/orders/" + no + "/restore-confirm", Map.of(
                "requestId", "r-6", "actor", "DISPATCHER", "actorName", "调度赵六",
                "restoredLoadKw", 320.50, "recoveredUsers", 50)));
        assertEquals(Boolean.TRUE, restored.get("restored"));
        assertEquals("RESTORED", restored.get("status"));
        assertEquals(1, countSql("SELECT COUNT(*) FROM load_recovery lr"
                + " JOIN fault_order o ON o.id = lr.order_id WHERE o.order_no = ?", no));
        assertEquals(1, countSql("SELECT COUNT(*) FROM generator_truck gt"
                + " JOIN fault_order o ON o.id = gt.order_id WHERE o.order_no = ? AND gt.status = 'WITHDRAWN'",
                no), "复电后发电车应撤回");

        // 日结封存终态
        var sealed = require2xx(post("/orders/" + no + "/seal", Map.of("requestId", "r-7")));
        assertEquals("SEALED", sealed.get("status"));

        // 班组已释放
        assertEquals(0, countSql("SELECT COUNT(*) FROM crew_execution ce"
                + " JOIN crew c ON c.id = ce.crew_id WHERE c.crew_code = 'C01'"));
    }

    @Test
    void assessWithoutImportantUser_defaultsNormal() {
        clock.freezeAt(T0.toEpochMilli());
        var resp = require2xx(post("/reports", Map.of(
                "requestId", "n-1",
                "feederCode", "F103",
                "faultPoint", "开发区三线#3杆",
                "affectedUsers", 3,
                "outageTime", T0.toString())));
        String no = orderNo(resp);
        var assessed = require2xx(post("/orders/" + no + "/assess", Map.of("requestId", "n-2")));
        assertEquals("NORMAL", assessed.get("grade"));
    }

    @Test
    void unknownFeeder_reports404() {
        clock.freezeAt(T0.toEpochMilli());
        ResponseEntity<Map<String, Object>> resp = post("/reports", Map.of(
                "requestId", "n-3",
                "feederCode", "F404",
                "faultPoint", "X",
                "affectedUsers", 1,
                "outageTime", T0.toString()));
        assertEquals(404, resp.getStatusCode().value());
        assertFalse(resp.getStatusCode().is2xxSuccessful());
    }
}
