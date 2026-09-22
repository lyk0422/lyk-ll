package com.example.starter.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.sync.domain.DiscardReason;
import com.example.starter.sync.domain.IngestOutcome;
import com.example.starter.sync.service.FusionService;
import com.example.starter.sync.service.SensorSyncService;

/**
 * 消息接收分支测试：正常接收、重复幂等、乱序容忍、迟到丢弃、命中已提交帧窗口丢弃。
 */
class SensorSyncServiceTest extends AbstractSyncTest {

    @Autowired
    private SensorSyncService sensorSyncService;

    @Autowired
    private FusionService fusionService;

    @Test
    void acceptsMessageAndPersistsIt() {
        IngestOutcome outcome = sensorSyncService.ingest(SensorType.LIDAR, "lidar-1", TARGET);

        assertThat(outcome.isAccepted()).isTrue();
        assertThat(outcome.sample().correctedTs()).isEqualTo(TARGET);
        assertThat(outcome.sample().driftVersion()).isZero();
        assertThat(messageCount(SensorType.LIDAR, "lidar-1")).isEqualTo(1);
    }

    @Test
    void duplicateMessageIsIdempotentAndRecorded() {
        sensorSyncService.ingest(SensorType.RADAR, "radar-1", TARGET);
        IngestOutcome second = sensorSyncService.ingest(SensorType.RADAR, "radar-1", TARGET);

        assertThat(second.isAccepted()).isFalse();
        assertThat(second.reason()).isEqualTo(DiscardReason.DUPLICATE_MESSAGE);
        assertThat(messageCount(SensorType.RADAR, "radar-1")).isEqualTo(1);
        Integer duplicates = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_event WHERE event_type = 'MESSAGE_DUPLICATE'", Integer.class);
        assertThat(duplicates).isEqualTo(1);
    }

    @Test
    void outOfOrderWithinLatenessIsAccepted() {
        sensorSyncService.ingest(SensorType.CAMERA, "cam-new", TARGET);
        IngestOutcome outOfOrder = sensorSyncService.ingest(SensorType.CAMERA, "cam-old", TARGET - 300);

        assertThat(outOfOrder.isAccepted()).isTrue();
    }

    @Test
    void lateMessageBeyondLatenessIsDiscardedWithReason() {
        sensorSyncService.ingest(SensorType.CAMERA, "cam-new", TARGET);
        // 水位线 1_000_000，迟到容忍 500ms：999_400 < 999_500 判定为迟到
        IngestOutcome late = sensorSyncService.ingest(SensorType.CAMERA, "cam-late", TARGET - 600);

        assertThat(late.isAccepted()).isFalse();
        assertThat(late.reason()).isEqualTo(DiscardReason.LATE_MESSAGE);
        String reason = jdbc.queryForObject(
                "SELECT discard_reason FROM sensor_message WHERE message_id = 'cam-late'",
                String.class);
        assertThat(reason).isEqualTo("LATE_MESSAGE");
    }

    @Test
    void messageArrivingAfterFrameCommittedIsDiscardedAndFrameStaysImmutable() {
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-a", TARGET - 10);
        sensorSyncService.ingest(SensorType.RADAR, "radar-a", TARGET + 10);
        sensorSyncService.ingest(SensorType.CAMERA, "cam-a", TARGET);
        sensorSyncService.ingest(SensorType.IMU, "imu-a", TARGET + 5);
        fusionService.commit(TARGET);

        IngestOutcome lateForCommitted = sensorSyncService.ingest(SensorType.LIDAR, "lidar-b", TARGET - 20);

        assertThat(lateForCommitted.isAccepted()).isFalse();
        assertThat(lateForCommitted.reason()).isEqualTo(DiscardReason.FRAME_ALREADY_COMMITTED);
        assertThat(fusionService.getFrame(TARGET).inputOf(SensorType.LIDAR).messageId())
                .isEqualTo("lidar-a");
    }
}
