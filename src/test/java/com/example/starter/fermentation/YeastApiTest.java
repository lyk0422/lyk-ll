package com.example.starter.fermentation;

import com.example.starter.fermentation.api.YeastDtos.ControlledStartRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酵母代次限用与投酵回收追溯测试：登记、受控投酵（代次/状态/失效边界）、
 * 回收判定（活率恰 90% 合格、污染报废）、版本乐观锁与幂等语义。
 */
class YeastApiTest extends BaseFermentationTest {

    // ---------- 登记 ----------

    @Test
    @DisplayName("登记酵母：初始代次 0、可用、版本 1；重复编号 409；同键重放不产生重复数据")
    void registerInitialStateAndReplay() throws Exception {
        String requestId = newRequestId();
        MvcResult first = registerYeast(requestId, "YEAST-1");
        assertEquals(201, first.getResponse().getStatus());
        JsonNode body = json(first);
        assertEquals(0, body.get("generation").asInt(), "初始代次应为 0");
        assertEquals("AVAILABLE", body.get("status").asText(), "初始状态应为可用");
        assertEquals(1, body.get("version").asInt(), "初始版本应为 1");
        assertTrue(body.get("lastRecoveredAt").isNull(), "首次登记无回收时刻");

        MvcResult replay = registerYeast(requestId, "YEAST-1");
        assertEquals(201, replay.getResponse().getStatus(), "同键同参应重放首次结果");
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());

