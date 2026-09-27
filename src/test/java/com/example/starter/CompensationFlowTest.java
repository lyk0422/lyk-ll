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
 * 染料批次效价补偿主流程与失败分支：
 * 批次登记/系数修订版本控制、补偿批准冻结、受控投料偏差边界（按补偿目标恰±0.5%合法）、
 * 代码错配/余量不足整单拒绝、修订失效与已投料不追改、旧入口语义保留、证据查询。
 */
class CompensationFlowTest extends BaseIntegrationTest {

    // ------------------------------------------------------------------
    // 批次登记：主流程、参数非法 400、染料不存在 404、批次号冲突 409、幂等
    // ------------------------------------------------------------------

    @Test
    void registerBatch_mainFlow_versionStartsAtOne_andQueryable() {
        Map<String, Object> body = registerBatch("rb-1", "B-X-1", "D-RED", "1.100000", "500.000",
                "2030-01-01T00:00:00Z");
        assertEquals("B-X-1", body.get("batchNo"));
        assertEquals("D-RED", body.get("dyeCode"));
        assertEquals(1, body.get("version"));
        assertEquals(0, new BigDecimal("1.100000").compareTo(bd(body.get("factor"))));

        Map<String, Object> view = rest.getForObject(url("/api/dye-batches/B-X-1"), Map.class);
        assertEquals(1, view.get("version"));
        assertEquals(0, new BigDecimal("500.000").compareTo(bd(view.get("availableG"))));
        assertEquals("2030-01-01T00:00:00Z", view.get("validUntil"));
    }

    @Test
    void registerBatch_validationAndConflictBranches() {
        // 系数为零 => 400
        ResponseEntity<Map> zeroFactor = post("/api/dye-batches", Map.of(
                "requestId", "rb-z", "batchNo", "B-Z", "dyeCode", "D-RED",
                "factor", new BigDecimal("0"), "availableG", new BigDecimal("100"),
                "validUntil", "2030-01-01T00:00:00Z"));
        assertEquals(400, zeroFactor.getStatusCode().value());

        // 染料不存在 => 404
        ResponseEntity<Map> noDye = post("/api/dye-batches", Map.of(
                "requestId", "rb-nd", "batchNo", "B-ND", "dyeCode", "D-NOPE",
                "factor", new BigDecimal("1.0"), "availableG", new BigDecimal("100"),
                "validUntil", "2030-01-01T00:00:00Z"));
        assertEquals(404, noDye.getStatusCode().value());

        // 批次号重复（不同 requestId）=> 409
        registerBatch("rb-dup-1", "B-DUP", "D-RED", "1.0", "100", "2030-01-01T00:00:00Z");
        ResponseEntity<Map> dup = post("/api/dye-batches", Map.of(
                "requestId", "rb-dup-2", "batchNo", "B-DUP", "dyeCode", "D-RED",
                "factor", new BigDecimal("1.0"), "availableG", new BigDecimal("100"),
                "validUntil", "2030-01-01T00:00:00Z"));
        assertEquals(409, dup.getStatusCode().value());

        // 批次查询不存在 => 404
        ResponseEntity<Map> missing = rest.getForEntity(url("/api/dye-batches/B-NOPE"), Map.class);
        assertEquals(404, missing.getStatusCode().value());
    }

