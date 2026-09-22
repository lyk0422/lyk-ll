package com.example.starter.sensor.engine;

import com.example.starter.sensor.SyncProperties;
import com.example.starter.sensor.domain.FrameFailReason;
import com.example.starter.sensor.domain.SensorType;
import com.example.starter.sensor.po.CorrectionRow;
import com.example.starter.sensor.po.MessageRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PairingEngine} 纯函数单元测试：校正选择、窗口闭区间边界、
 * 线性插值与缺帧/超窗/无法插值等稳定失败原因。
 */
class PairingEngineTest {

    private static final long T = 1_000_000_000L;
    private static final long WINDOW = 50_000_000L;
    private static final long MAX_GAP = 200_000_000L;

    private SyncProperties.SensorWindow window(long windowNanos, long maxGap) {
        SyncProperties.SensorWindow w = new SyncProperties.SensorWindow();
        w.setWindowNanos(windowNanos);
        w.setLatenessNanos(100_000_000L);
        w.setMaxInterpolationGapNanos(maxGap);
        return w;
    }

    private MessageRow msg(String id, long rawTs) {
        return new MessageRow("RADAR", id, rawTs, null, 0L);
    }

    private MessageRow msg(String id, long rawTs, String payload) {
        return new MessageRow("RADAR", id, rawTs, payload, 0L);
    }

    @Test
    void noMessagesMeansMissingFrame() {
        PairingEngine.PairResult r = PairingEngine.pair(SensorType.LIDAR, List.of(),
                List.of(), T, window(WINDOW, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.reason()).isEqualTo(FrameFailReason.MISSING_FRAME);
    }

    @Test
    void exactWindowBoundaryIsInclusive() {
        // 校正时戳恰好落在目标时刻 ±window 上，闭区间必须接受
        MessageRow edge = msg("m-edge", T + WINDOW);
        PairingEngine.PairResult r = PairingEngine.pair(SensorType.LIDAR, List.of(edge),
                List.of(), T, window(WINDOW, 0L));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.input().timeDeltaNanos()).isEqualTo(WINDOW);
        assertThat(r.input().interpolated()).isFalse();
        assertThat(r.input().anchorMessageIds()).containsExactly("m-edge");
    }

    @Test
    void justOutsideWindowForNonInterpolatableSensorIsOutOfWindow() {
        MessageRow edge = msg("m-out", T + WINDOW + 1L);
        PairingEngine.PairResult r = PairingEngine.pair(SensorType.CAMERA, List.of(edge),
                List.of(), T, window(WINDOW, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.reason()).isEqualTo(FrameFailReason.OUT_OF_WINDOW);
        assertThat(r.detail()).contains("m-out");
    }

    @Test
    void nearestMessageInsideWindowWins() {
        MessageRow far = msg("m-far", T - 40_000_000L);
        MessageRow near = msg("m-near", T + 5_000_000L);
        PairingEngine.PairResult r = PairingEngine.pair(SensorType.LIDAR,
                List.of(far, near), List.of(), T, window(WINDOW, 0L));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.input().messageId()).isEqualTo("m-near");
        assertThat(r.input().timeDeltaNanos()).isEqualTo(5_000_000L);
    }

    @Test
    void tieOnDistanceChoosesEarlierMessageDeterministically() {
        MessageRow later = msg("m-b", T + 10_000_000L);
        MessageRow earlier = msg("m-a", T - 10_000_000L);
        PairingEngine.PairResult r = PairingEngine.pair(SensorType.LIDAR,
                List.of(later, earlier), List.of(), T, window(WINDOW, 0L));
        assertThat(r.input().messageId()).isEqualTo("m-a");
    }

