package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 分缸主流程与失败分支（H2 事务边界真实验证）：
 * 耗用按子缸布重比例分摊且合计守恒、来源转 SPLIT 只读并释放原缸、
 * 目标占用/重复/重量不符整单回滚、同源最多分缸一次、子缸不得重复投料扣批次。
 */
class LotSplitFlowTest extends BaseIntegrationTest {

    /** 排缸并投料 100kg（R-BASE：RED 2000g / YELLOW 1500g / BLUE 500g）。 */
    private void scheduleAndFeed100(String lotNo, String vatNo) {
        schedule("sch-" + lotNo, lotNo, vatNo, "R-BASE", 1, "100");
        postOk("/api/feedings", Map.of(
                "requestId", "feed-" + lotNo,
                "lotNo", lotNo,
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("2000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("1500.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("500.0000")))));
    }

    private Map<String, Object> splitBody(String requestId, String sourceLotNo, int expectedVersion,
                                          List<Object[]> children) {
        return Map.of(
                "requestId", requestId,
                "sourceLotNo", sourceLotNo,
                "expectedVersion", expectedVersion,
                "children", children.stream().map(c -> Map.of(
                        "lotNo", c[0],
                        "vatNo", c[1],
                        "fabricWeightKg", new BigDecimal((String) c[2]))).toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> childOf(Map<String, Object> body, String lotNo) {
        return ((List<Map<String, Object>>) body.get("children")).stream()
                .filter(c -> lotNo.equals(c.get("lotNo"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private BigDecimal usageOf(Map<String, Object> child, String dyeCode) {
        return ((List<Map<String, Object>>) child.get("usages")).stream()
                .filter(u -> dyeCode.equals(u.get("dyeCode"))).findFirst()
                .map(u -> bd(u.get("allocatedG"))).orElseThrow();
    }

    private BigDecimal allocatedSum(String dyeCode) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(allocated_g),0) FROM lot_split_usage WHERE dye_code = ?",
                BigDecimal.class, dyeCode);
    }

    @Test
    void split_100kgInto40And60_conservesUsageAndReleasesSourceVat() {
        scheduleAndFeed100("L100", "V1");
        BigDecimal redStockAfterFeed = stock("D-RED");

        Map<String, Object> body = postOk("/api/lots/split", splitBody("split-1", "L100", 1,
                List.of(new Object[] {"L100A", "V2", "40"}, new Object[] {"L100B", "V3", "60"})));

        assertEquals("SPLIT", body.get("sourceStatus"));
        assertEquals(1, ((Number) body.get("splitVersion")).intValue());
        assertNotNull(body.get("splitAt"), "UTC 留痕");

        Map<String, Object> childA = childOf(body, "L100A");
        Map<String, Object> childB = childOf(body, "L100B");
        assertEquals("FED", childA.get("status"), "子缸继承已投料进度");
        assertEquals(0, new BigDecimal("800").compareTo(usageOf(childA, "D-RED")));
        assertEquals(0, new BigDecimal("1200").compareTo(usageOf(childB, "D-RED")));
        assertEquals(0, new BigDecimal("600").compareTo(usageOf(childA, "D-YELLOW")));
        assertEquals(0, new BigDecimal("900").compareTo(usageOf(childB, "D-YELLOW")));
        assertEquals(0, new BigDecimal("200").compareTo(usageOf(childA, "D-BLUE")));
        assertEquals(0, new BigDecimal("300").compareTo(usageOf(childB, "D-BLUE")));

        // 耗用守恒：各染料子耗用合计 = 来源实际耗用；库存不因分缸再扣。
        assertEquals(0, new BigDecimal("2000.0000").compareTo(allocatedSum("D-RED")));
        assertEquals(0, new BigDecimal("1500.0000").compareTo(allocatedSum("D-YELLOW")));
        assertEquals(0, new BigDecimal("500.0000").compareTo(allocatedSum("D-BLUE")));
        assertEquals(0, redStockAfterFeed.compareTo(stock("D-RED")), "分缸不得再扣库存");

        // 来源只读并释放原缸；目标占用同事务建立。
        assertEquals("SPLIT", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='L100'", String.class));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM vat_active WHERE vat_no='V1'", Long.class), "原缸已释放");
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM vat_active WHERE vat_no='V2'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM vat_active WHERE vat_no='V3'", Long.class));
        assertEquals("FED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='L100A'", String.class));
        assertEquals(0, new BigDecimal("40.000").compareTo(jdbc.queryForObject(
                "SELECT fabric_weight_kg FROM lot WHERE lot_no='L100A'", BigDecimal.class)));

        // 原称量证据链不覆盖：来源称量仍在。
        assertEquals(Long.valueOf(3), jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing w JOIN feeding f ON f.id=w.feeding_id "
                        + "JOIN lot l ON l.id=f.lot_id WHERE l.lot_no='L100'", Long.class));

        // 谱系：来源查子缸，子缸查来源。
        Map<String, Object> lineage = postOkLineage("L100");
        assertEquals("SOURCE", lineage.get("role"));
        assertEquals(2, ((List<?>) lineage.get("children")).size());
        Map<String, Object> childLineage = postOkLineage("L100A");
        assertEquals("CHILD", childLineage.get("role"));
        assertEquals("L100", childLineage.get("splitFromLotNo"));
        assertEquals(0, new BigDecimal("800").compareTo(
                bd(((List<Map<String, Object>>) childLineage.get("usages")).stream()
                        .filter(u -> "D-RED".equals(u.get("dyeCode"))).findFirst().orElseThrow()
                        .get("allocatedG"))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postOkLineage(String lotNo) {
        ResponseEntity<Map> resp = rest.getForEntity(url("/api/lots/" + lotNo + "/split"), Map.class);
        assertEquals(200, resp.getStatusCode().value(), "谱系查询应成功: " + resp.getBody());
        return resp.getBody();
    }

    @Test
    void split_minimalUnitAllocation_remainderRoundRobinByAscendingLotNo() {
        // RED 实际 2000.0003g：40/60 向下截取后余 2 个 0.0001g 单位，按缸号升序各补 1。
        schedule("sch-ls", "LS", "VS", "R-BASE", 1, "100");
        postOk("/api/feedings", Map.of(
                "requestId", "feed-ls",
                "lotNo", "LS",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("2000.0003")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("1500.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("500.0000")))));

        Map<String, Object> body = postOk("/api/lots/split", splitBody("split-ls", "LS", 1,
                List.of(new Object[] {"LS-B", "VB", "60"}, new Object[] {"LS-A", "VA", "40"})));

        Map<String, Object> childA = childOf(body, "LS-A");
        Map<String, Object> childB = childOf(body, "LS-B");
        // 精确比例：A=800.00012→800.0001，B=1200.00018→1200.0001，余 1 单位给缸号较小的 LS-A。
        assertEquals(0, new BigDecimal("800.0002").compareTo(usageOf(childA, "D-RED")));
        assertEquals(0, new BigDecimal("1200.0001").compareTo(usageOf(childB, "D-RED")));
        assertEquals(0, new BigDecimal("2000.0003").compareTo(allocatedSum("D-RED")),
                "最小单位分摊后合计必须等于来源实际耗用");
    }

    @Test
    void split_rejectsSourceNotFedJudgedOrAlreadySplit() {
        // 未投料
        schedule("sch-c", "LC", "VC", "R-BASE", 1, "100");
        ResponseEntity<Map> notFed = post("/api/lots/split", splitBody("sp-c", "LC", 1,
                List.of(new Object[] {"LC-A", "VCA", "40"}, new Object[] {"LC-B", "VCB", "60"})));
        assertEquals(409, notFed.getStatusCode().value());

        // 已色判
        scheduleAndFeed100("LJ", "VJ");
        postOk("/api/judgments", Map.of("requestId", "jdg-lj", "lotNo", "LJ",
                "deltaE", new BigDecimal("0.30")));
        ResponseEntity<Map> judged = post("/api/lots/split", splitBody("sp-j", "LJ", 1,
                List.of(new Object[] {"LJ-A", "VJA", "40"}, new Object[] {"LJ-B", "VJB", "60"})));
        assertEquals(409, judged.getStatusCode().value(), "判色先成则分缸拒绝");

        // 先分缸则来源判色拒绝；同源第二次分缸也拒绝
        scheduleAndFeed100("LS2", "VS2");
        postOk("/api/lots/split", splitBody("sp-s2", "LS2", 1,
                List.of(new Object[] {"LS2-A", "VA2", "40"}, new Object[] {"LS2-B", "VB2", "60"})));
        ResponseEntity<Map> judgeAfter = post("/api/judgments", Map.of(
                "requestId", "jdg-s2", "lotNo", "LS2", "deltaE", new BigDecimal("0.30")));
        assertEquals(409, judgeAfter.getStatusCode().value(), "分缸先成则来源判色拒绝");
        ResponseEntity<Map> secondSplit = post("/api/lots/split", splitBody("sp-s2-again", "LS2", 1,
                List.of(new Object[] {"LS2-C", "VC2", "50"}, new Object[] {"LS2-D", "VD2", "50"})));
        assertEquals(409, secondSplit.getStatusCode().value(), "两次拆同源最多一次成功");
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_split s JOIN lot l ON l.id=s.source_lot_id "
                        + "WHERE l.lot_no='LS2'", Long.class));

        // 已分缸来源不得归档，仅查谱系
        ResponseEntity<Map> archive = post("/api/lots/LS2/archive", Map.of());
        assertEquals(409, archive.getStatusCode().value());
    }

    @Test
    void split_validationFailures_rollbackWholeOrder() {
        scheduleAndFeed100("LV", "VV");

        // 子缸数量越界
        assertEquals(400, post("/api/lots/split", splitBody("sv-1", "LV", 1,
                List.<Object[]>of(new Object[] {"LV-A", "V1A", "100"}))).getStatusCode().value());
        assertEquals(400, post("/api/lots/split", splitBody("sv-5", "LV", 1,
                List.of(new Object[] {"LV-A", "V1A", "20"}, new Object[] {"LV-B", "V1B", "20"},
                        new Object[] {"LV-C", "V1C", "20"}, new Object[] {"LV-D", "V1D", "20"},
                        new Object[] {"LV-E", "V1E", "20"}))).getStatusCode().value());
        // 合计不等于来源布重
        assertEquals(400, post("/api/lots/split", splitBody("sv-sum", "LV", 1,
                List.of(new Object[] {"LV-A", "V1A", "40"}, new Object[] {"LV-B", "V1B", "50"})))
                .getStatusCode().value());
        // 超过三位小数
        assertEquals(400, post("/api/lots/split", splitBody("sv-scale", "LV", 1,
                List.of(new Object[] {"LV-A", "V1A", "40.0005"}, new Object[] {"LV-B", "V1B", "59.9995"})))
                .getStatusCode().value());
        // 子缸号重复 / 目标缸重复 / 子缸号同来源
        assertEquals(400, post("/api/lots/split", splitBody("sv-duplot", "LV", 1,
                List.of(new Object[] {"LV-A", "V1A", "40"}, new Object[] {"LV-A", "V1B", "60"})))
                .getStatusCode().value());
        assertEquals(400, post("/api/lots/split", splitBody("sv-dupvat", "LV", 1,
                List.of(new Object[] {"LV-A", "V1A", "40"}, new Object[] {"LV-B", "V1A", "60"})))
                .getStatusCode().value());
        assertEquals(400, post("/api/lots/split", splitBody("sv-self", "LV", 1,
                List.of(new Object[] {"LV", "V1A", "40"}, new Object[] {"LV-B", "V1B", "60"})))
                .getStatusCode().value());
        // 目标缸被占用（来源原缸 VV 尚未释放）
        assertEquals(409, post("/api/lots/split", splitBody("sv-occ", "LV", 1,
                List.of(new Object[] {"LV-A", "VV", "40"}, new Object[] {"LV-B", "V1B", "60"})))
                .getStatusCode().value());
        // 版本不符
        assertEquals(409, post("/api/lots/split", splitBody("sv-ver", "LV", 2,
                List.of(new Object[] {"LV-A", "V1A", "40"}, new Object[] {"LV-B", "V1B", "60"})))
                .getStatusCode().value());

        // 全部失败整单回滚：来源仍 FED、原缸占用不变、无子缸、无分缸记录、失败不占幂等键
        assertEquals("FED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LV'", String.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM vat_active WHERE vat_no='VV'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE lot_no LIKE 'LV%'", Long.class), "不得残留子缸");
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_split", Long.class));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE operation='SPLIT'", Long.class),
                "失败不占键");
    }

    @Test
    void split_occupiedTargetVatByOtherLot_rejected() {
        scheduleAndFeed100("LO", "VO");
        schedule("sch-other", "LOTHER", "VOTHER", "R-BASE", 1, "50");
        ResponseEntity<Map> resp = post("/api/lots/split", splitBody("sp-occ", "LO", 1,
                List.of(new Object[] {"LO-A", "VOTHER", "40"}, new Object[] {"LO-B", "VOB", "60"})));
        assertEquals(409, resp.getStatusCode().value(), "任一目标占用整单拒绝");
        assertEquals("FED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LO'", String.class));
    }

    @Test
    void split_idempotency_replaySameParams_conflictOnDifferent() {
        scheduleAndFeed100("LI", "VI");
        Map<String, Object> request = splitBody("sp-idem", "LI", 1,
                List.of(new Object[] {"LI-A", "VIA", "40"}, new Object[] {"LI-B", "VIB", "60"}));

        ResponseEntity<Map> first = post("/api/lots/split", request);
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<Map> replay = post("/api/lots/split", request);
        assertEquals(201, replay.getStatusCode().value(), "同参成功先重放原响应");
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(first.getBody().get("splitAt"), replay.getBody().get("splitAt"));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_split", Long.class), "重放不得重复建单");
        assertEquals(Long.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE lot_no LIKE 'LI-%'", Long.class), "子缸只建一次");

        // 同键异参 → 409，且不产生新业务动作
        ResponseEntity<Map> conflict = post("/api/lots/split", splitBody("sp-idem", "LI", 1,
                List.of(new Object[] {"LI-A", "VIA", "50"}, new Object[] {"LI-B", "VIB", "50"})));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_split", Long.class));
    }

    @Test
    void split_controlledLot_childCannotRefeedAndBatchNotDeductedAgain() {
        // 受控缸 50kg：补偿目标 RED 1000 / YELLOW 750 / BLUE 250（种子批次系数 1.0）
        scheduleControlled("sch-ctl", "LCTL", "VCTL", "R-BASE", 1, "50");
        approveSeedCompensation("cmp-ctl", "LCTL");
        feedControlledExact("feed-ctl", "LCTL", 1);
        BigDecimal redBatchAfterFeed = batchAvailable("B-RED-001");
        assertEquals(0, new BigDecimal("99000.000").compareTo(redBatchAfterFeed));

        Map<String, Object> body = postOk("/api/lots/split", splitBody("sp-ctl", "LCTL", 1,
                List.of(new Object[] {"LCTL-A", "VCTLA", "20"}, new Object[] {"LCTL-B", "VCTLB", "30"})));
        assertEquals(0, new BigDecimal("400").compareTo(usageOf(childOf(body, "LCTL-A"), "D-RED")));
        assertEquals(0, new BigDecimal("600").compareTo(usageOf(childOf(body, "LCTL-B"), "D-RED")));
        assertEquals(0, redBatchAfterFeed.compareTo(batchAvailable("B-RED-001")),
                "分缸保留原批次库存不变");

        // 子缸不得重新调用首次投料重复扣批次余量
        ResponseEntity<Map> refeed = post("/api/feedings", Map.of(
                "requestId", "feed-ctl-a",
                "lotNo", "LCTL-A",
                "expectedVersion", 2,
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("400.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("300.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("100.0000")))));
        assertEquals(409, refeed.getStatusCode().value(), "子缸继承耗用不得再扣一次");
        assertEquals(0, redBatchAfterFeed.compareTo(batchAvailable("B-RED-001")), "重扣被拒绝后余量不变");

        // 子缸分别色检并按原规则归档
        Map<String, Object> judgeA = postOk("/api/judgments", Map.of(
                "requestId", "jdg-ctl-a", "lotNo", "LCTL-A", "deltaE", new BigDecimal("0.40")));
        assertEquals("PASS", judgeA.get("verdict"));
        Map<String, Object> archiveA = postOk("/api/lots/LCTL-A/archive", Map.of());
        assertEquals("ARCHIVED", archiveA.get("status"));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM vat_active WHERE vat_no='VCTLA'", Long.class), "子缸归档释放染缸");

        // 来源补偿版本与称量冻结不追改
        Map<String, Object> comp = compensationRow("LCTL");
        assertEquals("FED", comp.get("status"));
        assertEquals(2, ((Number) comp.get("version")).intValue(), "补偿版本保持投料后版本，分缸不追改");
    }

    @Test
    void split_lineageOfUnsplitLot_404() {
        schedule("sch-n", "LN", "VN", "R-BASE", 1, "50");
        ResponseEntity<Map> resp = rest.getForEntity(url("/api/lots/LN/split"), Map.class);
        assertEquals(404, resp.getStatusCode().value());
        ResponseEntity<Map> missing = rest.getForEntity(url("/api/lots/NOPE/split"), Map.class);
        assertEquals(404, missing.getStatusCode().value());
        assertNull(missing.getBody().get("splitVersion"));
        assertTrue(String.valueOf(missing.getBody().get("message")).contains("不存在"));
    }
}
