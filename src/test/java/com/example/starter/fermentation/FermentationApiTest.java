package com.example.starter.fermentation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主酵控温、双乙酰判定、后熟判定的边界值与状态机测试（H2 实际库验证）。
 */
class FermentationApiTest extends BaseFermentationTest {

    @Test
    @DisplayName("温度偏差恰 0.5℃ 判定合格，0.51℃ 超差")
    void temperatureExactlyHalfDegreeIsWithinTolerance() throws Exception {
        long id = createBatch(newRequestId(), "B-TEMP-1", "TANK-1");
        startMainFerment(id, newRequestId(), T0);

        // 主酵设定值 10.0℃，恰 +0.5℃ 合格
        JsonNode ok = json(addReading(id, newRequestId(), T0.plusHours(1), "10.50"));
        assertFalse(ok.get("outOfTolerance").asBoolean(), "恰 0.5℃ 偏差应判定合格");
        assertEquals(0, ok.get("consecutiveOut").asInt());
        assertEquals(0, ok.get("deviation").decimalValue().compareTo(new java.math.BigDecimal("0.50")));

        // 恰 -0.5℃ 同样合格
        JsonNode okLow = json(addReading(id, newRequestId(), T0.plusHours(2), "9.50"));
        assertFalse(okLow.get("outOfTolerance").asBoolean(), "恰 -0.5℃ 偏差应判定合格");

        // 0.51℃ 超差
        JsonNode out = json(addReading(id, newRequestId(), T0.plusHours(3), "10.51"));
        assertTrue(out.get("outOfTolerance").asBoolean(), "0.51℃ 偏差应判定超差");
        assertEquals(1, out.get("consecutiveOut").asInt());
    }

    @Test
    @DisplayName("恰连续 3 个超差采样点触发告警，恢复合格清零")
    void alarmTriggersAtExactlyThreeConsecutiveOutOfTolerance() throws Exception {
        long id = createBatch(newRequestId(), "B-ALARM-1", "TANK-1");
        startMainFerment(id, newRequestId(), T0);

        JsonNode first = json(addReading(id, newRequestId(), T0.plusHours(1), "11.00"));
        assertTrue(first.get("outOfTolerance").asBoolean());
        assertEquals(1, first.get("consecutiveOut").asInt());
        assertFalse(first.get("alarmTriggered").asBoolean(), "第 1 个超差点不应告警");

        JsonNode second = json(addReading(id, newRequestId(), T0.plusHours(2), "11.00"));
        assertEquals(2, second.get("consecutiveOut").asInt());
        assertFalse(second.get("alarmTriggered").asBoolean(), "连续 2 点不应告警");

        JsonNode third = json(addReading(id, newRequestId(), T0.plusHours(3), "11.00"));
        assertEquals(3, third.get("consecutiveOut").asInt());
        assertTrue(third.get("alarmTriggered").asBoolean(), "恰连续 3 点应触发告警");

        JsonNode fourth = json(addReading(id, newRequestId(), T0.plusHours(4), "11.00"));
        assertEquals(4, fourth.get("consecutiveOut").asInt());
        assertTrue(fourth.get("alarmTriggered").asBoolean(), "连续 4 点仍应告警");

        JsonNode recovered = json(addReading(id, newRequestId(), T0.plusHours(5), "10.00"));
        assertFalse(recovered.get("outOfTolerance").asBoolean());
        assertEquals(0, recovered.get("consecutiveOut").asInt(), "合格点应清零连续超差计数");
        assertFalse(recovered.get("alarmTriggered").asBoolean());
    }

    @Test
    @DisplayName("双乙酰恰 0.10mg/L 且采样恰间隔 12 小时允许降温")
    void diacetylExactlyLimitAndExactlyTwelveHoursAllowsCooling() throws Exception {
        long id = createBatch(newRequestId(), "B-DIA-1", "TANK-1");
        startMainFerment(id, newRequestId(), T0);

        assertEquals(201, addDiacetyl(id, newRequestId(), T0.plusHours(1), "0.10")
                .getResponse().getStatus());
        assertEquals(201, addDiacetyl(id, newRequestId(), T0.plusHours(13), "0.10")
                .getResponse().getStatus());

        MvcResult result = cooling(id, newRequestId(), T0.plusHours(14));
        assertEquals(200, result.getResponse().getStatus(),
                "恰 0.10mg/L 且恰 12 小时间隔应允许降温");
        JsonNode body = json(result);
        assertEquals("COOLING", body.get("status").asText());
    }

    @Test
    @DisplayName("双乙酰超过 0.10mg/L 或间隔不足 12 小时拒绝降温")
    void diacetylAboveLimitOrShortIntervalRejectsCooling() throws Exception {
        // 第二次检测 0.11 > 0.10：拒绝
        long id = createBatch(newRequestId(), "B-DIA-2", "TANK-1");
        startMainFerment(id, newRequestId(), T0);
        addDiacetyl(id, newRequestId(), T0.plusHours(1), "0.10");
        addDiacetyl(id, newRequestId(), T0.plusHours(13), "0.11");
        assertEquals(422, cooling(id, newRequestId(), T0.plusHours(14)).getResponse().getStatus(),
                "双乙酰超过 0.10mg/L 应拒绝降温");

        // 间隔 11 小时 < 12 小时：拒绝
        long id2 = createBatch(newRequestId(), "B-DIA-3", "TANK-2");
        startMainFerment(id2, newRequestId(), T0);
        addDiacetyl(id2, newRequestId(), T0.plusHours(1), "0.09");
        addDiacetyl(id2, newRequestId(), T0.plusHours(12), "0.09");
        assertEquals(422, cooling(id2, newRequestId(), T0.plusHours(13)).getResponse().getStatus(),
                "采样间隔不足 12 小时应拒绝降温");

        // 仅一次检测：拒绝
        long id3 = createBatch(newRequestId(), "B-DIA-4", "TANK-3");
        startMainFerment(id3, newRequestId(), T0);
        addDiacetyl(id3, newRequestId(), T0.plusHours(1), "0.09");
        assertEquals(422, cooling(id3, newRequestId(), T0.plusHours(14)).getResponse().getStatus(),
                "检测不足两次应拒绝降温");
    }