    @Test
    void registerBatch_idempotentReplayAndConflict() {
        registerBatch("rb-idem", "B-IDEM", "D-RED", "1.200000", "100.000", "2030-01-01T00:00:00Z");

        // 同键同参：重放首次冻结结果（201 + version 1），不新增批次
        ResponseEntity<Map> replay = post("/api/dye-batches", Map.of(
                "requestId", "rb-idem", "batchNo", "B-IDEM", "dyeCode", "D-RED",
                "factor", new BigDecimal("1.200000"), "availableG", new BigDecimal("100.000"),
                "validUntil", "2030-01-01T00:00:00Z"));
        assertEquals(201, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM dye_batch WHERE batch_no='B-IDEM'", Long.class));

        // 同键异参（精确时刻不同）=> 409
        ResponseEntity<Map> conflict = post("/api/dye-batches", Map.of(
                "requestId", "rb-idem", "batchNo", "B-IDEM", "dyeCode", "D-RED",
                "factor", new BigDecimal("1.200000"), "availableG", new BigDecimal("100.000"),
                "validUntil", "2031-01-01T00:00:00Z"));
        assertEquals(409, conflict.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 系数修订：expectedVersion 版本控制、未投料批准失效、已投料历史不追改
    // ------------------------------------------------------------------

    @Test
    void reviseFactor_expectedVersionMismatch_rejected_successIncrementsVersion() {
        // 批次不存在 => 404
        ResponseEntity<Map> missing = post("/api/dye-batches/B-NOPE/factor", Map.of(
                "requestId", "rf-0", "expectedVersion", 1, "factor", new BigDecimal("1.5")));
        assertEquals(404, missing.getStatusCode().value());

        // 版本不匹配 => 422
        ResponseEntity<Map> mismatch = post("/api/dye-batches/B-RED-1/factor", Map.of(
                "requestId", "rf-1", "expectedVersion", 9, "factor", new BigDecimal("1.5")));
        assertEquals(422, mismatch.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM dye_batch WHERE batch_no='B-RED-1'", Integer.class));

        // 成功修订：版本 1 -> 2
        Map<String, Object> revised = postOk("/api/dye-batches/B-RED-1/factor", Map.of(
                "requestId", "rf-2", "expectedVersion", 1, "factor", new BigDecimal("1.500000")));
        assertEquals(2, revised.get("version"));
        assertEquals(0, new BigDecimal("1.500000").compareTo(bd(revised.get("factor"))));
        assertEquals(0, revised.get("invalidatedApprovals"));

        // 同键同参重放：返回首次冻结结果，版本不再增加
        ResponseEntity<Map> replay = post("/api/dye-batches/B-RED-1/factor", Map.of(
                "requestId", "rf-2", "expectedVersion", 1, "factor", new BigDecimal("1.500000")));
        assertEquals(200, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM dye_batch WHERE batch_no='B-RED-1'", Integer.class));

        // 同键异参 => 409
        ResponseEntity<Map> conflict = post("/api/dye-batches/B-RED-1/factor", Map.of(
                "requestId", "rf-2", "expectedVersion", 1, "factor", new BigDecimal("1.600000")));
        assertEquals(409, conflict.getStatusCode().value());
    }

    @Test
    void reviseFactor_invalidatesUnfedApproval_fedHistoryNotRetroChanged() {
        // 缸次 1：批准后不投料，修订后批准失效
        schedule("rv-s1", "RLV1", "RVV1", "R-BASE", 1, "50");
        approveDefault("rv-a1", "RLV1");
        // 缸次 2：批准并投料，历史冻结不追改
        schedule("rv-s2", "RLV2", "RVV2", "R-BASE", 1, "50");
        approveDefault("rv-a2", "RLV2");
        controlledFeedExact("rv-f2", "RLV2");

        Map<String, Object> revised = postOk("/api/dye-batches/B-RED-1/factor", Map.of(
                "requestId", "rv-revise", "expectedVersion", 1, "factor", new BigDecimal("2.000000")));
        assertEquals(2, revised.get("version"));
        assertEquals(1, revised.get("invalidatedApprovals"), "仅未投料批准失效");

        // 未投料批准失效：受控投料 422，证据状态 INVALID
        ResponseEntity<Map> feedInvalid = post("/api/controlled-feedings", Map.of(
                "requestId", "rv-f1", "lotNo", "RLV1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals(422, feedInvalid.getStatusCode().value());
        Map<String, Object> evidence1 = rest.getForObject(url("/api/lots/RLV1/compensation"), Map.class);
        assertEquals("INVALID", evidence1.get("status"));

        // 已投料历史不追改：批准仍为 FED，冻结系数仍是 1.25
        Map<String, Object> evidence2 = rest.getForObject(url("/api/lots/RLV2/compensation"), Map.class);
        assertEquals("FED", evidence2.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items2 = (List<Map<String, Object>>) evidence2.get("items");
        Map<String, Object> red2 = items2.stream()
                .filter(i -> "D-RED".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1.250000").compareTo(bd(red2.get("factor"))));
        assertEquals(0, new BigDecimal("800.000").compareTo(bd(red2.get("compensatedTargetG"))));

        // 失效缸次可按新版本重新批准并投料：新补偿目标 = 1000/2.0 = 500.000
        postOk("/api/lots/RLV1/compensation", Map.of(
                "requestId", "rv-a1b",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-1", "factorVersion", 2),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-1", "factorVersion", 1))));
        Map<String, Object> fed = postOk("/api/controlled-feedings", Map.of(
                "requestId", "rv-f1b", "lotNo", "RLV1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals("FED", fed.get("status"));
    }

    // ------------------------------------------------------------------
    // 补偿批准 + 受控投料主流程：冻结快照、原子扣减批次余量、证据查询、复算一致
    // ------------------------------------------------------------------

    @Test
    void controlledFeed_mainFlow_freezesSnapshot_deductsBatchBalance_evidenceAndRecalculate() {
        schedule("cf-s1", "CL1", "CV1", "R-BASE", 1, "50");
        Map<String, Object> approval = approveDefault("cf-a1", "CL1");
        assertEquals("VALID", approval.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) approval.get("items");
        assertEquals(3, items.size());
        Map<String, Object> red = items.stream()
                .filter(i -> "D-RED".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1000.0000").compareTo(bd(red.get("baseTargetG"))));
        assertEquals(0, new BigDecimal("800.000").compareTo(bd(red.get("compensatedTargetG"))),
                "补偿目标=原目标/系数=1000/1.25=800.000");
        Map<String, Object> blue = items.stream()
                .filter(i -> "D-BLUE".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("312.500").compareTo(bd(blue.get("compensatedTargetG"))),
                "250/0.80=312.500");

        // 基础配方不被修改
        assertEquals(0, new BigDecimal("2.0000").compareTo(jdbc.queryForObject(
                "SELECT dosage_gpl FROM recipe_item ri JOIN recipe r ON r.id=ri.recipe_id "
                        + "WHERE r.recipe_code='R-BASE' AND r.version=1 AND ri.dye_code='D-RED'",
                BigDecimal.class)));

        BigDecimal redBefore = batchAvailable("B-RED-1");
        Map<String, Object> fed = controlledFeedExact("cf-f1", "CL1");
        assertEquals("FED", fed.get("status"));
        assertNotNull(fed.get("approvalId"));
        assertEquals(0, redBefore.subtract(new BigDecimal("800.000")).compareTo(batchAvailable("B-RED-1")));
        assertEquals(0, new BigDecimal("2250.000").compareTo(batchAvailable("B-YELLOW-1")));
        assertEquals(0, new BigDecimal("2687.500").compareTo(batchAvailable("B-BLUE-1")));
        // 受控投料扣批次余量，不动旧全局库存
        assertEquals(0, new BigDecimal("5000.000").compareTo(stock("D-RED")));

        // 证据查询：冻结项 + 称量快照链
        Map<String, Object> evidence = rest.getForObject(url("/api/lots/CL1/compensation"), Map.class);
        assertEquals("FED", evidence.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> weighings = (List<Map<String, Object>>) evidence.get("weighings");
        assertEquals(3, weighings.size());
        Map<String, Object> redW = weighings.stream()
                .filter(w -> "D-RED".equals(w.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals("B-RED-1", redW.get("batchNo"));
        assertEquals(0, new BigDecimal("800.000").compareTo(bd(redW.get("compensatedTargetG"))));

        // 复算：受控称量链按冻结快照复算一致
        Map<String, Object> report = rest.getForObject(url("/api/recipes/R-BASE/recalculate"), Map.class);
        assertEquals(true, report.get("consistent"));
        assertEquals(0, ((List<?>) report.get("weighingMismatches")).size());
    }

    @Test
    void controlledFeed_deviationExactlyHalfPercentOnCompensatedTarget_allowed_aboveRejected() {
        // 补偿目标 RED 800.000：804.000 => 恰 +0.5% 合法
        schedule("cf-s2", "CL2", "CV2", "R-BASE", 1, "50");
        approveDefault("cf-a2", "CL2");
        Map<String, Object> ok = postOk("/api/controlled-feedings", Map.of(
                "requestId", "cf-f2", "lotNo", "CL2",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("804.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ok.get("weighings");
        Map<String, Object> red = rows.stream()
                .filter(r -> "D-RED".equals(r.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.500000").compareTo(bd(red.get("deviationPct"))));

        // 804.001 => 0.500125% > 0.5% 整单拒绝，不扣任何批次余量
        schedule("cf-s3", "CL3", "CV3", "R-BASE", 1, "50");
        approveDefault("cf-a3", "CL3");
        BigDecimal yellowBefore = batchAvailable("B-YELLOW-1");
        ResponseEntity<Map> rejected = post("/api/controlled-feedings", Map.of(
                "requestId", "cf-f3", "lotNo", "CL3",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("804.001")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals(0, yellowBefore.compareTo(batchAvailable("B-YELLOW-1")));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='CL3'", String.class));
        assertEquals("VALID", jdbc.queryForObject(
                "SELECT status FROM compensation_approval WHERE lot_no='CL3'", String.class),
                "拒绝投料不消耗批准");
    }

    @Test
    void controlledFeed_insufficientBatchBalance_rejectsWholeOrder_noPartialDeduction() {
        schedule("cf-s4", "CL4", "CV4", "R-BASE", 1, "50");
        approveDefault("cf-a4", "CL4");
        // BLUE 批次余量不足（需要 312.500，只有 300）：第二种不足时第一种也不扣
        jdbc.update("UPDATE dye_batch SET available_g=300 WHERE batch_no='B-BLUE-1'");
        BigDecimal redBefore = batchAvailable("B-RED-1");

        ResponseEntity<Map> resp = post("/api/controlled-feedings", Map.of(
                "requestId", "cf-f4", "lotNo", "CL4",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals(422, resp.getStatusCode().value());
        assertEquals(0, redBefore.compareTo(batchAvailable("B-RED-1")), "不得部分扣减");
        assertEquals(0, new BigDecimal("300.000").compareTo(batchAvailable("B-BLUE-1")));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM feeding f JOIN lot l ON l.id=f.lot_id WHERE l.lot_no='CL4'",
                Long.class));
    }

    @Test
    void approveCompensation_codeMismatchAndVersionMismatch_rejected() {
        registerBatch("cm-r1", "B-RED-WRONG", "D-RED", "1.0", "1000", "2030-01-01T00:00:00Z");
        schedule("cm-s1", "CM1", "CVM1", "R-BASE", 1, "50");

        // 代码错配：RED 批次用于 BLUE => 422
        ResponseEntity<Map> mismatch = post("/api/lots/CM1/compensation", Map.of(
                "requestId", "cm-a1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-RED-WRONG", "factorVersion", 1))));
        assertEquals(422, mismatch.getStatusCode().value());

        // 失败不占键：同键修正参数后可重试成功
        Map<String, Object> retry = postOk("/api/lots/CM1/compensation", Map.of(
                "requestId", "cm-a1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-1", "factorVersion", 1))));
        assertEquals("VALID", retry.get("status"));

        // 系数版本错配 => 422
        schedule("cm-s2", "CM2", "CVM2", "R-BASE", 1, "50");
        ResponseEntity<Map> wrongVersion = post("/api/lots/CM2/compensation", Map.of(
                "requestId", "cm-a2",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-1", "factorVersion", 7),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-1", "factorVersion", 1))));
        assertEquals(422, wrongVersion.getStatusCode().value());

        // 批次不存在 => 404
        ResponseEntity<Map> noBatch = post("/api/lots/CM2/compensation", Map.of(
                "requestId", "cm-a3",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-NOPE", "factorVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-1", "factorVersion", 1))));
        assertEquals(404, noBatch.getStatusCode().value());

        // 缺少配方染料 => 400
        ResponseEntity<Map> missing = post("/api/lots/CM2/compensation", Map.of(
                "requestId", "cm-a4",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1))));
        assertEquals(400, missing.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 旧入口语义保留：旧投料不受批次影响；受控缸次走旧入口拒绝；无批准走受控入口 404
    // ------------------------------------------------------------------

    @Test
    void legacyFeed_keptForUncontrolledLots_controlledLotRejectedOnLegacyEntry() {
        // 旧非受控缸次：原投料语义（放大目标、扣全局库存）
        schedule("lg-s1", "LG1", "LVG1", "R-BASE", 1, "50");
        feedExact("lg-f1", "LG1");
        assertEquals(0, new BigDecimal("4000.000").compareTo(stock("D-RED")));
        assertEquals(0, new BigDecimal("3000.000").compareTo(batchAvailable("B-RED-1")),
                "旧投料不扣批次余量");

        // 受控缸次走旧入口 => 422
        schedule("lg-s2", "LG2", "LVG2", "R-BASE", 1, "50");
        approveDefault("lg-a2", "LG2");
        ResponseEntity<Map> legacyOnControlled = post("/api/feedings", Map.of(
                "requestId", "lg-f2", "lotNo", "LG2",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
        assertEquals(422, legacyOnControlled.getStatusCode().value());
        assertEquals(0, new BigDecimal("4000.000").compareTo(stock("D-RED")), "拒绝不得扣库存");

        // 无补偿批准缸次走受控入口 => 404
        schedule("lg-s3", "LG3", "LVG3", "R-BASE", 1, "50");
        ResponseEntity<Map> controlledOnPlain = post("/api/controlled-feedings", Map.of(
                "requestId", "lg-f3", "lotNo", "LG3",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals(404, controlledOnPlain.getStatusCode().value());

        // 无批准缸次查补偿证据 => 404
        ResponseEntity<Map> noEvidence = rest.getForEntity(url("/api/lots/LG3/compensation"), Map.class);
        assertEquals(404, noEvidence.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 受控投料幂等：同键同参重放不重复扣减；同键异参 409
    // ------------------------------------------------------------------

    @Test
    void controlledFeed_idempotentReplay_noDoubleDeduction_conflictOnDifferentParams() {
        schedule("ci-s1", "CI1", "CVI1", "R-BASE", 1, "50");
        approveDefault("ci-a1", "CI1");
        BigDecimal redBefore = batchAvailable("B-RED-1");

        controlledFeedExact("ci-f1", "CI1");
        assertEquals(0, redBefore.subtract(new BigDecimal("800.000")).compareTo(batchAvailable("B-RED-1")));

        ResponseEntity<Map> replay = post("/api/controlled-feedings", Map.of(
                "requestId", "ci-f1", "lotNo", "CI1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals(200, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(0, redBefore.subtract(new BigDecimal("800.000")).compareTo(batchAvailable("B-RED-1")),
                "重放不得重复扣减批次余量");
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM feeding WHERE request_id='ci-f1'", Long.class));

        ResponseEntity<Map> conflict = post("/api/controlled-feedings", Map.of(
                "requestId", "ci-f1", "lotNo", "CI1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("799.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(0, redBefore.subtract(new BigDecimal("800.000")).compareTo(batchAvailable("B-RED-1")));
    }

    // ------------------------------------------------------------------
    // 并缸：补偿批次与目标完全相同才允许
    // ------------------------------------------------------------------

    @Test
    void merge_controlledLots_requiresIdenticalCompensation() {
        // 双方同批次同目标（同布重 50kg）=> 允许
        schedule("mg-s1a", "MG1A", "MVG1", "R-BASE", 1, "50");
        schedule("mg-s1b", "MG1B", "MVG2", "R-BASE", 1, "50");
        approveDefault("mg-a1a", "MG1A");
        approveDefault("mg-a1b", "MG1B");
        Map<String, Object> merged = postOk("/api/lots/merge", Map.of(
                "requestId", "mg-m1", "lotNo", "MG1A", "targetLotNo", "MG1B"));
        assertEquals("MERGED", merged.get("status"));

        // 批次不同 => 422
        registerBatch("mg-r2", "B-RED-2", "D-RED", "1.250000", "2000", "2030-01-01T00:00:00Z");
        schedule("mg-s2a", "MG2A", "MVG3", "R-BASE", 1, "50");
        schedule("mg-s2b", "MG2B", "MVG4", "R-BASE", 1, "50");
        approveDefault("mg-a2a", "MG2A");
        postOk("/api/lots/MG2B/compensation", Map.of(
                "requestId", "mg-a2b",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-2", "factorVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-1", "factorVersion", 1))));
        ResponseEntity<Map> diffBatch = post("/api/lots/merge", Map.of(
                "requestId", "mg-m2", "lotNo", "MG2A", "targetLotNo", "MG2B"));
        assertEquals(422, diffBatch.getStatusCode().value());
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='MG2A'", String.class));

        // 补偿目标不同（布重不同）=> 422
        schedule("mg-s3a", "MG3A", "MVG5", "R-BASE", 1, "50");
        schedule("mg-s3b", "MG3B", "MVG6", "R-BASE", 1, "60");
        approveDefault("mg-a3a", "MG3A");
        approveDefault("mg-a3b", "MG3B");
        ResponseEntity<Map> diffTarget = post("/api/lots/merge", Map.of(
                "requestId", "mg-m3", "lotNo", "MG3A", "targetLotNo", "MG3B"));
        assertEquals(422, diffTarget.getStatusCode().value());

        // 一方受控一方非受控 => 422
        schedule("mg-s4a", "MG4A", "MVG7", "R-BASE", 1, "50");
        schedule("mg-s4b", "MG4B", "MVG8", "R-BASE", 1, "50");
        approveDefault("mg-a4a", "MG4A");
        ResponseEntity<Map> mixed = post("/api/lots/merge", Map.of(
                "requestId", "mg-m4", "lotNo", "MG4A", "targetLotNo", "MG4B"));
        assertEquals(422, mixed.getStatusCode().value());
    }

    @Test
    void approveCompensation_idempotentReplayAndDuplicateApprovalRejected() {
        schedule("ap-s1", "AP1", "AVP1", "R-BASE", 1, "50");
        approveDefault("ap-a1", "AP1");

        // 同键同参重放：返回首次冻结结果
        ResponseEntity<Map> replay = post("/api/lots/AP1/compensation", Map.of(
                "requestId", "ap-a1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-1", "factorVersion", 1))));
        assertEquals(201, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation_approval WHERE lot_no='AP1'", Long.class));

        // 同键异参 => 409
        ResponseEntity<Map> conflict = post("/api/lots/AP1/compensation", Map.of(
                "requestId", "ap-a1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-1", "factorVersion", 1))));
        assertEquals(409, conflict.getStatusCode().value());

        // 已有有效批准，新键重复审批 => 422
        ResponseEntity<Map> dup = post("/api/lots/AP1/compensation", Map.of(
                "requestId", "ap-a2",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-1", "factorVersion", 1))));
        assertEquals(422, dup.getStatusCode().value());

        // 缸次不存在 => 404
        ResponseEntity<Map> noLot = post("/api/lots/AP-NOPE/compensation", Map.of(
                "requestId", "ap-a3",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-RED-1", "factorVersion", 1))));
        assertEquals(404, noLot.getStatusCode().value());
    }

    @Test
    void controlledFeed_expiredBatch_rejected() {
        // 已过期批次（截止在过去）：批准可冻结，投料时有效期校验拒绝
        registerBatch("ex-r1", "B-EXP", "D-RED", "1.250000", "2000", "2020-01-01T00:00:00Z");
        schedule("ex-s1", "EX1", "EVX1", "R-BASE", 1, "50");
        postOk("/api/lots/EX1/compensation", Map.of(
                "requestId", "ex-a1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-EXP", "factorVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-1", "factorVersion", 1))));
        BigDecimal expBefore = batchAvailable("B-EXP");
        ResponseEntity<Map> resp = post("/api/controlled-feedings", Map.of(
                "requestId", "ex-f1", "lotNo", "EX1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals(422, resp.getStatusCode().value());
        assertEquals(0, expBefore.compareTo(batchAvailable("B-EXP")), "过期拒绝不得扣减");
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM weighing", Long.class) == 0);
    }
}
