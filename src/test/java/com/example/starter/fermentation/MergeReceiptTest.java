package com.example.starter.fermentation;

import com.example.starter.fermentation.api.ReceivingDtos;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合批接收功能测试：接收罐登记与调容、源批实际升数台账、合批接收主流程与失败分支、
 * 容量恰满、坏一源回滚、版本失配、升数勾稽与幂等重放。
 */
class MergeReceiptTest extends BaseFermentationTest {

    private static final LocalDateTime RECEIVE_AT = T0.plusDays(9);

    @Test
    @DisplayName("主流程：40.125 升与 59.875 升合入 100 升空罐恰满，原子核销并释放原罐，升数可勾稽")
    void mergeExactlyFullAndReconcile() throws Exception {
        assertEquals(201, registerTank(newRequestId(), "RC-100", "100").getResponse().getStatus());
        long s1 = driveToJudged("B-MRG-1", "TANK-1");
        long s2 = driveToJudged("B-MRG-2", "TANK-2");
        assertEquals(201, registerVolume(s1, newRequestId(), "40.125").getResponse().getStatus());
        assertEquals(201, registerVolume(s2, newRequestId(), "59.875").getResponse().getStatus());

        MvcResult result = merge(newRequestId(), "RC-100", List.of(s1, s2), RECEIVE_AT);
        assertEquals(201, result.getResponse().getStatus(), "恰满接收应受理");
        JsonNode receipt = json(result);
        assertEquals(0, receipt.get("totalLiters").decimalValue()
                .compareTo(new BigDecimal("100.000")), "合计应等于各源登记量之和");
        assertEquals("LAGER-CURVE", receipt.get("curveCode").asText());
        assertEquals(1, receipt.get("curveVersion").asInt());
        assertEquals(2, receipt.get("sources").size());

        JsonNode tank = doGet("/api/receiving-tanks/RC-100");
        assertEquals(0, tank.get("receivedLiters").decimalValue()
                .compareTo(new BigDecimal("100.000")), "已接收量应恰满容量");
        assertEquals(2, tank.get("version").asInt(), "接收落账后台账版本应加 1");

        // 源批核销为 TRANSFERRED 终态并释放原罐
        assertEquals("TRANSFERRED", doGet("/api/batches/" + s1).get("status").asText());
        assertEquals("TRANSFERRED", doGet("/api/batches/" + s2).get("status").asText());
        assertEquals(RECEIVE_AT, LocalDateTime.parse(
                doGet("/api/batches/" + s1).get("transferredAt").asText()));
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE active_tank_key IN ('TANK-1','TANK-2')",
                Integer.class);
        assertEquals(0, active, "核销后应释放源批原罐占用");

        // 升数勾稽：接收单来源合计 = 接收单总量 = 接收罐已接收量
        JsonNode receipts = doGet("/api/receiving-tanks/RC-100/receipts");
        assertEquals(1, receipts.size());
        BigDecimal sourceSum = BigDecimal.ZERO;
        for (JsonNode source : receipts.get(0).get("sources")) {
            sourceSum = sourceSum.add(source.get("volumeLiters").decimalValue());
        }
        assertEquals(0, sourceSum.compareTo(receipts.get(0).get("totalLiters").decimalValue()),
                "来源明细合计应等于接收单总量");
        assertEquals(0, sourceSum.compareTo(tank.get("receivedLiters").decimalValue()),
                "接收单合计应等于接收罐已接收量");

        // 源量台账仍可查询（冻结），成熟度与采样只读：转出后采样仅记迟到事件
        assertEquals(0, doGet("/api/batches/" + s1 + "/sake-volume").get("volumeLiters").decimalValue()
                .compareTo(new BigDecimal("40.125")));
        assertEquals(202, addReading(s1, newRequestId(), RECEIVE_AT.plusHours(1), "-1.0")
                .getResponse().getStatus(), "核销后源批采样应只记迟到事件");

