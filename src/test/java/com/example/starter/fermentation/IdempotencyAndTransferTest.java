package com.example.starter.fermentation;

import com.example.starter.fermentation.api.BatchDtos.CreateBatchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等受理（同键重放 / 同键异参 409）与清酒转出终态测试。
 */
class IdempotencyAndTransferTest extends BaseFermentationTest {

    @Test
    @DisplayName("同键同参重放返回首次结果，不产生重复数据")
    void sameKeySameParamsReplaysFirstResult() throws Exception {
        String requestId = newRequestId();
        CreateBatchRequest request = new CreateBatchRequest(requestId, "B-IDEM-1", "TANK-1",
                "LAGER-CURVE", T0);

        MvcResult first = doPost("/api/batches", request);
        assertEquals(201, first.getResponse().getStatus());
        MvcResult replay = doPost("/api/batches", request);
        assertEquals(201, replay.getResponse().getStatus(), "同键同参应重放首次状态码");
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参应原样返回首次响应体");

        Integer batchCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE batch_no = 'B-IDEM-1'", Integer.class);
        assertEquals(1, batchCount, "重放不得产生重复批次");

        // 采样重放：同键同参返回首次采样记录，不重复落库
        long id = json(first).get("id").asLong();
        startMainFerment(id, newRequestId(), T0);
        String readingRequestId = newRequestId();
        MvcResult r1 = addReading(id, readingRequestId, T0.plusHours(1), "10.50");
        MvcResult r2 = addReading(id, readingRequestId, T0.plusHours(1), "10.50");
        assertEquals(201, r2.getResponse().getStatus());
        assertEquals(json(r1).get("id").asLong(), json(r2).get("id").asLong(),
                "重放应返回首次采样记录 ID");
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_reading WHERE batch_id = ?", Integer.class, id);
        assertEquals(1, readingCount, "重放不得产生重复采样");
    }

