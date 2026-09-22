package com.example.starter.sync.engine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.example.starter.sync.SensorType;
import com.example.starter.sync.SyncErrorCode;
import com.example.starter.sync.SyncException;
import com.example.starter.sync.SyncProperties;
import com.example.starter.sync.domain.DiscardReason;
import com.example.starter.sync.domain.DriftParams;
import com.example.starter.sync.domain.FrameInput;
import com.example.starter.sync.domain.FusionFrame;
import com.example.starter.sync.domain.IngestOutcome;
import com.example.starter.sync.domain.SensorSample;

/**
 * 多传感器时间同步核心引擎（纯内存、线程安全）。
 *
 * <p>每类传感器独立维护：已见消息ID集合、按校正时戳排序的样本缓冲、水位线。
 * 在线服务与事件重建共用同一引擎，保证重放结果与在线提交一致。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>重复消息ID：幂等丢弃，原因 DUPLICATE_MESSAGE；</li>
 *   <li>校正时戳 &lt; 水位线 - 迟到容忍：丢弃，原因 LATE_MESSAGE；</li>
 *   <li>校正时戳落入已提交帧窗口：丢弃，原因 FRAME_ALREADY_COMMITTED，已提交帧不可变；</li>
 *   <li>漂移参数按 原始时戳 &gt;= effectiveFrom 生效，新版本不影响已提交帧。</li>
 * </ul>
 */
public class SyncEngine {

    private final SyncProperties properties;
    private final Map<SensorType, TreeMap<Long, SensorSample>> buffers = new EnumMap<>(SensorType.class);
    private final Map<SensorType, Long> watermarks = new EnumMap<>(SensorType.class);
    private final Map<SensorType, List<DriftParams>> driftParams = new EnumMap<>(SensorType.class);
    private final Set<String> seenMessageKeys = new HashSet<>();
    private final List<Long> committedTargets = new ArrayList<>();

    public SyncEngine(SyncProperties properties) {
        this.properties = properties;
        for (SensorType type : SensorType.values()) {
            buffers.put(type, new TreeMap<>());
            watermarks.put(type, Long.MIN_VALUE);
            driftParams.put(type, new ArrayList<>());
        }
    }

    /**
     * 热更新漂移校正参数。版本必须大于该传感器当前最大版本。
     */
    public synchronized void updateDrift(DriftParams params) {
        List<DriftParams> list = driftParams.get(params.sensorType());
        int currentMax = list.isEmpty() ? 0 : list.get(list.size() - 1).version();
        if (params.version() <= currentMax) {
            throw new SyncException(SyncErrorCode.INVALID_DRIFT_VERSION,
                    "drift version " + params.version() + " must be greater than current " + currentMax
                            + " for " + params.sensorType());
        }
        list.add(params);
    }

    /**
     * 当前某传感器的最大漂移参数版本，无参数时为 0。
     */
    public synchronized int currentDriftVersion(SensorType type) {
        List<DriftParams> list = driftParams.get(type);
        return list.isEmpty() ? 0 : list.get(list.size() - 1).version();
    }

    /**
     * 取原始时戳 rawTs 生效的漂移参数：最后一个 effectiveFrom &lt;= rawTs 的版本。
     */
    private DriftParams activeDrift(SensorType type, long rawTs) {
        DriftParams active = DriftParams.none(type);
        for (DriftParams p : driftParams.get(type)) {
            if (p.effectiveFrom() <= rawTs) {
                active = p;
            }
        }
        return active;
    }

    /**
     * 接收一条传感器消息。重复、迟到、命中已提交帧窗口的消息被丢弃并给出原因。
     */
    public synchronized IngestOutcome ingest(SensorType type, String messageId, long rawTs) {
        DriftParams drift = activeDrift(type, rawTs);
        long correctedTs = rawTs + drift.offsetMs();
        SensorSample sample = new SensorSample(type, messageId, rawTs, correctedTs, drift.version());

        String key = type.name() + ':' + messageId;
        if (!seenMessageKeys.add(key)) {
            return IngestOutcome.discarded(sample, DiscardReason.DUPLICATE_MESSAGE);
        }
        long watermark = watermarks.get(type);
        if (watermark != Long.MIN_VALUE && correctedTs < watermark - properties.getLatenessMs()) {
            return IngestOutcome.discarded(sample, DiscardReason.LATE_MESSAGE);
        }
        if (coveredByCommittedFrame(type, correctedTs)) {
            return IngestOutcome.discarded(sample, DiscardReason.FRAME_ALREADY_COMMITTED);
        }
        buffers.get(type).put(correctedTs, sample);
        if (correctedTs > watermark) {
            watermarks.put(type, correctedTs);
        }
        return IngestOutcome.accepted(sample);
    }

