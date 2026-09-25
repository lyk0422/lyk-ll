package com.example.starter.metro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 进出闸-计费-清分-日结主流程集成测试（H2 内存库，MySQL 兼容模式）。
 * 合成线网：L1: S1-S2(3)-S3(4)-S4(3)-S5(2)；L2: S3-T1(5)-T2(4)-T3(3)，S3 换乘。
 */
class MetroFlowTest extends MetroTestBase {

    @Test
    void exactFiveKmBlockChargedAsFullBlock() throws Exception {
        // S2 -> S5 最短里程 9 公里（4+5，恰满 5 公里按加价档计）-> 3 元
        entry("r-e1", "tx-e1", "TK100", "S2", "2026-09-25T08:00:00");
        JsonNode trip = exit("r-x1", "tx-x1", "TK100", "S5", "2026-09-25T08:30:00");
        assertEquals(9, trip.get("distanceKm").asInt());
        assertEquals(300L, trip.get("fareFen").asLong());
        assertEquals("SETTLED", trip.get("status").asText());
        assertFalse(trip.get("overtime").asBoolean());
    }

    @Test
    void transferWithinExactlyThirtyMinutesChainsBaseFare() throws Exception {
        // 第一段 S1 -> S3：7 公里，3 元
        entry("r-e2", "tx-e2", "TK200", "S1", "2026-09-25T08:00:00");
        JsonNode first = exit("r-x2", "tx-x2", "TK200", "S3", "2026-09-25T08:30:00");
        assertEquals(300L, first.get("fareFen").asLong());
        // 恰 30 分钟后换乘进站（含边界），续程 S3 -> S4：3 公里
        entry("r-e3", "tx-e3", "TK200", "S3", "2026-09-25T09:00:00");
        JsonNode second = exit("r-x3", "tx-x3", "TK200", "S4", "2026-09-25T09:10:00");
        assertTrue(second.get("transferChained").asBoolean());
        assertEquals(10, second.get("chainCumKm").asInt());
        // 链累计 10 公里票价 400 - 已计 300 = 100，只计一次起步价
        assertEquals(100L, second.get("fareFen").asLong());
    }

    @Test
    void transferBeyondThirtyMinutesChargesNewBaseFare() throws Exception {
        entry("r-e4", "tx-e4", "TK300", "S1", "2026-09-25T08:00:00");
        exit("r-x4", "tx-x4", "TK300", "S3", "2026-09-25T08:30:00");
        // 31 分钟后进站，超出换乘窗口
        entry("r-e5", "tx-e5", "TK300", "S3", "2026-09-25T09:01:00");
        JsonNode second = exit("r-x5", "tx-x5", "TK300", "S4", "2026-09-25T09:10:00");
        assertFalse(second.get("transferChained").asBoolean());
        assertEquals(200L, second.get("fareFen").asLong());
    }

    @Test
    void exactlyFourHoursRideHasNoOvertimePenalty() throws Exception {
        // 恰 4 小时不罚
        entry("r-e6", "tx-e6", "TK400", "S1", "2026-09-25T08:00:00");
        JsonNode trip = exit("r-x6", "tx-x6", "TK400", "S2", "2026-09-25T12:00:00");
        assertFalse(trip.get("overtime").asBoolean());
        assertEquals(200L, trip.get("fareFen").asLong());
    }

    @Test
    void overFourHoursRideAddsNetworkMaxFarePenalty() throws Exception {
        // 4 小时 1 分钟，超时按线网最高票价（19 公里 -> 5 元）补收
        entry("r-e7", "tx-e7", "TK500", "S1", "2026-09-25T08:00:00");
        JsonNode trip = exit("r-x7", "tx-x7", "TK500", "S2", "2026-09-25T12:01:00");
        assertTrue(trip.get("overtime").asBoolean());
        assertEquals(200L + 500L, trip.get("fareFen").asLong());
    }

    @Test
    void crossLineSplitRoundingDiffGoesToLongestMileageLine() throws Exception {
        // S4 -> T1：8 公里（L1 承运 3 公里，L2 承运 5 公里），票价 3 元
        entry("r-e8", "tx-e8", "TK600", "S4", "2026-09-25T08:00:00");
        JsonNode trip = exit("r-x8", "tx-x8", "TK600", "T1", "2026-09-25T08:30:00");
        assertEquals(300L, trip.get("fareFen").asLong());

        JsonNode batch = getOk("/api/metro/settlement/2026-09-25");
        assertEquals(300L, batch.get("totalFen").asLong());
        JsonNode details = batch.get("details");
        assertEquals(2, details.size());
        JsonNode l1 = details.get(0).get("lineCode").asText().equals("L1")
                ? details.get(0) : details.get(1);
        JsonNode l2 = details.get(0).get("lineCode").asText().equals("L2")
                ? details.get(0) : details.get(1);
        // L1：300*3/8=112.5 -> 113；L2：300*5/8=187.5 -> 188；合计 301 超出 1 分，
        // 差额 -1 归入里程最长线路 L2 -> 187；合计必须等于实收 300
        assertEquals(113L, l1.get("amountFen").asLong());
        assertEquals(187L, l2.get("amountFen").asLong());
        assertEquals(0, new BigDecimal("0.3750").compareTo(l1.get("ratio").decimalValue()));
        assertEquals(0, new BigDecimal("0.6250").compareTo(l2.get("ratio").decimalValue()));
        assertEquals(113L + 187L, batch.get("totalFen").asLong());
    }

