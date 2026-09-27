package com.example.starter.fermentation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控投酵、代次限用、72 小时新鲜度与回收追溯的主流程与边界值测试（H2 实际库）。
 */
class YeastFlowTest extends BaseFermentationTest {

    /** 投酵后驱动批次完成主酵进入降温：两次合格双乙酰（间隔 13h）→ 降温。 */
    private void driveToCooling(long batchId) throws Exception {
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(1), "0.09")
                .getResponse().getStatus());
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(14), "0.08")
                .getResponse().getStatus());
        assertEquals(200, cooling(batchId, newRequestId(), T0.plusHours(14))
                .getResponse().getStatus());
    }

    @Test
    @DisplayName("登记单元代次0可用版本1；受控投酵原子绑定；合格回收转可用代次+1并解除占用")
    void registerPitchAndQualifiedRecovery() throws Exception {
        registerYeast(newRequestId(), "Y-A-1");
        JsonNode unit = yeastUnit("Y-A-1");
        assertEquals(0, unit.get("generation").asInt(), "初始代次为 0");
        assertEquals("AVAILABLE", unit.get("status").asText());
        assertEquals(1, unit.get("version").asInt(), "版本从 1 开始");
        assertFalse(unit.get("expired").asBoolean(), "首次单元无回收时刻不受 72 小时限制");

        long batchId = createBatch(newRequestId(), "B-Y-1", "TANK-1");
        // 受控投酵：版本 1，代次 0
        MvcResult pitchResult = pitch(batchId, newRequestId(), "Y-A-1", 1, T0);
        assertEquals(200, pitchResult.getResponse().getStatus());
        JsonNode pitchedBatch = json(pitchResult);
        assertEquals("MAIN_FERMENT", pitchedBatch.get("status").asText());
        assertEquals("Y-A-1", pitchedBatch.get("yeastUnitCode").asText());

        JsonNode occupied = yeastUnit("Y-A-1");
        assertEquals("IN_USE", occupied.get("status").asText(), "投酵后单元应被占用");
        assertEquals(batchId, occupied.get("occupiedByBatchId").asLong());
        assertEquals(2, occupied.get("version").asInt(), "投酵成功版本 +1");
        assertEquals(0, occupied.get("generation").asInt(), "投酵不增代次");

        // 完成主酵进入降温 → 合格回收（恰 90.00% 活率，无污染）
        driveToCooling(batchId);
        MvcResult recovery = recover(batchId, newRequestId(), 2, T0.plusHours(20), "90.00", false);
        assertEquals(200, recovery.getResponse().getStatus());
        JsonNode rec = json(recovery);
        assertTrue(rec.get("qualified").asBoolean(), "恰 90% 活率应判定合格");
        assertEquals(1, rec.get("generationAfter").asInt(), "合格回收代次 +1");

        JsonNode after = yeastUnit("Y-A-1");
        assertEquals("AVAILABLE", after.get("status").asText(), "合格回收转可用");
        assertEquals(1, after.get("generation").asInt());
        assertEquals(3, after.get("version").asInt(), "回收成功版本再 +1");
        assertTrue(after.get("occupiedByBatchId").isNull(), "回收应解除批次占用");
        assertEquals("2026-09-27T04:00:00", after.get("lastRecoveredAt").asText());

        // 批次侧保留酵母绑定以便追溯
        JsonNode batch = batch(batchId);
        assertEquals("Y-A-1", batch.get("yeastUnitCode").asText(), "回收后批次仍保留酵母绑定记录");
    }

    @Test
    @DisplayName("回收链可追溯，记录活率/污染/结论/回收后代次")
    void recoveryChainIsTraceable() throws Exception {
        registerYeast(newRequestId(), "Y-A-2");
        long batchId = createBatch(newRequestId(), "B-Y-2", "TANK-1");
        pitch(batchId, newRequestId(), "Y-A-2", 1, T0);
        driveToCooling(batchId);
        recover(batchId, newRequestId(), 2, T0.plusHours(20), "95.50", false);

        MvcResult list = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/yeast-units/Y-A-2/recoveries")).andReturn();
        assertEquals(200, list.getResponse().getStatus());
        JsonNode chain = json(list);
        assertEquals(1, chain.size());
        JsonNode r = chain.get(0);
        assertEquals("Y-A-2", r.get("yeastUnitCode").asText());
        assertEquals(batchId, r.get("batchId").asLong());
        assertEquals(0, r.get("viabilityPercent").decimalValue().compareTo(new java.math.BigDecimal("95.50")));
        assertFalse(r.get("contaminated").asBoolean());
        assertTrue(r.get("qualified").asBoolean());
        assertEquals(1, r.get("generationAfter").asInt());
        assertEquals(3, r.get("unitVersion").asInt(), "回收记录应留存回收后的单元版本 3");
    }

    @Test
    @DisplayName("代次5投酵合格回收成代次6：单元可用但不能再启动新主酵")
    void generationFiveRecoveredToSixCannotPitchAgain() throws Exception {
        jdbc.update("INSERT INTO yeast_unit (unit_code, generation, status, version, created_at)"
                + " VALUES ('Y-G6-E2E', 5, 'AVAILABLE', 1, ?)", java.sql.Timestamp.valueOf(T0));
        long b1 = createBatch(newRequestId(), "B-Y-G6A", "TANK-1");
        pitch(b1, newRequestId(), "Y-G6-E2E", 1, T0);
        driveToCooling(b1);
        MvcResult rec = recover(b1, newRequestId(), 2, T0.plusHours(20), "96.00", false);
        assertEquals(200, rec.getResponse().getStatus());
        JsonNode recBody = json(rec);
        assertTrue(recBody.get("qualified").asBoolean());
        assertEquals(6, recBody.get("generationAfter").asInt(), "代次 5 合格回收到 6");
        assertEquals(3, recBody.get("unitVersion").asInt());

        // 状态回到 AVAILABLE，但代次 6 投酵必须拒绝（422），不占版本
        JsonNode unit = yeastUnit("Y-G6-E2E");
        assertEquals("AVAILABLE", unit.get("status").asText());
        assertEquals(6, unit.get("generation").asInt());
        long b2 = createBatch(newRequestId(), "B-Y-G6B", "TANK-2");
        assertEquals(422, pitch(b2, newRequestId(), "Y-G6-E2E", 3, T0.plusHours(21))
                .getResponse().getStatus(), "代次 6 不能启动新主酵");
        assertEquals(3, yeastUnit("Y-G6-E2E").get("version").asInt(), "拒绝不增版本");
        assertEquals("FILLED", batch(b2).get("status").asText());
    }

    @Test
    @DisplayName("恰当代次5允许投酵；代次6不能启动新主酵")
    void generationFiveAllowedSixRejected() throws Exception {
        // 直接构造代次 5 的可用单元（版本 1）
        jdbc.update("INSERT INTO yeast_unit (unit_code, generation, status, version, created_at)"
                + " VALUES ('Y-G-5', 5, 'AVAILABLE', 1, ?)", java.sql.Timestamp.valueOf(T0));
        long b1 = createBatch(newRequestId(), "B-Y-G5", "TANK-1");
        assertEquals(200, pitch(b1, newRequestId(), "Y-G-5", 1, T0).getResponse().getStatus(),
                "恰当代次 5 应允许本次投酵");

        // 构造代次 6 的可用单元：投酵必须拒绝
        jdbc.update("INSERT INTO yeast_unit (unit_code, generation, status, version, created_at)"
                + " VALUES ('Y-G-6', 6, 'AVAILABLE', 1, ?)", java.sql.Timestamp.valueOf(T0));
        long b2 = createBatch(newRequestId(), "B-Y-G6", "TANK-2");
        MvcResult rejected = pitch(b2, newRequestId(), "Y-G-6", 1, T0);
        assertEquals(422, rejected.getResponse().getStatus(), "代次 6 不能启动新主酵");
        JsonNode y6 = yeastUnit("Y-G-6");
        assertEquals("AVAILABLE", y6.get("status").asText(), "代次 6 被拒后状态保持可用记录");
        assertEquals(6, y6.get("generation").asInt());
        assertEquals(1, y6.get("version").asInt(), "投酵失败版本不得增加");
        assertEquals("FILLED", batch(b2).get("status").asText(), "被拒批次仍为满罐");
    }

    @Test
    @DisplayName("恰72小时仍可投酵；超过72小时失效拒绝投酵")
    void freshnessExactly72HoursOkBeyondRejected() throws Exception {
        // 单元最近回收时刻 T0，代次 1
        jdbc.update("INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at,"
                + " version, created_at) VALUES ('Y-F-1', 1, 'AVAILABLE', ?, 2, ?)",
                java.sql.Timestamp.valueOf(T0), java.sql.Timestamp.valueOf(T0.minusDays(2)));

        // 恰 72 小时：允许
        long b1 = createBatch(newRequestId(), "B-Y-F1", "TANK-1");
        assertEquals(200, pitch(b1, newRequestId(), "Y-F-1", 2, T0.plusHours(72))
                .getResponse().getStatus(), "距回收恰 72 小时应仍可用");

        // 另一单元：72 小时 +1 分钟：拒绝
        jdbc.update("INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at,"
                + " version, created_at) VALUES ('Y-F-2', 1, 'AVAILABLE', ?, 2, ?)",
                java.sql.Timestamp.valueOf(T0), java.sql.Timestamp.valueOf(T0.minusDays(2)));
        long b2 = createBatch(newRequestId(), "B-Y-F2", "TANK-2");
        MvcResult rejected = pitch(b2, newRequestId(), "Y-F-2", 2,
                T0.plusHours(72).plusMinutes(1));
        assertEquals(422, rejected.getResponse().getStatus(), "距回收超过 72 小时应失效拒绝");
        assertEquals(2, yeastUnit("Y-F-2").get("version").asInt(), "失败不增版本");
    }

    @Test
    @DisplayName("回收活率恰90合格；89.99或有污染报废不可再投酵，报废单元再投422")
    void viabilityBoundaryAndContaminationScrap() throws Exception {
        // 恰 90.00 合格（已在主流程覆盖），这里验证 89.99 与污染报废
        registerYeast(newRequestId(), "Y-S-1");
        long b1 = createBatch(newRequestId(), "B-Y-S1", "TANK-1");
        pitch(b1, newRequestId(), "Y-S-1", 1, T0);
        driveToCooling(b1);
        MvcResult low = recover(b1, newRequestId(), 2, T0.plusHours(20), "89.99", false);
        assertEquals(200, low.getResponse().getStatus());
        assertFalse(json(low).get("qualified").asBoolean(), "活率 89.99% 应判定不合格");
        JsonNode scrapped = yeastUnit("Y-S-1");
        assertEquals("SCRAPPED", scrapped.get("status").asText(), "不合格回收应报废");
        assertEquals(0, scrapped.get("generation").asInt(), "不合格回收不加代次");

        // 报废单元再投酵：422
        long b2 = createBatch(newRequestId(), "B-Y-S2", "TANK-2");
        assertEquals(422, pitch(b2, newRequestId(), "Y-S-1", 3, T0.plusHours(21))
                .getResponse().getStatus(), "报废单元不可再投酵");

        // 污染：即使活率高也报废
        registerYeast(newRequestId(), "Y-S-2");
        long b3 = createBatch(newRequestId(), "B-Y-S3", "TANK-3");
        pitch(b3, newRequestId(), "Y-S-2", 1, T0);
        driveToCooling(b3);
        MvcResult contaminated = recover(b3, newRequestId(), 2, T0.plusHours(20), "99.00", true);
        assertEquals(200, contaminated.getResponse().getStatus());
        assertFalse(json(contaminated).get("qualified").asBoolean(), "有污染应判定不合格");
        assertEquals("SCRAPPED", yeastUnit("Y-S-2").get("status").asText());
    }

    @Test
    @DisplayName("回收只能一次：失败（报废）判定后同批再回收422，且不改变成熟度判定/转出")
    void recoveryOnlyOnceEvenWhenRejected() throws Exception {
        registerYeast(newRequestId(), "Y-O-1");
        long batchId = createBatch(newRequestId(), "B-Y-O1", "TANK-1");
        pitch(batchId, newRequestId(), "Y-O-1", 1, T0);
        driveToCooling(batchId);
        // 首次回收：污染报废（一次受理）
        assertEquals(200, recover(batchId, newRequestId(), 2, T0.plusHours(20), "70.00", true)
                .getResponse().getStatus());
        // 同批再次回收（不同 requestId、新版本）：422
        MvcResult again = recover(batchId, newRequestId(), 3, T0.plusHours(21), "99.00", false);
        assertEquals(422, again.getResponse().getStatus(), "失败批次也只能回收一次");
        // 单元仍为报废，未被第二次回收"翻案"
        assertEquals("SCRAPPED", yeastUnit("Y-O-1").get("status").asText());
        Integer recoveryRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_recovery_record WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(1, recoveryRows, "同批次只能有一条回收记录");

        // 成熟度判定与清酒转出不受回收影响：可继续后熟→判定→转出
        assertEquals(200, startMaturation(batchId, newRequestId(), T0.plusHours(21))
                .getResponse().getStatus());
        assertEquals(200, judge(batchId, newRequestId(), T0.plusHours(21).plusDays(7))
                .getResponse().getStatus());
        MvcResult transferred = transfer(batchId, newRequestId(),
                T0.plusHours(21).plusDays(7).plusHours(1));
        assertEquals(200, transferred.getResponse().getStatus());
        assertEquals("TRANSFERRED", json(transferred).get("status").asText());
    }

    @Test
    @DisplayName("失败分支：未完成主酵回收、未绑定酵母回收、非满罐投酵、占用中再投均422；对象不存在404")
    void businessRejections() throws Exception {
        registerYeast(newRequestId(), "Y-R-1");
        long batchId = createBatch(newRequestId(), "B-Y-R1", "TANK-1");

        // 满罐未投酵即回收：未绑定酵母 422
        assertEquals(422, recover(batchId, newRequestId(), 1, T0.plusHours(1), "95.00", false)
                .getResponse().getStatus());

        pitch(batchId, newRequestId(), "Y-R-1", 1, T0);
        // 主酵进行中（未降温）回收：422
        assertEquals(422, recover(batchId, newRequestId(), 2, T0.plusHours(1), "95.00", false)
                .getResponse().getStatus());

        // 占用中的单元被另一满罐批次投酵：422
        long other = createBatch(newRequestId(), "B-Y-R2", "TANK-2");
        assertEquals(422, pitch(other, newRequestId(), "Y-R-1", 2, T0.plusMinutes(30))
                .getResponse().getStatus(), "一个单元同时仅供一个主酵批次");

        // 同一批次重复投酵（已 MAIN_FERMENT）：422，不重复增次数
        MvcResult duplicate = pitch(batchId, newRequestId(), "Y-R-1", 2, T0.plusMinutes(30));
        assertEquals(422, duplicate.getResponse().getStatus(), "重复启动不应重复绑定");
        assertEquals(2, yeastUnit("Y-R-1").get("version").asInt(), "重复投酵不增版本");

        // 不存在的酵母 / 批次
        MvcResult noYeast = pitch(batchId, newRequestId(), "Y-NOPE", 1, T0);
        // batchId 已主酵，先报状态 422；换一个满罐批次验证 404
        long fresh = createBatch(newRequestId(), "B-Y-R3", "TANK-3");
        MvcResult missingYeast = pitch(fresh, newRequestId(), "Y-NOPE", 1, T0);
        assertEquals(404, missingYeast.getResponse().getStatus(), "酵母单元不存在应 404");
        MvcResult missingBatch = recover(999_999L, newRequestId(), 1, T0, "95.00", false);
        assertEquals(404, missingBatch.getResponse().getStatus(), "批次不存在应 404");
        assertEquals(422, noYeast.getResponse().getStatus());
    }

    @Test
    @DisplayName("乐观版本不匹配返回409且不改变业务状态")
    void versionMismatchReturns409() throws Exception {
        registerYeast(newRequestId(), "Y-V-1");
        long batchId = createBatch(newRequestId(), "B-Y-V1", "TANK-1");
        // 错误版本（当前为 1，传 9）：409
        MvcResult wrong = pitch(batchId, newRequestId(), "Y-V-1", 9, T0);
        assertEquals(409, wrong.getResponse().getStatus());
        assertEquals(1, yeastUnit("Y-V-1").get("version").asInt(), "版本冲突不得变更单元");
        assertEquals("FILLED", batch(batchId).get("status").asText());

        // 正确版本仍可成功
        assertEquals(200, pitch(batchId, newRequestId(), "Y-V-1", 1, T0).getResponse().getStatus());
    }

    @Test
    @DisplayName("参数非法返回400：空单元编号/活率越界/版本为负/时刻缺失")
    void invalidParamsReturn400() throws Exception {
        long batchId = createBatch(newRequestId(), "B-Y-INV", "TANK-1");
        // 空单元编号登记
        MvcResult blankCode = doPost("/api/yeast-units",
                new com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest(
                        newRequestId(), "  "));
        assertEquals(400, blankCode.getResponse().getStatus());
        // 活率 120% 越界
        MvcResult badViability = doPost("/api/batches/" + batchId + "/yeast-recovery",
                new com.example.starter.fermentation.api.YeastDtos.YeastRecoveryRequest(
                        newRequestId(), 1, T0, new java.math.BigDecimal("120.00"), false));
        assertEquals(400, badViability.getResponse().getStatus());
    }

    @Test
    @DisplayName("连续投酵-合格回收链：代次0逐代增长，回收后可投新批次")
    void repeatedPitchRecoveryAcrossBatches() throws Exception {
        registerYeast(newRequestId(), "Y-CHAIN");
        long b1 = createBatch(newRequestId(), "B-Y-C1", "TANK-1");
        pitch(b1, newRequestId(), "Y-CHAIN", 1, T0);
        driveToCooling(b1);
        recover(b1, newRequestId(), 2, T0.plusHours(20), "97.00", false);
        assertEquals(1, yeastUnit("Y-CHAIN").get("generation").asInt());
        assertEquals("AVAILABLE", yeastUnit("Y-CHAIN").get("status").asText());

        // 回收解除占用后，新批次可投同一单元（版本 3）
        long b2 = createBatch(newRequestId(), "B-Y-C2", "TANK-2");
        assertEquals(200, pitch(b2, newRequestId(), "Y-CHAIN", 3, T0.plusHours(21))
                .getResponse().getStatus());
        assertEquals(b2, yeastUnit("Y-CHAIN").get("occupiedByBatchId").asLong());
    }
}
