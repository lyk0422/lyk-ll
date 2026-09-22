package com.example.starter.sensor.engine;

import com.example.starter.sensor.SyncProperties;
import com.example.starter.sensor.domain.FrameFailReason;
import com.example.starter.sensor.domain.SensorType;
import com.example.starter.sensor.po.CorrectionRow;
import com.example.starter.sensor.po.MessageRow;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 融合帧配对引擎：以消息事件与校正版本为输入的纯函数计算。
 *
 * <p>在线构建与 H2 事件重建共用同一套规则，保证结果确定、重放幂等：
 * <ol>
 *     <li>按目标融合时刻选择该传感器生效的校正版本（{@code effective_at <= target} 的最新版本，
 *     无版本时为 0 号恒等校正），统一把原始时戳换算为校正时戳；</li>
 *     <li>存在校正时戳落入闭窗口 {@code |corrected - target| <= windowNanos} 的消息时直接配对；</li>
 *     <li>RADAR/IMU 允许在相邻量测间线性插值，要求两侧有锚点且间距不超过最大插值间距；</li>
 *     <li>其余情况返回稳定失败原因：MISSING_FRAME / OUT_OF_WINDOW / CANNOT_INTERPOLATE。</li>
 * </ol>
 */
public final class PairingEngine {

    /** 0 号恒等校正：slope=1，offset=0，热更新前与无版本时使用。 */
    public static final Correction IDENTITY = new Correction(0L, 1.0d, 0L, Long.MIN_VALUE);

    private static final ObjectMapper JSON = new ObjectMapper();

    private PairingEngine() {
    }

    /**
     * 选择目标时刻生效的校正版本：effective_at 不超过目标时刻的版本中，版本号最大者。
     */
    public static Correction selectCorrection(List<CorrectionRow> versions, long targetTs) {
        Correction selected = IDENTITY;
        for (CorrectionRow row : versions) {
            if (row.effectiveAt() <= targetTs && row.version() > selected.version()) {
                selected = new Correction(row.version(), row.slope(), row.offsetNanos(), row.effectiveAt());
            }
        }
        return selected;
    }

    /** 应用校正：corrected = round(raw * slope + offset)。 */
    public static long correct(long rawTs, Correction correction) {
        return Math.round(rawTs * correction.slope() + correction.offsetNanos());
    }

    /**
     * 为单个传感器配对目标时刻的输入证据。
     *
     * @param sensor   传感器类型
     * @param messages 该传感器全部已接受消息（原始时戳）
     * @param versions 该传感器可用校正版本（重建时可按截止线过滤）
     * @param targetTs 目标融合时刻，epoch 纳秒
     * @param window   窗口/插值参数
     */
    public static PairResult pair(SensorType sensor,
                                  List<MessageRow> messages,
                                  List<CorrectionRow> versions,
                                  long targetTs,
                                  SyncProperties.SensorWindow window) {
        Correction correction = selectCorrection(versions, targetTs);

        // 全部消息按所选版本换算为校正时戳
        List<Point> points = new ArrayList<>(messages.size());
        for (MessageRow m : messages) {
            long corrected = correct(m.rawTs(), correction);
            points.add(new Point(m, corrected, corrected - targetTs));
        }

        if (points.isEmpty()) {
            return PairResult.fail(FrameFailReason.MISSING_FRAME,
                    sensor + " 无任何已接受消息，目标时刻 " + targetTs + " 缺帧");
        }

        // 窗口内取与目标时刻绝对差最小者；平局取较早（原始时戳小、消息 ID 小）者保证确定性
        Point inWindow = null;
        long bestAbs = Long.MAX_VALUE;
        for (Point p : points) {
            long abs = Math.abs(p.delta());
            if (abs <= window.getWindowNanos()
                    && (abs < bestAbs
                        || (abs == bestAbs && earlier(p, inWindow)))) {
                inWindow = p;
                bestAbs = abs;
            }
        }
        if (inWindow != null) {
            return PairResult.ok(new MatchedInput(
                    sensor, inWindow.message().messageId(), inWindow.message().rawTs(),
                    inWindow.corrected(), inWindow.delta(), false,
                    List.of(inWindow.message().messageId()), correction.version(),
                    inWindow.message().payload()));
        }

        boolean interpolatable = sensor == SensorType.RADAR || sensor == SensorType.IMU;
        if (!interpolatable) {
            Point nearest = nearest(points);
            return PairResult.fail(FrameFailReason.OUT_OF_WINDOW,
                    sensor + " 最近消息 " + nearest.message().messageId()
                            + " 校正时戳 " + nearest.corrected() + " 与目标时刻 " + targetTs
                            + " 相差 " + nearest.delta() + "ns，超过窗口 "
                            + window.getWindowNanos() + "ns，且该传感器不支持插值");
        }

        // 寻找左右锚点：严格位于目标时刻两侧
        Point prev = null;
        Point next = null;
        for (Point p : points) {
            if (p.corrected() < targetTs && (prev == null || p.corrected() > prev.corrected())) {
                prev = p;
            }
            if (p.corrected() > targetTs && (next == null || p.corrected() < next.corrected())) {
                next = p;
            }
        }
        if (prev == null || next == null) {
            Point nearest = nearest(points);
            return PairResult.fail(FrameFailReason.CANNOT_INTERPOLATE,
                    sensor + " 目标时刻 " + targetTs + " 两侧缺少可插值锚点（"
                            + (prev == null ? "无前置量测" : "无后置量测")
                            + "），最近消息 " + nearest.message().messageId()
                            + " 校正时戳 " + nearest.corrected());
        }

        long gap = next.corrected() - prev.corrected();
        if (gap > window.getMaxInterpolationGapNanos()) {
            return PairResult.fail(FrameFailReason.CANNOT_INTERPOLATE,
                    sensor + " 锚点 " + prev.message().messageId() + "~" + next.message().messageId()
                            + " 校正间距 " + gap + "ns 超过最大插值间距 "
                            + window.getMaxInterpolationGapNanos() + "ns");
        }

        double fraction = (double) (targetTs - prev.corrected()) / gap;
        long interpolatedRaw = Math.round(
                prev.message().rawTs()
                        + (next.message().rawTs() - prev.message().rawTs()) * fraction);
        String interpolatedPayload = interpolatePayload(prev.message().payload(),
                next.message().payload(), fraction);
        String joinedIds = prev.message().messageId() + "~" + next.message().messageId();

        return PairResult.ok(new MatchedInput(
                sensor, joinedIds, interpolatedRaw, targetTs, 0L, true,
                List.of(prev.message().messageId(), next.message().messageId()),
                correction.version(), interpolatedPayload));
    }

