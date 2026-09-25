package com.example.starter.alarm;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.AlarmDtos;
import com.example.starter.api.dto.ReadingDtos;
import com.example.starter.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 告警确认的 requestId 幂等：同键同参返回首次结果，同键异参 409。
 */
class AlarmAckTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-05T02:00:00Z");

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
        draftStrategy("S1", "24.00", "18.00", "70.00", "1.0", "5.0");
        dispatch("S1", "REQ-D", T0.minusSeconds(60));
    }

    private Long triggerAlarm() {
        ingest(T0, "27.00", "70");
        ingest(T0.plusSeconds(60), "27.00", "70");
        ReadingDtos.ReadingResponse trigger = ingest(T0.plusSeconds(120), "27.00", "70");
        return trigger.alarmId();
    }

    @Test
    @DisplayName("确认告警成功后置 ACKNOWLEDGED；同 requestId 重放返回首次结果")
    void acknowledgeIsIdempotent() {
        Long alarmId = triggerAlarm();
        AlarmDtos.AlarmResponse first = alarmService.acknowledge(
                new AlarmDtos.AckRequest(alarmId, "ACK-1"));
        assertThat(first.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(first.ackRequestId()).isEqualTo("ACK-1");

        AlarmDtos.AlarmResponse replay = alarmService.acknowledge(
                new AlarmDtos.AckRequest(alarmId, "ACK-1"));
        assertThat(replay.acknowledgedAt()).isEqualTo(first.acknowledgedAt());
        assertThat(alarmService.listActive(ZONE)).isEmpty();
    }

    @Test
    @DisplayName("同 requestId 确认不同告警返回 409")
    void sameRequestDifferentAlarmConflicts() {
        Long alarmId = triggerAlarm();
        alarmService.acknowledge(new AlarmDtos.AckRequest(alarmId, "ACK-X"));
        assertThatThrownBy(() -> alarmService.acknowledge(new AlarmDtos.AckRequest(999_999L, "ACK-X")))
                .isInstanceOf(ConflictException.class);
    }
}