    @Test
    void interpolationUsedForRadarBetweenAnchors() {
        // 两锚点均在 50ms 窗口之外、间距 160ms 不超过最大插值间距 200ms
        MessageRow prev = msg("r0", T - 80_000_000L, "{\"v\":10.0}");
        MessageRow next = msg("r1", T + 80_000_000L, "{\"v\":30.0}");
        PairingEngine.PairResult r = PairingEngine.pair(SensorType.RADAR,
                List.of(prev, next), List.of(), T, window(WINDOW, MAX_GAP));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.input().interpolated()).isTrue();
        assertThat(r.input().messageId()).isEqualTo("r0~r1");
        assertThat(r.input().correctedTs()).isEqualTo(T);
        assertThat(r.input().timeDeltaNanos()).isZero();
        assertThat(r.input().anchorMessageIds()).containsExactly("r0", "r1");
        // 中点插值：10 + (30-10)*0.5 = 20
        assertThat(r.input().payload()).contains("20.0");
    }

    @Test
    void cannotInterpolateWithoutBothSides() {
        MessageRow onlyBefore = msg("r0", T - 80_000_000L);
        PairingEngine.PairResult r = PairingEngine.pair(SensorType.RADAR,
                List.of(onlyBefore), List.of(), T, window(WINDOW, MAX_GAP));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.reason()).isEqualTo(FrameFailReason.CANNOT_INTERPOLATE);
        assertThat(r.detail()).contains("无后置量测");
    }

    @Test
    void cannotInterpolateWhenAnchorGapTooLarge() {
        MessageRow prev = msg("r0", T - 150_000_000L);
        MessageRow next = msg("r1", T + 150_000_000L);
        PairingEngine.PairResult r = PairingEngine.pair(SensorType.RADAR,
                List.of(prev, next), List.of(), T, window(WINDOW, MAX_GAP));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.reason()).isEqualTo(FrameFailReason.CANNOT_INTERPOLATE);
        assertThat(r.detail()).contains("超过最大插值间距");
    }

    @Test
    void correctionSlopeAndOffsetAppliedToRawTimestamps() {
        // corrected = raw * 2 + 1000
        CorrectionRow v1 = new CorrectionRow(1L, "RADAR", 1L, 2.0d, 1_000L, 0L, 0L);
        // raw=499_999_500 -> corrected = 1_000_000_000 = T，窗口内直接命中
        MessageRow m = msg("m-corr", 499_999_500L);
        PairingEngine.PairResult r = PairingEngine.pair(SensorType.RADAR,
                List.of(m), List.of(v1), T, window(WINDOW, MAX_GAP));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.input().correctedTs()).isEqualTo(T);
        assertThat(r.input().correctionVersion()).isEqualTo(1L);
    }

    @Test
    void futureEffectiveCorrectionIsNotApplied() {
        // 生效时刻晚于目标时刻，必须继续使用恒等校正
        CorrectionRow future = new CorrectionRow(1L, "RADAR", 1L, 2.0d, 0L,
                T + 1L, 0L);
        MessageRow m = msg("m-raw", T);
        PairingEngine.PairResult r = PairingEngine.pair(SensorType.RADAR,
                List.of(m), List.of(future), T, window(WINDOW, MAX_GAP));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.input().correctedTs()).isEqualTo(T);
        assertThat(r.input().correctionVersion()).isZero();
    }

    @Test
    void selectCorrectionPicksLatestEffectiveVersion() {
        CorrectionRow v1 = new CorrectionRow(1L, "IMU", 1L, 1.0d, 100L, 0L, 0L);
        CorrectionRow v2 = new CorrectionRow(2L, "IMU", 2L, 1.0d, 200L, T - 10L, 0L);
        CorrectionRow v3 = new CorrectionRow(3L, "IMU", 3L, 1.0d, 300L, T + 10L, 0L);
        PairingEngine.Correction c = PairingEngine.selectCorrection(
                List.of(v1, v2, v3), T);
        assertThat(c.version()).isEqualTo(2L);
        assertThat(c.offsetNanos()).isEqualTo(200L);
    }

    @Test
    void unorderedArrivalDoesNotChangeDeterministicOutcome() {
        List<MessageRow> ordered = List.of(
                msg("a", T - 30_000_000L), msg("b", T), msg("c", T + 30_000_000L));
        List<MessageRow> shuffled = List.of(
                msg("c", T + 30_000_000L), msg("a", T - 30_000_000L), msg("b", T));

        PairingEngine.PairResult r1 = PairingEngine.pair(SensorType.IMU, ordered,
                List.of(), T, window(WINDOW, MAX_GAP));
        PairingEngine.PairResult r2 = PairingEngine.pair(SensorType.IMU, shuffled,
                List.of(), T, window(WINDOW, MAX_GAP));
        assertThat(r1.input().messageId()).isEqualTo("b");
        assertThat(r2.input().messageId()).isEqualTo("b");
    }
}
