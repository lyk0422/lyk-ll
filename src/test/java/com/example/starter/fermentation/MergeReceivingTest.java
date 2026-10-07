package com.example.starter.fermentation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合批接收业务测试：容量恰满、坏一源整单回滚、新旧核销共享、升数勾稽、
 * 乐观版本与 requestId 幂等。全部经由真实 H2（MODE=MySQL）事务验证，不 mock 数据库边界。
 */
class MergeReceivingTest extends BaseFermentationTest {

    private static final LocalDateTime RECEIVED_AT =
            BaseFermentationTest.T0.plusDays(30);

    /** 准备两个判定合格待转出的受控源批（使用不同发酵罐）。 */
    private long[] twoJudgedSources(String a, String b) throws Exception {
        return new long[]{
                driveToJudged(a, "TANK-1"),
                driveToJudged(b, "TANK-2")
        };
    }

    private int writeoffCount(Long batchId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM liquor_writeoff WHERE batch_id = ?", Integer.class, batchId);
        return c == null ? 0 : c;
    }

    private String batchStatus(long batchId) {
        return jdbc.queryForObject("SELECT status FROM fermentation_batch WHERE id = ?",
                String.class, batchId);
    }

    @Test
    @DisplayName("40 升与 60 升合入 100 升空罐：恰满受理，两源转终态释放原罐，核销 MERGE")
    void fortyAndSixtyMergeIntoEmptyHundredExactlyFull() throws Exception {
        long[] s = twoJudgedSources("B-MERGE-40", "B-MERGE-60");
        assertEquals(201, registerSourceLiquor(s[0], newRequestId(), "40.000")
                .getResponse().getStatus());
        assertEquals(201, registerSourceLiquor(s[1], newRequestId(), "60.000")
                .getResponse().getStatus());
        registerReceivingTank(newRequestId(), "RT-100", "100.000");

        MvcResult result = mergeReceive(newRequestId(), "RT-100", List.of(s[0], s[1]), 1,
                RECEIVED_AT);
        assertEquals(201, result.getResponse().getStatus(), "恰满应受理");
        JsonNode body = json(result);
        assertEquals(0, new BigDecimal("100.000").compareTo(body.get("totalLiters").decimalValue()));
        assertEquals(0, new BigDecimal("100.000")
                .compareTo(body.get("receivedLitersAfter").decimalValue()));
        assertEquals(2, body.get("tankVersion").asInt(), "受理成功罐版本加 1");
        assertEquals(2, body.get("items").size());

        // 两源进入原已转出终态并释放原发酵罐，不重新开始主酵
        assertEquals("TRANSFERRED", batchStatus(s[0]));
        assertEquals("TRANSFERRED", batchStatus(s[1]));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE id IN (?, ?) AND active_tank_key IS NULL",
                Integer.class, s[0], s[1]), "两源原发酵罐占用均应释放");
        assertEquals(1, writeoffCount(s[0]));
        assertEquals(1, writeoffCount(s[1]));
        assertEquals("MERGE", jdbc.queryForObject(
                "SELECT channel FROM liquor_writeoff WHERE batch_id = ?", String.class, s[0]));

        // 升数勾稽：受理单明细合计 = 单头合计 = 接收罐累计接收量
        long receiptId = body.get("receiptId").asLong();
        JsonNode receipt = json(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/merge-receipts/" + receiptId)).andReturn());
        BigDecimal itemSum = BigDecimal.ZERO;
        for (JsonNode item : receipt.get("items")) {
            itemSum = itemSum.add(item.get("liters").decimalValue());
        }
        assertEquals(0, itemSum.compareTo(receipt.get("totalLiters").decimalValue()));
        JsonNode tank = json(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/receiving-tanks/RT-100")).andReturn());
        assertEquals(0, new BigDecimal("100.000")
                .compareTo(tank.get("receivedLiters").decimalValue()));
    }

    @Test
    @DisplayName("恰满后再合入任意量都超容量：422 整单拒绝，不改任何状态")
    void secondMergeBeyondCapacityRejected() throws Exception {
        long[] s = twoJudgedSources("B-FULL-A", "B-FULL-B");
        registerSourceLiquor(s[0], newRequestId(), "40.000");
        registerSourceLiquor(s[1], newRequestId(), "60.000");
        registerReceivingTank(newRequestId(), "RT-FULL", "100.000");
        assertEquals(201, mergeReceive(newRequestId(), "RT-FULL", List.of(s[0], s[1]), 1,
                RECEIVED_AT).getResponse().getStatus());

        long[] more = new long[]{
                driveToJudged("B-FULL-C", "TANK-3")
        };
        // 仅再加一个源无法成单（至少 2 源），构造第二单两源共 0.001 升也超容量
        long extra2 = driveToJudged("B-FULL-D", "TANK-1");
        registerSourceLiquor(more[0], newRequestId(), "0.001");
        registerSourceLiquor(extra2, newRequestId(), "0.001");
        // 罐已恰满：需要先释放 TANK-1（其首批已转出，罐位已释放），新批使用 TANK-1/TANK-2 已可入罐
        MvcResult over = mergeReceive(newRequestId(), "RT-FULL", List.of(more[0], extra2), 2,
                RECEIVED_AT.plusHours(1));
        assertEquals(422, over.getResponse().getStatus(), "超容量应整单拒绝");
        assertEquals(0, new BigDecimal("100.000").compareTo(
                jdbc.queryForObject("SELECT received_liters FROM receiving_tank WHERE tank_code = 'RT-FULL'",
                        BigDecimal.class)));
        assertEquals("JUDGED", batchStatus(more[0]), "失败源批不得转终态");
        assertEquals("JUDGED", batchStatus(extra2));
        assertEquals(0, writeoffCount(more[0]));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merge_receipt WHERE receiving_tank_id ="
                        + " (SELECT id FROM receiving_tank WHERE tank_code='RT-FULL')",
                Integer.class), "失败不得新增受理单");
    }

    @Test
    @DisplayName("坏一源整单回滚：未登记清酒量的源导致 422，其余源量、罐占用、核销均不变")
    void oneBadSourceRollsBackWholeOrder() throws Exception {
        long good1 = driveToJudged("B-BAD-G1", "TANK-1");
        long good2 = driveToJudged("B-BAD-G2", "TANK-2");
        long noLiquor = driveToJudged("B-BAD-X", "TANK-3");
        registerSourceLiquor(good1, newRequestId(), "10.000");
        registerSourceLiquor(good2, newRequestId(), "20.000");
        // noLiquor 不登记源量
        registerReceivingTank(newRequestId(), "RT-BAD", "100.000");

        MvcResult result = mergeReceive(newRequestId(), "RT-BAD",
                List.of(good1, good2, noLiquor), 1, RECEIVED_AT);
        assertEquals(422, result.getResponse().getStatus());

        assertEquals("JUDGED", batchStatus(good1));
        assertEquals("JUDGED", batchStatus(good2));
        assertEquals("JUDGED", batchStatus(noLiquor));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM liquor_writeoff", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_receipt", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_receipt_item", Integer.class));
        assertEquals(0, new BigDecimal("0.000").compareTo(
                jdbc.queryForObject("SELECT received_liters FROM receiving_tank WHERE tank_code='RT-BAD'",
                        BigDecimal.class)));
        // 源量台账保持冻结不变
        assertEquals(new BigDecimal("10.000"), jdbc.queryForObject(
                "SELECT liters FROM source_liquor_register WHERE batch_id = ?", BigDecimal.class, good1));
    }

    @Test
    @DisplayName("曲线版本失配整单拒绝：一源为同曲线另一版本时 422 且不写核销")
    void curveVersionMismatchRejectsWholeOrder() throws Exception {
        long[] s = twoJudgedSources("B-VER-A", "B-VER-B");
        registerSourceLiquor(s[0], newRequestId(), "10");
        registerSourceLiquor(s[1], newRequestId(), "10");
        // 为 LAGER-CURVE 增加 v2 并把第二源切到 v2（合成边界数据：来源版本失配）
        jdbc.update("INSERT INTO process_curve (id, curve_code, version, status, created_at)"
                + " VALUES (999, 'LAGER-CURVE', 2, 'ACTIVE', TIMESTAMP '2026-03-01 00:00:00')");
        jdbc.update("INSERT INTO curve_segment (curve_id, phase, seq, setpoint_celsius, duration_hours)"
                + " VALUES (999, 'MATURATION', 1, -1.00, 168)");
        jdbc.update("UPDATE fermentation_batch SET curve_version = 2 WHERE id = ?", s[1]);
        registerReceivingTank(newRequestId(), "RT-VER", "100");

        assertEquals(422, mergeReceive(newRequestId(), "RT-VER", List.of(s[0], s[1]), 1, RECEIVED_AT)
                .getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM liquor_writeoff", Integer.class));
        assertEquals("JUDGED", batchStatus(s[0]));
        assertEquals("JUDGED", batchStatus(s[1]));
    }

    @Test
    @DisplayName("旧单笔转出先核销后，合批引用该源整单拒绝；合批后不产生第二笔核销")
    void singleTransferAndMergeShareUniqueWriteoff() throws Exception {
        long[] s = twoJudgedSources("B-WO-A", "B-WO-B");
        registerSourceLiquor(s[0], newRequestId(), "30");
        registerSourceLiquor(s[1], newRequestId(), "30");
        registerReceivingTank(newRequestId(), "RT-WO", "100");

        // 旧单笔转出先消费唯一核销
        assertEquals(200, transfer(s[0], newRequestId(), RECEIVED_AT.minusHours(2))
                .getResponse().getStatus());
        assertEquals(1, writeoffCount(s[0]));
        assertEquals("SINGLE", jdbc.queryForObject(
                "SELECT channel FROM liquor_writeoff WHERE batch_id = ?", String.class, s[0]));

        // 合批引用已转出源：整单拒绝，另一源不受影响
        MvcResult rejected = mergeReceive(newRequestId(), "RT-WO", List.of(s[0], s[1]), 1,
                RECEIVED_AT);
        assertEquals(422, rejected.getResponse().getStatus());
        assertEquals("JUDGED", batchStatus(s[1]), "未消费源应保持待转出");
        assertEquals(0, writeoffCount(s[1]));
        assertEquals(0, new BigDecimal("0.000").compareTo(
                jdbc.queryForObject("SELECT received_liters FROM receiving_tank WHERE tank_code='RT-WO'",
                        BigDecimal.class)));
    }

    @Test
    @DisplayName("合批先核销后，旧单笔转出不重复消费，每源仅一条核销")
    void mergeFirstThenSingleTransferDoesNotDoubleConsume() throws Exception {
        long[] s = twoJudgedSources("B-WO2-A", "B-WO2-B");
        registerSourceLiquor(s[0], newRequestId(), "30");
        registerSourceLiquor(s[1], newRequestId(), "30");
        registerReceivingTank(newRequestId(), "RT-WO2", "100");
        assertEquals(201, mergeReceive(newRequestId(), "RT-WO2", List.of(s[0], s[1]), 1, RECEIVED_AT)
                .getResponse().getStatus());

        // 源已转终态：旧转出终态幂等返回 200，但不新增核销、不改转出时间
        MvcResult again = transfer(s[0], newRequestId(), RECEIVED_AT.plusDays(1));
        assertEquals(200, again.getResponse().getStatus());
        assertEquals(1, writeoffCount(s[0]));
        assertEquals("MERGE", jdbc.queryForObject(
                "SELECT channel FROM liquor_writeoff WHERE batch_id = ?", String.class, s[0]));
    }

    @Test
    @DisplayName("合批 expectedVersion 旧版 409；成功后版本加 1，失败不推进版本")
    void mergeOptimisticVersionConflict() throws Exception {
        long[] s = twoJudgedSources("B-OPT-A", "B-OPT-B");
        registerSourceLiquor(s[0], newRequestId(), "10");
        registerSourceLiquor(s[1], newRequestId(), "10");
        registerReceivingTank(newRequestId(), "RT-OPT", "100");

        assertEquals(409, mergeReceive(newRequestId(), "RT-OPT", List.of(s[0], s[1]), 99, RECEIVED_AT)
                .getResponse().getStatus(), "旧版本应返回 409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM receiving_tank WHERE tank_code='RT-OPT'", Integer.class),
                "失败不得推进版本");
        assertEquals(201, mergeReceive(newRequestId(), "RT-OPT", List.of(s[0], s[1]), 1, RECEIVED_AT)
                .getResponse().getStatus());
    }

    @Test
    @DisplayName("容量调整：成功版本加 1；旧版 409；缩到已接收量以下 422")
    void capacityUpdateRules() throws Exception {
        JsonNode tank = registerReceivingTank(newRequestId(), "RT-CAP", "100");
        assertEquals(1, tank.get("version").asInt());

        MvcResult stale = updateTankCapacity("RT-CAP", newRequestId(), "120", 99);
        assertEquals(409, stale.getResponse().getStatus());

        MvcResult grown = updateTankCapacity("RT-CAP", newRequestId(), "120", 1);
        assertEquals(200, grown.getResponse().getStatus());
        assertEquals(2, json(grown).get("version").asInt());

        MvcResult shrinkOk = updateTankCapacity("RT-CAP", newRequestId(), "120", 2);
        assertEquals(200, shrinkOk.getResponse().getStatus(), "容量不小于已接收量(0)允许");
        assertEquals(3, json(shrinkOk).get("version").asInt());

        // 先合入 50 升
        long[] s = twoJudgedSources("B-CAP-A", "B-CAP-B");
        registerSourceLiquor(s[0], newRequestId(), "20");
        registerSourceLiquor(s[1], newRequestId(), "30");
        assertEquals(201, mergeReceive(newRequestId(), "RT-CAP", List.of(s[0], s[1]), 3, RECEIVED_AT)
                .getResponse().getStatus());
        MvcResult tooSmall = updateTankCapacity("RT-CAP", newRequestId(), "49.999", 4);
        assertEquals(422, tooSmall.getResponse().getStatus(), "不得缩到已接收量(50)以下");
        MvcResult exact = updateTankCapacity("RT-CAP", newRequestId(), "50.000", 4);
        assertEquals(200, exact.getResponse().getStatus(), "恰等于已接收量允许");
    }

    @Test
    @DisplayName("合批幂等：同键同参重放首次结果；异参 409；失败不占键可修正重试")
    void mergeIdempotencyReplayConflictAndFailureFreesKey() throws Exception {
        long[] s = twoJudgedSources("B-IDM-A", "B-IDM-B");
        registerSourceLiquor(s[0], newRequestId(), "15");
        registerSourceLiquor(s[1], newRequestId(), "15");
        registerReceivingTank(newRequestId(), "RT-IDM", "100");

        String requestId = newRequestId();
        MvcResult first = mergeReceive(requestId, "RT-IDM", List.of(s[0], s[1]), 1, RECEIVED_AT);
        assertEquals(201, first.getResponse().getStatus());
        MvcResult replay = mergeReceive(requestId, "RT-IDM", List.of(s[0], s[1]), 1, RECEIVED_AT);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参应原样重放");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM merge_receipt", Integer.class),
                "重放不得产生第二张受理单");

        // 同键异参：409
        MvcResult conflict = mergeReceive(requestId, "RT-IDM", List.of(s[0], s[1]), 1,
                RECEIVED_AT.plusHours(1));
        assertEquals(409, conflict.getResponse().getStatus());

        // 失败不占键：首次用错误版本失败，修正后同键成功
        long[] s2 = {
                driveToJudged("B-IDM-C", "TANK-1"),
                driveToJudged("B-IDM-D", "TANK-2")
        };
        registerSourceLiquor(s2[0], newRequestId(), "5");
        registerSourceLiquor(s2[1], newRequestId(), "5");
        registerReceivingTank(newRequestId(), "RT-IDM2", "100");
        String retryKey = newRequestId();
        assertEquals(409, mergeReceive(retryKey, "RT-IDM2", List.of(s2[0], s2[1]), 7, RECEIVED_AT)
                .getResponse().getStatus());
        assertEquals(201, mergeReceive(retryKey, "RT-IDM2", List.of(s2[0], s2[1]), 1, RECEIVED_AT)
                .getResponse().getStatus(), "失败不占键，修正版本后应受理");
    }

    @Test
    @DisplayName("源量登记：正数三位小数且不超发酵罐容量；登记后冻结不可重复登记")
    void sourceLiquorRegistrationValidationAndFreeze() throws Exception {
        long id = driveToJudged("B-LIQ-1", "TANK-1");

        // 超过发酵罐容量（TANK-1 容量 5000 升）：422
        assertEquals(422, registerSourceLiquor(id, newRequestId(), "5000.001")
                .getResponse().getStatus());
        // 非正数：400（Bean Validation）
        assertEquals(400, registerSourceLiquor(id, newRequestId(), "0")
                .getResponse().getStatus());
        // 超过三位小数：400
        assertEquals(400, registerSourceLiquor(id, newRequestId(), "1.0001")
                .getResponse().getStatus());
        // 合法：三位小数
        MvcResult ok = registerSourceLiquor(id, newRequestId(), "123.456");
        assertEquals(201, ok.getResponse().getStatus());
        assertTrue(json(ok).get("frozen").asBoolean(), "登记即冻结");

        // 重复登记：422，台账冻结
        assertEquals(422, registerSourceLiquor(id, newRequestId(), "123.456")
                .getResponse().getStatus());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_liquor_register WHERE batch_id = ?", Integer.class, id));

        // 未判定合格的批次不得登记：后熟中 422
        long immature = driveToMaturation("B-LIQ-2", "TANK-2");
        assertEquals(422, registerSourceLiquor(immature, newRequestId(), "10")
                .getResponse().getStatus());
    }

    @Test
    @DisplayName("受理单列表可逐单复算：各单来源升数之和等于接收罐累计接收量")
    void receiptsReconcileAcrossMultipleMerges() throws Exception {
        registerReceivingTank(newRequestId(), "RT-SUM", "1000");
        long[] a = {driveToJudged("B-SUM-A1", "TANK-1"), driveToJudged("B-SUM-A2", "TANK-2")};
        registerSourceLiquor(a[0], newRequestId(), "100");
        registerSourceLiquor(a[1], newRequestId(), "200");
        assertEquals(201, mergeReceive(newRequestId(), "RT-SUM", List.of(a[0], a[1]), 1, RECEIVED_AT)
                .getResponse().getStatus());

        long[] b = {driveToJudged("B-SUM-B1", "TANK-1"), driveToJudged("B-SUM-B2", "TANK-2")};
        registerSourceLiquor(b[0], newRequestId(), "50.5");
        registerSourceLiquor(b[1], newRequestId(), "49.5");
        assertEquals(201, mergeReceive(newRequestId(), "RT-SUM", List.of(b[0], b[1]), 2,
                RECEIVED_AT.plusHours(1)).getResponse().getStatus());

        JsonNode list = json(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/receiving-tanks/RT-SUM/merge-receipts")).andReturn());
        assertEquals(2, list.size());
        BigDecimal reconciled = BigDecimal.ZERO;
        for (JsonNode receipt : list) {
            for (JsonNode item : receipt.get("items")) {
                reconciled = reconciled.add(item.get("liters").decimalValue());
            }
        }
        JsonNode tank = json(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/receiving-tanks/RT-SUM")).andReturn());
        assertEquals(0, new BigDecimal("400.000").compareTo(reconciled));
        assertEquals(0, new BigDecimal("400.000")
                .compareTo(tank.get("receivedLiters").decimalValue()));
        assertNotNull(tank.get("createdAt"));
    }
}
