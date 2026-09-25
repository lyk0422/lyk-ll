package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * requestId 幂等边界补充：
 * - 投料同键异参 409 且不扣减库存；
 * - 首次业务失败整单回滚（含幂等占位），同键换正确参数可重试成功；
 * - 缸差判定不显式给对比缸时，自动选取同配方最近已判定缸次。
 */
class DyeIdempotencyEdgeTest extends BaseIntegrationTest {

    @Test
    void feed_sameKeyDifferentParams_conflict_andStockNotDeductedAgain() {
        schedule("ie-sched", "LE1", "VE1", "R-BASE", 1, "50");
        BigDecimal redBefore = stock("D-RED");

        feedExact("ie-feed", "LE1");
        assertEquals(0, redBefore.subtract(new BigDecimal("1000")).compareTo(stock("D-RED")));

        // 同键异参（RED 称量改成 999g）：409，库存保持首次扣减后的数量
        ResponseEntity<Map> conflict = post("/api/feedings", Map.of(
                "requestId", "ie-feed", "lotNo", "LE1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("999.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(0, redBefore.subtract(new BigDecimal("1000")).compareTo(stock("D-RED")),
                "409 冲突不得再次扣减库存");
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM feeding WHERE request_id='ie-feed'", Long.class));
        assertEquals(Long.valueOf(3), jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing w JOIN feeding f ON f.id=w.feeding_id "
                        + "WHERE f.request_id='ie-feed'", Long.class));
    }

    @Test
    void feed_firstAttemptRollsBack_sameKeyCanRetryWithCorrectParams() {
        schedule("ie-sched-2", "LE2", "VE2", "R-BASE", 1, "50");
        BigDecimal redBefore = stock("D-RED");

        // 首次偏差超标：业务异常导致事务（含幂等占位行）整体回滚
        ResponseEntity<Map> rejected = post("/api/feedings", Map.of(
                "requestId", "ie-feed-retry", "lotNo", "LE2",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1010.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals(0, redBefore.compareTo(stock("D-RED")));

        // 同键用正确称量重试：成功，且只有一笔投料
        feedExact("ie-feed-retry", "LE2");
        assertEquals(0, redBefore.subtract(new BigDecimal("1000")).compareTo(stock("D-RED")));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM feeding WHERE request_id='ie-feed-retry'", Long.class));
        assertEquals("FED", jdbc.queryForObject("SELECT status FROM lot WHERE lot_no='LE2'", String.class));
    }

    @Test
    void judge_withoutReference_autoPicksLatestJudgedSiblingWithSameRecipe() {
        schedule("ie-a", "LEA", "VEA", "R-BASE", 1, "50");
        schedule("ie-b", "LEB", "VEB", "R-BASE", 1, "50");
        feedExact("ief-a", "LEA");
        feedExact("ief-b", "LEB");

        // 先判定 LEB ΔE=0.30；LEA 不传对比缸，应自动选到 LEB，ΔE=0.35 => 缸差 0.05 合格
        postOk("/api/judgments", Map.of(
                "requestId", "iej-b", "lotNo", "LEB", "deltaE", new BigDecimal("0.30")));
        Map<String, Object> verdict = postOk("/api/judgments", Map.of(
                "requestId", "iej-a", "lotNo", "LEA", "deltaE", new BigDecimal("0.35")));
        assertEquals("PASS", verdict.get("verdict"));
        assertEquals("LEB", verdict.get("referenceLotNo"));
        assertEquals(0, new BigDecimal("0.0500").compareTo(bd(verdict.get("diff"))));
    }
}
