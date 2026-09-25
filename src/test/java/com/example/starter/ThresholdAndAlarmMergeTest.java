package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.web.dto.ApiDtos.AlarmResponse;

/**
 * 支撑时长 2 小时阈值与告警合并规则测试。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_threshold;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ThresholdAndAlarmMergeTest extends BaseIntegrationTest {

    @Test
    void exactlyTwoHoursSupportDoesNotCreateDispatchOrder() {
        // 容量 100kWh、负载 50kW、剩余 100%：支撑时长恰为 2.0 小时。
        givenStation("BS-1", "50", "100");
        givenReading("req-r1", "BS-1", T0.minus(Duration.ofMinutes(5)), "100");

        AlarmResponse response = givenAlarm("req-alarm-1", "BS-1", T0);

        assertThat(response.merged()).isFalse();
        assertThat(response.supportHours()).isEqualByComparingTo(new BigDecimal("2.0000"));
        assertThat(response.dispatchOrder()).isNull();
        assertThat(count("dispatch_order")).isZero();
    }

    @Test
    void belowTwoHoursSupportCreatesDispatchOrderWithGeneratorAndDeadline() {
        // 剩余 99%：支撑时长 1.98 小时，低于 2 小时，应生成调度单。
        givenStation("BS-2", "50", "100");
        givenGenerator("GEN-1");
        givenReading("req-r2", "BS-2", T0.minus(Duration.ofMinutes(5)), "99");

        AlarmResponse response = givenAlarm("req-alarm-2", "BS-2", T0);

        assertThat(response.merged()).isFalse();
        assertThat(response.supportHours()).isEqualByComparingTo(new BigDecimal("1.9800"));
        assertThat(response.dispatchOrder()).isNotNull();
        assertThat(response.dispatchOrder().stationCode()).isEqualTo("BS-2");
        assertThat(response.dispatchOrder().generatorCode()).isEqualTo("GEN-1");
        assertThat(response.dispatchOrder().status()).isEqualTo("DISPATCHED");
        assertThat(response.dispatchOrder().requestId()).isEqualTo("alarm-req-alarm-2");
        // 预计到达时限 = 告警时刻 + 支撑时长（1.98h = 7128s），UTC。
        assertThat(response.dispatchOrder().deadlineAt())
                .isEqualTo(T0.plusSeconds(7128));
        assertThat(response.dispatchOrder().assignedAt()).isEqualTo(T0);
        assertThat(count("dispatch_order")).isEqualTo(1);
        // 油机被占用，同时刻只能服务本站。
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status().name())
                .isEqualTo("ASSIGNED");
    }

    @Test
    void repeatedAlarmsWithinSameOutageMergeIntoOneEventWithoutDuplicateDispatch() {
        givenStation("BS-3", "50", "100");
        givenGenerator("GEN-2");
        givenReading("req-r3", "BS-3", T0.minus(Duration.ofMinutes(5)), "99");

        AlarmResponse first = givenAlarm("req-alarm-3a", "BS-3", T0);
        // 同一停电事件期内重复告警（起始时刻不同，事件仍在进行中）。
        AlarmResponse second = givenAlarm("req-alarm-3b", "BS-3", T0.plus(Duration.ofMinutes(3)));
        // 完全相同的“基站＋停电起始时刻”也去重。
        AlarmResponse third = givenAlarm("req-alarm-3c", "BS-3", T0);

        assertThat(second.merged()).isTrue();
        assertThat(third.merged()).isTrue();
        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(third.eventId()).isEqualTo(first.eventId());
        assertThat(second.dispatchOrder()).isNull();
        // 只有一个事件、一张调度单，不重复派单。
        assertThat(count("outage_event")).isEqualTo(1);
        assertThat(count("dispatch_order")).isEqualTo(1);
    }

    @Test
    void noReadingAssumesFullCapacityAndSkipsDispatchWhenThresholdMet() {
        // 无读数按满容量估算：100kWh / 40kW = 2.5h，不派单。
        givenStation("BS-4", "40", "100");
        givenGenerator("GEN-3");

        AlarmResponse response = givenAlarm("req-alarm-4", "BS-4", T0);

        assertThat(response.supportHours()).isEqualByComparingTo(new BigDecimal("2.5000"));
        assertThat(response.dispatchOrder()).isNull();
        assertThat(count("dispatch_order")).isZero();
    }

    @Test
    void latestReadingBeforeOutageStartIsUsedForEstimation() {
        // 停电起始时刻之后的读数不参与估算：T0 前 95%（1.9h → 派单）。
        givenStation("BS-5", "50", "100");
        givenGenerator("GEN-4");
        givenReading("req-r5a", "BS-5", T0.minus(Duration.ofMinutes(1)), "95");
        givenReading("req-r5b", "BS-5", T0.plus(Duration.ofMinutes(10)), "100");

        AlarmResponse response = givenAlarm("req-alarm-5", "BS-5", T0);

        assertThat(response.supportHours()).isEqualByComparingTo(new BigDecimal("1.9000"));
        assertThat(response.dispatchOrder()).isNotNull();
    }

    @Test
    void alarmForUnknownStationReturnsNotFound() {
        try {
            givenAlarm("req-alarm-x", "BS-MISSING", Instant.now());
            org.assertj.core.api.Assertions.fail("应抛出 404 业务异常");
        } catch (com.example.starter.service.BusinessException ex) {
            assertThat(ex.status().value()).isEqualTo(404);
            assertThat(ex.code()).isEqualTo("STATION_NOT_FOUND");
        }
    }
}