    /**
     * 对两个 JSON 对象负载做尽力数值插值：重叠键且均为数值时线性加权；
     * 非数值键与无法解析的负载不强行生成，返回 null。
     */
    static String interpolatePayload(String a, String b, double fraction) {
        if (a == null || b == null) {
            return null;
        }
        try {
            Map<String, Object> ma = JSON.readValue(a, Map.class);
            Map<String, Object> mb = JSON.readValue(b, Map.class);
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ma.entrySet()) {
                Object va = e.getValue();
                Object vb = mb.get(e.getKey());
                if (va instanceof Number na && vb instanceof Number nb) {
                    merged.put(e.getKey(), na.doubleValue()
                            + (nb.doubleValue() - na.doubleValue()) * fraction);
                }
            }
            return merged.isEmpty() ? null : JSON.writeValueAsString(merged);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Point nearest(List<Point> points) {
        return points.stream()
                .min(Comparator.comparingLong((Point p) -> Math.abs(p.delta()))
                        .thenComparingLong(p -> p.corrected())
                        .thenComparing(p -> p.message().messageId()))
                .orElseThrow();
    }

    private static boolean earlier(Point a, Point b) {
        if (b == null) {
            return true;
        }
        if (a.corrected() != b.corrected()) {
            return a.corrected() < b.corrected();
        }
        return a.message().messageId().compareTo(b.message().messageId()) < 0;
    }

    /**
     * 生效校正参数。
     *
     * @param version     版本号，0 表示恒等校正
     * @param slope       漂移斜率
     * @param offsetNanos 固定偏移，纳秒
     * @param effectiveAt 生效时刻，epoch 纳秒
     */
    public record Correction(long version, double slope, long offsetNanos, long effectiveAt) {
    }

    /**
     * 配对成功的单传感器输入（构建帧证据行所需的全部字段）。
     */
    public record MatchedInput(
            SensorType sensor,
            String messageId,
            long rawTs,
            long correctedTs,
            long timeDeltaNanos,
            boolean interpolated,
            List<String> anchorMessageIds,
            long correctionVersion,
            String payload) {
    }

    /**
     * 单传感器配对结果：成功携带输入，失败携带稳定原因码与可定位描述。
     */
    public static final class PairResult {
        private final boolean success;
        private final MatchedInput input;
        private final FrameFailReason reason;
        private final String detail;

        private PairResult(boolean success, MatchedInput input, FrameFailReason reason, String detail) {
            this.success = success;
            this.input = input;
            this.reason = reason;
            this.detail = detail;
        }

        static PairResult ok(MatchedInput input) {
            return new PairResult(true, input, null, null);
        }

        static PairResult fail(FrameFailReason reason, String detail) {
            return new PairResult(false, null, reason, detail);
        }

        public boolean isSuccess() {
            return success;
        }

        public MatchedInput input() {
            return input;
        }

        public FrameFailReason reason() {
            return reason;
        }

        public String detail() {
            return detail;
        }
    }

    /** 消息与其在某校正版本下的校正时戳、与目标时刻之差。 */
    private record Point(MessageRow message, long corrected, long delta) {
    }
}
