package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 染料批次效价补偿与受控投料：主流程与失败分支。
 * 种子批次：B-RED-01(D-RED,1.25) / B-YELLOW-01(D-YELLOW,1.0) / B-BLUE-01(D-BLUE,0.8)，版本均 1。
 * R-BASE v1、50kg、浴比10：原目标 RED 1000 / YELLOW 750 / BLUE 250；
 * 补偿目标 RED 800.000 / YELLOW 750.000 / BLUE 312.500。
 */
class DyeBatchFlowTest extends BaseIntegrationTest {

    private static final String VALID_UNTIL = "2099-01-01T00:00:00Z";

    // ------------------------------------------------------------------
    // 批次登记：版本从 1 开始；幂等重放与 409；参数非法 400
    // ------------------------------------------------------------------

    @Test
    void registerBatch_created_versionStartsAtOne_andIdempotent() {
        Map<String, Object> req = Map.of(
                "requestId", "rb-1", "batchNo", "B-NEW-1", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.10"), "availableG", new BigDecimal("500"),
                "validUntil", VALID_UNTIL);
        ResponseEntity<Map> first = post("/api/dye-batches", req);
        assertEquals(201, first.getStatusCode().value());
        assertEquals(1, ((Number) first.getBody().get("potencyVersion")).intValue());
        assertEquals(0, new BigDecimal("1.10").compareTo(bd(first.getBody().get("potency"))));

        // 同键同参重放：返回首次冻结结果，不新增批次
        ResponseEntity<Map> replay = post("/api/dye-batches", req);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM dye_batch WHERE batch_no='B-NEW-1'", Long.class));

        // 同键异参：409
        ResponseEntity<Map> conflict = post("/api/dye-batches", Map.of(
                "requestId", "rb-1", "batchNo", "B-NEW-1", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.20"), "availableG", new BigDecimal("500"),
                "validUntil", VALID_UNTIL));
        assertEquals(409, conflict.getStatusCode().value());

        // 换键同批次号：唯一约束 409
        ResponseEntity<Map> dup = post("/api/dye-batches", Map.of(
                "requestId", "rb-2", "batchNo", "B-NEW-1", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.10"), "availableG", new BigDecimal("500"),
                "validUntil", VALID_UNTIL));
        assertEquals(409, dup.getStatusCode().value());
    }

