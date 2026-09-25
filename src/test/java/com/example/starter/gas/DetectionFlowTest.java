package com.example.starter.gas;

import com.example.starter.gas.service.GasStationService;
import com.example.starter.gas.web.IdempotencyConflictException;
import com.example.starter.gas.web.dto.DetectionRequest;
import com.example.starter.gas.web.dto.DetectionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行监测：浓度区间边界、恰连续 2 次锁阀、合格清零、锁阀零投加、幂等语义。
 */
@Import(FixedClockConfig.class)
class DetectionFlowTest extends AbstractGasIntegrationTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 26, 9, 0, 0);

    @Autowired
    private GasStationService service;

    private DetectionRequest req(String requestId, LocalDateTime at, String concentration, String flow) {
        return new DetectionRequest(requestId, STATION, at, new BigDecimal(concentration),
                new BigDecimal(flow), null);
    }

    @Test
    void concentrationBoundsAreInclusive() {
        // 恰下边界 16 = 20*(1-20%)，恰上边界 24 = 20*(1+20%)，均合格
        DetectionResponse lower = service.detect(req("D-LOW", T, "16.00", "100"));
        assertThat(lower.inRange()).isTrue();
        assertThat(lower.consecutiveViolations()).isZero();
        assertThat(lower.alarmTriggered()).isFalse();

        DetectionResponse upper = service.detect(req("D-HIGH", T.plusMinutes(1), "24.00", "100"));
        assertThat(upper.inRange()).isTrue();
        assertThat(upper.consecutiveViolations()).isZero();
        assertThat(upper.alarmTriggered()).isFalse();
    }

    @Test
    void justBeyondBoundsViolatesButDoesNotLock() {
        DetectionResponse first = service.detect(req("D-1", T, "15.99", "100"));
        assertThat(first.inRange()).isFalse();
        assertThat(first.consecutiveViolations()).isEqualTo(1);
        // 首次越限不锁阀，仍正常加注
        assertThat(first.alarmTriggered()).isFalse();
        assertThat(first.valveState()).isEqualTo("OPEN");
        assertThat(first.injection().valveLocked()).isFalse();
        assertThat(first.injection().actualConsumeG()).isEqualByComparingTo("2.000000");
    }

    @Test
    void exactlyTwoConsecutiveViolationsLockValveAndStopDosing() {
        service.detect(req("D-1", T, "25", "100"));
        DetectionResponse second = service.detect(req("D-2", T.plusMinutes(1), "25.50", "100"));

        // 恰连续 2 次触发告警并联锁锁阀
        assertThat(second.consecutiveViolations()).isEqualTo(2);
        assertThat(second.alarmTriggered()).isTrue();
        assertThat(second.valveState()).isEqualTo("LOCKED");
        assertThat(second.alarmActive()).isTrue();
        // 锁阀后该次起实际投加为 0（理论仍按比例复算）
        assertThat(second.injection().valveLocked()).isTrue();
        assertThat(second.injection().theoreticalConsumeG()).isEqualByComparingTo("2.000000");
        assertThat(second.injection().actualConsumeG()).isEqualByComparingTo("0");

        var snap = stationSnapshot();
        assertThat(snap.get("valve_state")).isEqualTo("LOCKED");
        assertThat(snap.get("alarm_active")).isEqualTo(true);
        assertThat(snap.get("violation_count")).isEqualTo(2);
        // 第一条正常加注 2g，第二条锁阀 0g
        assertThat((BigDecimal) snap.get("odor_consumed")).isEqualByComparingTo("2.000000");
        assertThat(countOf("station_event", "event_kind = 'ODOR_ALARM'")).isEqualTo(1);
    }

    @Test
    void thirdConsecutiveViolationKeepsLockWithoutNewAlarmTriggerFlag() {
        service.detect(req("D-1", T, "25", "100"));
        service.detect(req("D-2", T.plusMinutes(1), "25", "100"));
        DetectionResponse third = service.detect(req("D-3", T.plusMinutes(2), "25", "100"));

        assertThat(third.alarmTriggered()).isFalse();
        assertThat(third.valveState()).isEqualTo("LOCKED");
        assertThat(third.injection().actualConsumeG()).isEqualByComparingTo("0");
        // 告警事件只在恰第 2 次时记一条
        assertThat(countOf("station_event", "event_kind = 'ODOR_ALARM'")).isEqualTo(1);
    }

    @Test
    void inRangeReadingResetsViolationCount() {
        service.detect(req("D-1", T, "25", "100"));
        DetectionResponse back = service.detect(req("D-2", T.plusMinutes(1), "20", "100"));
        assertThat(back.inRange()).isTrue();
        assertThat(back.consecutiveViolations()).isZero();

        // 再连续两次才锁阀：中间合格已切断计数
        service.detect(req("D-3", T.plusMinutes(2), "25", "100"));
        DetectionResponse fourth = service.detect(req("D-4", T.plusMinutes(3), "25", "100"));
        assertThat(fourth.consecutiveViolations()).isEqualTo(2);
        assertThat(fourth.alarmTriggered()).isTrue();
    }

    @Test
    void sameRequestIdAndParamsReplaysFirstResult() {
        DetectionResponse first = service.detect(req("DUP", T, "25", "100"));
        DetectionResponse replay = service.detect(req("DUP", T, "25.0", "100.00"));

        assertThat(replay.idempotentReplayed()).isTrue();
        assertThat(first.idempotentReplayed()).isFalse();
        assertThat(replay.alarmTriggered()).isEqualTo(first.alarmTriggered());
        assertThat(replay.consecutiveViolations()).isEqualTo(first.consecutiveViolations());
        // 重放不产生第二条检测/投加数据
        assertThat(countOf("concentration_detection", "request_id = ?", "DUP")).isEqualTo(1);
        assertThat(countOf("odorant_injection", "source_request_id = ?", "DUP")).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409AndLeavesStateUntouched() {
        service.detect(req("CONF", T, "20", "100"));
        var before = stationSnapshot();

        assertThatThrownBy(() -> service.detect(req("CONF", T, "25", "100")))
                .isInstanceOf(IdempotencyConflictException.class);

        var after = stationSnapshot();
        // 409 不得改变阀门状态与计数
        assertThat(after.get("valve_state")).isEqualTo(before.get("valve_state"));
        assertThat(after.get("violation_count")).isEqualTo(before.get("violation_count"));
        assertThat(after.get("odor_consumed")).isEqualTo(before.get("odor_consumed"));
        assertThat(countOf("concentration_detection", "request_id = ?", "CONF")).isEqualTo(1);
    }

    @Test
    void failedBusinessCleansPendingPlaceholderSoKeyCanBeReused() {
        // 首次请求指向不存在的门站：业务失败回滚，幂等占位被清理
        assertThatThrownBy(() -> service.detect(new DetectionRequest(
                "REUSE", "NO-SUCH", T, new BigDecimal("20"), new BigDecimal("100"), null)))
                .isInstanceOf(com.example.starter.gas.web.NotFoundException.class);
        assertThat(countOf("idempotent_request", "request_id = ?", "REUSE")).isZero();

        // 同一 requestId 随后用于合法门站应正常受理
        DetectionResponse resp = service.detect(req("REUSE", T, "20", "100"));
        assertThat(resp.idempotentReplayed()).isFalse();
        assertThat(countOf("concentration_detection", "request_id = ?", "REUSE")).isEqualTo(1);
    }
}
