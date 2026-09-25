package com.example.starter.irrigation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灌区配水服务 API 集成测试：覆盖编组、发布、计量、加购、结转封存主流程与失败分支。
 * 全部数据为合成数据，使用 H2 内存库（MySQL 兼容模式）。
 */
class IrrigationApiTest extends AbstractIntegrationTest {

    /**
     * 创建三轮灌组（D1=100 亩、D2=200 亩、D3=300 亩），返回斗口编号到 id 的映射。
     */
    private Map<String, Long> createStandardGroup() {
        Map<String, Object> body = new HashMap<>();
        body.put("code", "GRP-A");
        body.put("name", "合成轮灌组A");
        body.put("outlets", List.of(
                Map.of("code", "D1", "name", "斗口1", "areaMu", 100),
                Map.of("code", "D2", "name", "斗口2", "areaMu", 200),
                Map.of("code", "D3", "name", "斗口3", "areaMu", 300)));
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/groups", body, JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        JsonNode json = response.getBody();
        Map<String, Long> ids = new HashMap<>();
        ids.put("group", json.get("groupId").asLong());
        for (JsonNode outlet : json.get("outlets")) {
            ids.put(outlet.get("code").asText(), outlet.get("outletId").asLong());
        }
        return ids;
    }

    private ResponseEntity<JsonNode> publishPlan(String requestId, long groupId, int roundNo, long totalWaterM3) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("groupId", groupId);
        body.put("roundNo", roundNo);
        body.put("totalWaterM3", totalWaterM3);
        return rest.postForEntity("/api/plans", body, JsonNode.class);
    }

    private ResponseEntity<JsonNode> reportMetering(long outletId, int roundNo, long amountM3) {
        Map<String, Object> body = new HashMap<>();
        body.put("outletId", outletId);
        body.put("roundNo", roundNo);
        body.put("amountM3", amountM3);
        return rest.postForEntity("/api/meterings", body, JsonNode.class);
    }

    private ResponseEntity<JsonNode> seal(long groupId, int roundNo) {
        return rest.postForEntity("/api/settlements/seal",
                Map.of("groupId", groupId, "roundNo", roundNo), JsonNode.class);
    }

    private ResponseEntity<JsonNode> extraPurchase(String requestId, long groupId, int roundNo, long amountM3) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("groupId", groupId);
        body.put("roundNo", roundNo);
        body.put("amountM3", amountM3);
        return rest.postForEntity("/api/extra-purchases", body, JsonNode.class);
    }

    private Map<String, JsonNode> detailsByCode(JsonNode plan) {
        Map<String, JsonNode> map = new HashMap<>();
        for (JsonNode detail : plan.get("details")) {
            map.put(detail.get("outletCode").asText(), detail);
        }
        return map;
    }

    private Map<Long, JsonNode> settlementByOutlet(JsonNode settlement) {
        Map<Long, JsonNode> map = new HashMap<>();
        for (JsonNode detail : settlement.get("details")) {
            map.put(detail.get("outletId").asLong(), detail);
        }
        return map;
    }

    @Test
    void 完整业务流程_编组_发布_计量_加购_结转封存_迟到计量_下轮补供() {
        Map<String, Long> ids = createStandardGroup();
        long groupId = ids.get("group");

        // 1. 发布第 1 轮计划：权重 0.1667/0.3333/0.5000 → 1667/3333/5000，合计等于可供总量。
        ResponseEntity<JsonNode> planResponse = publishPlan("req-plan-1", groupId, 1, 10000);
        assertEquals(HttpStatus.CREATED, planResponse.getStatusCode());
        JsonNode plan = planResponse.getBody();
        assertFalse(plan.get("replayed").asBoolean());
        assertEquals("EFFECTIVE", plan.get("status").asText());
        assertEquals("1970-01-08", plan.get("roundStart").asText());
        assertEquals("1970-01-15", plan.get("roundEnd").asText());
        Map<String, JsonNode> details = detailsByCode(plan);
        assertEquals(1667, details.get("D1").get("plannedM3").asLong());
        assertEquals(3333, details.get("D2").get("plannedM3").asLong());
        assertEquals(5000, details.get("D3").get("plannedM3").asLong());
        long plannedSum = details.values().stream().mapToLong(d -> d.get("plannedM3").asLong()).sum();
        assertEquals(10000, plannedSum);

        // 2. 计量：D1 足额，D2/D3 欠供。
        assertEquals("CONFIRMED", reportMetering(ids.get("D1"), 1, 1667).getBody().get("status").asText());
        assertEquals("CONFIRMED", reportMetering(ids.get("D2"), 1, 3000).getBody().get("status").asText());
        assertEquals("CONFIRMED", reportMetering(ids.get("D3"), 1, 3000).getBody().get("status").asText());

        // 3. 临时加购 100 m³：仅欠供斗口参与，按欠供 333:2000 比例分配 → 14/86。
        ResponseEntity<JsonNode> purchaseResponse = extraPurchase("req-extra-1", groupId, 1, 100);
        assertEquals(HttpStatus.CREATED, purchaseResponse.getStatusCode());
        JsonNode purchase = purchaseResponse.getBody();
        assertEquals(2, purchase.get("allocations").size());
        Map<Long, Long> allocations = new HashMap<>();
        for (JsonNode allocation : purchase.get("allocations")) {
            allocations.put(allocation.get("outletId").asLong(), allocation.get("amountM3").asLong());
        }
        assertEquals(14L, allocations.get(ids.get("D2")));
        assertEquals(86L, allocations.get(ids.get("D3")));

        // 4. 封存第 1 轮：D2 欠供 333 全额结转；D3 欠供 2000，结转上限 1500（30%），作废 500。
        ResponseEntity<JsonNode> sealResponse = seal(groupId, 1);
        assertEquals(HttpStatus.OK, sealResponse.getStatusCode());
        JsonNode settlement = sealResponse.getBody();
        assertFalse(settlement.get("replayed").asBoolean());
        Map<Long, JsonNode> byOutlet = settlementByOutlet(settlement);
        JsonNode d1 = byOutlet.get(ids.get("D1"));
        assertEquals(1667, d1.get("plannedM3").asLong());
        assertEquals(1667, d1.get("actualM3").asLong());
        assertEquals(0, d1.get("deficitM3").asLong());
        JsonNode d2 = byOutlet.get(ids.get("D2"));
        assertEquals(333, d2.get("deficitM3").asLong());
        assertEquals(333, d2.get("carryoverM3").asLong());
        assertEquals(0, d2.get("voidedM3").asLong());
        JsonNode d3 = byOutlet.get(ids.get("D3"));
        assertEquals(2000, d3.get("deficitM3").asLong());
        assertEquals(1500, d3.get("carryoverM3").asLong());
        assertEquals(500, d3.get("voidedM3").asLong());

        // 5. 重复封存：幂等成功，返回首次结果。
        ResponseEntity<JsonNode> sealReplay = seal(groupId, 1);
        assertEquals(HttpStatus.OK, sealReplay.getStatusCode());
        assertTrue(sealReplay.getBody().get("replayed").asBoolean());
        assertEquals(settlement.get("settlementId").asLong(),
                sealReplay.getBody().get("settlementId").asLong());

        // 6. 迟到计量：只记迟到事件，不改结转结果。
        ResponseEntity<JsonNode> lateResponse = reportMetering(ids.get("D2"), 1, 3333);
        assertEquals("LATE", lateResponse.getBody().get("status").asText());
        JsonNode settledAfterLate = rest.getForEntity(
                "/api/settlements?groupId=" + groupId + "&roundNo=1", JsonNode.class).getBody();
        assertEquals(3000, settlementByOutlet(settledAfterLate).get(ids.get("D2")).get("actualM3").asLong());
        assertEquals(333, settlementByOutlet(settledAfterLate).get(ids.get("D2")).get("carryoverM3").asLong());
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_metering_event WHERE group_id = ? AND round_no = 1",
                Integer.class, groupId);
        assertEquals(1, lateEvents);

        // 7. 第 2 轮计划：上一轮结转转入本轮补供。
        ResponseEntity<JsonNode> round2 = publishPlan("req-plan-2", groupId, 2, 12000);
        assertEquals(HttpStatus.CREATED, round2.getStatusCode());
        Map<String, JsonNode> round2Details = detailsByCode(round2.getBody());
        assertEquals(2000, round2Details.get("D1").get("baseM3").asLong());
        assertEquals(0, round2Details.get("D1").get("carryoverInM3").asLong());
        assertEquals(2000, round2Details.get("D1").get("plannedM3").asLong());
        assertEquals(4000, round2Details.get("D2").get("baseM3").asLong());
        assertEquals(333, round2Details.get("D2").get("carryoverInM3").asLong());
        assertEquals(4333, round2Details.get("D2").get("plannedM3").asLong());
        assertEquals(6000, round2Details.get("D3").get("baseM3").asLong());
        assertEquals(1500, round2Details.get("D3").get("carryoverInM3").asLong());
        assertEquals(7500, round2Details.get("D3").get("plannedM3").asLong());
    }

    @Test
    void 同键重放返回首次结果_同键异参409且不产生新版本() {
        Map<String, Long> ids = createStandardGroup();
        long groupId = ids.get("group");

        ResponseEntity<JsonNode> first = publishPlan("req-idem-1", groupId, 1, 10000);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());

        // 同键同参：返回首次结果，标记重放。
        ResponseEntity<JsonNode> replay = publishPlan("req-idem-1", groupId, 1, 10000);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertTrue(replay.getBody().get("replayed").asBoolean());
        assertEquals(first.getBody().get("planVersionId").asLong(),
                replay.getBody().get("planVersionId").asLong());

        // 同键异参：409，且不产生新版本。
        ResponseEntity<JsonNode> conflict = publishPlan("req-idem-1", groupId, 1, 9999);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        JsonNode versions = rest.getForEntity(
                "/api/plans?groupId=" + groupId + "&roundNo=1", JsonNode.class).getBody();
        assertEquals(1, versions.size());
    }

    @Test
    void 修改发布新版本_旧版本可查_同轮同组仅一个生效版本() {
        Map<String, Long> ids = createStandardGroup();
        long groupId = ids.get("group");

        ResponseEntity<JsonNode> v1 = publishPlan("req-v1", groupId, 1, 10000);
        assertEquals(HttpStatus.CREATED, v1.getStatusCode());
        ResponseEntity<JsonNode> v2 = publishPlan("req-v2", groupId, 1, 20000);
        assertEquals(HttpStatus.CREATED, v2.getStatusCode());
        assertEquals(2, v2.getBody().get("versionNo").asInt());

        // 生效版本为 v2。
        JsonNode effective = rest.getForEntity(
                "/api/plans/effective?groupId=" + groupId + "&roundNo=1", JsonNode.class).getBody();
        assertEquals(v2.getBody().get("planVersionId").asLong(), effective.get("planVersionId").asLong());
        assertEquals(20000, effective.get("totalWaterM3").asLong());

        // 旧版本可查且状态为已废止。
        JsonNode versions = rest.getForEntity(
                "/api/plans?groupId=" + groupId + "&roundNo=1", JsonNode.class).getBody();
        assertEquals(2, versions.size());
        assertEquals("SUPERSEDED", versions.get(0).get("status").asText());
        assertEquals("EFFECTIVE", versions.get(1).get("status").asText());

        // 首次请求按键重放仍返回 v1 结果。
        ResponseEntity<JsonNode> replayV1 = publishPlan("req-v1", groupId, 1, 10000);
        assertEquals(HttpStatus.OK, replayV1.getStatusCode());
        assertEquals(v1.getBody().get("planVersionId").asLong(),
                replayV1.getBody().get("planVersionId").asLong());
    }

    @Test
    void 计量按斗口轮次去重_重复上报不重复累计() {
        Map<String, Long> ids = createStandardGroup();
        long groupId = ids.get("group");
        publishPlan("req-plan-dedup", groupId, 1, 10000);

        assertEquals("CONFIRMED", reportMetering(ids.get("D1"), 1, 500).getBody().get("status").asText());
        assertEquals("CONFIRMED", reportMetering(ids.get("D1"), 1, 500).getBody().get("status").asText());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM metering WHERE outlet_id = ? AND round_no = 1",
                Integer.class, ids.get("D1"));
        Long amount = jdbc.queryForObject(
                "SELECT amount_m3 FROM metering WHERE outlet_id = ? AND round_no = 1",
                Long.class, ids.get("D1"));
        assertEquals(1, count);
        assertEquals(500L, amount);

        // 更正上报按快照覆盖，仍只有一行。
        assertEquals("CONFIRMED", reportMetering(ids.get("D1"), 1, 800).getBody().get("status").asText());
        amount = jdbc.queryForObject(
                "SELECT amount_m3 FROM metering WHERE outlet_id = ? AND round_no = 1",
                Long.class, ids.get("D1"));
        assertEquals(800L, amount);
    }

    @Test
    void 无欠供斗口时加购返回400() {
        Map<String, Long> ids = createStandardGroup();
        long groupId = ids.get("group");
        publishPlan("req-plan-full", groupId, 1, 3000);
        reportMetering(ids.get("D1"), 1, 500);
        reportMetering(ids.get("D2"), 1, 1000);
        reportMetering(ids.get("D3"), 1, 1500);

        ResponseEntity<JsonNode> response = extraPurchase("req-extra-full", groupId, 1, 100);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void 轮次封存后禁止发布新计划与加购() {
        Map<String, Long> ids = createStandardGroup();
        long groupId = ids.get("group");
        publishPlan("req-plan-sealed", groupId, 1, 10000);
        assertEquals(HttpStatus.OK, seal(groupId, 1).getStatusCode());

        ResponseEntity<JsonNode> publishAfterSeal = publishPlan("req-plan-after-seal", groupId, 1, 20000);
        assertEquals(HttpStatus.CONFLICT, publishAfterSeal.getStatusCode());
        ResponseEntity<JsonNode> purchaseAfterSeal = extraPurchase("req-extra-after-seal", groupId, 1, 100);
        assertEquals(HttpStatus.CONFLICT, purchaseAfterSeal.getStatusCode());
    }

    @Test
    void 参数校验失败返回400_资源不存在返回404() {
        // 缺少 requestId。
        Map<String, Object> invalid = new HashMap<>();
        invalid.put("groupId", 1);
        invalid.put("roundNo", 1);
        invalid.put("totalWaterM3", 1000);
        ResponseEntity<JsonNode> badRequest = rest.postForEntity("/api/plans", invalid, JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, badRequest.getStatusCode());

        // 轮灌组不存在。
        ResponseEntity<JsonNode> notFound = publishPlan("req-404", 999999L, 1, 1000);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        ResponseEntity<JsonNode> groupNotFound = rest.getForEntity("/api/groups/999999", JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND, groupNotFound.getStatusCode());
    }
}