        MvcResult duplicate = registerYeast(newRequestId(), "YEAST-1");
        assertEquals(409, duplicate.getResponse().getStatus(), "重复编号应返回 409");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_unit WHERE yeast_code = 'YEAST-1'", Integer.class);
        assertEquals(1, count, "重复登记不得产生重复数据");
    }

    // ---------- 受控投酵 ----------

    @Test
    @DisplayName("受控投酵：原子绑定酵母并启动主酵；旧入口不指定酵母仍兼容")
    void controlledStartBindsYeastAtomically() throws Exception {
        assertEquals(201, registerYeast(newRequestId(), "YEAST-1").getResponse().getStatus());
        long batchId = createBatch(newRequestId(), "B-Y1", "TANK-1");

        MvcResult result = controlledStart(batchId, newRequestId(), "YEAST-1", 1, T0);
        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("MAIN_FERMENT", body.get("status").asText());
        assertEquals("YEAST-1", body.get("yeastCode").asText());
        assertEquals(0, body.get("yeastGeneration").asInt());
        assertEquals(2, body.get("yeastVersion").asInt(), "绑定成功后版本应加 1");

        JsonNode yeast = getYeast("YEAST-1");
        assertEquals("IN_USE", yeast.get("status").asText(), "投酵后酵母应为绑定中");
        assertEquals(batchId, yeast.get("activeBatchId").asLong(), "应绑定当前批次");

        // 旧入口（不指定酵母）兼容：另一批次仍可走原主酵启动
        long legacyBatch = createBatch(newRequestId(), "B-Y2", "TANK-2");
        startMainFerment(legacyBatch, newRequestId(), T0);
        JsonNode legacy = json(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/batches/" + legacyBatch)).andReturn());
        assertEquals("MAIN_FERMENT", legacy.get("status").asText(), "旧入口应仍可启动主酵");
    }

    @Test
    @DisplayName("受控投酵失败分支：批次/酵母不存在 404，参数非法 400，版本不匹配 409，非满罐 422")
    void controlledStartFailureBranches() throws Exception {
        assertEquals(201, registerYeast(newRequestId(), "YEAST-1").getResponse().getStatus());
        long batchId = createBatch(newRequestId(), "B-Y1", "TANK-1");

        MvcResult batchNotFound = controlledStart(999999L, newRequestId(), "YEAST-1", 1, T0);
        assertEquals(404, batchNotFound.getResponse().getStatus(), "批次不存在应返回 404");

        MvcResult yeastNotFound = controlledStart(batchId, newRequestId(), "YEAST-X", 1, T0);
        assertEquals(404, yeastNotFound.getResponse().getStatus(), "酵母不存在应返回 404");

        MvcResult badRequest = doPost("/api/batches/" + batchId + "/main-ferment/start-controlled",
                new ControlledStartRequest("", "YEAST-1", 1, T0));
        assertEquals(400, badRequest.getResponse().getStatus(), "空 requestId 应返回 400");

        Map<String, Object> missingVersion = new HashMap<>();
        missingVersion.put("requestId", newRequestId());
        missingVersion.put("yeastCode", "YEAST-1");
        missingVersion.put("startedAt", T0.toString());
        MvcResult missing = doPost("/api/batches/" + batchId + "/main-ferment/start-controlled",
                missingVersion);
        assertEquals(400, missing.getResponse().getStatus(), "缺少 expectedVersion 应返回 400");

        MvcResult versionConflict = controlledStart(batchId, newRequestId(), "YEAST-1", 2, T0);
        assertEquals(409, versionConflict.getResponse().getStatus(), "版本不匹配应返回 409");

        // 批次已进入主酵后再次受控投酵：422，且不重复绑定
        assertEquals(200, controlledStart(batchId, newRequestId(), "YEAST-1", 1, T0)
                .getResponse().getStatus());
        assertEquals(201, registerYeast(newRequestId(), "YEAST-2").getResponse().getStatus());
        MvcResult notFilled = controlledStart(batchId, newRequestId(), "YEAST-2", 1,
                T0.plusMinutes(1));
        assertEquals(422, notFilled.getResponse().getStatus(), "非满罐批次应返回 422");
        JsonNode yeast2 = getYeast("YEAST-2");
        assertEquals("AVAILABLE", yeast2.get("status").asText(), "失败不得绑定酵母");
        assertEquals(1, yeast2.get("version").asInt(), "失败不得增加版本");
    }

    @Test
    @DisplayName("一单元仅供一个批次：酵母绑定中其他批次投酵 422；报废单元不可投酵")
    void controlledStartRejectsInUseAndScrapped() throws Exception {
        assertEquals(201, registerYeast(newRequestId(), "YEAST-1").getResponse().getStatus());
        long batchA = createBatch(newRequestId(), "B-Y1", "TANK-1");
        assertEquals(200, controlledStart(batchA, newRequestId(), "YEAST-1", 1, T0)
                .getResponse().getStatus());

        long batchB = createBatch(newRequestId(), "B-Y2", "TANK-2");
        MvcResult inUse = controlledStart(batchB, newRequestId(), "YEAST-1", 2, T0);
        assertEquals(422, inUse.getResponse().getStatus(), "绑定中酵母不可再投酵");

        // 报废单元不可投酵：回收判定不合格（活率 80%）
        driveBatchToCooling(batchA);
        assertEquals(200, recycle("YEAST-1", newRequestId(), batchA, 2, "80.00", false,
                T0.plusHours(20)).getResponse().getStatus());
        JsonNode scrapped = getYeast("YEAST-1");
        assertEquals("SCRAPPED", scrapped.get("status").asText());
        MvcResult pitchScrapped = controlledStart(batchB, newRequestId(), "YEAST-1", 3, T0);
        assertEquals(422, pitchScrapped.getResponse().getStatus(), "报废单元不可再投酵");
    }

    @Test
    @DisplayName("代次限用：恰 5 允许投酵，代次 6 拒绝；回收合格达 6 后不可再启动新主酵")
    void generationLimitEnforced() throws Exception {
        // 恰 5 允许
        assertEquals(201, registerYeast(newRequestId(), "YEAST-G5").getResponse().getStatus());
        jdbc.update("UPDATE yeast_unit SET generation = 5 WHERE yeast_code = 'YEAST-G5'");
        long batchA = createBatch(newRequestId(), "B-G5", "TANK-1");
        assertEquals(200, controlledStart(batchA, newRequestId(), "YEAST-G5", 1, T0)
                .getResponse().getStatus(), "恰 5 代应允许本次投酵");

        // 回收合格 → 代次 6，不可再投酵
        driveBatchToCooling(batchA);
        assertEquals(200, recycle("YEAST-G5", newRequestId(), batchA, 2, "95.00", false,
                T0.plusHours(20)).getResponse().getStatus());
        JsonNode yeast = getYeast("YEAST-G5");
        assertEquals(6, yeast.get("generation").asInt(), "回收合格应使代次加 1 至 6");
        assertEquals("AVAILABLE", yeast.get("status").asText());

        long batchB = createBatch(newRequestId(), "B-G6", "TANK-2");
        MvcResult rejected = controlledStart(batchB, newRequestId(), "YEAST-G5", 3, T0);
        assertEquals(422, rejected.getResponse().getStatus(), "代次 6 不能启动新主酵");

        // 直接构造代次 6 亦拒绝
        assertEquals(201, registerYeast(newRequestId(), "YEAST-G6").getResponse().getStatus());
        jdbc.update("UPDATE yeast_unit SET generation = 6 WHERE yeast_code = 'YEAST-G6'");
        MvcResult rejected6 = controlledStart(batchB, newRequestId(), "YEAST-G6", 1, T0);
        assertEquals(422, rejected6.getResponse().getStatus());
    }

    @Test
    @DisplayName("失效边界：距回收恰 72 小时仍可用，超过 72 小时失效，首次登记无回收时刻不受限")
    void expiryBoundary() throws Exception {
        LocalDateTime recoveredAt = T0;
        // 恰 72 小时仍可用
        assertEquals(201, registerYeast(newRequestId(), "YEAST-E1").getResponse().getStatus());
        jdbc.update("UPDATE yeast_unit SET last_recovered_at = ? WHERE yeast_code = 'YEAST-E1'",
                Timestamp.valueOf(recoveredAt));
        long batchA = createBatch(newRequestId(), "B-E1", "TANK-1");
        assertEquals(200, controlledStart(batchA, newRequestId(), "YEAST-E1", 1,
                recoveredAt.plusHours(72)).getResponse().getStatus(), "恰 72 小时仍可用");

        // 超过 72 小时失效
        assertEquals(201, registerYeast(newRequestId(), "YEAST-E2").getResponse().getStatus());
        jdbc.update("UPDATE yeast_unit SET last_recovered_at = ? WHERE yeast_code = 'YEAST-E2'",
                Timestamp.valueOf(recoveredAt));
        long batchB = createBatch(newRequestId(), "B-E2", "TANK-2");
        MvcResult expired = controlledStart(batchB, newRequestId(), "YEAST-E2", 1,
                recoveredAt.plusHours(72).plusMinutes(1));
        assertEquals(422, expired.getResponse().getStatus(), "超过 72 小时应失效");

        // 首次登记无回收时刻不受此限
        assertEquals(201, registerYeast(newRequestId(), "YEAST-E3").getResponse().getStatus());
        long batchC = createBatch(newRequestId(), "B-E3", "TANK-3");
        assertEquals(200, controlledStart(batchC, newRequestId(), "YEAST-E3", 1,
                T0.plusDays(30)).getResponse().getStatus(), "无回收时刻不受 72 小时限制");
    }

    // ---------- 回收 ----------

    @Test
    @DisplayName("回收合格：恰 90% 活率无污染转可用、代次加 1、解除占用同事务，可追溯")
    void recycleQualifiedBoundary() throws Exception {
        assertEquals(201, registerYeast(newRequestId(), "YEAST-1").getResponse().getStatus());
        long batchId = createBatch(newRequestId(), "B-Y1", "TANK-1");
        assertEquals(200, controlledStart(batchId, newRequestId(), "YEAST-1", 1, T0)
                .getResponse().getStatus());
        driveBatchToCooling(batchId);

        LocalDateTime recoveredAt = T0.plusHours(20);
        MvcResult result = recycle("YEAST-1", newRequestId(), batchId, 2, "90.00", false,
                recoveredAt);
        assertEquals(200, result.getResponse().getStatus(), "恰 90% 活率应判定合格");
        JsonNode body = json(result);
        assertEquals("RETURNED", body.get("result").asText());
        assertEquals(0, body.get("generationBefore").asInt());
        assertEquals(1, body.get("generationAfter").asInt());
        assertEquals(3, body.get("yeastVersion").asInt(), "回收成功后版本应再加 1");

        JsonNode yeast = getYeast("YEAST-1");
        assertEquals("AVAILABLE", yeast.get("status").asText());
        assertEquals(1, yeast.get("generation").asInt());
        assertEquals(3, yeast.get("version").asInt());
        assertTrue(yeast.get("activeBatchId").isNull(), "回收应解除批次占用");
        assertEquals("2026-09-27T04:00:00", yeast.get("lastRecoveredAt").asText());

        // 追溯链可复算
        JsonNode recycles = json(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/yeast-units/YEAST-1/recycles")).andReturn());
        assertEquals(1, recycles.size());
        assertEquals(batchId, recycles.get(0).get("batchId").asLong());
        assertEquals(0, recycles.get(0).get("viabilityPercent").decimalValue()
                .compareTo(new java.math.BigDecimal("90.00")), "活率应记录为 90.00");
        assertEquals("RETURNED", recycles.get(0).get("result").asText());
    }

    @Test
    @DisplayName("回收报废：活率不足 90% 或有污染即报废，代次不变，不可再投酵")
    void recycleScrapped() throws Exception {
        // 活率 89.99 报废
        assertEquals(201, registerYeast(newRequestId(), "YEAST-1").getResponse().getStatus());
        long batchA = createBatch(newRequestId(), "B-Y1", "TANK-1");
        assertEquals(200, controlledStart(batchA, newRequestId(), "YEAST-1", 1, T0)
                .getResponse().getStatus());
        driveBatchToCooling(batchA);
        MvcResult low = recycle("YEAST-1", newRequestId(), batchA, 2, "89.99", false,
                T0.plusHours(20));
        assertEquals(200, low.getResponse().getStatus());
        assertEquals("SCRAPPED", json(low).get("result").asText());
        JsonNode yeast = getYeast("YEAST-1");
        assertEquals("SCRAPPED", yeast.get("status").asText());
        assertEquals(0, yeast.get("generation").asInt(), "报废不应增加代次");

        // 有污染即使活率 95% 也报废
        assertEquals(201, registerYeast(newRequestId(), "YEAST-2").getResponse().getStatus());
        long batchB = createBatch(newRequestId(), "B-Y2", "TANK-2");
        assertEquals(200, controlledStart(batchB, newRequestId(), "YEAST-2", 1, T0)
                .getResponse().getStatus());
        driveBatchToCooling(batchB);
        MvcResult contaminated = recycle("YEAST-2", newRequestId(), batchB, 2, "95.00", true,
                T0.plusHours(20));
        assertEquals(200, contaminated.getResponse().getStatus());
        assertEquals("SCRAPPED", json(contaminated).get("result").asText());
        assertEquals("SCRAPPED", getYeast("YEAST-2").get("status").asText());
    }

    @Test
    @DisplayName("回收前置：绑定批次未完成主酵 422；版本不匹配 409；酵母不存在 404")
    void recyclePreconditions() throws Exception {
        assertEquals(201, registerYeast(newRequestId(), "YEAST-1").getResponse().getStatus());
        long batchId = createBatch(newRequestId(), "B-Y1", "TANK-1");
        assertEquals(200, controlledStart(batchId, newRequestId(), "YEAST-1", 1, T0)
                .getResponse().getStatus());

        MvcResult notFinished = recycle("YEAST-1", newRequestId(), batchId, 2, "95.00", false,
                T0.plusHours(5));
        assertEquals(422, notFinished.getResponse().getStatus(), "主酵未完成不受理回收");

        driveBatchToCooling(batchId);
        MvcResult versionConflict = recycle("YEAST-1", newRequestId(), batchId, 5, "95.00",
                false, T0.plusHours(20));
        assertEquals(409, versionConflict.getResponse().getStatus(), "版本不匹配应返回 409");

        MvcResult notFound = recycle("YEAST-X", newRequestId(), batchId, 1, "95.00", false,
                T0.plusHours(20));
        assertEquals(404, notFound.getResponse().getStatus(), "酵母不存在应返回 404");

        // 未绑定该批次：422
        assertEquals(201, registerYeast(newRequestId(), "YEAST-2").getResponse().getStatus());
        MvcResult notBound = recycle("YEAST-2", newRequestId(), batchId, 1, "95.00", false,
                T0.plusHours(20));
        assertEquals(422, notBound.getResponse().getStatus(), "未投酵绑定不受理回收");
    }

    @Test
    @DisplayName("每批次仅回收一次：回收后新请求不再增代次；失败批次（REVIEW）也只能回收一次")
    void recycleOnlyOncePerBatch() throws Exception {
        // 正常批次回收一次后，新请求 422 且代次不再增加
        assertEquals(201, registerYeast(newRequestId(), "YEAST-1").getResponse().getStatus());
        long batchA = createBatch(newRequestId(), "B-Y1", "TANK-1");
        assertEquals(200, controlledStart(batchA, newRequestId(), "YEAST-1", 1, T0)
                .getResponse().getStatus());
        driveBatchToCooling(batchA);
        assertEquals(200, recycle("YEAST-1", newRequestId(), batchA, 2, "95.00", false,
                T0.plusHours(20)).getResponse().getStatus());
        MvcResult again = recycle("YEAST-1", newRequestId(), batchA, 3, "95.00", false,
                T0.plusHours(21));
        assertEquals(422, again.getResponse().getStatus(), "已回收单元不受理新回收请求");
        assertEquals(1, getYeast("YEAST-1").get("generation").asInt(), "不得再增代次");
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_recycle_record WHERE batch_id = ?",
                Integer.class, batchA);
        assertEquals(1, records, "每批次仅一条回收记录");

        // 失败批次（整批复审 REVIEW 终态）可回收一次，且仅一次
        assertEquals(201, registerYeast(newRequestId(), "YEAST-2").getResponse().getStatus());
        long batchB = createBatch(newRequestId(), "B-Y2", "TANK-2");
        assertEquals(200, controlledStart(batchB, newRequestId(), "YEAST-2", 1, T0)
                .getResponse().getStatus());
        driveBatchToCooling(batchB);
        assertEquals(200, startMaturation(batchB, newRequestId(), T0.plusHours(15))
                .getResponse().getStatus());
        assertEquals(200, extendMaturation(batchB, newRequestId(), T0.plusDays(8))
                .getResponse().getStatus());
        MvcResult judged = judge(batchB, newRequestId(), maturationStart().plusDays(13));
        assertEquals(200, judged.getResponse().getStatus());
        assertEquals("REVIEW", json(judged).get("status").asText(), "延长后仍不合格应整批复审");

        assertEquals(200, recycle("YEAST-2", newRequestId(), batchB, 2, "95.00", false,
                T0.plusDays(14)).getResponse().getStatus(), "失败批次完成主酵后允许回收一次");
        MvcResult againFailed = recycle("YEAST-2", newRequestId(), batchB, 3, "95.00", false,
                T0.plusDays(15));
        assertEquals(422, againFailed.getResponse().getStatus(), "失败批次也只能回收一次");
        assertEquals(1, getYeast("YEAST-2").get("generation").asInt());
    }

    // ---------- 幂等 ----------

    @Test
    @DisplayName("幂等：同键同参重放首次冻结结果，同键异参 409，失败不占键")
    void idempotencySemantics() throws Exception {
        assertEquals(201, registerYeast(newRequestId(), "YEAST-1").getResponse().getStatus());
        long batchId = createBatch(newRequestId(), "B-Y1", "TANK-1");

        // 受控投酵同键重放：返回首次结果，不重复绑定、版本不再增加
        String pitchRequestId = newRequestId();
        MvcResult first = controlledStart(batchId, pitchRequestId, "YEAST-1", 1, T0);
        assertEquals(200, first.getResponse().getStatus());
        MvcResult replay = controlledStart(batchId, pitchRequestId, "YEAST-1", 1, T0);
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参应重放首次冻结结果");
        assertEquals(2, getYeast("YEAST-1").get("version").asInt(), "重放不得重复增版本");

        // 同键异参：409
        MvcResult conflict = controlledStart(batchId, pitchRequestId, "YEAST-1", 1,
                T0.plusHours(1));
        assertEquals(409, conflict.getResponse().getStatus(), "同键异参应返回 409");

        // 失败不占键：回收时批次未完成主酵 422，同键修正参数后受理
        driveBatchToCooling(batchId);
        String recycleRequestId = newRequestId();
        // 先以错误版本触发 409（失败不占键）
        MvcResult failed = recycle("YEAST-1", recycleRequestId, batchId, 9, "95.00", false,
                T0.plusHours(20));
        assertEquals(409, failed.getResponse().getStatus());
        MvcResult succeeded = recycle("YEAST-1", recycleRequestId, batchId, 2, "95.00", false,
                T0.plusHours(20));
        assertEquals(200, succeeded.getResponse().getStatus(), "失败不占键，修正后应受理");

        // 回收同键重放：代次不再增加
        MvcResult recycleReplay = recycle("YEAST-1", recycleRequestId, batchId, 2, "95.00",
                false, T0.plusHours(20));
        assertEquals(200, recycleReplay.getResponse().getStatus());
        assertEquals(succeeded.getResponse().getContentAsString(),
                recycleReplay.getResponse().getContentAsString());
        assertEquals(1, getYeast("YEAST-1").get("generation").asInt(), "重放不得再增代次");

        // 回收同键异参：409
        MvcResult recycleConflict = recycle("YEAST-1", recycleRequestId, batchId, 2, "80.00",
                false, T0.plusHours(20));
        assertEquals(409, recycleConflict.getResponse().getStatus());
    }

    // ---------- 辅助 ----------

    /** 主酵期内补两次合格双乙酰并下达降温指令，使批次完成主酵。 */
    private void driveBatchToCooling(long batchId) throws Exception {
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(1), "0.09")
                .getResponse().getStatus());
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(14), "0.08")
                .getResponse().getStatus());
        assertEquals(200, cooling(batchId, newRequestId(), T0.plusHours(14))
                .getResponse().getStatus());
    }
}
