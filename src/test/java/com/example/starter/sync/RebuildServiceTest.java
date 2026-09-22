package com.example.starter.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.sync.domain.FusionFrame;
import com.example.starter.sync.service.DriftService;
import com.example.starter.sync.service.FusionService;
import com.example.starter.sync.service.RebuildService;
import com.example.starter.sync.service.SensorSyncService;

/**
 * 事件重建测试：从 H2 事件记录重放重建融合帧及输入证据，重放幂等。
 */
class RebuildServiceTest extends AbstractSyncTest {

    @Autowired
    private SensorSyncService sensorSyncService;

    @Autowired
    private FusionService fusionService;

    @Autowired
    private DriftService driftService;

    @Autowired
    private RebuildService rebuildService;

    @Test
    void rebuildsCommittedFrameFromEventsWithIdenticalEvidence() {
        // 场景包含漂移热更新、乱序、重复与插值，重建结果必须与在线提交一致
        driftService.update(SensorType.LIDAR, 1, 20L, TARGET - 1_000);
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-before", TARGET - 100);
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-after", TARGET + 40);
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-after", TARGET + 40); // 重复，幂等丢弃
        sensorSyncService.ingest(SensorType.RADAR, "radar-1", TARGET + 30);
        sensorSyncService.ingest(SensorType.CAMERA, "cam-1", TARGET - 10);
        sensorSyncService.ingest(SensorType.IMU, "imu-1", TARGET + 5);
        sensorSyncService.ingest(SensorType.IMU, "imu-0", TARGET - 100); // 乱序但在容忍内
        FusionFrame committed = fusionService.commit(TARGET);

        FusionFrame rebuilt = rebuildService.rebuild(TARGET);

        assertThat(rebuilt.targetTs()).isEqualTo(TARGET);
        assertThat(rebuilt.inputs()).containsExactlyInAnyOrderElementsOf(committed.inputs());
        // LIDAR 校正时戳 = 原始 + 20：target-80 与 target+60 均超出 50ms 窗口但可插值
        assertThat(rebuilt.inputOf(SensorType.LIDAR).interpolated()).isTrue();
        assertThat(rebuilt.inputOf(SensorType.LIDAR).messageId()).isEqualTo("lidar-before+lidar-after");
    }

    @Test
    void rebuildIsIdempotent() {
        sensorSyncService.ingest(SensorType.LIDAR, "lidar-1", TARGET);
        sensorSyncService.ingest(SensorType.RADAR, "radar-1", TARGET);
        sensorSyncService.ingest(SensorType.CAMERA, "cam-1", TARGET);
        sensorSyncService.ingest(SensorType.IMU, "imu-1", TARGET);
        fusionService.commit(TARGET);

        FusionFrame first = rebuildService.rebuild(TARGET);
        FusionFrame second = rebuildService.rebuild(TARGET);

        assertThat(second.inputs()).containsExactlyInAnyOrderElementsOf(first.inputs());
    }

    @Test
    void rebuildOfUncommittedTargetReturnsNotFound() {
        assertThatThrownBy(() -> rebuildService.rebuild(TARGET + 9_999))
                .isInstanceOf(SyncException.class)
                .extracting(e -> ((SyncException) e).code())
                .isEqualTo(SyncErrorCode.FRAME_NOT_FOUND);
    }
}
