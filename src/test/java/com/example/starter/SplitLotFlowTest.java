package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 分缸主流程与失败分支（H2 MODE=MySQL 真实事务）：
 * - 100kg 分 40/60 后逐染料耗用守恒（2000→800/1200 等），来源染料/批次库存不重扣；
 * - 重量不符、目标占用（含来源本缸）、重复目标、非 FED、已判、已归档、子缸数越界整单拒绝；
 * - 占用冲突整单回滚：来源仍 FED 仍占原缸，无子缸/无分缸单；
 * - expectedVersion 版本语义、幂等同参重放/异参 409/失败不占键；
 * - 子缸禁止重新首次投料、来源分缸后判色拒绝；受控来源冻结批次/补偿证据继承且批次余量不动。
 */
class SplitLotFlowTest extends BaseIntegrationTest {

    /** R-BASE 100kg、浴比10 的实际称量：RED 2000 / YELLOW 1500 / BLUE 500。 */
    private Map<String, Object> feed100(String requestId, String lotNo) {
        return postOk("/api/feedings", Map.of(
                "requestId", requestId,
                "lotNo", lotNo,
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("2000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("1500.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("500.0000")))));
    }

    private Map<String, Object> splitBody(String requestId, String source, int expectedVersion,
                                          Object... triplets) {
        @SuppressWarnings("unchecked")
        List<Object> targets = new java.util.ArrayList<>();
        for (int i = 0; i < triplets.length; i += 3) {
            targets.add(Map.of(
                    "lotNo", triplets[i],
                    "vatNo", triplets[i + 1],
                    "fabricWeightKg", new BigDecimal((String) triplets[i + 2])));
        }
        return Map.of(
                "requestId", requestId,
                "sourceLotNo", source,
                "expectedVersion", expectedVersion,
                "targets", targets);
    }

    private Map<String, Object> split4060(String requestId) {
        return splitBody(requestId, "LS", 1,
                "LC1", "VC1", "40",
                "LC2", "VC2", "60");
    }

    @Test
    void split100kg_into40and60_usageConservedAndStockUnchanged() {
        schedule("sp-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("sp-feed", "LS");
        BigDecimal redBefore = stock("D-RED");
        BigDecimal yellowBefore = stock("D-YELLOW");
        BigDecimal blueBefore = stock("D-BLUE");

        ResponseEntity<Map> resp = post("/api/lots/split", split4060("sp-split"));
        assertEquals(200, resp.getStatusCode().value(), String.valueOf(resp.getBody()));
        Map<String, Object> body = resp.getBody();
        assertEquals(2, body.get("splitVersion"));
        assertEquals("SPLIT", body.get("status"));
        assertEquals(false, body.get("batchStockChanged"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) body.get("children");
        assertEquals(2, children.size());
        assertChild(children.get(0), 1, "LC1", "VC1", "40",
                Map.of("D-RED", "800.0000", "D-YELLOW", "600.0000", "D-BLUE", "200.0000"));
        assertChild(children.get(1), 2, "LC2", "VC2", "60",
                Map.of("D-RED", "1200.0000", "D-YELLOW", "900.0000", "D-BLUE", "300.0000"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conservation = (List<Map<String, Object>>) body.get("conservation");
        for (Map<String, Object> c : conservation) {
            assertEquals(true, c.get("conserved"),
                    c.get("dyeCode") + " 子耗用合计必须等于来源实际耗用: " + c);
            assertEquals(bd(c.get("sourceActualG")), bd(c.get("childrenSumG")));
        }

        // 来源只读、释放原缸；两子缸 FED 且各自占缸。
        assertEquals("SPLIT", jdbc.queryForObject("SELECT status FROM lot WHERE lot_no='LS'", String.class));
        assertEquals(Long.valueOf(0),
                jdbc.queryForObject("SELECT COUNT(*) FROM vat_active WHERE vat_no='VS'", Long.class));
        assertEquals(Long.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM vat_active WHERE vat_no='VC1'", Long.class));
        assertEquals(Long.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM vat_active WHERE vat_no='VC2'", Long.class));
        assertEquals("FED", jdbc.queryForObject("SELECT status FROM lot WHERE lot_no='LC1'", String.class));
        assertEquals("FED", jdbc.queryForObject("SELECT status FROM lot WHERE lot_no='LC2'", String.class));

        // 分缸不重扣：染料总库存与分缸前一致；原称量保留未覆盖。
        assertEquals(0, redBefore.compareTo(stock("D-RED")));
        assertEquals(0, yellowBefore.compareTo(stock("D-YELLOW")));
        assertEquals(0, blueBefore.compareTo(stock("D-BLUE")));
        assertEquals(Long.valueOf(3), jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing w JOIN feeding f ON f.id=w.feeding_id "
                        + "WHERE f.lot_id=(SELECT id FROM lot WHERE lot_no='LS')", Long.class));

        // 谱系查询：逐染料守恒，子缸编号/布重正确。
        ResponseEntity<Map> lineage = rest.getForEntity(url("/api/lots/LS/lineage"), Map.class);
        assertEquals(200, lineage.getStatusCode().value());
        Map<String, Object> lv = lineage.getBody();
        assertEquals("LS", lv.get("sourceLotNo"));
        assertEquals("SPLIT", lv.get("sourceStatus"));
        assertEquals(false, lv.get("queriedIsChild"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lchildren = (List<Map<String, Object>>) lv.get("children");
        assertEquals(2, lchildren.size());
        assertEquals(0, new BigDecimal("800.0000").compareTo(new BigDecimal(usageOf(lv, 1, "D-RED"))));
        assertEquals(0, new BigDecimal("1200.0000").compareTo(new BigDecimal(usageOf(lv, 2, "D-RED"))));
    }

    @Test
    void lineage_fromChildLot_returnsSourceLineage() {
        schedule("ln-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("ln-feed", "LS");
        postOk("/api/lots/split", split4060("ln-split"));

        Map<String, Object> child = rest.getForObject(url("/api/lots/LC1"), Map.class);
        assertEquals("LS", child.get("splitParentLotNo"));
        assertEquals(1, child.get("splitSeq"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inherited = (List<Map<String, Object>>) child.get("inheritedWeighings");
        assertNotNull(inherited);
        assertEquals(3, inherited.size());

        Map<String, Object> lineage = rest.getForObject(url("/api/lots/LC1/lineage"), Map.class);
        assertEquals("LS", lineage.get("sourceLotNo"));
        assertEquals(true, lineage.get("queriedIsChild"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conservation =
                (List<Map<String, Object>>) lineage.get("conservation");
        conservation.forEach(c -> assertEquals(true, c.get("conserved")));
    }

    @Test
    void weightSumMismatch_wholeRequestRejected() {
        schedule("wm-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("wm-feed", "LS");
        ResponseEntity<Map> resp = post("/api/lots/split",
                splitBody("wm-split", "LS", 1, "LC1", "VC1", "40", "LC2", "VC2", "59.999"));
        assertEquals(422, resp.getStatusCode().value());
        assertNoSplitSideEffects("LS", "VS");
    }

    @Test
    void occupiedTargetVat_wholeRequestRolledBack() {
        schedule("oc-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("oc-feed", "LS");
        // 先排一个占缸缸次在 VC2（CREATED 即占缸）
        schedule("oc-sched-other", "LO", "VC2", "R-BASE", 1, "10");

        ResponseEntity<Map> resp = post("/api/lots/split", split4060("oc-split"));
        assertEquals(409, resp.getStatusCode().value());
        assertNoSplitSideEffects("LS", "VS");
    }

    @Test
    void sourceOwnVatAsTarget_isOccupiedAndRejected() {
        schedule("sv-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("sv-feed", "LS");
        ResponseEntity<Map> resp = post("/api/lots/split",
                splitBody("sv-split", "LS", 1, "LC1", "VC1", "40", "LC2", "VS", "60"));
        assertEquals(409, resp.getStatusCode().value());
        assertNoSplitSideEffects("LS", "VS");
    }

    @Test
    void duplicateTargets_andNonPositiveWeight_rejected() {
        schedule("dup-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("dup-feed", "LS");

        assertEquals(400, post("/api/lots/split",
                splitBody("dup-1", "LS", 1, "LC1", "VC1", "40", "LC1", "VC2", "60"))
                .getStatusCode().value());
        assertEquals(400, post("/api/lots/split",
                splitBody("dup-2", "LS", 1, "LC1", "VC1", "40", "LC2", "VC1", "60"))
                .getStatusCode().value());
        assertEquals(400, post("/api/lots/split",
                splitBody("dup-3", "LS", 1, "LC1", "VC1", "0", "LC2", "VC2", "100"))
                .getStatusCode().value(), "零重量由入站校验拒绝(400)");
        assertNoSplitSideEffects("LS", "VS");
    }

    @Test
    void targetCountOutOfRange_rejectedByValidation() {
        schedule("tc-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("tc-feed", "LS");
        assertEquals(400, post("/api/lots/split",
                splitBody("tc-1", "LS", 1, "LC1", "VC1", "100")).getStatusCode().value());
        assertEquals(400, post("/api/lots/split",
                splitBody("tc-2", "LS", 1,
                        "L1", "V1", "25", "L2", "V2", "25", "L3", "V3", "25", "L4", "V4", "24",
                        "L5", "V5", "1")).getStatusCode().value());
        assertNoSplitSideEffects("LS", "VS");
    }

    @Test
    void onlyFedLotCanSplit_createdJudgedArchivedRejected() {
        schedule("nf-sched", "LC", "VC", "R-BASE", 1, "100");
        assertEquals(409, post("/api/lots/split",
                splitBody("nf-1", "LC", 1, "A1", "W1", "40", "A2", "W2", "60"))
                .getStatusCode().value());

        schedule("jd-sched", "LJ", "VJ", "R-BASE", 1, "100");
        feed100("jd-feed", "LJ");
        postOk("/api/judgments", Map.of(
                "requestId", "jd-judge", "lotNo", "LJ", "deltaE", new BigDecimal("0.30")));
        assertEquals(409, post("/api/lots/split",
                splitBody("jd-split", "LJ", 1, "J1", "W1", "40", "J2", "W2", "60"))
                .getStatusCode().value());

        postOk("/api/lots/LJ/archive", Map.of());
        assertEquals(409, post("/api/lots/split",
                splitBody("jd-split-2", "LJ", 2, "K1", "W3", "40", "K2", "W4", "60"))
                .getStatusCode().value());
    }

    @Test
    void staleVersion_conflictAndVersionAdvancesOnSuccess() {
        schedule("ver-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("ver-feed", "LS");
        assertEquals(409, post("/api/lots/split",
                splitBody("ver-bad", "LS", 2, "LC1", "VC1", "40", "LC2", "VC2", "60"))
                .getStatusCode().value(), "首次分缸 expectedVersion 必须为 1");
        postOk("/api/lots/split", split4060("ver-split"));
        // 成功后版本为 2；再次拆同源（即便带新版本）也拒绝。
        assertEquals(409, post("/api/lots/split",
                splitBody("ver-again", "LS", 2, "LD1", "VD1", "40", "LD2", "VD2", "60"))
                .getStatusCode().value());
        assertEquals(2, jdbc.queryForObject(
                "SELECT split_version FROM lot WHERE lot_no='LS'", Integer.class));
    }

    @Test
    void idempotency_replaySameParams_conflictDifferentParams_failureDoesNotHoldKey() {
        schedule("id-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("id-feed", "LS");

        // 首次失败（重量不符）：事务回滚不占键。
        assertEquals(422, post("/api/lots/split",
                splitBody("id-split", "LS", 1, "LC1", "VC1", "40", "LC2", "VC2", "59"))
                .getStatusCode().value());
        // 同键换正确参数成功。
        ResponseEntity<Map> first = post("/api/lots/split", split4060("id-split"));
        assertEquals(200, first.getStatusCode().value());

        // 同键同参重放原响应。
        ResponseEntity<Map> replay = post("/api/lots/split", split4060("id-split"));
        assertEquals(200, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(first.getBody(), replay.getBody());

        // 同键异参 409。
        assertEquals(409, post("/api/lots/split",
                splitBody("id-split", "LS", 1, "LC1", "VC1", "60", "LC2", "VC2", "40"))
                .getStatusCode().value());

        assertEquals(Long.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM lot_split", Long.class));
    }

    @Test
    void childCannotRefeed_andSourceCannotJudgeAfterSplit() {
        schedule("cf2-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("cf2-feed", "LS");
        BigDecimal redBefore = stock("D-RED");
        postOk("/api/lots/split", split4060("cf2-split"));

        // 子缸重新首次投料：拒绝，且库存不动。
        ResponseEntity<Map> refeed = post("/api/feedings", Map.of(
                "requestId", "cf2-refeed", "lotNo", "LC1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("600.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("200.0000")))));
        assertEquals(409, refeed.getStatusCode().value());
        assertEquals(0, redBefore.compareTo(stock("D-RED")), "拒绝重投不得再扣染料余量");

        // 来源判色拒绝；子缸可正常分别色检。
        assertEquals(409, post("/api/judgments", Map.of(
                "requestId", "cf2-j-src", "lotNo", "LS", "deltaE", new BigDecimal("0.20")))
                .getStatusCode().value());
        Map<String, Object> j1 = postOk("/api/judgments", Map.of(
                "requestId", "cf2-j1", "lotNo", "LC1", "deltaE", new BigDecimal("0.30")));
        assertEquals("PASS", j1.get("verdict"));
        assertEquals("PASSED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LC1'", String.class));

        // 来源只读不可归档。
        assertEquals(409, rest.postForEntity(url("/api/lots/LS/archive"),
                new org.springframework.http.HttpEntity<>(Map.of(), jsonHeaders()), Map.class)
                .getStatusCode().value());
    }

    @Test
    void controlledSource_splitInheritsFrozenEvidence_andBatchStockUnchanged() {
        scheduleControlled("cs-sched", "LS", "VS", "R-BASE", 1, "100");
        approveSeedCompensation("cs-comp", "LS");
        postOk("/api/feedings", Map.of(
                "requestId", "cs-feed", "lotNo", "LS", "expectedVersion", 1,
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("2000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("1500.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("500.0000")))));
        BigDecimal redBatchBefore = batchAvailable("B-RED-001");

        Map<String, Object> split = postOk("/api/lots/split", split4060("cs-split"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) split.get("children");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) children.get(0).get("weighings")).stream()
                .filter(w -> "D-RED".equals(((Map<?, ?>) w).get("dyeCode"))).findFirst().orElseThrow();
        assertEquals("B-RED-001", first.get("batchNo"), "子耗用继承原批次证据链");

        // 批次余量分缸前后不变（不按效价重算、不重复扣批次）。
        assertEquals(0, redBatchBefore.compareTo(batchAvailable("B-RED-001")));
        // 子缸为受控缸且继承进度；重新受控投料同样拒绝。
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT controlled FROM lot WHERE lot_no='LC1'", Boolean.class));
        ResponseEntity<Map> refeed = post("/api/feedings", Map.of(
                "requestId", "cs-refeed", "lotNo", "LC1", "expectedVersion", 1,
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("600.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("200.0000")))));
        assertEquals(409, refeed.getStatusCode().value());
        assertEquals(0, redBatchBefore.compareTo(batchAvailable("B-RED-001")));
    }

    @Test
    void threeChildrenWithRemainderUnits_conservationHolds() {
        // 33.333/33.333/33.334 拆 100kg 来源，验证非整除比例下截位+循环分配仍守恒。
        schedule("tr-sched", "LS", "VS", "R-BASE", 1, "100");
        feed100("tr-feed", "LS"); // 2000/1500/500
        Map<String, Object> split = postOk("/api/lots/split",
                splitBody("tr-split", "LS", 1,
                        "LC1", "VC1", "33.333", "LC2", "VC2", "33.333", "LC3", "VC3", "33.334"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conservation =
                (List<Map<String, Object>>) split.get("conservation");
        Map<String, Object> red = conservation.stream()
                .filter(c -> "D-RED".equals(c.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("2000.0000").compareTo(bd(red.get("sourceActualG"))));
        assertEquals(0, new BigDecimal("2000.0000").compareTo(bd(red.get("childrenSumG"))));
        conservation.forEach(c -> {
            assertEquals(true, c.get("conserved"));
            assertEquals(0, bd(c.get("childrenSumG")).compareTo(bd(c.get("sourceActualG"))));
        });
    }

    @SuppressWarnings("unchecked")
    private static String usageOf(Map<String, Object> lineage, int seq, String dyeCode) {
        for (Map<String, Object> child : (List<Map<String, Object>>) lineage.get("children")) {
            if (((Number) child.get("seq")).intValue() == seq) {
                for (Map<String, Object> w : (List<Map<String, Object>>) child.get("weighings")) {
                    if (dyeCode.equals(w.get("dyeCode"))) {
                        return bd(w.get("actualG")).toPlainString();
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void assertChild(Map<String, Object> child, int seq, String lotNo, String vatNo,
                             String weight, Map<String, String> expectedUsage) {
        assertEquals(seq, child.get("seq"));
        assertEquals(lotNo, child.get("lotNo"));
        assertEquals(vatNo, child.get("vatNo"));
        assertEquals(0, new BigDecimal(weight).compareTo(bd(child.get("fabricWeightKg"))));
        assertEquals("FED", child.get("status"));
        Map<String, String> actual = new java.util.LinkedHashMap<>();
        for (Map<String, Object> w : (List<Map<String, Object>>) child.get("weighings")) {
            actual.put((String) w.get("dyeCode"), bd(w.get("actualG")).toPlainString());
        }
        expectedUsage.forEach((dye, g) -> assertEquals(0, new BigDecimal(g).compareTo(new BigDecimal(actual.get(dye))),
                dye + " 分摊克重应为 " + g + " 实际 " + actual.get(dye)));
    }

    private void assertNoSplitSideEffects(String source, String sourceVat) {
        assertEquals("FED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no=?", String.class, source));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM vat_active WHERE vat_no=?", Long.class, sourceVat),
                "失败整单回滚：来源仍占原缸");
        assertEquals(Long.valueOf(0),
                jdbc.queryForObject("SELECT COUNT(*) FROM lot_split", Long.class));
        assertEquals(Long.valueOf(0),
                jdbc.queryForObject("SELECT COUNT(*) FROM lot_split_item", Long.class));
        assertEquals(Long.valueOf(0),
                jdbc.queryForObject("SELECT COUNT(*) FROM split_weighing", Long.class));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE split_parent_lot_id IS NOT NULL", Long.class));
    }
}
