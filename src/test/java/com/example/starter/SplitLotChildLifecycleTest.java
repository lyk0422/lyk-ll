package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 分缸子缸后续规则：分别色检，沿用原合格/回修（仅一次）/降级及归档规则；
 * 子缸判色不影响来源只读状态，来源不参与判定。
 */
class SplitLotChildLifecycleTest extends BaseIntegrationTest {

    private void prepareAndSplit() {
        schedule("lc-sched", "LS", "VS", "R-BASE", 1, "100");
        postOk("/api/feedings", Map.of(
                "requestId", "lc-feed", "lotNo", "LS",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("2000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("1500.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("500.0000")))));
        postOk("/api/lots/split", Map.of(
                "requestId", "lc-split", "sourceLotNo", "LS", "expectedVersion", 1,
                "targets", List.of(
                        Map.of("lotNo", "LC1", "vatNo", "VC1",
                                "fabricWeightKg", new BigDecimal("40")),
                        Map.of("lotNo", "LC2", "vatNo", "VC2",
                                "fabricWeightKg", new BigDecimal("60")))));
    }

    @Test
    void childrenJudgedIndependentlyRepairOnceThenDowngradeAndArchive() {
        prepareAndSplit();

        // LC1：与已判 LC2（ΔE=0.30）缸差 0.70 > 0.5，首次判回修。
        postOk("/api/judgments", Map.of(
                "requestId", "lc-j2", "lotNo", "LC2", "deltaE", new BigDecimal("0.30")));
        Map<String, Object> repair = postOk("/api/judgments", Map.of(
                "requestId", "lc-j1", "lotNo", "LC1", "deltaE", new BigDecimal("1.00")));
        assertEquals("REPAIR", repair.get("verdict"));
        assertEquals("REPAIRING", repair.get("status"));
        assertEquals(1, repair.get("repairCount"));

        // 回修后复测仍超差（与 LC2 差 0.61）→ 降级。
        Map<String, Object> downgrade = postOk("/api/judgments", Map.of(
                "requestId", "lc-j1-r", "lotNo", "LC1", "deltaE", new BigDecimal("0.91")));
        assertEquals("DOWNGRADE", downgrade.get("verdict"));
        assertEquals("DOWNGRADED", downgrade.get("status"));

        // 子缸各自归档；来源保持 SPLIT 只读。
        postOk("/api/lots/LC1/archive", Map.of());
        postOk("/api/lots/LC2/archive", Map.of());
        assertEquals("ARCHIVED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LC1'", String.class));
        assertEquals("ARCHIVED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LC2'", String.class));
        assertEquals("SPLIT", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LS'", String.class));
        // 子缸归档释放各自染缸。
        assertEquals(Long.valueOf(0),
                jdbc.queryForObject("SELECT COUNT(*) FROM vat_active", Long.class));
        // 来源谱系仍可查。
        Map<String, Object> lineage = rest.getForObject(url("/api/lots/LC1/lineage"), Map.class);
        assertEquals("LS", lineage.get("sourceLotNo"));
    }

    @Test
    void childStartsFedWithSplitVersionOne_andNoIndependentFeedingRecord() {
        prepareAndSplit();
        // 子缸自身分缸版本默认为 1（元数据存在），它们以 FED 起步但没有独立投料记录。
        Map<String, Object> child = rest.getForObject(url("/api/lots/LC1"), Map.class);
        assertEquals(1, child.get("splitVersion"));
        assertEquals("FED", child.get("status"));
        assertEquals(null, child.get("weighings"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inherited = (List<Map<String, Object>>) child.get("inheritedWeighings");
        assertEquals(3, inherited.size());
    }
}