    private boolean coveredByCommittedFrame(SensorType type, long correctedTs) {
        long window = properties.windowMs(type);
        for (Long target : committedTargets) {
            if (Math.abs(correctedTs - target) <= window) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标记目标时刻的帧已提交（在线提交成功后或重放 FRAME_COMMITTED 事件时调用）。
     */
    public synchronized void markCommitted(long targetTs) {
        if (!committedTargets.contains(targetTs)) {
            committedTargets.add(targetTs);
        }
    }

    /**
     * 目标时刻是否已提交。
     */
    public synchronized boolean isCommitted(long targetTs) {
        return committedTargets.contains(targetTs);
    }

    /**
     * 清空全部运行时状态（水位线、缓冲、漂移参数、已提交标记）。供测试隔离使用。
     */
    public synchronized void reset() {
        for (SensorType type : SensorType.values()) {
            buffers.get(type).clear();
            watermarks.put(type, Long.MIN_VALUE);
            driftParams.get(type).clear();
        }
        seenMessageKeys.clear();
        committedTargets.clear();
    }

    /**
     * 提交目标融合时刻的融合帧：所有必需传感器必须落在各自窗口内或可插值。
     * 成功后该目标时刻不可再提交，也不接受落入窗口的新消息。
     */
    public synchronized FusionFrame commit(long targetTs) {
        if (isCommitted(targetTs)) {
            throw new SyncException(SyncErrorCode.FRAME_ALREADY_COMMITTED,
                    "fusion frame already committed at target " + targetTs);
        }
        FusionFrame frame = computeFrame(targetTs);
        committedTargets.add(targetTs);
        return frame;
    }

    /**
     * 计算目标时刻的候选融合帧（不改变提交状态）。缺帧、超出窗口、无法插值时抛出稳定错误码。
     */
    public synchronized FusionFrame computeFrame(long targetTs) {
        List<FrameInput> inputs = new ArrayList<>();
        for (SensorType type : SensorType.values()) {
            inputs.add(resolveInput(type, targetTs));
        }
        return new FusionFrame(null, targetTs, inputs);
    }

    private FrameInput resolveInput(SensorType type, long targetTs) {
        TreeMap<Long, SensorSample> buffer = buffers.get(type);
        long window = properties.windowMs(type);

        SensorSample best = null;
        long bestDelta = Long.MAX_VALUE;
        Map.Entry<Long, SensorSample> floor = buffer.floorEntry(targetTs);
        Map.Entry<Long, SensorSample> ceil = buffer.ceilingEntry(targetTs);
        List<Map.Entry<Long, SensorSample>> nearest = new ArrayList<>(2);
        if (floor != null) {
            nearest.add(floor);
        }
        if (ceil != null) {
            nearest.add(ceil);
        }
        for (Map.Entry<Long, SensorSample> e : nearest) {
            long delta = Math.abs(e.getKey() - targetTs);
            if (delta <= window && delta < bestDelta) {
                best = e.getValue();
                bestDelta = delta;
            }
        }
        if (best != null) {
            return new FrameInput(type, best.messageId(), best.rawTs(), best.correctedTs(),
                    best.correctedTs() - targetTs, false);
        }

        if (buffer.isEmpty()) {
            throw new SyncException(SyncErrorCode.MISSING_INPUT,
                    "no samples for required sensor " + type + " at target " + targetTs);
        }
        if (floor == null || ceil == null) {
            throw new SyncException(SyncErrorCode.OUT_OF_WINDOW,
                    "samples of " + type + " are all out of window +" + window + "ms around target "
                            + targetTs + " and interpolation needs samples on both sides");
        }
        long interpWindow = properties.interpolationWindowMs(type);
        if (targetTs - floor.getKey() > interpWindow || ceil.getKey() - targetTs > interpWindow) {
            throw new SyncException(SyncErrorCode.CANNOT_INTERPOLATE,
                    "bracketing samples of " + type + " exceed interpolation window " + interpWindow
                            + "ms around target " + targetTs);
        }
        SensorSample before = floor.getValue();
        SensorSample after = ceil.getValue();
        return new FrameInput(type, before.messageId() + "+" + after.messageId(),
                targetTs, targetTs, 0L, true);
    }
}