    @Test
    void duplicateTxNoRejectedWith409() throws Exception {
        entry("r-e9", "tx-dup", "TK700", "S1", "2026-09-25T08:00:00");
        MvcResult second = postRaw("/api/metro/entry", java.util.Map.of(
                "requestId", "r-e10", "txNo", "tx-dup", "ticketId", "TK701",
                "station", "S2", "occurredAt", "2026-09-25T08:05:00"));
        assertEquals(409, second.getResponse().getStatus());
        assertEquals(1, countRows("metro_transaction"));
    }

    @Test
    void provisionalEntryReversedAtomicallyWhenExitRegisteredAfterSeal() throws Exception {
        // 进闸后未出闸，日结按最远站票价暂记（S1 最远 19 公里 -> 5 元）
        entry("r-e11", "tx-e11", "TK800", "S1", "2026-09-25T08:00:00");
        JsonNode sealed = seal("r-s1", "2026-09-25");
        assertEquals(500L, sealed.get("totalFen").asLong());
        assertFalse(sealed.get("alreadySealed").asBoolean());

        // 补登出闸：自然批次 2026-09-25 已封账，顺延至 2026-09-26 并登记差异
        JsonNode trip = exit("r-x11", "tx-x11", "TK800", "S2", "2026-09-25T10:00:00");
        assertEquals(200L, trip.get("fareFen").asLong());
        assertEquals(500L, trip.get("provisionalFareFen").asLong());
        assertEquals("2026-09-26", trip.get("batchDate").asText());

        // 已封账批次合计不变
        JsonNode sealedBatch = getOk("/api/metro/settlement/2026-09-25");
        assertEquals("SEALED", sealedBatch.get("status").asText());
        assertEquals(500L, sealedBatch.get("totalFen").asLong());

        // 次日批次：同一事务内冲销暂记（-500）并按实结算（+200），不双边计额
        JsonNode nextBatch = getOk("/api/metro/settlement/2026-09-26");
        long sum = 0L;
        boolean sawReversal = false;
        boolean sawSettle = false;
        for (JsonNode detail : nextBatch.get("details")) {
            sum += detail.get("amountFen").asLong();
            sawReversal |= "REVERSAL".equals(detail.get("detailType").asText());
            sawSettle |= "SETTLE".equals(detail.get("detailType").asText());
        }
        assertTrue(sawReversal);
        assertTrue(sawSettle);
        assertEquals(-300L, sum);
        // 行程全周期明细合计 = 实收 200（暂记 500 - 冲销 500 + 结算 200）
        assertEquals(500L - 500L + 200L, 500L + sum);

        // 差异登记：封账后迟到交易
        JsonNode diffs = getOk("/api/metro/diffs?date=2026-09-25");
        assertEquals(1, diffs.size());
        assertEquals("LATE_AFTER_SEAL", diffs.get(0).get("reason").asText());
        assertEquals("2026-09-26", diffs.get(0).get("postedBatchDate").asText());
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResult() throws Exception {
        JsonNode first = entry("r-replay", "tx-replay", "TK900", "S1", "2026-09-25T08:00:00");
        JsonNode second = entry("r-replay", "tx-replay", "TK900", "S1", "2026-09-25T08:00:00");
        assertEquals(first, second);
        assertEquals(1, countRows("metro_transaction"));
        assertEquals(1, countRows("trip"));
    }

    @Test
    void sameRequestIdDifferentParamsReturns409WithoutDoublePosting() throws Exception {
        entry("r-conflict", "tx-c1", "TK901", "S1", "2026-09-25T08:00:00");
        MvcResult conflict = postRaw("/api/metro/entry", java.util.Map.of(
                "requestId", "r-conflict", "txNo", "tx-c2", "ticketId", "TK901",
                "station", "S2", "occurredAt", "2026-09-25T08:00:00"));
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals(1, countRows("metro_transaction"));
        assertEquals(1, countRows("trip"));
    }

    @Test
    void sealIsTerminalAndRepeatSealReturnsIdempotentSuccess() throws Exception {
        entry("r-e12", "tx-e12", "TK902", "S1", "2026-09-25T08:00:00");
        exit("r-x12", "tx-x12", "TK902", "S3", "2026-09-25T08:30:00");
        JsonNode first = seal("r-s12", "2026-09-25");
        assertEquals(300L, first.get("totalFen").asLong());
        assertFalse(first.get("alreadySealed").asBoolean());
        // 不同 requestId 重复封账：幂等成功，合计不变
        JsonNode second = seal("r-s13", "2026-09-25");
        assertTrue(second.get("alreadySealed").asBoolean());
        assertEquals(300L, second.get("totalFen").asLong());
        // 同 requestId 重放：返回首次结果
        JsonNode replay = seal("r-s12", "2026-09-25");
        assertEquals(first, replay);
        JsonNode batch = getOk("/api/metro/settlement/2026-09-25");
        assertEquals("SEALED", batch.get("status").asText());
        assertEquals(300L, batch.get("totalFen").asLong());
    }
}