    @Test
    @DisplayName("后熟恰 7 天判定合格，差 1 小时不合格且可延长一次")
    void maturationExactlySevenDaysIsQualified() throws Exception {
        long id = driveToMaturation("B-MAT-1", "TANK-1");

        // 差 1 小时不足 7 天：不合格，可延长
        JsonNode early = json(judge(id, newRequestId(), maturationStart().plusDays(7).minusHours(1)));
        assertFalse(early.get("qualified").asBoolean(), "不足 7 天应判定不合格");
        assertTrue(early.get("canExtend").asBoolean(), "未延长过应允许延长");
        assertEquals("MATURATION", early.get("status").asText());

        // 恰 7 天：合格
        JsonNode exact = json(judge(id, newRequestId(), maturationStart().plusDays(7)));
        assertTrue(exact.get("qualified").asBoolean(), "恰 7 天应判定合格");
        assertEquals("JUDGED", exact.get("status").asText());
        assertEquals(7, exact.get("requiredDays").asInt());
    }

    @Test
    @DisplayName("后熟仅允许延长一次，延长后仍不合格整批复审")
    void extendOnlyOnceAndReviewAfterExtendedFailure() throws Exception {
        long id = driveToMaturation("B-EXT-1", "TANK-1");

        // 不足 7 天判定不合格 → 允许延长
        JsonNode early = json(judge(id, newRequestId(), maturationStart().plusDays(5)));
        assertFalse(early.get("qualified").asBoolean());
        assertTrue(early.get("canExtend").asBoolean());

        assertEquals(200, extendMaturation(id, newRequestId(), maturationStart().plusDays(5))
                .getResponse().getStatus(), "首次延长应成功");
        assertEquals(409, extendMaturation(id, newRequestId(), maturationStart().plusDays(6))
                .getResponse().getStatus(), "第二次延长应返回 409");

        // 延长后要求 14 天：第 8 天判定仍不合格 → 整批复审（终态）
        JsonNode stillBad = json(judge(id, newRequestId(), maturationStart().plusDays(8)));
        assertFalse(stillBad.get("qualified").asBoolean());
        assertFalse(stillBad.get("canExtend").asBoolean());
        assertEquals("REVIEW", stillBad.get("status").asText(), "延长后仍不合格应整批复审");
        assertEquals(14, stillBad.get("requiredDays").asInt());

        // 复审终态不可转出
        assertEquals(422, transfer(id, newRequestId(), maturationStart().plusDays(9))
                .getResponse().getStatus(), "REVIEW 批次不允许转出");
    }

    @Test
    @DisplayName("延长后满 14 天判定合格")
    void extendedMaturationQualifiedAtFourteenDays() throws Exception {
        long id = driveToMaturation("B-EXT-2", "TANK-1");
        extendMaturation(id, newRequestId(), maturationStart().plusDays(5));
        JsonNode judged = json(judge(id, newRequestId(), maturationStart().plusDays(14)));
        assertTrue(judged.get("qualified").asBoolean(), "延长后恰 14 天应判定合格");
        assertEquals("JUDGED", judged.get("status").asText());
    }

    @Test
    @DisplayName("降温指令与曲线版本切换同事务：成功后按新版本降温，失败不改变版本")
    void coolingSwitchesCurveVersionAtomically() throws Exception {
        long id = createBatch(newRequestId(), "B-CURVE-1", "TANK-1");
        startMainFerment(id, newRequestId(), T0);

        // 双乙酰未达标：降温失败，曲线版本保持 v1，状态保持主酵
        assertEquals(422, cooling(id, newRequestId(), T0.plusHours(1)).getResponse().getStatus());
        JsonNode afterFail = batch(id);
        assertEquals(1, afterFail.get("curveVersion").asInt(), "降温失败不得切换曲线版本");
        assertEquals("MAIN_FERMENT", afterFail.get("status").asText());

        // 发布曲线 v2（v1 作废），降温应原子切换到 v2
        publishCurveV2();
        addDiacetyl(id, newRequestId(), T0.plusHours(1), "0.09");
        addDiacetyl(id, newRequestId(), T0.plusHours(14), "0.08");
        MvcResult result = cooling(id, newRequestId(), T0.plusHours(14));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals(2, body.get("curveVersion").asInt(), "降温应切换到最新生效曲线版本");
        assertEquals("COOLING", body.get("status").asText());

        // 切换后按新曲线（v2 主酵段 9.5℃）复算：降温期设定值应取 v2 的 3.0℃
        JsonNode reading = json(addReading(id, newRequestId(), T0.plusHours(15), "3.00"));
        assertEquals(2, reading.get("curveVersion").asInt(), "降温后采样应记录新曲线版本");
        assertEquals(0, reading.get("setpoint").decimalValue()
                .compareTo(new java.math.BigDecimal("3.00")), "降温期应按 v2 设定值 3.0℃ 复算");
        assertFalse(reading.get("outOfTolerance").asBoolean());
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
