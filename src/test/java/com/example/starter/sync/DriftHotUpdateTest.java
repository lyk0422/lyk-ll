package com.example.starter.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.sync.domain.FusionFrame;
import com.example.starter.sync.domain.IngestOutcome;
import com.example.starter.sync.service.DriftService;
import com.example.starter.sync.service.FusionService;
import com.example.starter.sync.service.SensorSyncService;

/**
 * 时钟漂移热更新测试：新参数只影响生效时刻之后未提交的候选，不改写已提交帧。
 */
class DriftHotUpdateTest extends AbstractSyncTest {

    @Autowired
    private DriftService driftService;

    @Autowired
    private SensorSyncService sensorSyncService;

    @Autowired
    private FusionService fusionService;

    @Test
    void driftParamsApplyOnlyToMessagesAtOrAfterEffectiveFrom() {
        driftService.update(SensorType.LIDAR, 1, 100L, TARGET - 500);

        IngestOutcome before = sensorSyncService.ingest(SensorType.LIDAR, "lidar-before", TARGET - 900);
        IngestOutcome after = sensorSyncService.ingest(SensorType.LIDAR, "lidar-after", TARGET - 20);

        assertThat(after.sample().correctedTs()).isEqualTo(TARGET + 80);
        assertThat(after.sample().driftVersion()).isEqualTo(1);
        // 原始时戳早于生效时刻，仍用无校正版本
        assertThat(before.sample().correctedTs()).isEqualTo(TARGET - 900);
        assertThat(before.sample().driftVersion()).isZero();
    }

    @Test
    void driftVersionMustIncrease() {
        driftService.update(SensorType.RADAR, 1, 10L, 0L);

        assertThatThrownBy(() -> driftService.update(SensorType.RADAR, 1, 20L, 0L))
                .isInstanceOf(SyncException.class)
                .extracting(e -> ((SyncException) e).code())
                .isEqualTo(SyncErrorCode.INVALID_DRIFT_VERSION);
        assertThat(driftService.currentVersion(SensorType.RADAR)).isEqualTo(1);
    }

    @Test
    void hotUpdateDoesNotRewriteCommittedFrame() {
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-1", TARGET);
        sensorSyncService.ingest(SensorType.RADAR, "radar-1", TARGET);
        sensorSyncService.ingest(SensorType.CAMERA, "cam-1", TARGET);
        sensorSyncService.ingest(SensorType.IMU, "imu-1", TARGET);
        FusionFrame committed = fusionService.commit(TARGET);

        // 提交后热更新漂移参数：已提交帧不变，新消息使用新版本
        driftService.update(SensorType.LIDAR, 1, 40L, TARGET);
        FusionFrame reread = fusionService.getFrame(TARGET);
        assertThat(reread.inputs()).containsExactlyInAnyOrderElementsOf(committed.inputs());

        IngestOutcome next = sensorSyncService.ingest(SensorType.LIDAR, "lidar-2", TARGET + 1_000);
        assertThat(next.sample().correctedTs()).isEqualTo(TARGET + 1_040);
        assertThat(next.sample().driftVersion()).isEqualTo(1);
    }
}
