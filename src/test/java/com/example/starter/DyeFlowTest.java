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
 * 主流程与失败分支：对色阈值、放大称量、缸差回修/降级、并缸、配方冻结版本、归档终态、复算。
 */
class DyeFlowTest extends BaseIntegrationTest {

    // ------------------------------------------------------------------
    // 对色：恰 0.8 合格，0.8 以上不放行；requestId 幂等与 409
    // ------------------------------------------------------------------

    @Test
    void colorCheck_exactlyZeroPointEight_passes() {
        Map<String, Object> body = postOk("/api/color-checks", Map.of(
                "requestId", "cc-1", "sampleId", "S-1", "deltaE", new BigDecimal("0.8")));
        assertEquals(true, body.get("passed"));
        assertEquals(0, new BigDecimal("0.8").compareTo(bd(body.get("limit"))));
    }

    @Test
    void colorCheck_aboveLimit_rejected_andIdempotentConflictHasNoSideEffect() {
        ResponseEntity<Map> first = post("/api/color-checks", Map.of(
                "requestId", "cc-2", "sampleId", "S-2", "deltaE", new BigDecimal("0.81")));
        assertEquals(200, first.getStatusCode().value());
        assertEquals(false, first.getBody().get("passed"));

        // 同键同参重放：返回首次结果
        ResponseEntity<Map> replay = post("/api/color-checks", Map.of(
                "requestId", "cc-2", "sampleId", "S-2", "deltaE", new BigDecimal("0.81")));
        assertEquals(200, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM color_check WHERE request_id = 'cc-2'", Long.class));

        // 同键异参：409，且不新增对色记录
        ResponseEntity<Map> conflict = post("/api/color-checks", Map.of(
                "requestId", "cc-2", "sampleId", "S-2-CHANGED", "deltaE", new BigDecimal("0.20")));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM color_check WHERE request_id = 'cc-2'", Long.class));
    }

    // ------------------------------------------------------------------
    // 放大配方与称量：恰 0.5% 合法；超 0.5% 拒绝且不扣库存；原子扣减与重放
    // ------------------------------------------------------------------

    @Test
    void feed_scalesRecipe_deviationExactlyHalfPercentAllowed_andDeductsStockAtomically() {
        schedule("sched-1", "L1", "V1", "R-BASE", 1, "50");
        BigDecimal redBefore = stock("D-RED");

        // RED 目标 1000g，实际 1005g => 偏差恰 0.5%，合法
        Map<String, Object> body = postOk("/api/feedings", Map.of(
                "requestId", "feed-1", "lotNo", "L1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1005.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("weighings");
        Map<String, Object> red = rows.stream().filter(r -> "D-RED".equals(r.get("dyeCode"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.500000").compareTo(bd(red.get("deviationPct"))));
        assertEquals(0, redBefore.subtract(new BigDecimal("1005")).compareTo(stock("D-RED")));
        assertEquals(0, new BigDecimal("4250.000").compareTo(stock("D-YELLOW")));
        assertEquals("FED", body.get("status"));

        // 同键重放：不再扣减库存
        ResponseEntity<Map> replay = post("/api/feedings", Map.of(
                "requestId", "feed-1", "lotNo", "L1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1005.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
        assertEquals(200, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
        assertEquals(0, redBefore.subtract(new BigDecimal("1005")).compareTo(stock("D-RED")));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM feeding WHERE lot_id = (SELECT id FROM lot WHERE lot_no='L1')", Long.class));
    }

    @Test
    void feed_deviationAboveHalfPercent_rejectedWholeOrder_stockUnchanged_lotStaysCreated() {
        schedule("sched-2", "L2", "V2", "R-BASE", 1, "50");
        BigDecimal redBefore = stock("D-RED");
        BigDecimal yellowBefore = stock("D-YELLOW");

        ResponseEntity<Map> resp = post("/api/feedings", Map.of(
                "requestId", "feed-2", "lotNo", "L2",
                "weighings", List.of(
                        // 1005.1g => 偏差 0.51% > 0.5%
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1005.1000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
        assertEquals(422, resp.getStatusCode().value());

        // 整单回滚：所有染化料库存不变，无投料/称量落库，缸次仍可重新投料
        assertEquals(0, redBefore.compareTo(stock("D-RED")));
        assertEquals(0, yellowBefore.compareTo(stock("D-YELLOW")));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM feeding f JOIN lot l ON l.id=f.lot_id WHERE l.lot_no='L2'", Long.class));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='L2'", String.class));

        feedExact("feed-2-retry", "L2");
        assertEquals(0, redBefore.subtract(new BigDecimal("1000")).compareTo(stock("D-RED")));
    }

    @Test
    void feed_insufficientStock_rejectedWithoutPartialDeduction() {
        schedule("sched-3", "L3", "V3", "R-BASE", 1, "50");
        jdbc.update("UPDATE dye SET stock_g = 100 WHERE code = 'D-BLUE'");
        BigDecimal redBefore = stock("D-RED");

        ResponseEntity<Map> resp = post("/api/feedings", Map.of(
                "requestId", "feed-3", "lotNo", "L3",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
        assertEquals(409, resp.getStatusCode().value());
        assertEquals(0, redBefore.compareTo(stock("D-RED")), "库存不足时不得有部分扣减");
        assertEquals(new BigDecimal("100.000"), stock("D-BLUE"));
    }

    // ------------------------------------------------------------------
    // 缸差：恰 0.5 合格；超 0.5 回修一次；回修后仍超差降级；归档终态
    // ------------------------------------------------------------------

    @Test
    void lotDifference_exactlyHalf_passes() {
        schedule("s-4a", "LA", "VA", "R-BASE", 1, "50");
        schedule("s-4b", "LB", "VB", "R-BASE", 1, "50");
        feedExact("f-4a", "LA");
        feedExact("f-4b", "LB");

        postOk("/api/judgments", Map.of(
                "requestId", "j-4b-0", "lotNo", "LB", "deltaE", new BigDecimal("0.30")));
        Map<String, Object> verdict = postOk("/api/judgments", Map.of(
                "requestId", "j-4a-0", "lotNo", "LA", "deltaE", new BigDecimal("0.80"),
                "referenceLotNo", "LB"));
        assertEquals("PASS", verdict.get("verdict"));
        assertEquals(0, new BigDecimal("0.5000").compareTo(bd(verdict.get("diff"))));
    }

    @Test
    void lotDifference_overLimit_repairsOnce_thenStillOver_downgrades_andArchiveIsTerminal() {
        schedule("s-5a", "L5A", "V5A", "R-BASE", 1, "50");
        schedule("s-5b", "L5B", "V5B", "R-BASE", 1, "50");
        feedExact("f-5a", "L5A");
        feedExact("f-5b", "L5B");

        postOk("/api/judgments", Map.of(
                "requestId", "j-5b-0", "lotNo", "L5B", "deltaE", new BigDecimal("0.30")));

        // 首次缸差 0.6 > 0.5 => REPAIR，仅一次
        Map<String, Object> first = postOk("/api/judgments", Map.of(
                "requestId", "j-5a-0", "lotNo", "L5A", "deltaE", new BigDecimal("0.90"),
                "referenceLotNo", "L5B"));
        assertEquals("REPAIR", first.get("verdict"));
        assertEquals("REPAIRING", first.get("status"));
        assertEquals(1, first.get("repairCount"));

        // 回修后复测仍超差 => DOWNGRADE
        Map<String, Object> second = postOk("/api/judgments", Map.of(
                "requestId", "j-5a-1", "lotNo", "L5A", "deltaE", new BigDecimal("0.95"),
                "referenceLotNo", "L5B"));
        assertEquals("DOWNGRADE", second.get("verdict"));
        assertEquals("DOWNGRADED", second.get("status"));
        assertEquals(1, second.get("repairCount"), "只允许回修一次");

        // 判定 requestId 幂等：重放返回首次 REPAIR 结果，不新增台账
        ResponseEntity<Map> replay = post("/api/judgments", Map.of(
                "requestId", "j-5a-0", "lotNo", "L5A", "deltaE", new BigDecimal("0.90"),
                "referenceLotNo", "L5B"));
        assertEquals(200, replay.getStatusCode().value());
        assertEquals("REPAIR", replay.getBody().get("verdict"));
        assertEquals(Long.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_judgment WHERE lot_id=(SELECT id FROM lot WHERE lot_no='L5A')",
                Long.class));

        // 归档终态
        Map<String, Object> archived = postOk("/api/lots/L5A/archive", Map.of());
        assertEquals("ARCHIVED", archived.get("status"));
        assertEquals(true, archived.get("terminal"));

        // 重复归档幂等成功
        ResponseEntity<Map> archiveAgain = post("/api/lots/L5A/archive", Map.of());
        assertEquals(200, archiveAgain.getStatusCode().value());
        assertEquals(true, archiveAgain.getBody().get("replayed"));

        // 归档后迟到测色：只记迟到事件，不改判
        Map<String, Object> late = postOk("/api/lots/L5A/late-color", Map.of(
                "deltaE", new BigDecimal("0.01"), "note", "归档后仪器补传"));
        assertEquals(true, late.get("recordedOnly"));
        assertEquals("ARCHIVED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='L5A'", String.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_color_event WHERE lot_id=(SELECT id FROM lot WHERE lot_no='L5A')",
                Long.class));

        // 归档后再来判定：冲突
        ResponseEntity<Map> afterArchive = post("/api/judgments", Map.of(
                "requestId", "j-5a-x", "lotNo", "L5A", "deltaE", new BigDecimal("0.10")));
        assertEquals(409, afterArchive.getStatusCode().value());

        // 未判定缸次不得归档
        schedule("s-5c", "L5C", "V5C", "R-BASE", 1, "50");
        ResponseEntity<Map> earlyArchive = post("/api/lots/L5C/archive", Map.of());
        assertEquals(409, earlyArchive.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 并缸：同配方同浴比放行，不同配方拒绝
    // ------------------------------------------------------------------

    @Test
    void merge_sameRecipeSameRatio_allowed_andReleasesVat() {
        schedule("m-1a", "MA", "VM1", "R-BASE", 1, "50");
        schedule("m-1b", "MB", "VM2", "R-BASE", 1, "50");

        Map<String, Object> merged = postOk("/api/lots/merge", Map.of(
                "requestId", "merge-1", "lotNo", "MA", "targetLotNo", "MB"));
        assertEquals("MERGED", merged.get("status"));
        assertEquals(0, new BigDecimal("100.000").compareTo(bd(merged.get("combinedFabricWeightKg"))));
        assertEquals("MERGED", jdbc.queryForObject("SELECT status FROM lot WHERE lot_no='MA'", String.class));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM vat_active WHERE vat_no='VM1'", Long.class), "被并缸释放染缸");

        // 释放出的染缸可立即排新缸
        Map<String, Object> rescheduled = schedule("m-1c", "MC", "VM1", "R-BASE", 1, "20");
        assertNotNull(rescheduled.get("lotNo"));

        // 并缸幂等重放
        ResponseEntity<Map> replay = post("/api/lots/merge", Map.of(
                "requestId", "merge-1", "lotNo", "MA", "targetLotNo", "MB"));
        assertEquals(200, replay.getStatusCode().value());
        assertEquals("true", replay.getHeaders().getFirst("X-Idempotent-Replayed"));
    }

    @Test
    void merge_differentRecipe_rejected() {
        createPublishedRecipe("R-OTHER", new BigDecimal("8.00"),
                List.of(List.of("D-RED", new BigDecimal("1.0000"))));
        schedule("m-2a", "M2A", "VM3", "R-BASE", 1, "50");
        schedule("m-2b", "M2B", "VM4", "R-OTHER", 1, "50");

        ResponseEntity<Map> resp = post("/api/lots/merge", Map.of(
                "requestId", "merge-2", "lotNo", "M2A", "targetLotNo", "M2B"));
        assertEquals(409, resp.getStatusCode().value());
        assertEquals("CREATED", jdbc.queryForObject("SELECT status FROM lot WHERE lot_no='M2A'", String.class));
    }

    @Test
    void merge_sameRecipeDifferentRatio_rejected() {
        // R-BASE v2 浴比 12，同配方不同浴比
        rest.postForEntity(url("/api/recipes/R-BASE/revise"),
                new org.springframework.http.HttpEntity<>(Map.of(
                        "recipeCode", "R-BASE",
                        "liquorRatio", new BigDecimal("12.00"),
                        "items", List.of(
                                Map.of("dyeCode", "D-RED", "dosageGpl", new BigDecimal("2.0000")),
                                Map.of("dyeCode", "D-YELLOW", "dosageGpl", new BigDecimal("1.5000")),
                                Map.of("dyeCode", "D-BLUE", "dosageGpl", new BigDecimal("0.5000")))),
                jsonHeaders()), Map.class);
        postOk("/api/recipes/R-BASE/publish", Map.of());

        schedule("m-3a", "M3A", "VM5", "R-BASE", 1, "50");
        schedule("m-3b", "M3B", "VM6", "R-BASE", 2, "50");
        ResponseEntity<Map> resp = post("/api/lots/merge", Map.of(
                "requestId", "merge-3", "lotNo", "M3A", "targetLotNo", "M3B"));
        assertEquals(409, resp.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 配方发布冻结与新版本
    // ------------------------------------------------------------------

    @Test
    void recipe_publishFreezes_reviseCreatesNewVersion_oldVersionPreserved() {
        createPublishedRecipe("R-NEW", new BigDecimal("10.00"),
                List.of(List.of("D-RED", new BigDecimal("2.0000"))));

        // 修改已发布配方 => 新版本草稿，旧版本保留
        Map<String, Object> revised = postOk("/api/recipes/R-NEW/revise", Map.of(
                "recipeCode", "R-NEW",
                "liquorRatio", new BigDecimal("12.00"),
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "dosageGpl", new BigDecimal("2.2000")),
                        Map.of("dyeCode", "D-BLUE", "dosageGpl", new BigDecimal("0.3000")))));
        assertEquals(2, revised.get("version"));
        assertEquals("DRAFT", revised.get("status"));

        // 旧版本仍为 PUBLISHED 且内容不变
        Map<String, Object> old = jdbc.queryForMap(
                "SELECT status, liquor_ratio FROM recipe WHERE recipe_code='R-NEW' AND version=1");
        assertEquals("PUBLISHED", old.get("status"));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) old.get("liquor_ratio")));

        // 草稿版本禁止排缸
        ResponseEntity<Map> draftSchedule = post("/api/lots", Map.of(
                "requestId", "sc-draft", "lotNo", "LD", "vatNo", "VD",
                "recipeCode", "R-NEW", "recipeVersion", 2, "fabricWeightKg", new BigDecimal("50")));
        assertEquals(409, draftSchedule.getStatusCode().value());

        postOk("/api/recipes/R-NEW/publish", Map.of());

        // v1 缸次冻结在 v1，配方再改版也不影响
        schedule("sc-v1", "LV1", "VV1", "R-NEW", 1, "50");
        schedule("sc-v2", "LV2", "VV2", "R-NEW", 2, "50");
        assertEquals(1, jdbc.queryForObject(
                "SELECT recipe_version FROM lot WHERE lot_no='LV1'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT recipe_version FROM lot WHERE lot_no='LV2'", Integer.class));

        // 已发布再次发布 => 409（冻结不可改）
        ResponseEntity<Map> republish = post("/api/recipes/R-NEW/publish", Map.of());
        assertEquals(409, republish.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 排缸幂等与染缸唯一占用
    // ------------------------------------------------------------------

    @Test
    void schedule_sameKeyDifferentParams_conflictAndNoSideEffect() {
        postOk("/api/lots", Map.of(
                "requestId", "sc-6", "lotNo", "L6", "vatNo", "V6",
                "recipeCode", "R-BASE", "recipeVersion", 1, "fabricWeightKg", new BigDecimal("50")));

        ResponseEntity<Map> conflict = post("/api/lots", Map.of(
                "requestId", "sc-6", "lotNo", "L6-X", "vatNo", "V6-X",
                "recipeCode", "R-BASE", "recipeVersion", 1, "fabricWeightKg", new BigDecimal("50")));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM lot", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM vat_active", Long.class));
    }

    @Test
    void schedule_draftOrMissingRecipe_rejected() {
        ResponseEntity<Map> missing = post("/api/lots", Map.of(
                "requestId", "sc-7", "lotNo", "L7", "vatNo", "V7",
                "recipeCode", "R-NOPE", "recipeVersion", 1, "fabricWeightKg", new BigDecimal("50")));
        assertEquals(404, missing.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 复算：配方版本、称量链、缸差台账一致
    // ------------------------------------------------------------------

    @Test
    void recalculate_versionsWeighingsAndJudgments_areConsistent() {
        schedule("rc-a", "RCA", "RVCA", "R-BASE", 1, "50");
        schedule("rc-b", "RCB", "RVCB", "R-BASE", 1, "50");
        feedExact("rf-a", "RCA");
        feedExact("rf-b", "RCB");
        postOk("/api/judgments", Map.of(
                "requestId", "rj-b", "lotNo", "RCB", "deltaE", new BigDecimal("0.30")));
        postOk("/api/judgments", Map.of(
                "requestId", "rj-a", "lotNo", "RCA", "deltaE", new BigDecimal("0.40"),
                "referenceLotNo", "RCB"));

        Map<String, Object> report = rest.getForObject(url("/api/recipes/R-BASE/recalculate"), Map.class);
        assertEquals(true, report.get("consistent"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) report.get("versions");
        @SuppressWarnings("unchecked")
        List<?> v1Items = (List<?>) versions.get(0).get("items");
        assertEquals(3, v1Items.size(), "R-BASE v1 含 3 个染化料项");
        assertTrue(((Number) report.get("weighingChecked")).intValue() >= 6);
        assertTrue(((Number) report.get("judgmentChecked")).intValue() >= 2);
        assertEquals(0, ((List<?>) report.get("weighingMismatches")).size());
        assertEquals(0, ((List<?>) report.get("judgmentMismatches")).size());
    }
}
