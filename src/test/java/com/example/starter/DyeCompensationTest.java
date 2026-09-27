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
 * 染料批次效价补偿：批次登记、系数修订（expectedVersion）、补偿审批、受控投料、并缸一致性。
 * 线性模型：补偿目标 = 原目标 / 系数，四舍五入到三位小数；now&lt;截止有效，恰在截止失效。
 */
class DyeCompensationTest extends BaseIntegrationTest {

    // ------------------------------------------------------------------
    // 批次登记：版本从 1 开始；零系数 400；未知染料 404；重复批次 409；幂等
    // ------------------------------------------------------------------

    @Test
    void registerDyeLot_success_versionAndPotencyVersionStartAtOne_idempotent() {
        Map<String, Object> body = registerDyeLot("dl-1", "LOT-R1", "D-RED", "2.0", "2000");
        assertEquals("LOT-R1", body.get("lotCode"));
        assertEquals(1, body.get("version"));
        assertEquals(1, body.get("potencyVersion"));
        assertEquals(0, new BigDecimal("2.0").compareTo(bd(body.get("potency"))));

        // 同键同参重放：返回首次冻结结果，不新增批次
        ResponseEntity<Map> replay = post("/api/dye-lots", Map.of(
                "requestId", "dl-1", "lotCode", "LOT-R1", "dyeCode", "D-RED",
                "potency", new BigDecimal("2.0"), "availableG", new BigDecimal("2000"),
                "validUntil", java.time.Instant.now().plusSeconds(3600).toString()));
        // 重放参数须与首次完全一致（含精确时刻），此处异参应 409
        assertEquals(409, replay.getStatusCode().value());
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM dye_lot WHERE lot_code='LOT-R1'", Long.class));
    }

    @Test
    void registerDyeLot_sameKeySameParams_replaysFrozenResult() {
        String validUntil = java.time.Instant.now().plusSeconds(3600).toString();
        Map<String, Object> req = Map.of(
                "requestId", "dl-2", "lotCode", "LOT-R2", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.25"), "availableG", new BigDecimal("800"),
                "validUntil", validUntil);
        ResponseEntity<Map> first = post("/api/dye-lots", req);
        assertEquals(201, first.getStatusCode().value());

        ResponseEntity<Map> replay = post("/api/dye-lots", req);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM dye_lot WHERE lot_code='LOT-R2'", Long.class));
    }

