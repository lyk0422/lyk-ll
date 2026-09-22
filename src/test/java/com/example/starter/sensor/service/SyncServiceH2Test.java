package com.example.starter.sensor.service;

import com.example.starter.sensor.api.CorrectionRequest;
import com.example.starter.sensor.api.FrameInputView;
import com.example.starter.sensor.api.FrameView;
import com.example.starter.sensor.api.IngestMessageRequest;
import com.example.starter.sensor.api.IngestMessageResponse;
import com.example.starter.sensor.domain.FrameFailReason;
import com.example.starter.sensor.domain.IngestStatus;
import com.example.starter.sensor.domain.SensorType;
import com.example.starter.sensor.po.DroppedRow;
import com.example.starter.sensor.repo.SyncRepository;
import com.example.starter.sensor.support.AbstractH2IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 同步服务 H2 集成测试：真实嵌入式 H2（MODE=MySQL）约束与事务，
 * 覆盖缺帧、乱序、迟到、重复、漂移热更新、窗口边界与事件重建。
 */
class SyncServiceH2Test extends AbstractH2IntegrationTest {

    private static final long T = 2_000_000_000L;
    private static final long MS = 1_000_000L;

    @Autowired
    private SyncService syncService;

    @Autowired
    private ReplayService replayService;

    @Autowired
    private SyncRepository repository;

    private IngestMessageResponse ingest(SensorType sensor, String id, long rawTs) {
        return ingest(sensor, id, rawTs, null);
    }

    private IngestMessageResponse ingest(SensorType sensor, String id, long rawTs, String payload) {
        return syncService.ingest(sensor, new IngestMessageRequest(id, rawTs, payload));
    }

    private void pushWatermarkPastWindow(long targetTs) {
        // 各传感器补一条远超窗口右缘的消息，使 enforceWatermark 门控通过
        ingest(SensorType.LIDAR, "lidar-wm", targetTs + 200 * MS);
        ingest(SensorType.RADAR, "radar-wm", targetTs + 200 * MS);
        ingest(SensorType.CAMERA, "camera-wm", targetTs + 200 * MS);
        ingest(SensorType.IMU, "imu-wm", targetTs + 100 * MS);
    }

