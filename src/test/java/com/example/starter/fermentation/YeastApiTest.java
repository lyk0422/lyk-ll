package com.example.starter.fermentation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控投酵、代次限用与回收追溯的主流程、边界值与失败分支测试（H2 MySQL 兼容模式实际库）。
 */
class YeastApiTest extends BaseFermentationTest {

    /** 主酵合格并完成（进入降温）：两次合格双乙酰后下达降温指令。 */
    private void finishMainFerment(long batchId) throws Exception {
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(1), "0.09")
                .getResponse().getStatus());
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(13), "0.08")
                .getResponse().getStatus());
        assertEquals(200, cooling(batchId, newRequestId(), T0.plusHours(13))
                .getResponse().getStatus());
    }

    @Test
    @DisplayName("登记酵母：初始代次 0、可用、版本 1；重复登记 409；查询不存在 404")
    void registerYeastDefaultsAndDuplicate() throws Exception {
        MvcResult created = doPost("/api/yeast-units",
                new com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest(
                        newRequestId(), "Y-REG-1"));
        assertEquals(201, created.getResponse().getStatus());
        JsonNode unit = json(created);
        assertEquals("Y-REG-1", unit.get("unitCode").asText());
        assertEquals(0, unit.get("generation").asInt());
        assertEquals("AVAILABLE", unit.get("status").asText());
        assertEquals(1, unit.get("version").asInt());
        assertTrue(unit.get("lastRecoveredAt").isNull());
        assertTrue(unit.get("occupiedByBatchId").isNull());

        // 重复编号：409
        MvcResult duplicate = doPost("/api/yeast-units",
                new com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest(
                        newRequestId(), "Y-REG-1"));
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_unit WHERE unit_code = 'Y-REG-1'", Integer.class));

        // 不存在：404
        assertEquals(404, mockMvc.perform(org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get("/api/yeast-units/NO-SUCH")).andReturn()
                .getResponse().getStatus());
    }

    @Test
    @DisplayName("主流程：受控投酵原子绑定，合格回收转可用、代次加 1、解除占用，版本逐次加 1")
    void controlledPitchAndQualifiedRecovery() throws Exception {
        registerYeast(newRequestId(), "Y-MAIN-1");
        long batchId = createBatch(newRequestId(), "B-Y-1", "TANK-1");

        MvcResult pitch = pitch(batchId, newRequestId(), "Y-MAIN-1", 1, T0);
        assertEquals(200, pitch.getResponse().getStatus());
        JsonNode pitched = json(pitch);
        assertEquals("MAIN_FERMENT", pitched.get("batchStatus").asText());
        assertEquals(0, pitched.get("generation").asInt());
        assertEquals("IN_USE", pitched.get("yeastStatus").asText());
        assertEquals(2, pitched.get("yeastVersion").asInt(), "成功投酵版本应加 1");

        JsonNode inUse = yeastUnit("Y-MAIN-1");
        assertEquals("IN_USE", inUse.get("status").asText());
        assertEquals(batchId, inUse.get("occupiedByBatchId").asLong());

        // 另一批次不得占用同一单元
        long other = createBatch(newRequestId(), "B-Y-2", "TANK-2");
        MvcResult occupied = pitch(other, newRequestId(), "Y-MAIN-1", 2, T0);
        assertEquals(422, occupied.getResponse().getStatus());
        JsonNode otherBatch = json(mockMvc.perform(org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get("/api/batches/" + other)).andReturn());
        assertEquals("FILLED", otherBatch.get("status").asText(), "占用失败不得改变失败批次状态");

        // 完成主酵后回收，活率 92% 无污染
        finishMainFerment(batchId);
        MvcResult recovery = recoverYeast(batchId, newRequestId(), 2, T0.plusHours(14),
                "92.00", false);
        assertEquals(200, recovery.getResponse().getStatus());
        JsonNode rec = json(recovery);
        assertTrue(rec.get("qualified").asBoolean());
        assertEquals(1, rec.get("generationAfter").asInt(), "合格回收代次应加 1");
        assertEquals("AVAILABLE", rec.get("yeastStatus").asText());
        assertEquals(3, rec.get("yeastVersion").asInt(), "回收成功版本应再加 1");

        JsonNode available = yeastUnit("Y-MAIN-1");
        assertEquals("AVAILABLE", available.get("status").asText());
        assertEquals(1, available.get("generation").asInt());
        assertTrue(available.get("occupiedByBatchId").isNull(), "回收应解除批次占用");
        assertEquals("2026-09-26T22:00:00", available.get("lastRecoveredAt").asText());

        // 回收追溯记录可查
        JsonNode queried = json(mockMvc.perform(org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get("/api/batches/" + batchId + "/yeast-recovery"))
                .andReturn());
        assertEquals("Y-MAIN-1", queried.get("yeastUnitCode").asText());
        assertEquals(0, new BigDecimal("92.00").compareTo(queried.get("viabilityPercent")
                .decimalValue()));
    }

    @Test
    @DisplayName("代次边界：恰 5 允许本次投酵，合格回收后到 6；代次 6 不能启动新主酵")
    void generationFiveAllowedSixRejected() throws Exception {
        // 直接合成一个已回收 5 次（代次 5）、可用、版本 1 的单元
        jdbc.update("INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at,"
                        + " occupied_by_batch_id, version) VALUES (?, 5, 'AVAILABLE', ?, NULL, 1)",
                "Y-GEN-5", Timestamp.valueOf(T0.minusHours(1)));

        long first = createBatch(newRequestId(), "B-GEN-1", "TANK-1");
        MvcResult pitchAtFive = pitch(first, newRequestId(), "Y-GEN-5", 1, T0);
        assertEquals(200, pitchAtFive.getResponse().getStatus(), "恰代次 5 应允许本次投酵");
        finishMainFerment(first);
        MvcResult recovered = recoverYeast(first, newRequestId(), 2, T0.plusHours(13),
                "95.00", false);
        assertEquals(200, recovered.getResponse().getStatus());
        assertEquals(6, json(recovered).get("generationAfter").asInt());
        assertEquals("AVAILABLE", json(recovered).get("yeastStatus").asText());

        long second = createBatch(newRequestId(), "B-GEN-2", "TANK-2");
        MvcResult pitchAtSix = pitch(second, newRequestId(), "Y-GEN-5", 3, T0.plusHours(14));
        assertEquals(422, pitchAtSix.getResponse().getStatus(), "代次 6 不能启动新主酵");
        assertEquals("FILLED", json(mockMvc.perform(org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get("/api/batches/" + second)).andReturn())
                .get("status").asText());
        assertEquals(6, yeastUnit("Y-GEN-5").get("generation").asInt(), "拒绝投酵不得改变代次");
    }

    @Test
    @DisplayName("回收时限：恰 72 小时仍可用，超过 72 小时失效；首次单元无回收时刻不受限")
    void recoveryValidityBoundaries() throws Exception {
        // 恰 72 小时：可投酵
        jdbc.update("INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at,"
                        + " occupied_by_batch_id, version) VALUES (?, 1, 'AVAILABLE', ?, NULL, 1)",
                "Y-72-OK", Timestamp.valueOf(T0.minusHours(72)));
        long okBatch = createBatch(newRequestId(), "B-72-1", "TANK-1");
        assertEquals(200, pitch(okBatch, newRequestId(), "Y-72-OK", 1, T0)
                .getResponse().getStatus(), "恰 72 小时应仍可用");

        // 超过 72 小时：失效 422
        jdbc.update("INSERT INTO yeast_unit (unit_code, generation, status, last_recovered_at,"
                        + " occupied_by_batch_id, version) VALUES (?, 1, 'AVAILABLE', ?, NULL, 1)",
                "Y-72-BAD", Timestamp.valueOf(T0.minusHours(73)));
        long badBatch = createBatch(newRequestId(), "B-72-2", "TANK-2");
        MvcResult expired = pitch(badBatch, newRequestId(), "Y-72-BAD", 1, T0);
        assertEquals(422, expired.getResponse().getStatus());
        assertEquals("FILLED", json(mockMvc.perform(org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get("/api/batches/" + badBatch)).andReturn())
                .get("status").asText());

        // 首次单元（无回收时刻）：即使投酵时刻遥远也不受限
        registerYeast(newRequestId(), "Y-FIRST");
        long firstBatch = createBatch(newRequestId(), "B-72-3", "TANK-3");
        assertEquals(200, pitch(firstBatch, newRequestId(), "Y-FIRST", 1, T0.plusYears(2))
                .getResponse().getStatus(), "首次单元不应受 72 小时限制");
    }

    @Test
    @DisplayName("回收判定边界：恰 90% 合格；89.99% 报废；活率合格但污染报废；报废不可再投、代次不变")
    void recoveryQualificationBoundaries() throws Exception {
        registerYeast(newRequestId(), "Y-REC-EDGE");
        long batchId = createBatch(newRequestId(), "B-REC-1", "TANK-1");
        pitch(batchId, newRequestId(), "Y-REC-EDGE", 1, T0);
        finishMainFerment(batchId);

        // 先验证恰 90.00 合格：用另一个单元走污染报废，再单独验证恰 90
        MvcResult contaminated = recoverYeast(batchId, newRequestId(), 2, T0.plusHours(13),
                "95.00", true);
        assertEquals(200, contaminated.getResponse().getStatus());
        JsonNode rec = json(contaminated);
        assertFalse(rec.get("qualified").asBoolean());
        assertEquals("SCRAPPED", rec.get("yeastStatus").asText());
        assertEquals(0, rec.get("generationAfter").asInt(), "报废时代次不变");
        assertEquals(3, rec.get("yeastVersion").asInt(), "报废回收版本仍应加 1");

        JsonNode scrapped = yeastUnit("Y-REC-EDGE");
        assertEquals("SCRAPPED", scrapped.get("status").asText());
        assertNull(scrapped.get("occupiedByBatchId").isNull() ? null
                : scrapped.get("occupiedByBatchId"), "报废应解除占用");

        // 报废单元不可再投酵
        long other = createBatch(newRequestId(), "B-REC-2", "TANK-2");
        assertEquals(422, pitch(other, newRequestId(), "Y-REC-EDGE", 3, T0.plusHours(14))
                .getResponse().getStatus());

        // 恰 90.00 合格、89.99 报废：两个新单元
        registerYeast(newRequestId(), "Y-REC-90");
        long b90 = createBatch(newRequestId(), "B-REC-3", "TANK-3");
        pitch(b90, newRequestId(), "Y-REC-90", 1, T0);
        finishMainFerment(b90);
        JsonNode exactly90 = json(recoverYeast(b90, newRequestId(), 2, T0.plusHours(13),
                "90.00", false));
        assertTrue(exactly90.get("qualified").asBoolean(), "恰 90% 应判定合格");
        assertEquals("AVAILABLE", exactly90.get("yeastStatus").asText());
    }

    @Test
    @DisplayName("89.99% 活率回收报废")
    void recoveryJustBelowLimitScrapped() throws Exception {
        registerYeast(newRequestId(), "Y-REC-LOW");
        long batchId = createBatch(newRequestId(), "B-LOW-1", "TANK-1");
        pitch(batchId, newRequestId(), "Y-REC-LOW", 1, T0);
        finishMainFerment(batchId);
        JsonNode rec = json(recoverYeast(batchId, newRequestId(), 2, T0.plusHours(13),
                "89.99", false));
        assertFalse(rec.get("qualified").asBoolean());
        assertEquals("SCRAPPED", rec.get("yeastStatus").asText());
        assertEquals(0, rec.get("generationAfter").asInt());
    }

    @Test
    @DisplayName("回收前置与一次性：主酵未完成 422、未绑定酵母 422、重复回收 422 且仅一条追溯")
    void recoveryPreconditionsAndOnceOnly() throws Exception {
        registerYeast(newRequestId(), "Y-PRE");
        long batchId = createBatch(newRequestId(), "B-PRE-1", "TANK-1");
        pitch(batchId, newRequestId(), "Y-PRE", 1, T0);

        // 主酵进行中回收：422，无追溯记录
        MvcResult early = recoverYeast(batchId, newRequestId(), 2, T0.plusHours(1),
                "95.00", false);
        assertEquals(422, early.getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_recovery WHERE batch_id = ?", Integer.class, batchId));
        assertEquals("IN_USE", yeastUnit("Y-PRE").get("status").asText(), "提前回收不得解除占用");

        // 未启用受控投酵的历史批次回收：422
        long legacy = createBatch(newRequestId(), "B-PRE-2", "TANK-2");
        startMainFerment(legacy, newRequestId(), T0);
        assertEquals(422, recoverYeast(legacy, newRequestId(), 1, T0.plusHours(1),
                "95.00", false).getResponse().getStatus());

        // 完成主酵并回收成功
        finishMainFerment(batchId);
        assertEquals(200, recoverYeast(batchId, newRequestId(), 2, T0.plusHours(14),
                "91.00", false).getResponse().getStatus());
        // 重复回收：422（失败批次也只能回收一次）
        assertEquals(422, recoverYeast(batchId, newRequestId(), 3, T0.plusHours(15),
                "91.00", false).getResponse().getStatus());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_recovery WHERE batch_id = ?", Integer.class, batchId));

        // 无回收记录查询：404
        assertEquals(404, mockMvc.perform(org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get("/api/batches/" + legacy + "/yeast-recovery"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("失败回收不改成熟度判定与清酒转出：批次可继续后熟、判定、转出")
    void failedRecoveryDoesNotChangeBatchMaturation() throws Exception {
        registerYeast(newRequestId(), "Y-KEEP");
        long batchId = createBatch(newRequestId(), "B-KEEP-1", "TANK-1");
        pitch(batchId, newRequestId(), "Y-KEEP", 1, T0);
        finishMainFerment(batchId);
        // 污染报废回收
        assertEquals(200, recoverYeast(batchId, newRequestId(), 2, T0.plusHours(13),
                "99.00", true).getResponse().getStatus());

        // 批次仍处降温，后续后熟→判定→转出不受影响
        assertEquals(200, startMaturation(batchId, newRequestId(), T0.plusHours(14))
                .getResponse().getStatus());
        JsonNode judged = json(judge(batchId, newRequestId(), T0.plusHours(14).plusDays(7)));
        assertTrue(judged.get("qualified").asBoolean());
        MvcResult transferred = transfer(batchId, newRequestId(),
                T0.plusHours(14).plusDays(7).plusHours(1));
        assertEquals(200, transferred.getResponse().getStatus());
        assertEquals("TRANSFERRED", json(transferred).get("status").asText());

        // 回收记录保持首次报废判定，不被后续流程修改
        JsonNode rec = json(mockMvc.perform(org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get("/api/batches/" + batchId + "/yeast-recovery"))
                .andReturn());
        assertFalse(rec.get("qualified").asBoolean());
        assertEquals("SCRAPPED", rec.get("yeastStatus").asText());
    }

    @Test
    @DisplayName("版本不符返回 409 且不变更；历史入口开始主酵后受控投酵 422 且不增次数")
    void versionMismatchAndLegacyStartCompatibility() throws Exception {
        registerYeast(newRequestId(), "Y-VER");
        long batchId = createBatch(newRequestId(), "B-VER-1", "TANK-1");

        MvcResult wrongVersion = pitch(batchId, newRequestId(), "Y-VER", 9, T0);
        assertEquals(409, wrongVersion.getResponse().getStatus());
        assertEquals(1, yeastUnit("Y-VER").get("version").asInt(), "版本不符不得变更单元");
        assertEquals("FILLED", json(mockMvc.perform(org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get("/api/batches/" + batchId)).andReturn())
                .get("status").asText());

        // 失败不占键：同一 requestId 携带正确参数可重新受理
        assertEquals(200, pitch(batchId, "retry-after-version-conflict", "Y-VER", 1, T0)
                .getResponse().getStatus());

        // 历史入口批次不受受控投酵影响
        long legacy = createBatch(newRequestId(), "B-VER-2", "TANK-2");
        startMainFerment(legacy, newRequestId(), T0);
        MvcResult latePitch = pitch(legacy, newRequestId(), "Y-VER", 2, T0.plusHours(1));
        assertEquals(422, latePitch.getResponse().getStatus(), "已启动主酵的批次重复启动应拒绝");
        // 单元仍只被第一个批次占用
        assertEquals(batchId, yeastUnit("Y-VER").get("occupiedByBatchId").asLong());
    }

    @Test
    @DisplayName("幂等：投酵与回收同键同参重放冻结首次结果，异参 409，失败不占键")
    void pitchAndRecoveryIdempotency() throws Exception {
        registerYeast(newRequestId(), "Y-IDEM");
        long batchId = createBatch(newRequestId(), "B-IDEM-Y", "TANK-1");

        String pitchKey = newRequestId();
        MvcResult p1 = pitch(batchId, pitchKey, "Y-IDEM", 1, T0);
        MvcResult p2 = pitch(batchId, pitchKey, "Y-IDEM", 1, T0);
        assertEquals(200, p2.getResponse().getStatus());
        assertEquals(p1.getResponse().getContentAsString(), p2.getResponse().getContentAsString(),
                "同键同参应原样冻结首次投酵结果");
        assertEquals(2, yeastUnit("Y-IDEM").get("version").asInt(), "重放不得重复增加版本");

        // 同键异参（时刻不同）：409
        MvcResult pConflict = pitch(batchId, pitchKey, "Y-IDEM", 1, T0.plusHours(2));
        assertEquals(409, pConflict.getResponse().getStatus());

        finishMainFerment(batchId);
        String recoveryKey = newRequestId();
        MvcResult r1 = recoverYeast(batchId, recoveryKey, 2, T0.plusHours(13), "93.50", false);
        MvcResult r2 = recoverYeast(batchId, recoveryKey, 2, T0.plusHours(13), "93.50", false);
        assertEquals(200, r2.getResponse().getStatus());
        assertEquals(r1.getResponse().getContentAsString(), r2.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_recovery WHERE batch_id = ?", Integer.class, batchId));

        MvcResult rConflict = recoverYeast(batchId, recoveryKey, 2, T0.plusHours(13),
                "80.00", false);
        assertEquals(409, rConflict.getResponse().getStatus(), "回收同键异参应返回 409");

        // 失败不占键：先对未完成主酵的批次回收失败（422），主酵完成后同键可成功
        registerYeast(newRequestId(), "Y-IDEM-2");
        long second = createBatch(newRequestId(), "B-IDEM-Z", "TANK-2");
        pitch(second, newRequestId(), "Y-IDEM-2", 1, T0);
        String retryKey = newRequestId();
        assertEquals(422, recoverYeast(second, retryKey, 2, T0.plusHours(1), "93.00", false)
                .getResponse().getStatus());
        finishMainFerment(second);
        assertEquals(200, recoverYeast(second, retryKey, 2, T0.plusHours(13), "93.00", false)
                .getResponse().getStatus(), "失败的请求不应占用幂等键");
    }

    @Test
    @DisplayName("参数非法 400：空 requestId、expectedVersion 为 0、活率越界")
    void invalidParamsReturn400() throws Exception {
        long batchId = createBatch(newRequestId(), "B-VAL-1", "TANK-1");

        assertEquals(400, pitch(batchId, "", "Y", 1, T0).getResponse().getStatus());
        assertEquals(400, doPost("/api/batches/" + batchId + "/yeast-pitch",
                new com.example.starter.fermentation.api.YeastDtos.PitchYeastRequest(
                        newRequestId(), "Y", 0, T0)).getResponse().getStatus());
        assertEquals(400, recoverYeast(batchId, newRequestId(), 1, T0, "100.01", false)
                .getResponse().getStatus());
        assertEquals(400, recoverYeast(batchId, newRequestId(), 1, T0, "-0.01", false)
                .getResponse().getStatus());

        // 批次不存在：404
        assertEquals(404, pitch(999999L, newRequestId(), "Y", 1, T0).getResponse().getStatus());
        // 酵母不存在：404
        assertEquals(404, pitch(batchId, newRequestId(), "NO-SUCH-YEAST", 1, T0)
                .getResponse().getStatus());
    }
}
