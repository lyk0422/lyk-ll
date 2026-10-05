package com.example.starter.alarm;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.AlarmDtos;
import com.example.starter.api.dto.AlarmHandlingDtos;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 告警处置：仅 ACTIVE 告警可发起；发起与完结均按 requestId 幂等，同键异参 409。
 */
class AlarmHandlingTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-09T00:00:00Z");

    private Long alarmId;

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
        alarmId = dispatchBaselineAndTriggerAlarm(T0, T0.plusSeconds(60));
    }

    private AlarmHandlingDtos.StartRequest startRequest(String code, String requestId) {
        return new AlarmHandlingDtos.StartRequest(code, alarmId, requestId,
                bd("23.00"), bd("26.00"), bd("65.00"), bd("75.00"));
    }

    @Test
    @DisplayName("发起处置成功置 IN_PROGRESS；同键同参重放返回首次结果，同键异参 409")
    void startIsIdempotent() {
        AlarmHandlingDtos.HandlingResponse first = alarmHandlingService.start(startRequest("H1", "REQ-H1"));
        assertThat(first.status()).isEqualTo("IN_PROGRESS");
        assertThat(first.zoneCode()).isEqualTo(ZONE);
        assertThat(first.alarmId()).isEqualTo(alarmId);

        AlarmHandlingDtos.HandlingResponse replay = alarmHandlingService.start(startRequest("H1", "REQ-H1"));
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM alarm_handling WHERE handling_code = 'H1'", Integer.class);
        assertThat(count).isEqualTo(1);

        // 同键异参：requestId 相同但窗口不同，指纹不一致 → 409。
        AlarmHandlingDtos.StartRequest different = new AlarmHandlingDtos.StartRequest(
                "H1", alarmId, "REQ-H1", bd("20.00"), bd("26.00"), bd("65.00"), bd("75.00"));
        assertThatThrownBy(() -> alarmHandlingService.start(different))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("非 ACTIVE 告警不可发起处置；告警不存在 404；窗口下限大于上限 422")
    void startValidation() {
        // 窗口校验在告警状态检查之前执行会互相干扰，先验证窗口（告警仍为 ACTIVE）。
        AlarmHandlingDtos.StartRequest badWindow = new AlarmHandlingDtos.StartRequest(
                "H4", alarmId, "REQ-H4", bd("26.00"), bd("23.00"), bd("65"), bd("75"));
        assertThatThrownBy(() -> alarmHandlingService.start(badWindow))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("下限不得大于上限");

        AlarmHandlingDtos.StartRequest missingAlarm = new AlarmHandlingDtos.StartRequest(
                "H3", 999_999L, "REQ-H3", bd("23"), bd("26"), bd("65"), bd("75"));
        assertThatThrownBy(() -> alarmHandlingService.start(missingAlarm))
                .isInstanceOf(NotFoundException.class);

        alarmService.acknowledge(new AlarmDtos.AckRequest(alarmId, "ACK-1"));
        assertThatThrownBy(() -> alarmHandlingService.start(startRequest("H2", "REQ-H2")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("完结处置置 COMPLETED 并记录完结 requestId；重复完结幂等；完结不存在处置 404")
    void completeIsIdempotent() {
        alarmHandlingService.start(startRequest("H1", "REQ-H1"));

        AlarmHandlingDtos.HandlingResponse completed =
                alarmHandlingService.complete("H1", new AlarmHandlingDtos.CompleteRequest("REQ-H1-DONE"));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.completeRequestId()).isEqualTo("REQ-H1-DONE");
        assertThat(completed.completedAt()).isNotNull();

        // 同键重放返回首次结果。
        AlarmHandlingDtos.HandlingResponse replay =
                alarmHandlingService.complete("H1", new AlarmHandlingDtos.CompleteRequest("REQ-H1-DONE"));
        assertThat(replay.completedAt()).isEqualTo(completed.completedAt());

        // 已完结的处置用新键重复完结：状态已为终态，幂等返回当前状态。
        AlarmHandlingDtos.HandlingResponse again =
                alarmHandlingService.complete("H1", new AlarmHandlingDtos.CompleteRequest("REQ-H1-DONE2"));
        assertThat(again.status()).isEqualTo("COMPLETED");
        assertThat(again.completeRequestId()).isEqualTo("REQ-H1-DONE");

        assertThatThrownBy(() -> alarmHandlingService.complete("NOPE",
                new AlarmHandlingDtos.CompleteRequest("REQ-NOPE")))
                .isInstanceOf(NotFoundException.class);
    }
}