    private FrameInputView input(FrameView frame, SensorType sensor) {
        return frame.inputs().stream()
                .filter(i -> i.sensor().equals(sensor.name()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void fullFlowCommitsImmutableFrameWithEvidence() {
        // LIDAR 乱序到达（80ms 逆序，在 100ms 迟到容忍内）；RADAR 两锚点均在 50ms 窗口之外
        ingest(SensorType.LIDAR, "l-high", T + 80 * MS, "{\"pts\":12001}");
        ingest(SensorType.RADAR, "r0", T - 80 * MS, "{\"v\":10.0}");
        ingest(SensorType.LIDAR, "l0", T, "{\"pts\":12000}");
        ingest(SensorType.CAMERA, "c0", T - 5 * MS, "{\"img\":\"cam0\"}");
        ingest(SensorType.IMU, "i0", T + 5 * MS, "{\"gx\":0.01}");
        ingest(SensorType.RADAR, "r1", T + 80 * MS, "{\"v\":30.0}");

        FrameView frame = syncService.buildFrame(T, false);
        assertThat(frame.status()).isEqualTo("COMMITTED");
        assertThat(frame.frameId()).isEqualTo("F-" + T);
        assertThat(frame.inputs()).hasSize(4);

        FrameInputView lidar = input(frame, SensorType.LIDAR);
        assertThat(lidar.messageId()).isEqualTo("l0");
        assertThat(lidar.rawTimestamp()).isEqualTo(T);
        assertThat(lidar.correctedTimestamp()).isEqualTo(T);
        assertThat(lidar.timeDeltaNanos()).isZero();
        assertThat(lidar.interpolated()).isFalse();
        assertThat(lidar.anchorMessageIds()).containsExactly("l0");

        FrameInputView camera = input(frame, SensorType.CAMERA);
        assertThat(camera.timeDeltaNanos()).isEqualTo(-5 * MS);

        FrameInputView imu = input(frame, SensorType.IMU);
        assertThat(imu.timeDeltaNanos()).isEqualTo(5 * MS);

        FrameInputView radar = input(frame, SensorType.RADAR);
        assertThat(radar.interpolated()).isTrue();
        assertThat(radar.messageId()).isEqualTo("r0~r1");
        assertThat(radar.anchorMessageIds()).containsExactly("r0", "r1");
        assertThat(radar.timeDeltaNanos()).isZero();
        // 中点插值：10 + (30-10)*0.5 = 20
        assertThat(radar.rawTimestamp()).isEqualTo(T);

        long firstCommittedAt = frame.committedAt();

        // 之后再到达窗口内的新消息（甚至更贴近目标时刻），已提交帧不可变
        clock.advanceMillis(1234L);
        ingest(SensorType.LIDAR, "l-late-in-window", T - 1 * MS);
        FrameView again = syncService.buildFrame(T, false);
        assertThat(again.status()).isEqualTo("COMMITTED");
        assertThat(again.committedAt()).isEqualTo(firstCommittedAt);
        assertThat(input(again, SensorType.LIDAR).messageId()).isEqualTo("l0");
    }

    @Test
    void missingFrameProducesStableRejection() {
        ingest(SensorType.LIDAR, "l0", T);
        ingest(SensorType.RADAR, "r0", T);
        ingest(SensorType.CAMERA, "c0", T);
        // 无 IMU 数据
        FrameView frame = syncService.buildFrame(T, false);
        assertThat(frame.status()).isEqualTo("REJECTED");
        assertThat(frame.reason()).isEqualTo(FrameFailReason.MISSING_FRAME.name());
        assertThat(frame.reasonDetail()).contains("IMU");
        assertThat(frame.inputs()).isEmpty();

        // 终态：之后补齐数据，同一目标时刻仍保持原拒绝结论（稳定错误）
        ingest(SensorType.IMU, "i0", T);
        FrameView retry = syncService.buildFrame(T, false);
        assertThat(retry.status()).isEqualTo("REJECTED");
        assertThat(retry.reason()).isEqualTo(FrameFailReason.MISSING_FRAME.name());
    }

    @Test
    void outOfWindowAndCannotInterpolateHaveStableReasons() {
        // l-far 在 LIDAR 窗口（50ms）之外，但距后续 l0 不超过 100ms 迟到容忍
        ingest(SensorType.LIDAR, "l-far", T + 90 * MS);
        ingest(SensorType.RADAR, "r0", T - 150 * MS);
        ingest(SensorType.RADAR, "r1", T + 150 * MS);
        ingest(SensorType.CAMERA, "c0", T);
        ingest(SensorType.IMU, "i0", T);

        FrameView frame = syncService.buildFrame(T, false);
        assertThat(frame.status()).isEqualTo("REJECTED");
        // 按传感器固定顺序，LIDAR 先失败：超出窗口且不支持插值
        assertThat(frame.reason()).isEqualTo(FrameFailReason.OUT_OF_WINDOW.name());
        assertThat(frame.reasonDetail()).contains("LIDAR").contains("l-far");

        // 把 LIDAR 补进窗口后，新目标时刻暴露 RADAR 锚点间距 300ms > 200ms，无法插值
        ingest(SensorType.LIDAR, "l0", T);
        long t2 = T + 40 * MS;
        FrameView frame2 = syncService.buildFrame(t2, false);
        assertThat(frame2.status()).isEqualTo("REJECTED");
        assertThat(frame2.reason()).isEqualTo(FrameFailReason.CANNOT_INTERPOLATE.name());
        assertThat(frame2.reasonDetail()).contains("r0~r1");

        // 事件重建同样给出稳定原因
        FrameView replayView = replayService.reconstruct(T, null);
        assertThat(replayView.status()).isEqualTo("REJECTED");
        assertThat(replayView.reason()).isEqualTo(FrameFailReason.CANNOT_INTERPOLATE.name());
        assertThat(replayView.reasonDetail()).contains("r0~r1");
        assertThat(replayView.reconstructed()).isTrue();
    }

    @Test
    void duplicateMessagesAreIdempotentAndRecorded() {
        IngestMessageResponse first = ingest(SensorType.LIDAR, "dup-1", T);
        assertThat(first.status()).isEqualTo(IngestStatus.ACCEPTED);

        IngestMessageResponse second = ingest(SensorType.LIDAR, "dup-1", T);
        IngestMessageResponse third = ingest(SensorType.LIDAR, "dup-1", T + 999);
        assertThat(second.status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(third.status()).isEqualTo(IngestStatus.DUPLICATE);

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sensor_message WHERE sensor='LIDAR' AND message_id='dup-1'",
                Integer.class);
        assertThat(rows).isEqualTo(1);

        var dropped = repository.findDropped("LIDAR");
        assertThat(dropped).extracting(DroppedRow::reason)
                .containsExactly("DUPLICATE", "DUPLICATE");
        assertThat(dropped.get(0).reasonDetail()).contains("dup-1");
    }

    @Test
    void lateMessageIsDroppedAndNeverAffectsFrames() {
        // 先在目标时刻放置 LIDAR 帧并制造高水位线
        ingest(SensorType.LIDAR, "l0", T);
        ingest(SensorType.RADAR, "r0", T);
        ingest(SensorType.CAMERA, "c0", T);
        ingest(SensorType.IMU, "i0", T);

        ingest(SensorType.LIDAR, "l-new", 5_000 * MS);
        // 水位线 5s，迟到容忍 100ms：4.899999999s 严格早于 4.9s，判迟到
        IngestMessageResponse late = ingest(SensorType.LIDAR, "l-old", 4_899 * MS + 999_999L);
        assertThat(late.status()).isEqualTo(IngestStatus.LATE);
        assertThat(late.watermark()).isEqualTo(5_000 * MS);

        Integer eventRows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sensor_message WHERE sensor='LIDAR' AND message_id='l-old'",
                Integer.class);
        assertThat(eventRows).isZero();

        DroppedRow dropped = repository.findDropped("LIDAR").get(0);
        assertThat(dropped.reason()).isEqualTo("LATE");
        assertThat(dropped.reasonDetail()).contains("水位线");

        // 容忍窗口内（恰在边界）的乱序消息仍应接受
        IngestMessageResponse outOfOrder = ingest(SensorType.LIDAR, "l-ooo", 4_900 * MS);
        assertThat(outOfOrder.status()).isEqualTo(IngestStatus.ACCEPTED);

        FrameView committed = syncService.buildFrame(T, false);
        assertThat(committed.status()).isEqualTo("COMMITTED");

        // 已提交帧之后再来一条严重迟到消息，帧不变
        IngestMessageResponse veryLate = ingest(SensorType.IMU, "i-way-too-old", 0L);
        assertThat(veryLate.status()).isEqualTo(IngestStatus.LATE);
        FrameView unchanged = syncService.buildFrame(T, false);
        assertThat(unchanged.committedAt()).isEqualTo(committed.committedAt());
        assertThat(input(unchanged, SensorType.IMU).messageId()).isEqualTo("i0");
    }

    @Test
    void watermarkGateWaitsThenAllowsCommitOnRetry() {
        ingest(SensorType.LIDAR, "l0", T);
        ingest(SensorType.RADAR, "r0", T);
        ingest(SensorType.CAMERA, "c0", T);
        ingest(SensorType.IMU, "i0", T);

        FrameView waiting = syncService.buildFrame(T, true);
        assertThat(waiting.status()).isEqualTo("REJECTED");
        assertThat(waiting.reason()).isEqualTo(FrameFailReason.WAITING_FOR_WATERMARK.name());

        pushWatermarkPastWindow(T);
        FrameView committed = syncService.buildFrame(T, true);
        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(committed.inputs()).hasSize(4);
    }

    @Test
    void correctionHotUpdateVersionsAndOnlyAffectsLaterCandidates() {
        // v1：IMU 时钟慢 1ms，offset +1ms，立即生效
        var v1 = syncService.updateCorrection(SensorType.IMU,
                new CorrectionRequest(1.0d, 1 * MS, 0L));
        assertThat(v1.version()).isEqualTo(1L);

        ingest(SensorType.LIDAR, "l0", T);
        ingest(SensorType.RADAR, "r0", T);
        ingest(SensorType.CAMERA, "c0", T);
        ingest(SensorType.IMU, "i0", T - 1 * MS);
        pushWatermarkPastWindow(T);

        clock.advanceMillis(500L);
        long cutoffV1 = clock.nowMillis();

        FrameView atT = syncService.buildFrame(T, true);
        assertThat(atT.status()).isEqualTo("COMMITTED");
        FrameInputView imu = input(atT, SensorType.IMU);
        assertThat(imu.correctedTimestamp()).isEqualTo(T);
        assertThat(imu.rawTimestamp()).isEqualTo(T - 1 * MS);

        // v2 热更新：offset 改 +5ms，仅影响之后构建、且目标时刻 >= effectiveAt 的候选
        clock.advanceMillis(500L);
        var v2 = syncService.updateCorrection(SensorType.IMU,
                new CorrectionRequest(1.0d, 5 * MS, 0L));
        assertThat(v2.version()).isEqualTo(2L);

        // 已提交历史帧不被改写
        FrameView historical = syncService.buildFrame(T, true);
        assertThat(historical.status()).isEqualTo("COMMITTED");
        assertThat(input(historical, SensorType.IMU).correctedTimestamp()).isEqualTo(T);

        // 之后新目标时刻的候选使用 v2：补齐其他传感器在 t2 附近的数据
        long t2 = T + 100 * MS;
        ingest(SensorType.LIDAR, "l1", t2);
        ingest(SensorType.RADAR, "r2", t2);
        ingest(SensorType.CAMERA, "c1", t2);
        ingest(SensorType.IMU, "i1", t2 - 5 * MS);
        FrameView atT2 = syncService.buildFrame(t2, false);
        assertThat(atT2.status()).isEqualTo("COMMITTED");
        assertThat(input(atT2, SensorType.IMU).correctedTimestamp()).isEqualTo(t2);

        // 事件重建：无截止线时 v2 生效，raw T-1ms -> corrected T+4ms（仍在 IMU 10ms 窗口内）
        FrameView replayLatest = replayService.reconstruct(T, null);
        assertThat(replayLatest.status()).isEqualTo("COMMITTED");
        assertThat(input(replayLatest, SensorType.IMU).correctedTimestamp())
                .isEqualTo(T + 4 * MS);

        // 截止到 v1 写入时刻重建，复原历史结果
        FrameView replayHistorical = replayService.reconstruct(T, cutoffV1);
        assertThat(replayHistorical.status()).isEqualTo("COMMITTED");
        assertThat(input(replayHistorical, SensorType.IMU).correctedTimestamp())
                .isEqualTo(T);
    }

    @Test
    void futureEffectiveCorrectionDoesNotApplyYet() {
        syncService.updateCorrection(SensorType.LIDAR,
                new CorrectionRequest(1.0d, 999 * MS, T + 1L));
        ingest(SensorType.LIDAR, "l0", T);
        ingest(SensorType.RADAR, "r0", T);
        ingest(SensorType.CAMERA, "c0", T);
        ingest(SensorType.IMU, "i0", T);
        FrameView frame = syncService.buildFrame(T, false);
        assertThat(frame.status()).isEqualTo("COMMITTED");
        assertThat(input(frame, SensorType.LIDAR).correctedTimestamp()).isEqualTo(T);
    }

    @Test
    void reconstructionIsIdempotentAndWritesNothing() {
        ingest(SensorType.LIDAR, "l0", T);
        ingest(SensorType.RADAR, "r0", T - 80 * MS, "{\"v\":1.0}");
        ingest(SensorType.RADAR, "r1", T + 80 * MS, "{\"v\":2.0}");
        ingest(SensorType.CAMERA, "c0", T);
        ingest(SensorType.IMU, "i0", T);

        FrameView a = replayService.reconstruct(T, null);
        FrameView b = replayService.reconstruct(T, null);
        assertThat(a).usingRecursiveComparison().isEqualTo(b);
        assertThat(a.status()).isEqualTo("COMMITTED");
        assertThat(input(a, SensorType.RADAR).interpolated()).isTrue();

        Integer frameRows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM fusion_frame", Integer.class);
        Integer inputRows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM frame_input", Integer.class);
        assertThat(frameRows).isZero();
        assertThat(inputRows).isZero();
    }

    @Test
    void invalidSlopeRejected() {
        assertThatThrownBy(() -> syncService.updateCorrection(SensorType.IMU,
                new CorrectionRequest(0.0d, 0L, 0L)))
                .hasMessageContaining("slope");
    }

    @Test
    void windowBoundaryEvidenceUsesClosestMessage() {
        // CAMERA 窗口 60ms：恰好 -60ms 处于闭区间边界，必须命中
        ingest(SensorType.CAMERA, "c-edge", T - 60 * MS);
        ingest(SensorType.LIDAR, "l0", T);
        ingest(SensorType.RADAR, "r0", T);
        ingest(SensorType.IMU, "i0", T);
        FrameView frame = syncService.buildFrame(T, false);
        assertThat(frame.status()).isEqualTo("COMMITTED");
        FrameInputView camera = input(frame, SensorType.CAMERA);
        assertThat(camera.timeDeltaNanos()).isEqualTo(-60 * MS);
        assertThat(camera.interpolated()).isFalse();
    }

    @Test
    void watermarkExposedPerSensor() {
        ingest(SensorType.IMU, "i0", 100 * MS);
        ingest(SensorType.IMU, "i1", 80 * MS);
        assertThat(syncService.watermarkOf(SensorType.IMU)).isEqualTo(100 * MS);
        assertThat(syncService.watermarkOf(SensorType.LIDAR)).isEqualTo(Long.MIN_VALUE);
    }
}