    @Test
    void registerDyeLot_invalidParams_400_unknownDye_404_duplicate_409_expired_422() {
        // 系数零：参数非法 400
        ResponseEntity<Map> zero = post("/api/dye-lots", Map.of(
                "requestId", "dl-z", "lotCode", "LOT-Z", "dyeCode", "D-RED",
                "potency", new BigDecimal("0"), "availableG", new BigDecimal("100"),
                "validUntil", java.time.Instant.now().plusSeconds(3600).toString()));
        assertEquals(400, zero.getStatusCode().value());

        // 染料代码不存在：404
        ResponseEntity<Map> unknownDye = post("/api/dye-lots", Map.of(
                "requestId", "dl-u", "lotCode", "LOT-U", "dyeCode", "D-NOPE",
                "potency", new BigDecimal("1.0"), "availableG", new BigDecimal("100"),
                "validUntil", java.time.Instant.now().plusSeconds(3600).toString()));
        assertEquals(404, unknownDye.getStatusCode().value());

        // 有效截止已过：业务拒绝 422
        ResponseEntity<Map> expired = post("/api/dye-lots", Map.of(
                "requestId", "dl-e", "lotCode", "LOT-E", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.0"), "availableG", new BigDecimal("100"),
                "validUntil", java.time.Instant.now().minusSeconds(60).toString()));
        assertEquals(422, expired.getStatusCode().value());

        // 重复批次号：409
        registerDyeLot("dl-d", "LOT-D", "D-RED", "1.0", "100");
        ResponseEntity<Map> dup = post("/api/dye-lots", Map.of(
                "requestId", "dl-d2", "lotCode", "LOT-D", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.0"), "availableG", new BigDecimal("100"),
                "validUntil", java.time.Instant.now().plusSeconds(3600).toString()));
        assertEquals(409, dup.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 补偿审批 + 受控投料主流程：补偿目标三位小数、偏差按补偿目标、扣批次余量
    // ------------------------------------------------------------------

    @Test
    void approveAndControlledFeed_mainFlow_compTargetRoundedToThreeDecimals() {
        // R-BASE v1、50kg、浴比10：原目标 RED 1000 / YELLOW 750 / BLUE 250
        registerDyeLot("dl-m1", "LOT-MR", "D-RED", "2.0", "2000");
        registerDyeLot("dl-m2", "LOT-MY", "D-YELLOW", "1.5", "2000");
        registerDyeLot("dl-m3", "LOT-MB", "D-BLUE", "0.3", "5000");
        schedule("sc-m", "LM", "VM", "R-BASE", 1, "50");

        Map<String, Object> approval = approve("ap-m", "LM", List.of(
                List.of("D-RED", "LOT-MR", 1),
                List.of("D-YELLOW", "LOT-MY", 1),
                List.of("D-BLUE", "LOT-MB", 1)));
        assertEquals("ACTIVE", approval.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) approval.get("items");
        Map<String, Object> red = items.stream()
                .filter(i -> "D-RED".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        Map<String, Object> yellow = items.stream()
                .filter(i -> "D-YELLOW".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        Map<String, Object> blue = items.stream()
                .filter(i -> "D-BLUE".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        // 补偿目标 = 原目标/系数：1000/2=500.000，750/1.5=500.000，250/0.3=833.333（三位小数四舍五入）
        assertEquals(0, new BigDecimal("500.000").compareTo(bd(red.get("compTargetG"))));
        assertEquals(0, new BigDecimal("500.000").compareTo(bd(yellow.get("compTargetG"))));
        assertEquals(0, new BigDecimal("833.333").compareTo(bd(blue.get("compTargetG"))));
        assertEquals(0, new BigDecimal("250.0000").compareTo(bd(blue.get("baseTargetG"))));

        // 已发布基础配方不被补偿批准修改
        assertEquals(0, new BigDecimal("0.5000").compareTo(jdbc.queryForObject(
                "SELECT dosage_gpl FROM recipe_item ri JOIN recipe r ON r.id=ri.recipe_id "
                        + "WHERE r.recipe_code='R-BASE' AND r.version=1 AND ri.dye_code='D-BLUE'",
                BigDecimal.class)));

        BigDecimal redStockBefore = stock("D-RED");
        Map<String, Object> fed = postOk("/api/feedings", Map.of(
                "requestId", "feed-m", "lotNo", "LM",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("833.333")))));
        assertEquals(true, fed.get("controlled"));
        assertEquals("FED", fed.get("status"));

        // 扣减批次余量，不扣旧染化料库存；批准转为 CONSUMED
        assertEquals(0, new BigDecimal("1500.000").compareTo(batchAvailable("LOT-MR")));
        assertEquals(0, new BigDecimal("1500.000").compareTo(batchAvailable("LOT-MY")));
        assertEquals(0, new BigDecimal("4166.667").compareTo(batchAvailable("LOT-MB")));
        assertEquals(0, redStockBefore.compareTo(stock("D-RED")), "受控投料不扣旧库存表");
        assertEquals("CONSUMED", jdbc.queryForObject(
                "SELECT status FROM comp_approval WHERE lot_no='LM'", String.class));

        // 称量链携带冻结批次与系数
        Map<String, Object> weighing = jdbc.queryForMap(
                "SELECT w.target_g, w.dye_lot_code, w.potency FROM weighing w "
                        + "JOIN feeding f ON f.id=w.feeding_id JOIN lot l ON l.id=f.lot_id "
                        + "WHERE l.lot_no='LM' AND w.dye_code='D-BLUE'");
        assertEquals(0, new BigDecimal("833.333").compareTo((BigDecimal) weighing.get("target_g")));
        assertEquals("LOT-MB", weighing.get("dye_lot_code"));
        assertEquals(0, new BigDecimal("0.3").compareTo((BigDecimal) weighing.get("potency")));

        // 证据查询：批准明细与冻结目标可查
        Map<String, Object> evidence = rest.getForObject(url("/api/lots/LM/compensation"), Map.class);
        assertEquals("CONSUMED", evidence.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidenceItems = (List<Map<String, Object>>) evidence.get("items");
        assertEquals(3, evidenceItems.size());

        // 复算：受控称量链按冻结系数复算一致
        Map<String, Object> report = rest.getForObject(url("/api/recipes/R-BASE/recalculate"), Map.class);
        assertEquals(true, report.get("consistent"));
        assertEquals(0, ((List<?>) report.get("weighingMismatches")).size());
    }

    // ------------------------------------------------------------------
    // 审批失败分支：代码错配 / 余量不足 / 系数版本错配 / 批次不存在 / 失败不占键
    // ------------------------------------------------------------------

    @Test
    void approve_codeMismatch_rejected422() {
        registerDyeLot("dl-c1", "LOT-CR", "D-RED", "2.0", "2000");
        registerDyeLot("dl-c2", "LOT-CY", "D-YELLOW", "1.5", "2000");
        registerDyeLot("dl-c3", "LOT-CB", "D-BLUE", "1.0", "2000");
        schedule("sc-c", "LC", "VC", "R-BASE", 1, "50");

        // 批次染化料与配方项错配（D-RED 项指定了 D-YELLOW 的批次）
        ResponseEntity<Map> mismatch = post("/api/compensations", Map.of(
                "requestId", "ap-c1", "lotNo", "LC",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-CY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-CY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "lotCode", "LOT-CB", "potencyVersion", 1))));
        assertEquals(422, mismatch.getStatusCode().value());

        // 缺项：整单拒绝
        ResponseEntity<Map> missing = post("/api/compensations", Map.of(
                "requestId", "ap-c2", "lotNo", "LC",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-CR", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-CY", "potencyVersion", 1))));
        assertEquals(422, missing.getStatusCode().value());

        // 批次不存在：404
        ResponseEntity<Map> notFound = post("/api/compensations", Map.of(
                "requestId", "ap-c3", "lotNo", "LC",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-NOPE", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-CY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "lotCode", "LOT-CB", "potencyVersion", 1))));
        assertEquals(404, notFound.getStatusCode().value());

        // 失败不占键：同键换正确参数可重试成功
        Map<String, Object> retried = approve("ap-c2", "LC", List.of(
                List.of("D-RED", "LOT-CR", 1),
                List.of("D-YELLOW", "LOT-CY", 1),
                List.of("D-BLUE", "LOT-CB", 1)));
        assertEquals("ACTIVE", retried.get("status"));

        // 重复生效批准：422
        ResponseEntity<Map> duplicate = post("/api/compensations", Map.of(
                "requestId", "ap-c4", "lotNo", "LC",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-CR", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-CY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "lotCode", "LOT-CB", "potencyVersion", 1))));
        assertEquals(422, duplicate.getStatusCode().value());
    }

    @Test
    void approve_insufficientRemainingOrPotencyVersionMismatch_rejected422() {
        registerDyeLot("dl-i1", "LOT-IR", "D-RED", "2.0", "400"); // 补偿目标 500 > 400
        registerDyeLot("dl-i2", "LOT-IY", "D-YELLOW", "1.5", "2000");
        registerDyeLot("dl-i3", "LOT-IB", "D-BLUE", "1.0", "2000");
        schedule("sc-i", "LI", "VI", "R-BASE", 1, "50");

        ResponseEntity<Map> insufficient = post("/api/compensations", Map.of(
                "requestId", "ap-i1", "lotNo", "LI",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-IR", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-IY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "lotCode", "LOT-IB", "potencyVersion", 1))));
        assertEquals(422, insufficient.getStatusCode().value());
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM comp_approval WHERE lot_no='LI'", Long.class), "整单拒绝不落库");

        // 系数版本错配：当前版本 1，指定 2
        registerDyeLot("dl-i4", "LOT-IR2", "D-RED", "2.0", "2000");
        ResponseEntity<Map> versionMismatch = post("/api/compensations", Map.of(
                "requestId", "ap-i2", "lotNo", "LI",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-IR2", "potencyVersion", 2),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-IY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "lotCode", "LOT-IB", "potencyVersion", 1))));
        assertEquals(422, versionMismatch.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 受控投料边界：恰 ±0.5% 合法；第二种不足时第一种也不扣
    // ------------------------------------------------------------------

    @Test
    void controlledFeed_deviationExactlyHalfPercent_allowed_overLimit_rejectedAtomically() {
        registerDyeLot("dl-b1", "LOT-BR", "D-RED", "2.0", "2000");
        registerDyeLot("dl-b2", "LOT-BY", "D-YELLOW", "1.5", "2000");
        registerDyeLot("dl-b3", "LOT-BB", "D-BLUE", "1.0", "2000");
        schedule("sc-b1", "LB1", "VB1", "R-BASE", 1, "50");
        approve("ap-b1", "LB1", List.of(
                List.of("D-RED", "LOT-BR", 1),
                List.of("D-YELLOW", "LOT-BY", 1),
                List.of("D-BLUE", "LOT-BB", 1)));

        // 补偿目标 RED 500.000：实际 502.500 => 偏差恰 +0.5%，合法
        Map<String, Object> fed = postOk("/api/feedings", Map.of(
                "requestId", "feed-b1", "lotNo", "LB1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("502.500")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.000")))));
        assertEquals("FED", fed.get("status"));
        assertEquals(0, new BigDecimal("1497.500").compareTo(batchAvailable("LOT-BR")));

        // 第二缸：RED 偏差 0.52% 超限 => 422，且任何批次余量都不扣
        schedule("sc-b2", "LB2", "VB2", "R-BASE", 1, "50");
        approve("ap-b2", "LB2", List.of(
                List.of("D-RED", "LOT-BR", 1),
                List.of("D-YELLOW", "LOT-BY", 1),
                List.of("D-BLUE", "LOT-BB", 1)));
        BigDecimal yellowBefore = batchAvailable("LOT-BY");
        BigDecimal blueBefore = batchAvailable("LOT-BB");
        ResponseEntity<Map> rejected = post("/api/feedings", Map.of(
                "requestId", "feed-b2", "lotNo", "LB2",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("502.600")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.000")))));
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals(0, yellowBefore.compareTo(batchAvailable("LOT-BY")));
        assertEquals(0, blueBefore.compareTo(batchAvailable("LOT-BB")));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LB2'", String.class));
    }

    @Test
    void controlledFeed_secondBatchInsufficient_firstBatchNotDeducted() {
        registerDyeLot("dl-s1", "LOT-SR", "D-RED", "2.0", "2000");
        registerDyeLot("dl-s2", "LOT-SY", "D-YELLOW", "1.5", "2000");
        registerDyeLot("dl-s3", "LOT-SB", "D-BLUE", "1.0", "100"); // 目标 250 > 实际称量 250 不足
        schedule("sc-s", "LS", "VS", "R-BASE", 1, "50");
        // 审批时 BLUE 余量 100 < 补偿目标 250，审批即整单拒绝；补足 BLUE 后重新审批
        ResponseEntity<Map> rejected = post("/api/compensations", Map.of(
                "requestId", "ap-s1", "lotNo", "LS",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-SR", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-SY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "lotCode", "LOT-SB", "potencyVersion", 1))));
        assertEquals(422, rejected.getStatusCode().value());

        jdbc.update("UPDATE dye_lot SET available_g=260 WHERE lot_code='LOT-SB'");
        approve("ap-s2", "LS", List.of(
                List.of("D-RED", "LOT-SR", 1),
                List.of("D-YELLOW", "LOT-SY", 1),
                List.of("D-BLUE", "LOT-SB", 1)));

        // 投料时 BLUE 实际称量 300 > 余量 260：整单拒绝，RED/YELLOW 不扣
        BigDecimal redBefore = batchAvailable("LOT-SR");
        BigDecimal yellowBefore = batchAvailable("LOT-SY");
        ResponseEntity<Map> feedRejected = post("/api/feedings", Map.of(
                "requestId", "feed-s1", "lotNo", "LS",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("300.000")))));
        // 300 相对目标 250 偏差 20% 先触发偏差拒绝；改为偏差内但余量不足的场景
        assertEquals(422, feedRejected.getStatusCode().value());
        assertEquals(0, redBefore.compareTo(batchAvailable("LOT-SR")), "第一种染料不得扣减");
        assertEquals(0, yellowBefore.compareTo(batchAvailable("LOT-SY")));

        // 偏差合法（251.000，+0.4%）但余量 260 >= 251 可扣；先造出余量不足：扣到 250.5
        jdbc.update("UPDATE dye_lot SET available_g=250.500 WHERE lot_code='LOT-SB'");
        ResponseEntity<Map> feedRejected2 = post("/api/feedings", Map.of(
                "requestId", "feed-s2", "lotNo", "LS",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("251.000")))));
        assertEquals(422, feedRejected2.getStatusCode().value());
        assertEquals(0, redBefore.compareTo(batchAvailable("LOT-SR")), "余量不足时第一种也不扣");
        assertEquals(0, yellowBefore.compareTo(batchAvailable("LOT-SY")));
        assertEquals(0, new BigDecimal("250.500").compareTo(batchAvailable("LOT-SB")));
    }

    // ------------------------------------------------------------------
    // 系数修订：expectedVersion 乐观锁；未投料批准失效；已投料历史不追改
    // ------------------------------------------------------------------

    @Test
    void revisePotency_expectedVersionGuard_invalidatesUnfedApproval_fedHistoryUntouched() {
        registerDyeLot("dl-r1", "LOT-RR", "D-RED", "2.0", "5000");
        registerDyeLot("dl-r2", "LOT-RY", "D-YELLOW", "1.5", "5000");
        registerDyeLot("dl-r3", "LOT-RB", "D-BLUE", "1.0", "5000");
        schedule("sc-r1", "LRA", "VRA", "R-BASE", 1, "50");
        schedule("sc-r2", "LRB", "VRB", "R-BASE", 1, "50");
        approve("ap-r1", "LRA", List.of(
                List.of("D-RED", "LOT-RR", 1),
                List.of("D-YELLOW", "LOT-RY", 1),
                List.of("D-BLUE", "LOT-RB", 1)));
        approve("ap-r2", "LRB", List.of(
                List.of("D-RED", "LOT-RR", 1),
                List.of("D-YELLOW", "LOT-RY", 1),
                List.of("D-BLUE", "LOT-RB", 1)));

        // LRB 先投料（批准转 CONSUMED）
        postOk("/api/feedings", Map.of(
                "requestId", "feed-rb", "lotNo", "LRB",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.000")))));

        // expectedVersion 不一致：409
        ResponseEntity<Map> wrongVersion = post("/api/dye-lots/LOT-RR/revise-potency", Map.of(
                "requestId", "rev-r0", "expectedVersion", 7, "potency", new BigDecimal("2.5")));
        assertEquals(409, wrongVersion.getStatusCode().value());

        // 正确版本修订：版本与系数版本各加 1，未投料批准失效
        Map<String, Object> revised = postOk("/api/dye-lots/LOT-RR/revise-potency", Map.of(
                "requestId", "rev-r1", "expectedVersion", 1, "potency", new BigDecimal("2.5")));
        assertEquals(2, revised.get("version"));
        assertEquals(2, revised.get("potencyVersion"));
        assertEquals(0, new BigDecimal("2.5").compareTo(bd(revised.get("potency"))));
        assertEquals(1, revised.get("invalidatedApprovals"));
        assertEquals("INVALIDATED", jdbc.queryForObject(
                "SELECT status FROM comp_approval WHERE lot_no='LRA'", String.class));
        assertEquals("CONSUMED", jdbc.queryForObject(
                "SELECT status FROM comp_approval WHERE lot_no='LRB'", String.class),
                "已投料批准不追改");

        // 修订幂等：同键同参重放不再加版本
        ResponseEntity<Map> replay = post("/api/dye-lots/LOT-RR/revise-potency", Map.of(
                "requestId", "rev-r1", "expectedVersion", 1, "potency", new BigDecimal("2.5")));
        assertEquals(200, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM dye_lot WHERE lot_code='LOT-RR'", Integer.class));

        // 失效批准的缸次投料：422（系数版本不匹配）
        ResponseEntity<Map> feedInvalid = post("/api/feedings", Map.of(
                "requestId", "feed-ra", "lotNo", "LRA",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.000")))));
        assertEquals(422, feedInvalid.getStatusCode().value());

        // 已投料历史不追改：LRB 称量链仍是冻结的 500.000 / 系数 2.0
        Map<String, Object> lrb = rest.getForObject(url("/api/lots/LRB"), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> weighings = (List<Map<String, Object>>) lrb.get("weighings");
        Map<String, Object> redWeighing = weighings.stream()
                .filter(w -> "D-RED".equals(w.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("500.000").compareTo(bd(redWeighing.get("targetG"))));
        assertEquals(0, new BigDecimal("2.0").compareTo(bd(redWeighing.get("potency"))));

        // 新批准须使用新系数版本 2，补偿目标按新系数 1000/2.5=400.000
        Map<String, Object> reApproved = approve("ap-r3", "LRA", List.of(
                List.of("D-RED", "LOT-RR", 2),
                List.of("D-YELLOW", "LOT-RY", 1),
                List.of("D-BLUE", "LOT-RB", 1)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) reApproved.get("items");
        Map<String, Object> red = items.stream()
                .filter(i -> "D-RED".equals(i.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("400.000").compareTo(bd(red.get("compTargetG"))));
    }

    // ------------------------------------------------------------------
    // 并缸：补偿批次与目标完全相同才允许
    // ------------------------------------------------------------------

    @Test
    void merge_controlledLots_identicalCompensationAllowed_otherwiseRejected() {
        registerDyeLot("dl-g1", "LOT-GR", "D-RED", "2.0", "9000");
        registerDyeLot("dl-g2", "LOT-GY", "D-YELLOW", "1.5", "9000");
        registerDyeLot("dl-g3", "LOT-GB", "D-BLUE", "1.0", "9000");
        registerDyeLot("dl-g4", "LOT-GR2", "D-RED", "2.0", "9000");

        // 同批次同目标（同布重）：允许并缸
        schedule("sc-g1", "LG1", "VG1", "R-BASE", 1, "50");
        schedule("sc-g2", "LG2", "VG2", "R-BASE", 1, "50");
        approve("ap-g1", "LG1", List.of(
                List.of("D-RED", "LOT-GR", 1),
                List.of("D-YELLOW", "LOT-GY", 1),
                List.of("D-BLUE", "LOT-GB", 1)));
        approve("ap-g2", "LG2", List.of(
                List.of("D-RED", "LOT-GR", 1),
                List.of("D-YELLOW", "LOT-GY", 1),
                List.of("D-BLUE", "LOT-GB", 1)));
        Map<String, Object> merged = postOk("/api/lots/merge", Map.of(
                "requestId", "merge-g1", "lotNo", "LG1", "targetLotNo", "LG2"));
        assertEquals("MERGED", merged.get("status"));

        // 批次不同：拒绝
        schedule("sc-g3", "LG3", "VG3", "R-BASE", 1, "50");
        schedule("sc-g4", "LG4", "VG4", "R-BASE", 1, "50");
        approve("ap-g3", "LG3", List.of(
                List.of("D-RED", "LOT-GR", 1),
                List.of("D-YELLOW", "LOT-GY", 1),
                List.of("D-BLUE", "LOT-GB", 1)));
        approve("ap-g4", "LG4", List.of(
                List.of("D-RED", "LOT-GR2", 1),
                List.of("D-YELLOW", "LOT-GY", 1),
                List.of("D-BLUE", "LOT-GB", 1)));
        ResponseEntity<Map> differentBatch = post("/api/lots/merge", Map.of(
                "requestId", "merge-g2", "lotNo", "LG3", "targetLotNo", "LG4"));
        assertEquals(409, differentBatch.getStatusCode().value());

        // 受控与非受控：拒绝
        schedule("sc-g5", "LG5", "VG5", "R-BASE", 1, "50");
        ResponseEntity<Map> mixedControl = post("/api/lots/merge", Map.of(
                "requestId", "merge-g3", "lotNo", "LG3", "targetLotNo", "LG5"));
        assertEquals(409, mixedControl.getStatusCode().value());
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LG3'", String.class));

        // 目标不同（布重不同导致补偿目标不同）：拒绝
        schedule("sc-g6", "LG6", "VG6", "R-BASE", 1, "60");
        approve("ap-g6", "LG6", List.of(
                List.of("D-RED", "LOT-GR", 1),
                List.of("D-YELLOW", "LOT-GY", 1),
                List.of("D-BLUE", "LOT-GB", 1)));
        ResponseEntity<Map> differentTarget = post("/api/lots/merge", Map.of(
                "requestId", "merge-g4", "lotNo", "LG3", "targetLotNo", "LG6"));
        assertEquals(409, differentTarget.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 旧非受控缸次：保持原投料语义（扣旧库存表，无补偿证据）
    // ------------------------------------------------------------------

    @Test
    void uncontrolledLot_keepsOriginalFeedSemantics() {
        schedule("sc-u", "LU", "VU", "R-BASE", 1, "50");
        BigDecimal redBefore = stock("D-RED");
        feedExact("feed-u", "LU");
        assertEquals(0, redBefore.subtract(new BigDecimal("1000")).compareTo(stock("D-RED")),
                "非受控缸次仍扣旧库存表");
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM comp_approval WHERE lot_no='LU'", Long.class));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing w JOIN feeding f ON f.id=w.feeding_id "
                        + "JOIN lot l ON l.id=f.lot_id WHERE l.lot_no='LU' AND w.dye_lot_code IS NOT NULL",
                Long.class), "非受控称量链不带批次信息");

        ResponseEntity<Map> noEvidence = rest.getForEntity(
                url("/api/lots/LU/compensation"), Map.class);
        assertEquals(404, noEvidence.getStatusCode().value());
        assertNotNull(noEvidence.getBody());
    }

    // ------------------------------------------------------------------
    // 审批幂等：同键异参 409；指纹区分集合与版本
    // ------------------------------------------------------------------

    @Test
    void approve_sameKeyDifferentParams_conflict409() {
        registerDyeLot("dl-p1", "LOT-PR", "D-RED", "2.0", "2000");
        registerDyeLot("dl-p2", "LOT-PY", "D-YELLOW", "1.5", "2000");
        registerDyeLot("dl-p3", "LOT-PB", "D-BLUE", "1.0", "2000");
        schedule("sc-p", "LP", "VP", "R-BASE", 1, "50");
        approve("ap-p1", "LP", List.of(
                List.of("D-RED", "LOT-PR", 1),
                List.of("D-YELLOW", "LOT-PY", 1),
                List.of("D-BLUE", "LOT-PB", 1)));

        // 同键异参（换批次）：409，不产生第二个批准
        ResponseEntity<Map> conflict = post("/api/compensations", Map.of(
                "requestId", "ap-p1", "lotNo", "LP",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-PY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-PY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "lotCode", "LOT-PB", "potencyVersion", 1))));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM comp_approval WHERE lot_no='LP'", Long.class));

        // 同键同参重放：返回首次冻结结果
        ResponseEntity<Map> replay = post("/api/compensations", Map.of(
                "requestId", "ap-p1", "lotNo", "LP",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-PR", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-PY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "lotCode", "LOT-PB", "potencyVersion", 1))));
        assertEquals(201, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertTrue(replay.getBody() != null && "ACTIVE".equals(replay.getBody().get("status")));
    }
}
