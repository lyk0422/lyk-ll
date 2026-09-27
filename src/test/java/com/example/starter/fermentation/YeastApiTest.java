package com.example.starter.fermentation;

import com.example.starter.fermentation.api.YeastDtos.ControlledStartRequest;
import com.example.starter.fermentation.api.YeastDtos.RecoverYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastUnitRequest;
import com.example.starter.fermentation.service.IdempotencyService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 酵母代次限用与投酵回收追溯测试：登记、受控投酵（代次 ≤5、72 小时失效）、
 * 回收判定（活率恰 90% 合格、污染报废）、版本乐观锁与幂等边界。
 */
class YeastApiTest extends BaseFermentationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    // ---------- 测试助手 ----------

    MvcResult registerYeast(String requestId, String unitCode) throws Exception {
        return doPost("/api/yeast-units", new RegisterYeastUnitRequest(requestId, unitCode));
    }

    MvcResult controlledStart(long batchId, String requestId, LocalDateTime at,
                              String unitCode, int expectedVersion) throws Exception {
        return doPost("/api/batches/" + batchId + "/main-ferment/start-controlled",
                new ControlledStartRequest(requestId, at, unitCode, expectedVersion));
    }

    MvcResult recoverYeast(String unitCode, String requestId, LocalDateTime at,
                           String viability, boolean contaminated, int expectedVersion)
            throws Exception {
        return doPost("/api/yeast-units/" + unitCode + "/recovery",
                new RecoverYeastRequest(requestId, at, new BigDecimal(viability),
                        contaminated, expectedVersion));
    }

    JsonNode getYeast(String unitCode) throws Exception {
        return json(mockMvc.perform(get("/api/yeast-units/" + unitCode)).andReturn());
    }

    JsonNode getRecoveries(String unitCode) throws Exception {
        return json(mockMvc.perform(get("/api/yeast-units/" + unitCode + "/recoveries"))
                .andReturn());
    }

    /** 登记酵母并驱动绑定批次完成主酵（进入降温），返回批次 ID；此时酵母版本为 2。 */
    long driveToCoolingWithYeast(String batchNo, String tankCode, String unitCode)
            throws Exception {
        assertEquals(201, registerYeast(newRequestId(), unitCode).getResponse().getStatus());
        long batchId = createBatch(newRequestId(), batchNo, tankCode);
        assertEquals(200, controlledStart(batchId, newRequestId(), T0, unitCode, 1)
                .getResponse().getStatus());
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(1), "0.09")
                .getResponse().getStatus());
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(14), "0.08")
                .getResponse().getStatus());
        assertEquals(200, cooling(batchId, newRequestId(), T0.plusHours(14))
                .getResponse().getStatus());
        return batchId;
    }

    // ---------- 登记 ----------

    @Test
    @DisplayName("登记酵母单元：初始代次 0、可用、版本 1；重复编号 409；缺参 400")
    void registerYeastUnit() throws Exception {
        MvcResult created = registerYeast(newRequestId(), "YEAST-A");
        assertEquals(201, created.getResponse().getStatus());
        JsonNode unit = json(created);
        assertEquals(0, unit.get("generation").asInt());
        assertEquals("AVAILABLE", unit.get("status").asText());
        assertEquals(1, unit.get("version").asInt());
        assertTrue(unit.get("lastRecoveredAt").isNull(), "首次单元无回收时刻");
        assertTrue(unit.get("activeBatchId").isNull());

        assertEquals(409, registerYeast(newRequestId(), "YEAST-A").getResponse().getStatus(),
                "重复编号应返回 409");
        assertEquals(400, doPost("/api/yeast-units",
                new RegisterYeastUnitRequest(newRequestId(), null)).getResponse().getStatus(),
                "缺少编号应返回 400");
        assertEquals(404, mockMvc.perform(get("/api/yeast-units/YEAST-NOPE"))
                .andReturn().getResponse().getStatus(), "不存在的单元应返回 404");
    }

    // ---------- 受控投酵 ----------

    @Test
    @DisplayName("受控投酵：原子绑定酵母启动主酵；历史入口（不指定酵母）保持兼容")
    void controlledPitchBindsYeastAtomically() throws Exception {
        assertEquals(201, registerYeast(newRequestId(), "YEAST-B").getResponse().getStatus());
        long batchId = createBatch(newRequestId(), "B-YEAST-1", "TANK-1");

        MvcResult started = controlledStart(batchId, newRequestId(), T0, "YEAST-B", 1);
        assertEquals(200, started.getResponse().getStatus());
        JsonNode body = json(started);
        assertEquals("MAIN_FERMENT", body.get("batch").get("status").asText());
        JsonNode yeast = body.get("yeastUnit");
        assertEquals("IN_USE", yeast.get("status").asText());
        assertEquals(2, yeast.get("version").asInt(), "绑定成功后版本 +1");
        assertEquals(batchId, yeast.get("activeBatchId").asLong());
        assertEquals(0, yeast.get("generation").asInt(), "投酵不增代次");

        // 历史入口兼容：不指定酵母仍可启动主酵
        long legacyBatch = createBatch(newRequestId(), "B-YEAST-2", "TANK-2");
        startMainFerment(legacyBatch, newRequestId(), T0);
        assertEquals("MAIN_FERMENT", json(mockMvc.perform(
                get("/api/batches/" + legacyBatch)).andReturn()).get("status").asText());
    }

    @Test
    @DisplayName("受控投酵失败分支：酵母/批次不存在 404，版本不符 409，状态非法 422，缺参 400")
    void controlledPitchFailures() throws Exception {
        assertEquals(201, registerYeast(newRequestId(), "YEAST-C").getResponse().getStatus());
        long batchId = createBatch(newRequestId(), "B-YEAST-3", "TANK-1");

        // 酵母不存在 → 404
        assertEquals(404, controlledStart(batchId, newRequestId(), T0, "YEAST-NOPE", 1)
                .getResponse().getStatus());
        // 批次不存在 → 404
        assertEquals(404, controlledStart(999999L, newRequestId(), T0, "YEAST-C", 1)
                .getResponse().getStatus());
        // 版本不符 → 409
        assertEquals(409, controlledStart(batchId, newRequestId(), T0, "YEAST-C", 99)
                .getResponse().getStatus());
        // 缺少 expectedVersion → 400
        assertEquals(400, doPost("/api/batches/" + batchId + "/main-ferment/start-controlled",
                new ControlledStartRequest(newRequestId(), T0, "YEAST-C", null))
                .getResponse().getStatus());

        // 占用中酵母不可再投：YEAST-C 绑定批次 1 后，批次 2 再投 → 422
        assertEquals(200, controlledStart(batchId, newRequestId(), T0, "YEAST-C", 1)
                .getResponse().getStatus());
        long batch2 = createBatch(newRequestId(), "B-YEAST-4", "TANK-2");
        assertEquals(422, controlledStart(batch2, newRequestId(), T0, "YEAST-C", 2)
                .getResponse().getStatus(), "占用中酵母应拒绝新投酵");
        // 已主酵批次重复受控启动 → 422（不重复绑定、不重复增版本）
        assertEquals(422, controlledStart(batchId, newRequestId(), T0.plusHours(1), "YEAST-C", 2)
                .getResponse().getStatus());
        JsonNode yeast = getYeast("YEAST-C");
        assertEquals(2, yeast.get("version").asInt(), "失败请求不得改变版本");
        assertEquals(batchId, yeast.get("activeBatchId").asLong());
    }

    @Test
    @DisplayName("代次限用：恰 5 允许本次投酵，代次 6 拒绝")
    void generationBoundary() throws Exception {
        assertEquals(201, registerYeast(newRequestId(), "YEAST-G5").getResponse().getStatus());
        jdbc.update("UPDATE yeast_unit SET generation = 5 WHERE unit_code = 'YEAST-G5'");
        long batch1 = createBatch(newRequestId(), "B-YEAST-G5", "TANK-1");
        assertEquals(200, controlledStart(batch1, newRequestId(), T0, "YEAST-G5", 1)
                .getResponse().getStatus(), "代次恰 5 应允许本次投酵");

        assertEquals(201, registerYeast(newRequestId(), "YEAST-G6").getResponse().getStatus());
        jdbc.update("UPDATE yeast_unit SET generation = 6 WHERE unit_code = 'YEAST-G6'");
        long batch2 = createBatch(newRequestId(), "B-YEAST-G6", "TANK-2");
        assertEquals(422, controlledStart(batch2, newRequestId(), T0, "YEAST-G6", 1)
                .getResponse().getStatus(), "代次 6 不得启动新主酵");
        assertEquals("FILLED", json(mockMvc.perform(get("/api/batches/" + batch2)).andReturn())
                .get("status").asText(), "拒绝后批次应保持满罐");
    }

    // ---------- 回收 ----------

    @Test
    @DisplayName("回收主流程：活率恰 90% 合格，代次 +1 转可用，回收记录与解除占用同事务")
    void recoveryHappyPathEdgeViability() throws Exception {
        long batchId = driveToCoolingWithYeast("B-YEAST-R1", "TANK-1", "YEAST-R1");
        LocalDateTime recoveredAt = T0.plusHours(24);

        MvcResult recovered = recoverYeast("YEAST-R1", newRequestId(), recoveredAt,
                "90.00", false, 2);
        assertEquals(200, recovered.getResponse().getStatus());
        JsonNode body = json(recovered);
        JsonNode unit = body.get("yeastUnit");
        assertEquals("AVAILABLE", unit.get("status").asText());
        assertEquals(1, unit.get("generation").asInt(), "合格回收代次 +1");
        assertEquals(3, unit.get("version").asInt(), "回收成功后版本 +1");
        assertEquals("2026-09-27T08:00:00", unit.get("lastRecoveredAt").asText());
        assertTrue(unit.get("activeBatchId").isNull(), "回收应解除批次占用");
        JsonNode recovery = body.get("recovery");
        assertEquals("RECYCLED", recovery.get("outcome").asText());
        assertEquals(1, recovery.get("generationAfter").asInt());
        assertEquals(batchId, recovery.get("batchId").asLong());
        assertEquals(90.0, recovery.get("viabilityPercent").doubleValue());

        // 追溯链可查
        JsonNode chain = getRecoveries("YEAST-R1");
        assertEquals(1, chain.size());
        assertEquals("RECYCLED", chain.get(0).get("outcome").asText());
    }

    @Test
    @DisplayName("回收失败分支：活率不足或污染报废；主酵未完成 422；重复回收 422；版本 409；不存在 404")
    void recoveryFailureBranches() throws Exception {
        // 活率 89.99 < 90 → 报废
        driveToCoolingWithYeast("B-YEAST-R2", "TANK-1", "YEAST-R2");
        MvcResult retired = recoverYeast("YEAST-R2", newRequestId(), T0.plusHours(24),
                "89.99", false, 2);
        assertEquals(200, retired.getResponse().getStatus());
        JsonNode retiredUnit = json(retired).get("yeastUnit");
        assertEquals("RETIRED", retiredUnit.get("status").asText());
        assertEquals(0, retiredUnit.get("generation").asInt(), "报废不增代次");
        assertEquals("RETIRED", json(retired).get("recovery").get("outcome").asText());

        // 报废单元不可再投酵
        long batchX = createBatch(newRequestId(), "B-YEAST-RX", "TANK-2");
        assertEquals(422, controlledStart(batchX, newRequestId(), T0, "YEAST-R2", 3)
                .getResponse().getStatus(), "报废单元不得再投酵");

        // 活率合格但检出污染 → 报废
        driveToCoolingWithYeast("B-YEAST-R3", "TANK-3", "YEAST-R3");
        MvcResult contaminated = recoverYeast("YEAST-R3", newRequestId(), T0.plusHours(24),
                "95.00", true, 2);
        assertEquals(200, contaminated.getResponse().getStatus());
        assertEquals("RETIRED", json(contaminated).get("yeastUnit").get("status").asText());

        // 失败批次也只能回收一次：重复回收（新 requestId）→ 422，代次不再变化
        assertEquals(422, recoverYeast("YEAST-R2", newRequestId(), T0.plusHours(25),
                "99.00", false, 3).getResponse().getStatus(), "已回收单元不得再次回收");
        assertEquals(0, getYeast("YEAST-R2").get("generation").asInt(),
                "重复回收不得再增代次");
        assertEquals(1, getRecoveries("YEAST-R2").size(), "回收记录仅一条");

        // 主酵未完成不受理回收
        assertEquals(201, registerYeast(newRequestId(), "YEAST-R4").getResponse().getStatus());
        long batch4 = createBatch(newRequestId(), "B-YEAST-R4", "TANK-4");
        assertEquals(200, controlledStart(batch4, newRequestId(), T0, "YEAST-R4", 1)
                .getResponse().getStatus());
        assertEquals(422, recoverYeast("YEAST-R4", newRequestId(), T0.plusHours(5),
                "95.00", false, 2).getResponse().getStatus(), "主酵中不得回收");

        // 版本不符 → 409；单元不存在 → 404
        assertEquals(409, recoverYeast("YEAST-R4", newRequestId(), T0.plusHours(6),
                "95.00", false, 99).getResponse().getStatus());
        assertEquals(404, recoverYeast("YEAST-NOPE", newRequestId(), T0.plusHours(6),
                "95.00", false, 1).getResponse().getStatus());
        // 缺参 → 400
        assertEquals(400, doPost("/api/yeast-units/YEAST-R4/recovery",
                new RecoverYeastRequest(newRequestId(), T0.plusHours(6), null, false, 2))
                .getResponse().getStatus());
    }

    @Test
    @DisplayName("72 小时失效：恰 72 小时仍可用，超过失效置 EXPIRED，首次单元不受限")
    void expiryBoundary() throws Exception {
        LocalDateTime recoveredAt = T0.plusHours(24);

        // 恰 72 小时：允许投酵
        driveToCoolingWithYeast("B-YEAST-E1", "TANK-1", "YEAST-E1");
        assertEquals(200, recoverYeast("YEAST-E1", newRequestId(), recoveredAt,
                "95.00", false, 2).getResponse().getStatus());
        long batch2 = createBatch(newRequestId(), "B-YEAST-E2", "TANK-2");
        assertEquals(200, controlledStart(batch2, newRequestId(),
                recoveredAt.plusHours(72), "YEAST-E1", 3).getResponse().getStatus(),
                "恰 72 小时仍可用");

        // 超过 72 小时 1 分钟：拒绝并置 EXPIRED
        driveToCoolingWithYeast("B-YEAST-E3", "TANK-3", "YEAST-E2");
        assertEquals(200, recoverYeast("YEAST-E2", newRequestId(), recoveredAt,
                "95.00", false, 2).getResponse().getStatus());
        long batch4 = createBatch(newRequestId(), "B-YEAST-E4", "TANK-4");
        assertEquals(422, controlledStart(batch4, newRequestId(),
                recoveredAt.plusHours(72).plusMinutes(1), "YEAST-E2", 3)
                .getResponse().getStatus(), "超过 72 小时应拒绝投酵");
        JsonNode expired = getYeast("YEAST-E2");
        assertEquals("EXPIRED", expired.get("status").asText(), "超时应置为失效");
        // 失效单元不可再投酵
        long batch5 = createBatch(newRequestId(), "B-YEAST-E5", "TANK-5");
        assertEquals(422, controlledStart(batch5, newRequestId(),
                recoveredAt.plusHours(80), "YEAST-E2", 4).getResponse().getStatus());

        // 首次单元（无回收时刻）：任意晚时刻均可投酵
        assertEquals(201, registerYeast(newRequestId(), "YEAST-FRESH").getResponse().getStatus());
        long batch6 = createBatch(newRequestId(), "B-YEAST-E6", "TANK-6");
        assertEquals(200, controlledStart(batch6, newRequestId(),
                T0.plusHours(1000), "YEAST-FRESH", 1).getResponse().getStatus(),
                "首次单元不受 72 小时失效限制");
    }

    // ---------- 幂等与指纹 ----------

    @Test
    @DisplayName("幂等：同键同参重放首次冻结结果，同键异参 409，失败不占键")
    void idempotencyBoundaries() throws Exception {
        // 登记重放
        String registerKey = newRequestId();
        MvcResult first = registerYeast(registerKey, "YEAST-I1");
        assertEquals(201, first.getResponse().getStatus());
        MvcResult replay = registerYeast(registerKey, "YEAST-I1");
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参应原样重放");
        // 同键异参 → 409
        assertEquals(409, registerYeast(registerKey, "YEAST-I1-OTHER").getResponse().getStatus());
        Integer unitCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_unit WHERE unit_code LIKE 'YEAST-I1%'", Integer.class);
        assertEquals(1, unitCount, "重放与 409 均不得产生新单元");

        // 受控投酵重放：版本不重复递增
        long batchId = createBatch(newRequestId(), "B-YEAST-I1", "TANK-1");
        String pitchKey = newRequestId();
        MvcResult pitch1 = controlledStart(batchId, pitchKey, T0, "YEAST-I1", 1);
        assertEquals(200, pitch1.getResponse().getStatus());
        MvcResult pitch2 = controlledStart(batchId, pitchKey, T0, "YEAST-I1", 1);
        assertEquals(200, pitch2.getResponse().getStatus());
        assertEquals(pitch1.getResponse().getContentAsString(),
                pitch2.getResponse().getContentAsString());
        assertEquals(2, getYeast("YEAST-I1").get("version").asInt(),
                "重放不得重复递增版本");
        assertEquals(409, controlledStart(batchId, pitchKey, T0.plusHours(1), "YEAST-I1", 1)
                .getResponse().getStatus(), "同键异参应返回 409");

        // 失败不占键：主酵中回收 422 后，同键同参在完成主酵后可成功受理
        String recoverKey = newRequestId();
        assertEquals(422, recoverYeast("YEAST-I1", recoverKey, T0.plusHours(24),
                "95.00", false, 2).getResponse().getStatus());
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(1), "0.09")
                .getResponse().getStatus());
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(14), "0.08")
                .getResponse().getStatus());
        assertEquals(200, cooling(batchId, newRequestId(), T0.plusHours(14))
                .getResponse().getStatus());
        assertEquals(200, recoverYeast("YEAST-I1", recoverKey, T0.plusHours(24),
                "95.00", false, 2).getResponse().getStatus(), "失败不占键，可同键重试");

        // 回收重放：同键同参返回首次结果，代次不重复增加
        MvcResult recoverReplay = recoverYeast("YEAST-I1", recoverKey, T0.plusHours(24),
                "95.00", false, 2);
        assertEquals(200, recoverReplay.getResponse().getStatus());
        assertEquals(1, getYeast("YEAST-I1").get("generation").asInt(),
                "回收重放不得重复增代次");
        assertEquals(1, getRecoveries("YEAST-I1").size(), "回收记录仅一条");
        // 回收同键异参 → 409
        assertEquals(409, recoverYeast("YEAST-I1", recoverKey, T0.plusHours(25),
                "95.00", false, 2).getResponse().getStatus());
    }

    @Test
    @DisplayName("结构化指纹：合法文本含分隔符也可无歧义区分")
    void fingerprintUnambiguousWithSeparators() throws Exception {
        // 字段值本身含 '|'、':'、';' 时，长度前缀编码保证字段边界无歧义
        assertNotEquals(
                idempotencyService.structuredFingerprint("e", "r", "a|b"),
                idempotencyService.structuredFingerprint("e", "r", "a", "|b"));
        assertNotEquals(
                idempotencyService.structuredFingerprint("e", "r", "ab"),
                idempotencyService.structuredFingerprint("e", "r", "a", "b"));
        assertNotEquals(
                idempotencyService.structuredFingerprint("e", "r", "1:a;"),
                idempotencyService.structuredFingerprint("e", "r", "1", "a;"));
        assertEquals(
                idempotencyService.structuredFingerprint("e", "r", "a|b", "c;d"),
                idempotencyService.structuredFingerprint("e", "r", "a|b", "c;d"),
                "同参指纹应稳定");

        // 含分隔符的编号可正常登记、重放与异参识别
        String weirdCode = "YEAST|W;1:2";
        String key = newRequestId();
        MvcResult created = registerYeast(key, weirdCode);
        assertEquals(201, created.getResponse().getStatus());
        MvcResult replayed = registerYeast(key, weirdCode);
        assertEquals(201, replayed.getResponse().getStatus());
        assertEquals(created.getResponse().getContentAsString(),
                replayed.getResponse().getContentAsString());
        assertEquals(409, registerYeast(key, "YEAST|W;1:3").getResponse().getStatus(),
                "同键异参（仅尾部分隔文本不同）应返回 409");
    }
}
