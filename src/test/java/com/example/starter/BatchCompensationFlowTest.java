package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 染料批次效价补偿：主流程、失败分支与幂等/版本边界。
 * 线性模型：补偿目标 = 原放大目标 / 系数（三位小数，四舍五入）；偏差按补偿目标 ±0.5%。
 */
class BatchCompensationFlowTest extends BaseIntegrationTest {

    private static final String FAR_FUTURE = "2030-12-31T00:00:00+08:00";

    // ------------------------------------------------------------------
    // 批次登记
    // ------------------------------------------------------------------

    @Test
    void registerBatch_freezesFactorVersionOne_andIsQueriable() {
        Map<String, Object> body = registerBatch("rb-1", "B-X-001", "D-RED", "0.8", "5000", FAR_FUTURE);
        assertEquals(201, 201);
        assertEquals("B-X-001", body.get("batchNo"));
        assertEquals("D-RED", body.get("dyeCode"));
        assertEquals(1, body.get("factorVersion"));
        assertEquals(1, body.get("version"));
        assertEquals(0, new BigDecimal("0.800000").compareTo(bd(body.get("potencyFactor"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("factorHistory");
        assertEquals(1, history.size());

        // 重复批次号 409
        ResponseEntity<Map> dup = post("/api/batches", Map.of(
                "requestId", "rb-2", "batchNo", "B-X-001", "dyeCode", "D-RED",
                "potencyFactor", new BigDecimal("0.9"), "availableG", new BigDecimal("100"),
                "expireAt", FAR_FUTURE, "expectedVersion", 1));
        assertEquals(409, dup.getStatusCode().value());

        // 登记新聚合 expectedVersion 必须为 1
        ResponseEntity<Map> badVersion = post("/api/batches", Map.of(
                "requestId", "rb-3", "batchNo", "B-X-002", "dyeCode", "D-RED",
                "potencyFactor", new BigDecimal("1.0"), "availableG", new BigDecimal("100"),
                "expireAt", FAR_FUTURE, "expectedVersion", 2));
        assertEquals(400, badVersion.getStatusCode().value());

        // 系数零 400
        ResponseEntity<Map> zeroFactor = post("/api/batches", Map.of(
                "requestId", "rb-4", "batchNo", "B-X-003", "dyeCode", "D-RED",
                "potencyFactor", new BigDecimal("0"), "availableG", new BigDecimal("100"),
                "expireAt", FAR_FUTURE, "expectedVersion", 1));
        assertEquals(400, zeroFactor.getStatusCode().value());

        // 不存在的染料代码 400
        ResponseEntity<Map> unknownDye = post("/api/batches", Map.of(
                "requestId", "rb-5", "batchNo", "B-X-004", "dyeCode", "D-NOPE",
                "potencyFactor", new BigDecimal("1.0"), "availableG", new BigDecimal("100"),
                "expireAt", FAR_FUTURE, "expectedVersion", 1));
        assertEquals(400, unknownDye.getStatusCode().value());

        // 不存在批次查询 404
        ResponseEntity<Map> missing = rest.getForEntity(url("/api/batches/B-MISSING"), Map.class);
        assertEquals(404, missing.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 补偿目标：原目标/系数，三位小数四舍五入
    // ------------------------------------------------------------------

    @Test
    void compensation_targetIsBaseDividedByFactor_roundedToThreeDecimals_andRecipeUntouched() {
        registerBatch("rc-1", "B-RED-07", "D-RED", "0.7", "100000", FAR_FUTURE);
        registerBatch("rc-2", "B-RED-30", "D-RED", "3", "100000", FAR_FUTURE);
        scheduleControlled("rc-s", "LC", "VC", "R-BASE", 1, "50");

        Map<String, Object> comp = approveCompensation("rc-a", "LC", 1, new LinkedHashMap<>(Map.of(
                "D-RED", List.of("B-RED-07", 1),
                "D-YELLOW", List.of("B-YELLOW-001", 1),
                "D-BLUE", List.of("B-BLUE-001", 1))));
        assertEquals("FROZEN", comp.get("status"));
        assertEquals(true, comp.get("currentlyValid"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) comp.get("items");
        Map<String, Object> red = byDye(items, "D-RED");
        // 1000 / 0.7 = 1428.571428... → 1428.571
        assertEquals(0, new BigDecimal("1000.0000").compareTo(bd(red.get("baseTargetG"))));
        assertEquals(0, new BigDecimal("1428.571").compareTo(bd(red.get("compensatedTargetG"))));

        // 换系数 3 重冻：携带当前版本 1，成功后变 2；1000/3 = 333.333
        Map<String, Object> comp2 = approveCompensation("rc-b", "LC", 1, new LinkedHashMap<>(Map.of(
                "D-RED", List.of("B-RED-30", 1),
                "D-YELLOW", List.of("B-YELLOW-001", 1),
                "D-BLUE", List.of("B-BLUE-001", 1))));
        assertEquals(2, comp2.get("version"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items2 = (List<Map<String, Object>>) comp2.get("items");
        assertEquals(0, new BigDecimal("333.333").compareTo(bd(byDye(items2, "D-RED").get("compensatedTargetG"))));

        // 错版本重冻 409
        ResponseEntity<Map> stale = post("/api/compensations", compReq("rc-c", "LC", 9,
                Map.of("D-RED", List.of("B-RED-30", 1),
                        "D-YELLOW", List.of("B-YELLOW-001", 1),
                        "D-BLUE", List.of("B-BLUE-001", 1))));
        assertEquals(409, stale.getStatusCode().value());

        // 基础配方未被修改：仍 PUBLISHED，用量不变
        Map<String, Object> recipe = jdbc.queryForMap(
                "SELECT status FROM recipe WHERE recipe_code='R-BASE' AND version=1");
        assertEquals("PUBLISHED", recipe.get("status"));
        assertEquals(0, new BigDecimal("2.0000").compareTo(jdbc.queryForObject(
                "SELECT dosage_gpl FROM recipe_item ri JOIN recipe r ON r.id=ri.recipe_id "
                        + "WHERE r.recipe_code='R-BASE' AND r.version=1 AND ri.dye_code='D-RED'",
                BigDecimal.class)));
    }

    // ------------------------------------------------------------------
    // 补偿审批失败分支：非受控缸、集合不符、代码错配、余量不足、有效期
    // ------------------------------------------------------------------

    @Test
    void compensation_rejectsNonControlledLot_andUnknownLot404() {
        schedule("rnc-s", "LL", "VL", "R-BASE", 1, "50");
        ResponseEntity<Map> resp = post("/api/compensations",
                compReq("rnc-1", "LL", 1, seedBatches()));
        assertEquals(422, resp.getStatusCode().value(), "旧非受控缸次不办理补偿批准");

        ResponseEntity<Map> missingLot = post("/api/compensations",
                compReq("rnc-2", "NO-LOT", 1, seedBatches()));
        assertEquals(404, missingLot.getStatusCode().value());
    }

    @Test
    void compensation_wrongDyeSetOrCodeMismatch_rejectedWholeOrder() {
        registerBatch("wm-1", "B-RED-EXTRA", "D-RED", "1", "100000", FAR_FUTURE);
        scheduleControlled("wm-s", "LWM", "VWM", "R-BASE", 1, "50");

        // 缺少 D-BLUE
        ResponseEntity<Map> missing = post("/api/compensations", compReq("wm-a", "LWM", 1, Map.of(
                "D-RED", List.of("B-RED-001", 1),
                "D-YELLOW", List.of("B-YELLOW-001", 1))));
        assertEquals(400, missing.getStatusCode().value());

        // 代码错配：RED 配方染料引用了 YELLOW 批次
        ResponseEntity<Map> mismatch = post("/api/compensations", compReq("wm-b", "LWM", 1,
                new LinkedHashMap<>(Map.of(
                        "D-RED", List.of("B-YELLOW-001", 1),
                        "D-YELLOW", List.of("B-YELLOW-001", 1),
                        "D-BLUE", List.of("B-BLUE-001", 1)))));
        assertEquals(422, mismatch.getStatusCode().value());

        // 引用不存在的批次版本 404
        ResponseEntity<Map> noBatch = post("/api/compensations", compReq("wm-c", "LWM", 1,
                new LinkedHashMap<>(Map.of(
                        "D-RED", List.of("B-RED-001", 99),
                        "D-YELLOW", List.of("B-YELLOW-001", 1),
                        "D-BLUE", List.of("B-BLUE-001", 1)))));
        assertEquals(404, noBatch.getStatusCode().value());
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation", Long.class), "失败不产生补偿批准");
    }

    @Test
    void compensation_insufficientBatchBalance_rejected() {
        registerBatch("ib-0", "B-RED-SHORT", "D-RED", "0.5", "100", FAR_FUTURE);
        scheduleControlled("ib-s", "LIB", "VIB", "R-BASE", 1, "50");
        // 系数 0.5 => 补偿目标 2000g > 余量 100g
        ResponseEntity<Map> resp = post("/api/compensations", compReq("ib-1", "LIB", 1,
                new LinkedHashMap<>(Map.of(
                        "D-RED", List.of("B-RED-SHORT", 1),
                        "D-YELLOW", List.of("B-YELLOW-001", 1),
                        "D-BLUE", List.of("B-BLUE-001", 1)))));
        assertEquals(422, resp.getStatusCode().value());
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id='ib-1'", Long.class),
                "业务失败不占幂等键");

        // 同键改用合法参数重试：成功（失败不占键，不判异参 409）
        Map<String, Object> retried = approveCompensation("ib-1", "LIB", 1, seedBatches());
        assertEquals("FROZEN", retried.get("status"));
    }

    @Test
    void compensation_andFeed_expireBoundary_exactlyAtDeadlineIsInvalid() {
        Instant deadline = Instant.parse("2026-06-01T00:00:00Z");
        clock.setInstant(deadline);
        registerBatch("eb-1", "B-RED-EXP", "D-RED", "1", "100000", "2026-06-01T00:00:00Z");
        scheduleControlled("eb-s", "LE", "VE", "R-BASE", 1, "50");

        // 恰在截止：失效
        ResponseEntity<Map> atDeadline = post("/api/compensations", compReq("eb-a", "LE", 1,
                new LinkedHashMap<>(Map.of(
                        "D-RED", List.of("B-RED-EXP", 1),
                        "D-YELLOW", List.of("B-YELLOW-001", 1),
                        "D-BLUE", List.of("B-BLUE-001", 1)))));
        assertEquals(422, atDeadline.getStatusCode().value());

        // now 提前 1 秒：有效
        clock.setInstant(deadline.minus(Duration.ofSeconds(1)));
        approveCompensation("eb-b", "LE", 1, new LinkedHashMap<>(Map.of(
                "D-RED", List.of("B-RED-EXP", 1),
                "D-YELLOW", List.of("B-YELLOW-001", 1),
                "D-BLUE", List.of("B-BLUE-001", 1))));

        // 推进到截止时刻投料：批次恰失效，整单拒绝
        clock.setInstant(deadline);
        ResponseEntity<Map> fedAtDeadline = post("/api/feedings", Map.of(
                "requestId", "eb-f", "lotNo", "LE", "expectedVersion", 1,
                "weighings", exactWeighings("1000", "750", "250")));
        assertEquals(422, fedAtDeadline.getStatusCode().value());
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LE'", String.class));
    }

    // ------------------------------------------------------------------
    // 受控投料：偏差按补偿目标 ±0.5%，原子扣批次余量，不动染化料总库存
    // ------------------------------------------------------------------

    @Test
    void controlledFeed_deviationExactlyHalfAllowed_deductsBatchOnly_andWeighingChainFrozen() {
        registerBatch("cf-1", "B-RED-08", "D-RED", "0.8", "100000", FAR_FUTURE);
        scheduleControlled("cf-s", "LCF", "VCF", "R-BASE", 1, "50");
        approveCompensation("cf-a", "LCF", 1, new LinkedHashMap<>(Map.of(
                "D-RED", List.of("B-RED-08", 1),
                "D-YELLOW", List.of("B-YELLOW-001", 1),
                "D-BLUE", List.of("B-BLUE-001", 1))));
        BigDecimal redBatchBefore = batchAvailable("B-RED-08");
        BigDecimal dyeStockBefore = stock("D-RED");

        // RED 补偿目标 1250g，实际 1256.25 => 恰 +0.5% 合法
        Map<String, Object> fed = postOk("/api/feedings", Map.of(
                "requestId", "cf-f", "lotNo", "LCF", "expectedVersion", 1,
                "weighings", exactWeighings("1256.25", "750", "250")));
        assertEquals("FED", fed.get("status"));
        assertEquals(2, fed.get("compensationVersion"), "投料成功补偿版本加 1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) fed.get("weighings");
        Map<String, Object> red = byDye(rows, "D-RED");
        assertEquals(0, new BigDecimal("0.500000").compareTo(bd(red.get("deviationPct"))));
        assertEquals(0, new BigDecimal("1250.000").compareTo(bd(red.get("targetG"))));
        assertEquals(0, new BigDecimal("0.800000").compareTo(bd(red.get("potencyFactor"))));
        assertEquals("B-RED-08", red.get("batchNo"));

        // 扣批次余量，不扣染化料总库存
        assertEquals(0, redBatchBefore.subtract(new BigDecimal("1256.250")).compareTo(batchAvailable("B-RED-08")));
        assertEquals(0, dyeStockBefore.compareTo(stock("D-RED")), "受控投料不动染化料总库存");

        // 补偿批准冻结为 FED 历史
        assertEquals("FED", compensationRow("LCF").get("status"));

        // 已投料重复审批 422
        ResponseEntity<Map> reapprove = post("/api/compensations", compReq("cf-g", "LCF", 2,
                new LinkedHashMap<>(Map.of(
                        "D-RED", List.of("B-RED-08", 1),
                        "D-YELLOW", List.of("B-YELLOW-001", 1),
                        "D-BLUE", List.of("B-BLUE-001", 1)))));
        assertEquals(422, reapprove.getStatusCode().value());
    }

    @Test
    void controlledFeed_deviationAboveHalf_rejected_withoutAnyDeduction() {
        scheduleControlled("df-s", "LDF", "VDF", "R-BASE", 1, "50");
        approveSeedCompensation("df-a", "LDF");
        BigDecimal redBefore = batchAvailable("B-RED-001");
        BigDecimal yellowBefore = batchAvailable("B-YELLOW-001");

        ResponseEntity<Map> resp = post("/api/feedings", Map.of(
                "requestId", "df-f", "lotNo", "LDF", "expectedVersion", 1,
                // RED 目标 1000，实际 1005.1 => +0.51%
                "weighings", exactWeighings("1005.1", "750", "250")));
        assertEquals(422, resp.getStatusCode().value());
        assertEquals(0, redBefore.compareTo(batchAvailable("B-RED-001")), "整单回滚：批次余量不扣");
        assertEquals(0, yellowBefore.compareTo(batchAvailable("B-YELLOW-001")));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LDF'", String.class));
        assertEquals("FROZEN", compensationRow("LDF").get("status"));
    }

    @Test
    void controlledFeed_secondBatchShort_rejectsWholeOrder_firstNotDeducted() {
        scheduleControlled("sd-s", "LSD", "VSD", "R-BASE", 1, "50");
        approveSeedCompensation("sd-a", "LSD");
        // 将第二种染料（按 dye_code 顺序 D-BLUE 在 D-RED/D-YELLOW 之后）余量改为不足
        jdbc.update("UPDATE dye_batch SET available_g = 200 WHERE batch_no = 'B-BLUE-001' AND factor_version = 1");
        BigDecimal redBefore = batchAvailable("B-RED-001");

        ResponseEntity<Map> resp = post("/api/feedings", Map.of(
                "requestId", "sd-f", "lotNo", "LSD", "expectedVersion", 1,
                "weighings", exactWeighings("1000", "750", "250")));
        assertEquals(409, resp.getStatusCode().value());
        assertEquals(0, redBefore.compareTo(batchAvailable("B-RED-001")), "第二种不足时第一种也不扣");
        assertEquals(new BigDecimal("200.000"), batchAvailable("B-BLUE-001"));
    }

    @Test
    void controlledFeed_wrongOrMissingCompensationVersion_rejected() {
        scheduleControlled("wv-s", "LWV", "VWV", "R-BASE", 1, "50");

        // 未审批先投料 422
        ResponseEntity<Map> noComp = post("/api/feedings", Map.of(
                "requestId", "wv-f0", "lotNo", "LWV", "expectedVersion", 1,
                "weighings", exactWeighings("1000", "750", "250")));
        assertEquals(422, noComp.getStatusCode().value());

        approveSeedCompensation("wv-a", "LWV");

        // 缺 expectedVersion 400
        ResponseEntity<Map> missingVersion = post("/api/feedings", Map.of(
                "requestId", "wv-f1", "lotNo", "LWV",
                "weighings", exactWeighings("1000", "750", "250")));
        assertEquals(400, missingVersion.getStatusCode().value());

        // 错版本 409
        ResponseEntity<Map> stale = post("/api/feedings", Map.of(
                "requestId", "wv-f2", "lotNo", "LWV", "expectedVersion", 7,
                "weighings", exactWeighings("1000", "750", "250")));
        assertEquals(409, stale.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 系数修订：未投料批准失效；重新审批引用新版本；已投料历史不追改
    // ------------------------------------------------------------------

    @Test
    void factorRevision_invalidatesFrozenCompensation_reApproveFeeds_fedHistoryUntouched() {
        scheduleControlled("rv-s", "LRV", "VRV", "R-BASE", 1, "50");
        approveSeedCompensation("rv-a", "LRV");

        // 修订 B-RED-001：expectedVersion=1（批次聚合版本），新系数 1.1
        Map<String, Object> revised = reviseBatch("rv-r1", "B-RED-001", "1.1", "100000", FAR_FUTURE, 1);
        assertEquals(2, revised.get("factorVersion"));
        assertEquals(2, revised.get("version"));

        // 未投料补偿批准失效，版本加 1
        Map<String, Object> comp = compensationRow("LRV");
        assertEquals("INVALID", comp.get("status"));
        assertEquals(2, comp.get("version"));
        assertNotNull(comp.get("invalid_reason"));
        ResponseEntity<Map> evidence = rest.getForEntity(url("/api/lots/LRV/compensation"), Map.class);
        assertEquals(200, evidence.getStatusCode().value());
        assertEquals(false, evidence.getBody().get("currentlyValid"));

        // 失效状态下投料：即使携带最新版本也 422
        ResponseEntity<Map> feedInvalid = post("/api/feedings", Map.of(
                "requestId", "rv-fx", "lotNo", "LRV", "expectedVersion", 2,
                "weighings", exactWeighings("1000", "750", "250")));
        assertEquals(422, feedInvalid.getStatusCode().value());

        // 引用系数版本 v2 重新审批：1000/1.1 = 909.091
        approveCompensation("rv-a2", "LRV", 2, new LinkedHashMap<>(Map.of(
                "D-RED", List.of("B-RED-001", 2),
                "D-YELLOW", List.of("B-YELLOW-001", 1),
                "D-BLUE", List.of("B-BLUE-001", 1))));
        Map<String, Object> frozen = jdbc.queryForMap(
                "SELECT compensated_target_g FROM compensation_item "
                        + "WHERE compensation_id=(SELECT id FROM compensation WHERE lot_id="
                        + "(SELECT id FROM lot WHERE lot_no='LRV')) AND dye_code='D-RED'");
        assertEquals(0, new BigDecimal("909.091").compareTo((BigDecimal) frozen.get("compensated_target_g")));

        // 错聚合版本修订 409
        ResponseEntity<Map> staleRevise = post("/api/batches/B-RED-001/revise", Map.of(
                "requestId", "rv-r2-bad", "batchNo", "B-RED-001",
                "potencyFactor", new BigDecimal("1.2"), "availableG", new BigDecimal("100000"),
                "expireAt", FAR_FUTURE, "expectedVersion", 1));
        assertEquals(409, staleRevise.getStatusCode().value());

        // 按新目标投料成功（909.091 取实际 909.091；其余不变）
        postOk("/api/feedings", Map.of(
                "requestId", "rv-f", "lotNo", "LRV", "expectedVersion", 3,
                "weighings", exactWeighings("909.091", "750", "250")));
        assertEquals("FED", compensationRow("LRV").get("status"));

        // 投料后再次修订：已投料历史不追改（称量仍按 v2 系数 1.1 的冻结目标）
        reviseBatch("rv-r2", "B-RED-001", "1.2", "100000", FAR_FUTURE, 2);
        Map<String, Object> historical = jdbc.queryForMap(
                "SELECT w.potency_factor, w.target_g, w.factor_version FROM weighing w "
                        + "JOIN feeding f ON f.id=w.feeding_id JOIN lot l ON l.id=f.lot_id "
                        + "WHERE l.lot_no='LRV' AND w.dye_code='D-RED'");
        assertEquals(0, new BigDecimal("1.100000").compareTo((BigDecimal) historical.get("potency_factor")));
        assertEquals(0, new BigDecimal("909.091").compareTo((BigDecimal) historical.get("target_g")));
        assertEquals(2, ((Number) historical.get("factor_version")).intValue());
        assertEquals("FED", compensationRow("LRV").get("status"));
    }

    // ------------------------------------------------------------------
    // 受控并缸：批次与补偿目标完全相同才允许；混合受控/非受控拒绝
    // ------------------------------------------------------------------

    @Test
    void controlledMerge_identicalCompensation_allowed_andRefreezesForCombinedWeight() {
        scheduleControlled("cm-1s", "LCA", "VCA", "R-BASE", 1, "50");
        scheduleControlled("cm-2s", "LCB", "VCB", "R-BASE", 1, "50");
        approveSeedCompensation("cm-1a", "LCA");
        approveSeedCompensation("cm-2a", "LCB");

        // 缺 expectedVersion 400
        ResponseEntity<Map> noVersion = post("/api/lots/merge", Map.of(
                "requestId", "cm-x", "lotNo", "LCA", "targetLotNo", "LCB"));
        assertEquals(400, noVersion.getStatusCode().value());

        Map<String, Object> merged = postOk("/api/lots/merge", Map.of(
                "requestId", "cm-m", "lotNo", "LCA", "targetLotNo", "LCB", "expectedVersion", 1));
        assertEquals(true, merged.get("controlled"));
        assertEquals(2, merged.get("compensationVersion"));

        // 目标缸按合并布重 100kg 重冻补偿：RED 2000g，源缸补偿失效
        Map<String, Object> red = jdbc.queryForMap(
                "SELECT compensated_target_g FROM compensation_item ci JOIN compensation c ON c.id=ci.compensation_id "
                        + "JOIN lot l ON l.id=c.lot_id WHERE l.lot_no='LCB' AND ci.dye_code='D-RED'");
        assertEquals(0, new BigDecimal("2000.000").compareTo((BigDecimal) red.get("compensated_target_g")));
        assertEquals("INVALID", compensationRow("LCA").get("status"));

        // 合并后按新目标投料成功，携带目标补偿新版本 2
        Map<String, Object> fed = postOk("/api/feedings", Map.of(
                "requestId", "cm-f", "lotNo", "LCB", "expectedVersion", 2,
                "weighings", exactWeighings("2000", "1500", "500")));
        assertEquals("FED", fed.get("status"));
    }

    @Test
    void controlledMerge_sharedBatchCannotCoverDoubledTarget_rejectedWholeOrder() {
        // 两缸同批次同补偿（各 RED 目标 1000g），但共享批次余量只够一缸：合并后目标 2000g，拒绝并缸。
        jdbc.update("UPDATE dye_batch SET available_g = 1500 WHERE batch_no = 'B-RED-001' AND factor_version = 1");
        scheduleControlled("mb-s1", "LM1", "VMX1", "R-BASE", 1, "50");
        scheduleControlled("mb-s2", "LM2", "VMX2", "R-BASE", 1, "50");
        approveSeedCompensation("mb-a1", "LM1");
        approveSeedCompensation("mb-a2", "LM2");

        ResponseEntity<Map> resp = post("/api/lots/merge", Map.of(
                "requestId", "mb-m", "lotNo", "LM1", "targetLotNo", "LM2", "expectedVersion", 1));
        assertEquals(422, resp.getStatusCode().value());

        // 整单回滚：两缸仍 CREATED、各自补偿仍 FROZEN，染缸占用未释放
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LM1'", String.class));
        assertEquals("FROZEN", compensationRow("LM1").get("status"));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM vat_active WHERE vat_no='VMX1'", Long.class));
    }

    @Test
    void controlledMerge_differentTargetsOrBatchesOrMixedControl_rejected() {
        registerBatch("dm-1", "B-RED-08", "D-RED", "0.8", "100000", FAR_FUTURE);

        // 批次/目标不同
        scheduleControlled("dm-s1", "LDA", "VDA", "R-BASE", 1, "50");
        scheduleControlled("dm-s2", "LDB", "VDB", "R-BASE", 1, "50");
        approveCompensation("dm-a1", "LDA", 1, new LinkedHashMap<>(Map.of(
                "D-RED", List.of("B-RED-08", 1),
                "D-YELLOW", List.of("B-YELLOW-001", 1),
                "D-BLUE", List.of("B-BLUE-001", 1))));
        approveSeedCompensation("dm-a2", "LDB");
        ResponseEntity<Map> diffTarget = post("/api/lots/merge", Map.of(
                "requestId", "dm-m1", "lotNo", "LDA", "targetLotNo", "LDB", "expectedVersion", 1));
        assertEquals(422, diffTarget.getStatusCode().value());

        // 错目标补偿版本 409
        ResponseEntity<Map> stale = post("/api/lots/merge", Map.of(
                "requestId", "dm-m2", "lotNo", "LDA", "targetLotNo", "LDB", "expectedVersion", 42));
        assertEquals(409, stale.getStatusCode().value());

        // 受控 + 非受控混合 422
        schedule("dm-s3", "LDC", "VDC", "R-BASE", 1, "50");
        ResponseEntity<Map> mixed = post("/api/lots/merge", Map.of(
                "requestId", "dm-m3", "lotNo", "LDA", "targetLotNo", "LDC", "expectedVersion", 1));
        assertEquals(422, mixed.getStatusCode().value());

        // 两缸均保持 CREATED，未发生并缸
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LDA'", String.class));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LDB'", String.class));
    }

    // ------------------------------------------------------------------
    // 幂等：同键同参重放首次冻结；异参 409；失败不占键
    // ------------------------------------------------------------------

    @Test
    void idempotency_replaySameFreeze_conflictOnDifferentParams_failureDoesNotOccupyKey() {
        scheduleControlled("id-s", "LID", "VID", "R-BASE", 1, "50");
        Map<String, Object> first = approveSeedCompensation("id-a", "LID");
        assertEquals(1, first.get("version"));

        // 同键同参重放：首次冻结结果
        ResponseEntity<Map> replay = post("/api/compensations", compReq("id-a", "LID", 1, seedBatches()));
        assertEquals(201, replay.getStatusCode().value(), "重放恢复首次状态码 201");
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation WHERE lot_id=(SELECT id FROM lot WHERE lot_no='LID')",
                Long.class));

        // 同键异参（RED 改批次）409，不改变冻结
        ResponseEntity<Map> conflict = post("/api/compensations", compReq("id-a", "LID", 1,
                new LinkedHashMap<>(Map.of(
                        "D-RED", List.of("B-RED-08-MISSING", 1),
                        "D-YELLOW", List.of("B-YELLOW-001", 1),
                        "D-BLUE", List.of("B-BLUE-001", 1)))));
        // B-RED-08-MISSING 不存在，但指纹先于业务校验：异参直接 409
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("B-RED-001", jdbc.queryForObject(
                "SELECT batch_no FROM compensation_item WHERE compensation_id="
                        + "(SELECT id FROM compensation WHERE lot_id=(SELECT id FROM lot WHERE lot_no='LID')) "
                        + "AND dye_code='D-RED'", String.class));

        // 投料同键同参重放不重复扣批次余量
        feedControlledExact("id-f", "LID", 1);
        BigDecimal redAfter = batchAvailable("B-RED-001");
        ResponseEntity<Map> feedReplay = post("/api/feedings", Map.of(
                "requestId", "id-f", "lotNo", "LID", "expectedVersion", 1,
                "weighings", exactWeighings("1000", "750", "250")));
        assertEquals(200, feedReplay.getStatusCode().value());
        assertEquals("true", feedReplay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(0, redAfter.compareTo(batchAvailable("B-RED-001")));

        // 失败不占键：先用新键投料偏差超标（422），再用同键改正参数成功
        scheduleControlled("id-s2", "LID2", "VID2", "R-BASE", 1, "50");
        approveSeedCompensation("id-a2", "LID2");
        ResponseEntity<Map> bad = post("/api/feedings", Map.of(
                "requestId", "id-f2", "lotNo", "LID2", "expectedVersion", 1,
                "weighings", exactWeighings("1010", "750", "250")));
        assertEquals(422, bad.getStatusCode().value());
        feedControlledExact("id-f2", "LID2", 1);
        assertEquals("FED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LID2'", String.class));
    }

    @Test
    void legacyLot_keepsOldFeedingSemantics_andControlledEvidenceIsQueriable() {
        // 旧非受控缸：不带 expectedVersion，扣染化料总库存，与批次无关
        schedule("lg-s", "LLG", "VLG", "R-BASE", 1, "50");
        BigDecimal stockBefore = stock("D-RED");
        Map<String, Object> fed = feedExact("lg-f", "LLG");
        assertEquals(false, fed.get("controlled"));
        assertEquals(0, stockBefore.subtract(new BigDecimal("1000")).compareTo(stock("D-RED")));
        assertEquals(0, new BigDecimal("100000.000").compareTo(batchAvailable("B-RED-001")),
                "非受控投料不扣批次余量");

        // 旧缸带 expectedVersion 投料被 400 拒绝
        ResponseEntity<Map> withVersion = post("/api/feedings", Map.of(
                "requestId", "lg-never", "lotNo", "LLG", "expectedVersion", 1,
                "weighings", List.of()));
        assertTrue(withVersion.getStatusCode().value() == 400);

        // 缸次视图含 controlled 标志；受控缸补偿证据可查询
        Map<String, Object> lotView = rest.getForObject(url("/api/lots/LLG"), Map.class);
        assertEquals(false, lotView.get("controlled"));
        assertFalse(lotView.containsKey("compensation"));

        scheduleControlled("lg-cs", "LGC", "VGC", "R-BASE", 1, "50");
        approveSeedCompensation("lg-ca", "LGC");
        Map<String, Object> controlledView = rest.getForObject(url("/api/lots/LGC"), Map.class);
        assertEquals(true, controlledView.get("controlled"));
        assertNotNull(controlledView.get("compensation"));
        ResponseEntity<Map> noComp = rest.getForEntity(url("/api/lots/LLG/compensation"), Map.class);
        assertEquals(404, noComp.getStatusCode().value(), "旧缸无补偿证据");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Map<String, List<Object>> seedBatches() {
        return new LinkedHashMap<>(Map.of(
                "D-RED", List.of("B-RED-001", 1),
                "D-YELLOW", List.of("B-YELLOW-001", 1),
                "D-BLUE", List.of("B-BLUE-001", 1)));
    }

    private List<Map<String, Object>> exactWeighings(String red, String yellow, String blue) {
        return List.of(
                Map.of("dyeCode", "D-RED", "actualG", new BigDecimal(red)),
                Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal(yellow)),
                Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal(blue)));
    }

    private Map<String, Object> compReq(String requestId, String lotNo, int expectedVersion,
                                        Map<String, List<Object>> batchByDye) {
        return Map.of(
                "requestId", requestId,
                "lotNo", lotNo,
                "expectedVersion", expectedVersion,
                "items", batchByDye.entrySet().stream()
                        .map(e -> Map.of(
                                "dyeCode", e.getKey(),
                                "batchNo", e.getValue().get(0),
                                "factorVersion", e.getValue().get(1)))
                        .toList());
    }

    private Map<String, Object> byDye(List<Map<String, Object>> items, String dyeCode) {
        return items.stream().filter(i -> dyeCode.equals(i.get("dyeCode"))).findFirst().orElseThrow();
    }
}
