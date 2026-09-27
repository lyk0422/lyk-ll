package com.example.starter.fermentation;

import com.example.starter.fermentation.api.YeastDtos.PitchYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.YeastRecoveryRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酵母受控投酵/回收的幂等与结构化指纹测试（H2 实际库）：
 * 同键同参重放首次冻结结果；同键异参 409 且不改变业务；失败不占键；
 * 结构化指纹对含分隔符的合法文本无歧义。
 */
class YeastIdempotencyTest extends BaseFermentationTest {

    private void driveToCooling(long batchId) throws Exception {
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(1), "0.09")
                .getResponse().getStatus());
        assertEquals(201, addDiacetyl(batchId, newRequestId(), T0.plusHours(14), "0.08")
                .getResponse().getStatus());
        assertEquals(200, cooling(batchId, newRequestId(), T0.plusHours(14))
                .getResponse().getStatus());
    }

    @Test
    @DisplayName("投酵同键同参重放首次冻结结果，不重复占用不增版本")
    void pitchReplaysFirstResult() throws Exception {
        registerYeast(newRequestId(), "Y-I-1");
        long batchId = createBatch(newRequestId(), "B-Y-I1", "TANK-1");
        String requestId = newRequestId();
        PitchYeastRequest req = new PitchYeastRequest(requestId, "Y-I-1", 1, T0);

        MvcResult first = doPost("/api/batches/" + batchId + "/yeast-pitch", req);
        assertEquals(200, first.getResponse().getStatus());
        MvcResult replay = doPost("/api/batches/" + batchId + "/yeast-pitch", req);
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参应原样返回首次响应");
        assertEquals(2, yeastUnit("Y-I-1").get("version").asInt(), "重放不得重复增版本");
    }

    @Test
    @DisplayName("投酵同键异参（改版本）返回409且不改变业务状态")
    void pitchSameKeyDifferentParams409() throws Exception {
        registerYeast(newRequestId(), "Y-I-2");
        long batchId = createBatch(newRequestId(), "B-Y-I2", "TANK-1");
        String requestId = newRequestId();
        assertEquals(200, doPost("/api/batches/" + batchId + "/yeast-pitch",
                new PitchYeastRequest(requestId, "Y-I-2", 1, T0)).getResponse().getStatus());

        // 同 requestId 携带不同投酵时刻：409
        MvcResult conflict = doPost("/api/batches/" + batchId + "/yeast-pitch",
                new PitchYeastRequest(requestId, "Y-I-2", 1, T0.plusHours(2)));
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals(2, yeastUnit("Y-I-2").get("version").asInt(), "409 不得改变版本");
    }

    @Test
    @DisplayName("业务失败不占幂等键：同 requestId 修正参数后可成功")
    void failureDoesNotOccupyKey() throws Exception {
        registerYeast(newRequestId(), "Y-I-3");
        long batchId = createBatch(newRequestId(), "B-Y-I3", "TANK-1");
        String requestId = newRequestId();

        // 首次：错误版本 9 → 409 业务失败，不占键
        MvcResult failed = doPost("/api/batches/" + batchId + "/yeast-pitch",
                new PitchYeastRequest(requestId, "Y-I-3", 9, T0));
        assertEquals(409, failed.getResponse().getStatus());

        // 同 requestId 修正为正确版本：应成功（失败未落受理记录）
        MvcResult success = doPost("/api/batches/" + batchId + "/yeast-pitch",
                new PitchYeastRequest(requestId, "Y-I-3", 1, T0));
        assertEquals(200, success.getResponse().getStatus(), "失败不占键，修正后应可成功");
        assertEquals(2, yeastUnit("Y-I-3").get("version").asInt());
    }

    @Test
    @DisplayName("回收同键同参重放；回收与版本结果同事务；异参409不翻案")
    void recoveryReplayAndConflict() throws Exception {
        registerYeast(newRequestId(), "Y-I-4");
        long batchId = createBatch(newRequestId(), "B-Y-I4", "TANK-1");
        pitch(batchId, newRequestId(), "Y-I-4", 1, T0);
        driveToCooling(batchId);

        String requestId = newRequestId();
        YeastRecoveryRequest req = new YeastRecoveryRequest(requestId, 2, T0.plusHours(20),
                new BigDecimal("92.50"), false);
        MvcResult first = doPost("/api/batches/" + batchId + "/yeast-recovery", req);
        assertEquals(200, first.getResponse().getStatus());
        MvcResult replay = doPost("/api/batches/" + batchId + "/yeast-recovery", req);
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "回收同键同参应重放首次结果");
        // 重放不产生第二条回收记录
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_recovery_record WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(1, rows);

        // 同键异参（改活率）：409，单元结论不被翻案
        MvcResult conflict = doPost("/api/batches/" + batchId + "/yeast-recovery",
                new YeastRecoveryRequest(requestId, 2, T0.plusHours(20),
                        new BigDecimal("50.00"), true));
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals("AVAILABLE", yeastUnit("Y-I-4").get("status").asText());
        assertEquals(1, yeastUnit("Y-I-4").get("generation").asInt());
    }

    @Test
    @DisplayName("结构化指纹无歧义：含分隔符的单元编号与不同参数结构产生不同指纹")
    void structuredFingerprintDistinguishesSeparatorText() {
        // 通过幂等服务直接验证：相同文本不同字段切分必须区分
        String a = idempotencyFingerprint("E", "K", "a|b");
        String b = idempotencyFingerprintTwoFields("E", "K", "a", "b");
        assertNotEquals(a, b, "单个含分隔符文本与两个字段必须产生不同指纹");

        // 同结构同值应一致（确定性）
        assertEquals(a, idempotencyFingerprint("E", "K", "a|b"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.starter.fermentation.service.IdempotencyService idempotencyService;

    private String idempotencyFingerprint(String endpoint, String requestId, String... fields) {
        return idempotencyService.structuredFingerprint(endpoint, requestId, (Object[]) fields);
    }

    private String idempotencyFingerprintTwoFields(String endpoint, String requestId,
                                                   String f1, String f2) {
        return idempotencyService.structuredFingerprint(endpoint, requestId, f1, f2);
    }

    @Test
    @DisplayName("登记同键同参重放：重复登记返回首次单元，不产生重复编号行")
    void registerReplay() throws Exception {
        String requestId = newRequestId();
        RegisterYeastRequest req = new RegisterYeastRequest(requestId, "Y-I-5");
        MvcResult first = doPost("/api/yeast-units", req);
        assertEquals(201, first.getResponse().getStatus());
        JsonNode firstBody = json(first);
        MvcResult replay = doPost("/api/yeast-units", req);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(firstBody.get("id").asLong(), json(replay).get("id").asLong());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_unit WHERE unit_code = 'Y-I-5'", Integer.class);
        assertEquals(1, count);
        assertTrue(true);
    }
}
