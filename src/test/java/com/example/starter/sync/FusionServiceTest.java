package com.example.starter.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.sync.domain.FusionFrame;
import com.example.starter.sync.service.FusionService;
import com.example.starter.sync.service.SensorSyncService;

/**
 * 融合帧提交分支测试：主流程、缺帧、超出窗口、无法插值、插值输入、窗口边界、重复提交。
 */
class FusionServiceTest extends AbstractSyncTest {

    @Autowired
    private SensorSyncService sensorSyncService;

    @Autowired
    private FusionService fusionService;

    private void ingestRadarCameraImu() {
        sensorSyncService.ingest(SensorType.RADAR, "radar-1", TARGET + 30);
        sensorSyncService.ingest(SensorType.CAMERA, "cam-1", TARGET - 10);
        sensorSyncService.ingest(SensorType.IMU, "imu-1", TARGET + 5);
    }

    @Test
    void commitsFrameWithPerInputEvidence() {
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-1", TARGET - 20);
        ingestRadarCameraImu();

        FusionFrame frame = fusionService.commit(TARGET);

        assertThat(frame.id()).isNotNull();
        assertThat(frame.inputs()).hasSize(4);
        assertThat(frame.inputOf(SensorType.LIDAR).messageId()).isEqualTo("lidar-1");
        assertThat(frame.inputOf(SensorType.LIDAR).deltaMs()).isEqualTo(-20);
        assertThat(frame.inputOf(SensorType.LIDAR).interpolated()).isFalse();
        assertThat(frame.inputOf(SensorType.RADAR).deltaMs()).isEqualTo(30);
        assertThat(frame.inputOf(SensorType.IMU).deltaMs()).isEqualTo(5);
        // 持久化后可按目标时刻查询，证据一致
        FusionFrame stored = fusionService.getFrame(TARGET);
        assertThat(stored.inputs()).containsExactlyInAnyOrderElementsOf(frame.inputs());
    }

    @Test
    void commitFailsWithMissingInputWhenSensorNeverReported() {
        ingestRadarCameraImu();

        assertThatThrownBy(() -> fusionService.commit(TARGET))
                .isInstanceOf(SyncException.class)
                .extracting(e -> ((SyncException) e).code())
                .isEqualTo(SyncErrorCode.MISSING_INPUT);
        assertThat(frameCount(TARGET)).isZero();
    }

    @Test
    void commitFailsWithOutOfWindowWhenSamplesOnlyOnOneSide() {
        // LIDAR 窗口 50ms：样本在 target-51 之外，且没有目标时刻之后的样本，无法插值
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-far", TARGET - 51);
        ingestRadarCameraImu();

        assertThatThrownBy(() -> fusionService.commit(TARGET))
                .isInstanceOf(SyncException.class)
                .extracting(e -> ((SyncException) e).code())
                .isEqualTo(SyncErrorCode.OUT_OF_WINDOW);
    }

    @Test
    void commitFailsWithCannotInterpolateWhenBracketingSpanTooWide() {
        // LIDAR 插值上限 100ms：两侧样本各距目标 150ms
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-before", TARGET - 150);
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-after", TARGET + 150);
        ingestRadarCameraImu();

        assertThatThrownBy(() -> fusionService.commit(TARGET))
                .isInstanceOf(SyncException.class)
                .extracting(e -> ((SyncException) e).code())
                .isEqualTo(SyncErrorCode.CANNOT_INTERPOLATE);
    }

    @Test
    void commitUsesInterpolatedInputWhenBracketingSamplesExist() {
        // LIDAR 窗口 50ms 内无样本，前后 80ms/60ms 有样本（插值上限 100ms）
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-before", TARGET - 80);
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-after", TARGET + 60);
        ingestRadarCameraImu();

        FusionFrame frame = fusionService.commit(TARGET);

        assertThat(frame.inputOf(SensorType.LIDAR).interpolated()).isTrue();
        assertThat(frame.inputOf(SensorType.LIDAR).messageId()).isEqualTo("lidar-before+lidar-after");
        assertThat(frame.inputOf(SensorType.LIDAR).correctedTs()).isEqualTo(TARGET);
        assertThat(frame.inputOf(SensorType.LIDAR).deltaMs()).isZero();
    }

    @Test
    void windowBoundaryIsInclusive() {
        // LIDAR 窗口 50ms：恰好在边界上应被接受
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-edge", TARGET - 50);
        ingestRadarCameraImu();

        FusionFrame frame = fusionService.commit(TARGET);

        assertThat(frame.inputOf(SensorType.LIDAR).messageId()).isEqualTo("lidar-edge");
        assertThat(frame.inputOf(SensorType.LIDAR).deltaMs()).isEqualTo(-50);
    }

    @Test
    void duplicateCommitIsRejectedWithStableCode() {
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-1", TARGET);
        ingestRadarCameraImu();
        fusionService.commit(TARGET);

        assertThatThrownBy(() -> fusionService.commit(TARGET))
                .isInstanceOf(SyncException.class)
                .extracting(e -> ((SyncException) e).code())
                .isEqualTo(SyncErrorCode.FRAME_ALREADY_COMMITTED);
        assertThat(frameCount(TARGET)).isEqualTo(1);
    }

    @Test
    void getFrameOfUnknownTargetReturnsNotFound() {
        assertThatThrownBy(() -> fusionService.getFrame(TARGET + 777))
                .isInstanceOf(SyncException.class)
                .extracting(e -> ((SyncException) e).code())
                .isEqualTo(SyncErrorCode.FRAME_NOT_FOUND);
    }
}