    @Test
    @DisplayName("同键异参返回 409 且不改变业务状态与曲线版本")
    void sameKeyDifferentParamsReturns409() throws Exception {
        String requestId = newRequestId();
        MvcResult first = doPost("/api/batches",
                new CreateBatchRequest(requestId, "B-IDEM-2", "TANK-1", "LAGER-CURVE", T0));
        assertEquals(201, first.getResponse().getStatus());

        // 同 requestId 携带不同罐号：409
        MvcResult conflict = doPost("/api/batches",
                new CreateBatchRequest(requestId, "B-IDEM-2", "TANK-2", "LAGER-CURVE", T0));
        assertEquals(409, conflict.getResponse().getStatus(), "同键异参应返回 409");
        Integer batchCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch", Integer.class);
        assertEquals(1, batchCount, "409 不得改变业务数据");

        // 降温指令同键异参：409 且曲线版本不变
        long id = json(first).get("id").asLong();
        startMainFerment(id, newRequestId(), T0);
        addDiacetyl(id, newRequestId(), T0.plusHours(1), "0.09");
        addDiacetyl(id, newRequestId(), T0.plusHours(14), "0.08");
        String coolingRequestId = newRequestId();
        assertEquals(200, cooling(id, coolingRequestId, T0.plusHours(14))
                .getResponse().getStatus());
        MvcResult coolingConflict = cooling(id, coolingRequestId, T0.plusHours(16));
        assertEquals(409, coolingConflict.getResponse().getStatus(),
                "降温指令同键异参应返回 409");
        JsonNode batch = json(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/batches/" + id)).andReturn());
        assertEquals(1, batch.get("curveVersion").asInt(), "409 不得改变曲线版本");
        assertEquals("2026-09-26T22:00:00", batch.get("coolingStartedAt").asText(),
                "降温时间应保持首次受理值");
    }

    @Test
    @DisplayName("清酒转出为终态：重复转出幂等成功，迟到检测只记迟到事件")
    void transferIsTerminalAndLateDataOnlyRecorded() throws Exception {
        long id = driveToMaturation("B-TR-1", "TANK-1");
        judge(id, newRequestId(), maturationStart().plusDays(7));

        MvcResult first = transfer(id, newRequestId(), maturationStart().plusDays(7).plusHours(1));
        assertEquals(200, first.getResponse().getStatus());
        assertEquals("TRANSFERRED", json(first).get("status").asText());

        // 重复转出（新 requestId）：幂等成功，状态不变
        MvcResult again = transfer(id, newRequestId(), maturationStart().plusDays(8));
        assertEquals(200, again.getResponse().getStatus(), "重复转出应返回幂等成功");
        assertEquals("TRANSFERRED", json(again).get("status").asText());
        assertEquals(json(first).get("transferredAt").asText(),
                json(again).get("transferredAt").asText(), "转出时间不应被覆盖");

        // 转出后迟到的双乙酰检测：202 仅记迟到事件
        MvcResult lateDiacetyl = addDiacetyl(id, newRequestId(),
                maturationStart().plusDays(8), "0.09");
        assertEquals(202, lateDiacetyl.getResponse().getStatus());
        assertTrue(json(lateDiacetyl).get("late").asBoolean());

        // 转出后迟到的温度采样：202 仅记迟到事件
        MvcResult lateReading = addReading(id, newRequestId(),
                maturationStart().plusDays(8), "-1.00");
        assertEquals(202, lateReading.getResponse().getStatus());
        assertTrue(json(lateReading).get("late").asBoolean());

        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE batch_id = ?", Integer.class, id);
        assertEquals(2, lateEvents, "转出后到达的两条检测数据应各记一条迟到事件");
        Integer diacetylRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM diacetyl_test WHERE batch_id = ?", Integer.class, id);
        assertEquals(2, diacetylRows, "迟到检测不得写入正式检测链");
        Integer readingRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_reading WHERE batch_id = ?", Integer.class, id);
        assertEquals(0, readingRows, "迟到采样不得写入正式采样链");
    }

    @Test
    @DisplayName("未判定合格不得转出；转出后罐位释放可再入罐")
    void transferRequiresJudgedAndReleasesTank() throws Exception {
        long id = driveToMaturation("B-TR-2", "TANK-1");
        // 后熟中直接转出：422
        assertEquals(422, transfer(id, newRequestId(), maturationStart().plusDays(7))
                .getResponse().getStatus());

        judge(id, newRequestId(), maturationStart().plusDays(7));
        assertEquals(200, transfer(id, newRequestId(), maturationStart().plusDays(7).plusHours(1))
                .getResponse().getStatus());

        // 罐位已释放：同罐可受理新批次
        long next = createBatch(newRequestId(), "B-TR-3", "TANK-1");
        assertTrue(next > 0);
    }

    @Test
    @DisplayName("端到端主流程：满罐→主酵控温→双乙酰判定→降温→后熟→判定→清酒转出")
    void endToEndHappyPath() throws Exception {
        long id = createBatch(newRequestId(), "B-E2E-1", "TANK-1");
        startMainFerment(id, newRequestId(), T0);

        // 主酵控温：两个合格点 + 偏差恰 0.5℃ 合格点
        assertEquals(201, addReading(id, newRequestId(), T0.plusHours(1), "10.20")
                .getResponse().getStatus());
        JsonNode edge = json(addReading(id, newRequestId(), T0.plusHours(2), "10.50"));
        assertEquals(false, edge.get("outOfTolerance").asBoolean());

        // 双乙酰判定 → 降温
        addDiacetyl(id, newRequestId(), T0.plusHours(3), "0.10");
        addDiacetyl(id, newRequestId(), T0.plusHours(15), "0.10");
        JsonNode cooled = json(cooling(id, newRequestId(), T0.plusHours(16)));
        assertEquals("COOLING", cooled.get("status").asText());

        // 后熟 → 恰 7 天判定合格 → 转出
        assertEquals(200, startMaturation(id, newRequestId(), T0.plusHours(20))
                .getResponse().getStatus());
        JsonNode judged = json(judge(id, newRequestId(), T0.plusHours(20).plusDays(7)));
        assertEquals(true, judged.get("qualified").asBoolean());
        JsonNode transferred = json(transfer(id, newRequestId(), T0.plusHours(20).plusDays(7)
                .plusHours(2)));
        assertEquals("TRANSFERRED", transferred.get("status").asText());
    }
}