    @Test
    void registerBatch_invalidParams_400() {
        // 系数为零：参数非法 400
        ResponseEntity<Map> zero = post("/api/dye-batches", Map.of(
                "requestId", "rb-zero", "batchNo", "B-ZERO", "dyeCode", "D-RED",
                "potency", BigDecimal.ZERO, "availableG", new BigDecimal("500"),
                "validUntil", VALID_UNTIL));
        assertEquals(400, zero.getStatusCode().value());

        // 染料代码未登记：400
        ResponseEntity<Map> unknownDye = post("/api/dye-batches", Map.of(
                "requestId", "rb-unknown", "batchNo", "B-UNKNOWN", "dyeCode", "D-NOPE",
                "potency", new BigDecimal("1.0"), "availableG", new BigDecimal("500"),
                "validUntil", VALID_UNTIL));
        assertEquals(400, unknownDye.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 系数修订：expectedVersion 乐观锁，成功加 1
    // ------------------------------------------------------------------

    @Test
    void revisePotency_optimisticLocking() {
        ResponseEntity<Map> notFound = post("/api/dye-batches/B-NOPE/potency", Map.of(
                "requestId", "rp-404", "potency", new BigDecimal("1.5"), "expectedVersion", 1));
        assertEquals(404, notFound.getStatusCode().value());

        // 版本不匹配：409，版本不变
        ResponseEntity<Map> stale = post("/api/dye-batches/B-RED-01/potency", Map.of(
                "requestId", "rp-stale", "potency", new BigDecimal("1.5"), "expectedVersion", 2));
        assertEquals(409, stale.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT potency_version FROM dye_batch WHERE batch_no='B-RED-01'", Integer.class));

        // 匹配：成功，版本 1 → 2
        Map<String, Object> ok = postOk("/api/dye-batches/B-RED-01/potency", Map.of(
                "requestId", "rp-ok", "potency", new BigDecimal("1.5"), "expectedVersion", 1));
        assertEquals(2, ((Number) ok.get("potencyVersion")).intValue());
        assertEquals(0, new BigDecimal("1.5").compareTo(bd(ok.get("potency"))));

        // 旧版本再修订：409
        ResponseEntity<Map> staleAgain = post("/api/dye-batches/B-RED-01/potency", Map.of(
                "requestId", "rp-stale-2", "potency", new BigDecimal("1.6"), "expectedVersion", 1));
        assertEquals(409, staleAgain.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 补偿批准主流程：冻结批次/系数/目标，证据查询一致
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void approve_mainFlow_freezesSnapshot_andEvidenceQueryable() {
        schedule("ap-sched", "LA-1", "VA-1", "R-BASE", 1, "50");
        Map<String, Object> body = approveDefault("ap-1", "AP-1", "LA-1");
        assertEquals("ACTIVE", body.get("status"));
        assertEquals(1, ((Number) body.get("version")).intValue());
        assertEquals("R-BASE", body.get("recipeCode"));
        assertEquals(1, ((Number) body.get("recipeVersion")).intValue());

        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertEquals(3, items.size());
        Map<String, Object> red = items.stream()
                .filter(i -> "D-RED".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1000.0000").compareTo(bd(red.get("baseTargetG"))));
        assertEquals(0, new BigDecimal("800.000").compareTo(bd(red.get("compensatedTargetG"))));
        Map<String, Object> blue = items.stream()
                .filter(i -> "D-BLUE".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("312.500").compareTo(bd(blue.get("compensatedTargetG"))));

        // 证据查询：冻结明细与批准时一致
        Map<String, Object> evidence = rest.getForEntity(
                url("/api/compensations/AP-1"), Map.class).getBody();
        assertNotNull(evidence);
        assertEquals("AP-1", evidence.get("approvalNo"));
        assertEquals("ACTIVE", evidence.get("status"));
        List<Map<String, Object>> evItems = (List<Map<String, Object>>) evidence.get("items");
        assertEquals(3, evItems.size());
        Map<String, Object> evRed = evItems.stream()
                .filter(i -> "D-RED".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("800.000").compareTo(bd(evRed.get("compensatedTargetG"))));
        assertEquals(0, new BigDecimal("1.25").compareTo(bd(evRed.get("potency"))));

        ResponseEntity<Map> missing = rest.getForEntity(url("/api/compensations/AP-NOPE"), Map.class);
        assertEquals(404, missing.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 补偿批准失败分支：404/400/422 与失败不占幂等键
    // ------------------------------------------------------------------

    @Test
    void approve_failureBranches() {
        schedule("apf-sched", "LF-1", "VF-1", "R-BASE", 1, "50");

        // 缸次不存在：404
        ResponseEntity<Map> lotMissing = post("/api/compensations", Map.of(
                "requestId", "apf-404", "approvalNo", "APF-404", "lotNo", "L-NOPE",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-01", "potencyVersion", 1))));
        assertEquals(404, lotMissing.getStatusCode().value());

        // 批次不存在：404
        ResponseEntity<Map> batchMissing = post("/api/compensations", Map.of(
                "requestId", "apf-404b", "approvalNo", "APF-404B", "lotNo", "LF-1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-NOPE", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));
        assertEquals(404, batchMissing.getStatusCode().value());

        // 代码错配：D-BLUE 配 D-RED 的批次，整单拒绝 422
        ResponseEntity<Map> mismatch = post("/api/compensations", Map.of(
                "requestId", "apf-mis", "approvalNo", "APF-MIS", "lotNo", "LF-1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-RED-01", "potencyVersion", 1))));
        assertEquals(422, mismatch.getStatusCode().value());

        // 系数版本不一致：422
        ResponseEntity<Map> verMismatch = post("/api/compensations", Map.of(
                "requestId", "apf-ver", "approvalNo", "APF-VER", "lotNo", "LF-1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-01", "potencyVersion", 2),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));
        assertEquals(422, verMismatch.getStatusCode().value());

        // 明细缺染料：400
        ResponseEntity<Map> missing = post("/api/compensations", Map.of(
                "requestId", "apf-miss", "approvalNo", "APF-MISS", "lotNo", "LF-1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1))));
        assertEquals(400, missing.getStatusCode().value());

        // 失败不占幂等键：同键换正确参数可重试成功
        approveDefault("apf-miss", "APF-MISS", "LF-1");
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM compensation WHERE approval_no='APF-MISS'", String.class));

        // 已有生效批准：重复批准 422
        ResponseEntity<Map> dup = post("/api/compensations", Map.of(
                "requestId", "apf-dup", "approvalNo", "APF-DUP", "lotNo", "LF-1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));
        assertEquals(422, dup.getStatusCode().value());
    }

    @Test
    void approve_expiredOrInsufficientBatch_422() {
        // 已过期批次（截止在过去）
        postOk("/api/dye-batches", Map.of(
                "requestId", "rb-exp", "batchNo", "B-RED-EXP", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.0"), "availableG", new BigDecimal("5000"),
                "validUntil", "2020-01-01T00:00:00Z"));
        // 余量不足批次（100g < 补偿目标 800g）
        postOk("/api/dye-batches", Map.of(
                "requestId", "rb-low", "batchNo", "B-RED-LOW", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.25"), "availableG", new BigDecimal("100"),
                "validUntil", VALID_UNTIL));

        schedule("ape-sched-1", "LEXP", "VEXP", "R-BASE", 1, "50");
        ResponseEntity<Map> expired = post("/api/compensations", Map.of(
                "requestId", "ape-1", "approvalNo", "APE-1", "lotNo", "LEXP",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-EXP", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));
        assertEquals(422, expired.getStatusCode().value());

        schedule("ape-sched-2", "LLOW", "VLOW", "R-BASE", 1, "50");
        ResponseEntity<Map> insufficient = post("/api/compensations", Map.of(
                "requestId", "ape-2", "approvalNo", "APE-2", "lotNo", "LLOW",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-LOW", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));
        assertEquals(422, insufficient.getStatusCode().value());
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation WHERE approval_no IN ('APE-1','APE-2')", Long.class),
                "整单拒绝不得残留批准记录");
    }

    // ------------------------------------------------------------------
    // 受控投料：偏差按补偿目标（恰 0.5% 合法），原子扣减并写称量链
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void controlledFeed_mainFlow_exactHalfPercentAllowed_deductsAndWritesChain() {
        schedule("cf1-sched", "LC-1", "VC-1", "R-BASE", 1, "50");
        approveDefault("cf1-ap", "AP-C1", "LC-1");

        // RED 补偿目标 800.000，实际 804.000 => 偏差恰 +0.5%，合法
        Map<String, Object> body = postOk("/api/controlled-feedings", Map.of(
                "requestId", "cf1-feed", "lotNo", "LC-1", "approvalNo", "AP-C1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("804.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
        assertEquals("FED", body.get("status"));
        List<Map<String, Object>> weighings = (List<Map<String, Object>>) body.get("weighings");
        Map<String, Object> red = weighings.stream()
                .filter(w -> "D-RED".equals(w.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.500000").compareTo(bd(red.get("deviationPct"))));

        // 批次余量按实际称量扣减；总库存不动（受控投料只扣批次）
        assertEquals(0, new BigDecimal("3196.000").compareTo(batchAvailable("B-RED-01")));
        assertEquals(0, new BigDecimal("3250.000").compareTo(batchAvailable("B-YELLOW-01")));
        assertEquals(0, new BigDecimal("3687.500").compareTo(batchAvailable("B-BLUE-01")));
        assertEquals(0, new BigDecimal("5000.000").compareTo(stock("D-RED")));

        // 批准已消费、版本加 1；证据查询含称量链
        Map<String, Object> evidence = rest.getForEntity(url("/api/compensations/AP-C1"), Map.class).getBody();
        assertEquals("CONSUMED", evidence.get("status"));
        assertEquals(2, ((Number) evidence.get("version")).intValue());
        List<Map<String, Object>> chain = (List<Map<String, Object>>) evidence.get("weighings");
        assertEquals(3, chain.size());

        // 重复投料（换键）：批准已消费，422
        ResponseEntity<Map> again = post("/api/controlled-feedings", Map.of(
                "requestId", "cf1-feed-2", "lotNo", "LC-1", "approvalNo", "AP-C1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
        assertEquals(422, again.getStatusCode().value());
    }

    @Test
    void controlledFeed_deviationOverHalfPercent_422_noDeduction() {
        schedule("cf2-sched", "LC-2", "VC-2", "R-BASE", 1, "50");
        approveDefault("cf2-ap", "AP-C2", "LC-2");

        // RED 补偿目标 800，实际 810 => 偏差 1.25% > 0.5%，整单拒绝
        ResponseEntity<Map> rejected = post("/api/controlled-feedings", Map.of(
                "requestId", "cf2-feed", "lotNo", "LC-2", "approvalNo", "AP-C2",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("810.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals(0, new BigDecimal("4000.000").compareTo(batchAvailable("B-RED-01")));
        assertEquals(0, new BigDecimal("4000.000").compareTo(batchAvailable("B-BLUE-01")));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LC-2'", String.class));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM compensation WHERE approval_no='AP-C2'", String.class));
    }

    @Test
    void controlledFeed_secondBatchInsufficient_firstNotDeducted() {
        schedule("cf3-sched", "LC-3", "VC-3", "R-BASE", 1, "50");
        approveDefault("cf3-ap", "AP-C3", "LC-3");
        // BLUE 批次余量仅 100g < 实际 312.5g：第二种不足，第一种也不扣
        jdbc.update("UPDATE dye_batch SET available_g=100 WHERE batch_no='B-BLUE-01'");

        ResponseEntity<Map> rejected = post("/api/controlled-feedings", Map.of(
                "requestId", "cf3-feed", "lotNo", "LC-3", "approvalNo", "AP-C3",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals(0, new BigDecimal("4000.000").compareTo(batchAvailable("B-RED-01")),
                "第二种不足时第一种也不扣");
        assertEquals(0, new BigDecimal("4000.000").compareTo(batchAvailable("B-YELLOW-01")));
        assertEquals(0, new BigDecimal("100.000").compareTo(batchAvailable("B-BLUE-01")));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_feeding WHERE request_id='cf3-feed'", Long.class));
    }

    @Test
    void controlledFeed_approvalAndLotMismatch_404_422() {
        schedule("cf4-sched-1", "LC-4A", "VC-4A", "R-BASE", 1, "50");
        schedule("cf4-sched-2", "LC-4B", "VC-4B", "R-BASE", 1, "50");
        approveDefault("cf4-ap", "AP-C4", "LC-4A");

        // 批准不存在：404
        ResponseEntity<Map> notFound = post("/api/controlled-feedings", Map.of(
                "requestId", "cf4-404", "lotNo", "LC-4A", "approvalNo", "AP-NOPE",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.0000")))));
        assertEquals(404, notFound.getStatusCode().value());

        // 批准属于 LC-4A，投到 LC-4B：422
        ResponseEntity<Map> mismatch = post("/api/controlled-feedings", Map.of(
                "requestId", "cf4-mis", "lotNo", "LC-4B", "approvalNo", "AP-C4",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
        assertEquals(422, mismatch.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 系数修订使未投料批准失效；已投料历史不追改；失效后可按新版本重新批准
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void revisePotency_invalidatesActiveApproval_butKeepsConsumedHistory() {
        schedule("rv-sched-a", "LR-A", "VR-A", "R-BASE", 1, "50");
        schedule("rv-sched-b", "LR-B", "VR-B", "R-BASE", 1, "50");
        approveDefault("rv-ap-a", "AP-RA", "LR-A");
        approveDefault("rv-ap-b", "AP-RB", "LR-B");
        // LR-B 先投料（CONSUMED），LR-A 保持未投料（ACTIVE）
        controlledFeedExact("rv-feed-b", "LR-B", "AP-RB");
        BigDecimal redAvailableAfterFeed = batchAvailable("B-RED-01");

        // 修订 RED 批次系数 1.25 → 1.5（expectedVersion=1）
        Map<String, Object> revised = postOk("/api/dye-batches/B-RED-01/potency", Map.of(
                "requestId", "rv-revise", "potency", new BigDecimal("1.5"), "expectedVersion", 1));
        assertEquals(2, ((Number) revised.get("potencyVersion")).intValue());
        assertEquals(1, ((Number) revised.get("invalidatedApprovals")).intValue());

        // 未投料批准失效；已投料历史不追改
        assertEquals("INVALIDATED", jdbc.queryForObject(
                "SELECT status FROM compensation WHERE approval_no='AP-RA'", String.class));
        assertEquals("CONSUMED", jdbc.queryForObject(
                "SELECT status FROM compensation WHERE approval_no='AP-RB'", String.class));
        assertEquals(0, new BigDecimal("1.25").compareTo(jdbc.queryForObject(
                "SELECT potency FROM compensation_item ci JOIN compensation c ON c.id=ci.compensation_id "
                        + "WHERE c.approval_no='AP-RB' AND ci.dye_code='D-RED'", BigDecimal.class)),
                "已投料批准的冻结系数保持 1.25 不追改");

        // 失效批准投料：422，不扣批次
        ResponseEntity<Map> rejected = post("/api/controlled-feedings", Map.of(
                "requestId", "rv-feed-a", "lotNo", "LR-A", "approvalNo", "AP-RA",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals(0, redAvailableAfterFeed.compareTo(batchAvailable("B-RED-01")));

        // 按新系数版本 2 重新批准：补偿目标 RED = 1000/1.5 = 666.667
        Map<String, Object> reapproved = postOk("/api/compensations", Map.of(
                "requestId", "rv-ap-a2", "approvalNo", "AP-RA2", "lotNo", "LR-A",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-01", "potencyVersion", 2),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));
        List<Map<String, Object>> items = (List<Map<String, Object>>) reapproved.get("items");
        Map<String, Object> red = items.stream()
                .filter(i -> "D-RED".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("666.667").compareTo(bd(red.get("compensatedTargetG"))));

        // 按新补偿目标投料成功
        Map<String, Object> fed = postOk("/api/controlled-feedings", Map.of(
                "requestId", "rv-feed-a2", "lotNo", "LR-A", "approvalNo", "AP-RA2",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("666.6670")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
        assertEquals("FED", fed.get("status"));
    }

    // ------------------------------------------------------------------
    // 旧非受控缸次保持原投料语义；受控缸次禁止走旧入口
    // ------------------------------------------------------------------

    @Test
    void oldFeed_uncontrolledLotUnchanged_controlledLotRejected() {
        schedule("of-sched-1", "LO-1", "VO-1", "R-BASE", 1, "50");
        // 无补偿批准：旧投料语义不变
        feedExact("of-feed-1", "LO-1");
        assertEquals(0, new BigDecimal("4000.000").compareTo(stock("D-RED")));

        // 有生效批准：旧入口 422，须走受控投料
        schedule("of-sched-2", "LO-2", "VO-2", "R-BASE", 1, "50");
        approveDefault("of-ap", "AP-O2", "LO-2");
        ResponseEntity<Map> rejected = post("/api/feedings", Map.of(
                "requestId", "of-feed-2", "lotNo", "LO-2",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals(0, new BigDecimal("4000.000").compareTo(stock("D-RED")),
                "旧入口拒绝受控缸次不得扣库存");
    }

    // ------------------------------------------------------------------
    // 并缸：补偿批次与目标完全相同才允许
    // ------------------------------------------------------------------

    @Test
    void merge_controlledLots_requiresIdenticalCompensation() {
        // 补偿完全相同（同批次同目标）：允许并缸
        schedule("mg-sched-1", "LM-1", "VM-1", "R-BASE", 1, "50");
        schedule("mg-sched-2", "LM-2", "VM-2", "R-BASE", 1, "50");
        approveDefault("mg-ap-1", "AP-M1", "LM-1");
        approveDefault("mg-ap-2", "AP-M2", "LM-2");
        Map<String, Object> merged = postOk("/api/lots/merge", Map.of(
                "requestId", "mg-1", "lotNo", "LM-1", "targetLotNo", "LM-2"));
        assertEquals("MERGED", merged.get("status"));

        // 补偿目标不同（60kg vs 50kg）：拒绝
        schedule("mg-sched-3", "LM-3", "VM-3", "R-BASE", 1, "60");
        schedule("mg-sched-4", "LM-4", "VM-4", "R-BASE", 1, "50");
        approveDefault("mg-ap-3", "AP-M3", "LM-3");
        approveDefault("mg-ap-4", "AP-M4", "LM-4");
        ResponseEntity<Map> diffTarget = post("/api/lots/merge", Map.of(
                "requestId", "mg-2", "lotNo", "LM-3", "targetLotNo", "LM-4"));
        assertEquals(409, diffTarget.getStatusCode().value());

        // 一方有批准一方没有：拒绝
        schedule("mg-sched-5", "LM-5", "VM-5", "R-BASE", 1, "50");
        ResponseEntity<Map> oneSided = post("/api/lots/merge", Map.of(
                "requestId", "mg-3", "lotNo", "LM-5", "targetLotNo", "LM-4"));
        assertEquals(409, oneSided.getStatusCode().value());

        // 双方都无批准：旧语义不变，允许
        schedule("mg-sched-6", "LM-6", "VM-6", "R-BASE", 1, "50");
        Map<String, Object> oldStyle = postOk("/api/lots/merge", Map.of(
                "requestId", "mg-4", "lotNo", "LM-6", "targetLotNo", "LM-5"));
        assertEquals("MERGED", oldStyle.get("status"));
    }

    // ------------------------------------------------------------------
    // 受控投料幂等：同键同参重放不重复扣减；异参 409
    // ------------------------------------------------------------------

    @Test
    void controlledFeed_idempotency_replayAndConflict() {
        schedule("ci-sched", "LI-1", "VI-1", "R-BASE", 1, "50");
        approveDefault("ci-ap", "AP-I1", "LI-1");
        controlledFeedExact("ci-feed", "LI-1", "AP-I1");
        assertEquals(0, new BigDecimal("3200.000").compareTo(batchAvailable("B-RED-01")));

        // 同键同参：重放首次结果，不再扣减
        ResponseEntity<Map> replay = post("/api/controlled-feedings", Map.of(
                "requestId", "ci-feed", "lotNo", "LI-1", "approvalNo", "AP-I1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
        assertEquals(200, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(0, new BigDecimal("3200.000").compareTo(batchAvailable("B-RED-01")));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_feeding WHERE request_id='ci-feed'", Long.class));

        // 同键异参：409，不扣减
        ResponseEntity<Map> conflict = post("/api/controlled-feedings", Map.of(
                "requestId", "ci-feed", "lotNo", "LI-1", "approvalNo", "AP-I1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("799.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(0, new BigDecimal("3200.000").compareTo(batchAvailable("B-RED-01")));
    }

    @Test
    void approve_idempotency_replayAndConflict() {
        schedule("ai-sched", "LAI", "VAI", "R-BASE", 1, "50");
        approveDefault("ai-ap", "AP-AI", "LAI");

        // 同键同参重放：返回首次冻结结果
        ResponseEntity<Map> replay = post("/api/compensations", Map.of(
                "requestId", "ai-ap", "approvalNo", "AP-AI", "lotNo", "LAI",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));
        assertEquals(201, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation WHERE approval_no='AP-AI'", Long.class));

        // 同键异参（换批次）：409
        ResponseEntity<Map> conflict = post("/api/compensations", Map.of(
                "requestId", "ai-ap", "approvalNo", "AP-AI", "lotNo", "LAI",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));
        assertEquals(409, conflict.getStatusCode().value());
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation WHERE approval_no='AP-AI'", Long.class) == 1);
    }
}