        // 旧单笔转出共享唯一核销：核销后重复转出幂等 200，不重复加量
        assertEquals(200, transfer(s1, newRequestId(), RECEIVE_AT.plusHours(2))
                .getResponse().getStatus());
        assertEquals(0, doGet("/api/receiving-tanks/RC-100").get("receivedLiters").decimalValue()
                .compareTo(new BigDecimal("100.000")), "重复转出不得重复加量");
    }

    @Test
    @DisplayName("坏一源全单回滚：一源未合格则 422，源量、罐占用、酵母代次不变，失败不占幂等键")
    void badSourceRollsBackWholeReceipt() throws Exception {
        assertEquals(201, registerTank(newRequestId(), "RC-RB", "1000").getResponse().getStatus());
        long s1 = driveToJudged("B-RB-1", "TANK-1");
        long s2 = driveToJudged("B-RB-2", "TANK-2");
        long s3 = driveToMaturation("B-RB-3", "TANK-3");
        assertEquals(201, registerVolume(s1, newRequestId(), "10").getResponse().getStatus());
        assertEquals(201, registerVolume(s2, newRequestId(), "20").getResponse().getStatus());
        registerYeast(newRequestId(), "YEAST-RB");

        String requestId = newRequestId();
        MvcResult rejected = merge(requestId, "RC-RB", List.of(s1, s2, s3), RECEIVE_AT);
        assertEquals(422, rejected.getResponse().getStatus(), "一源不满足转出条件应全单拒绝");

        assertEquals("JUDGED", doGet("/api/batches/" + s1).get("status").asText(), "失败不得核销源批");
        assertEquals("JUDGED", doGet("/api/batches/" + s2).get("status").asText());
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE active_tank_key IN ('TANK-1','TANK-2')",
                Integer.class);
        assertEquals(2, active, "失败不得释放源批罐占用");
        assertEquals(0, doGet("/api/receiving-tanks/RC-RB").get("receivedLiters").decimalValue()
                .compareTo(BigDecimal.ZERO), "失败不得增加接收量");
        Integer receipts = jdbc.queryForObject("SELECT COUNT(*) FROM merge_receipt", Integer.class);
        assertEquals(0, receipts, "失败不得留下接收单");
        Integer volumes = jdbc.queryForObject("SELECT COUNT(*) FROM source_volume", Integer.class);
        assertEquals(2, volumes, "失败不得改源量台账");
        assertEquals(0, yeastUnit("YEAST-RB").get("generation").asInt(), "失败不得改酵母代次");

        // 失败不占幂等键：同一 requestId 修正参数后可重新受理
        MvcResult retried = merge(requestId, "RC-RB", List.of(s1, s2), RECEIVE_AT);
        assertEquals(201, retried.getResponse().getStatus(), "失败请求修正后应可重试");
        assertEquals(0, doGet("/api/receiving-tanks/RC-RB").get("receivedLiters").decimalValue()
                .compareTo(new BigDecimal("30.000")));
    }

    @Test
    @DisplayName("版本失配全单拒绝：来源曲线版本不一致返回 422 且不产生任何变更")
    void curveVersionMismatchRejected() throws Exception {
        assertEquals(201, registerTank(newRequestId(), "RC-VER", "1000").getResponse().getStatus());
        long s1 = driveToJudged("B-VER-1", "TANK-1");
        publishCurveV2();
        long s2 = driveToJudged("B-VER-2", "TANK-2");
        assertEquals(201, registerVolume(s1, newRequestId(), "10").getResponse().getStatus());
        assertEquals(201, registerVolume(s2, newRequestId(), "20").getResponse().getStatus());

        MvcResult result = merge(newRequestId(), "RC-VER", List.of(s1, s2), RECEIVE_AT);
        assertEquals(422, result.getResponse().getStatus(), "曲线版本失配应全单拒绝");
        assertTrue(json(result).get("message").asText().contains("失配"));
        assertEquals("JUDGED", doGet("/api/batches/" + s1).get("status").asText());
        assertEquals("JUDGED", doGet("/api/batches/" + s2).get("status").asText());
        assertEquals(0, doGet("/api/receiving-tanks/RC-VER").get("receivedLiters").decimalValue()
                .compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("容量校验：合计加既有接收量超容量 422；重复源、单源、未登记源、已转出源均拒绝")
    void capacityAndSourceValidation() throws Exception {
        assertEquals(201, registerTank(newRequestId(), "RC-CAP", "100").getResponse().getStatus());
        long s1 = driveToJudged("B-CAP-1", "TANK-1");
        long s2 = driveToJudged("B-CAP-2", "TANK-2");
        long s3 = driveToJudged("B-CAP-3", "TANK-3");
        assertEquals(201, registerVolume(s1, newRequestId(), "60").getResponse().getStatus());
        assertEquals(201, registerVolume(s2, newRequestId(), "60").getResponse().getStatus());

        assertEquals(422, merge(newRequestId(), "RC-CAP", List.of(s1, s2), RECEIVE_AT)
                .getResponse().getStatus(), "60+60 超过 100 容量应拒绝");
        assertEquals(422, merge(newRequestId(), "RC-CAP", List.of(s1, s1), RECEIVE_AT)
                .getResponse().getStatus(), "重复源批应拒绝");
        assertEquals(400, merge(newRequestId(), "RC-CAP", List.of(s1), RECEIVE_AT)
                .getResponse().getStatus(), "单源不满足 2 至 5 个应拒绝");
        assertEquals(422, merge(newRequestId(), "RC-CAP", List.of(s1, s3), RECEIVE_AT)
                .getResponse().getStatus(), "未登记实际升数的源应拒绝");
        assertEquals(404, merge(newRequestId(), "RC-CAP", List.of(s1, 999999L), RECEIVE_AT)
                .getResponse().getStatus(), "源批不存在应 404");
        assertEquals(404, merge(newRequestId(), "RC-NONE", List.of(s1, s2), RECEIVE_AT)
                .getResponse().getStatus(), "接收罐不存在应 404");

        // 已转出源：旧单笔转出先消费核销，合批拒绝且不影响其余源
        assertEquals(200, transfer(s2, newRequestId(), RECEIVE_AT).getResponse().getStatus());
        assertEquals(422, merge(newRequestId(), "RC-CAP", List.of(s1, s2), RECEIVE_AT)
                .getResponse().getStatus(), "已转出源应全单拒绝");
        assertEquals("JUDGED", doGet("/api/batches/" + s1).get("status").asText());
        assertEquals(0, doGet("/api/receiving-tanks/RC-CAP").get("receivedLiters").decimalValue()
                .compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("源量登记：仅 JUDGED 可登记、不超发酵罐容量（恰等允许）、最多三位小数、仅登记一次")
    void sourceVolumeRegistrationRules() throws Exception {
        long maturation = driveToMaturation("B-VOL-0", "TANK-1");
        assertEquals(422, registerVolume(maturation, newRequestId(), "10")
                .getResponse().getStatus(), "未合格批次不得登记实际升数");

        long s1 = driveToJudged("B-VOL-1", "TANK-2");
        assertEquals(422, registerVolume(s1, newRequestId(), "5000.001")
                .getResponse().getStatus(), "超过发酵罐容量应拒绝");
        assertEquals(400, registerVolume(s1, newRequestId(), "10.0001")
                .getResponse().getStatus(), "超过三位小数应 400");
        assertEquals(400, doPost("/api/batches/" + s1 + "/sake-volume",
                new ReceivingDtos.RegisterSourceVolumeRequest(newRequestId(), BigDecimal.ZERO))
                .getResponse().getStatus(), "非正数应 400");
        assertEquals(201, registerVolume(s1, newRequestId(), "5000")
                .getResponse().getStatus(), "恰等于发酵罐容量应允许");
        assertEquals(409, registerVolume(s1, newRequestId(), "10")
                .getResponse().getStatus(), "重复登记应 409（登记后冻结）");
        assertEquals(404, registerVolume(999999L, newRequestId(), "10")
                .getResponse().getStatus(), "批次不存在应 404");
    }

    @Test
    @DisplayName("接收罐调容：版本 1 起、expectedVersion 旧版 409、成功加 1、不得缩到已接收量以下（恰等允许）")
    void capacityUpdateVersioning() throws Exception {
        assertEquals(201, registerTank(newRequestId(), "RC-UPD", "100").getResponse().getStatus());
        assertEquals(1, doGet("/api/receiving-tanks/RC-UPD").get("version").asInt(), "版本应从 1 开始");

        assertEquals(409, updateCapacity("RC-UPD", newRequestId(), 2, "120")
                .getResponse().getStatus(), "旧版 expectedVersion 应 409");
        MvcResult updated = updateCapacity("RC-UPD", newRequestId(), 1, "120");
        assertEquals(200, updated.getResponse().getStatus());
        assertEquals(2, json(updated).get("version").asInt(), "成功调容版本应加 1");

        long s1 = driveToJudged("B-UPD-1", "TANK-1");
        long s2 = driveToJudged("B-UPD-2", "TANK-2");
        assertEquals(201, registerVolume(s1, newRequestId(), "40").getResponse().getStatus());
        assertEquals(201, registerVolume(s2, newRequestId(), "50").getResponse().getStatus());
        assertEquals(201, merge(newRequestId(), "RC-UPD", List.of(s1, s2), RECEIVE_AT)
                .getResponse().getStatus());
        assertEquals(3, doGet("/api/receiving-tanks/RC-UPD").get("version").asInt(),
                "接收落账后版本应再加 1");

        assertEquals(422, updateCapacity("RC-UPD", newRequestId(), 3, "89.999")
                .getResponse().getStatus(), "容量不得缩到已接收量 90 以下");
        MvcResult shrunk = updateCapacity("RC-UPD", newRequestId(), 3, "90");
        assertEquals(200, shrunk.getResponse().getStatus(), "恰等于已接收量应允许");
        assertEquals(4, json(shrunk).get("version").asInt());
    }

    @Test
    @DisplayName("幂等：同键同参重放首次结果且不加量，同键异参 409")
    void idempotentReplayAndConflict() throws Exception {
        assertEquals(201, registerTank(newRequestId(), "RC-IDEM", "100").getResponse().getStatus());
        long s1 = driveToJudged("B-IDEM-1", "TANK-1");
        long s2 = driveToJudged("B-IDEM-2", "TANK-2");
        assertEquals(201, registerVolume(s1, newRequestId(), "40").getResponse().getStatus());
        assertEquals(201, registerVolume(s2, newRequestId(), "60").getResponse().getStatus());

        String requestId = newRequestId();
        MvcResult first = merge(requestId, "RC-IDEM", List.of(s1, s2), RECEIVE_AT);
        assertEquals(201, first.getResponse().getStatus());
        long receiptId = json(first).get("id").asLong();

        MvcResult replay = merge(requestId, "RC-IDEM", List.of(s1, s2), RECEIVE_AT);
        assertEquals(201, replay.getResponse().getStatus(), "同键同参应重放首次结果");
        assertEquals(receiptId, json(replay).get("id").asLong(), "重放应返回同一接收单");
        assertEquals(0, doGet("/api/receiving-tanks/RC-IDEM").get("receivedLiters").decimalValue()
                .compareTo(new BigDecimal("100.000")), "重放不得重复加量");
        Integer receipts = jdbc.queryForObject("SELECT COUNT(*) FROM merge_receipt", Integer.class);
        assertEquals(1, receipts, "重放不得产生新接收单");

        assertEquals(409, merge(requestId, "RC-IDEM", List.of(s1, s2), RECEIVE_AT.plusHours(1))
                .getResponse().getStatus(), "同键异参应 409");

        String tankRequestId = newRequestId();
        assertEquals(201, registerTank(tankRequestId, "RC-IDEM-2", "50").getResponse().getStatus());
        assertEquals(201, registerTank(tankRequestId, "RC-IDEM-2", "50").getResponse().getStatus(),
                "登记接收罐同键同参应重放");
        assertEquals(409, registerTank(tankRequestId, "RC-IDEM-2", "60").getResponse().getStatus(),
                "登记接收罐同键异参应 409");
    }

    @Test
    @DisplayName("五源上限：5 个受控源批合批成功，6 个源批 400")
    void fiveSourcesAccepted() throws Exception {
        assertEquals(201, registerTank(newRequestId(), "RC-FIVE", "1000").getResponse().getStatus());
        String[] tanks = {"TANK-1", "TANK-2", "TANK-3", "TANK-4", "TANK-5"};
        List<Long> sources = new java.util.ArrayList<>();
        for (int i = 0; i < tanks.length; i++) {
            long id = driveToJudged("B-FIVE-" + i, tanks[i]);
            assertEquals(201, registerVolume(id, newRequestId(), "10").getResponse().getStatus());
            sources.add(id);
        }
        MvcResult result = merge(newRequestId(), "RC-FIVE", sources, RECEIVE_AT);
        assertEquals(201, result.getResponse().getStatus(), "恰 5 个源批应受理");
        assertEquals(5, json(result).get("sources").size());
        assertEquals(0, json(result).get("totalLiters").decimalValue()
                .compareTo(new BigDecimal("50.000")));

        assertEquals(400, merge(newRequestId(), "RC-FIVE",
                List.of(1L, 2L, 3L, 4L, 5L, 6L), RECEIVE_AT).getResponse().getStatus(),
                "6 个源批超出上限应 400");
    }

    /** 发布 LAGER-CURVE v2（主酵 9.5℃ / 降温 3.0℃ / 后熟 -1.0℃），v1 作废。 */
    private void publishCurveV2() {
        jdbc.update("UPDATE process_curve SET status = 'SUPERSEDED'"
                + " WHERE curve_code = 'LAGER-CURVE' AND version = 1");
        jdbc.update("INSERT INTO process_curve (id, curve_code, version, status, created_at)"
                        + " VALUES (100, 'LAGER-CURVE', 2, 'ACTIVE', ?)",
                Timestamp.valueOf(T0));
        jdbc.update("INSERT INTO curve_segment (curve_id, phase, seq, setpoint_celsius, duration_hours)"
                + " VALUES (100, 'MAIN_FERMENT', 1, 9.50, 72),"
                + " (100, 'COOLING', 1, 3.00, 48),"
                + " (100, 'MATURATION', 1, -1.00, 168)");
    }
}
