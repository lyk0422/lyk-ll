package com.example.starter.service;

import com.example.starter.domain.TrackEvent;
import com.example.starter.domain.TrackSnapshot;
import com.example.starter.domain.TrackStatus;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件流重放器：将 {@code (event_time, id)} 有序事件折叠为轨迹快照。
 *
 * <p>重放只依赖事件流本身，不读取轨迹当前态表；对同一批事件必然得到一致的
 * 状态、版本与最近观测，满足快照重建与重放确定性要求。</p>
 */
@Component
public class TrackReplayer {

    /** 创建类事件：此前不存在轨迹，以事件字段初始化。 */
    private static boolean isCreation(String eventType) {
        return "TRACK_CREATED".equals(eventType) || "ID_SWITCH_CREATED".equals(eventType);
    }

    /** 观测类事件：携带检测，更新最近观测、位置与置信度。 */
    private static boolean isObservation(String eventType) {
        return "OBSERVATION".equals(eventType);
    }

    public List<TrackSnapshot> replay(List<TrackEvent> events) {
        Map<String, TrackSnapshot> snapshots = new LinkedHashMap<>();
        for (TrackEvent e : events) {
            snapshots.compute(e.trackUid(), (uid, prev) -> fold(prev, e));
        }
        return snapshots.values().stream()
                .sorted(Comparator.comparing(TrackSnapshot::trackUid))
                .toList();
    }

    private TrackSnapshot fold(TrackSnapshot prev, TrackEvent e) {
        if (prev == null || isCreation(e.eventType())) {
            // 创建事件：版本从 1 开始；位置在创建时为空，由随后同检测的 OBSERVATION 事件补齐。
            return new TrackSnapshot(
                    e.trackUid(),
                    e.category(),
                    e.toStatus(),
                    e.confidence() == null ? 0.0 : e.confidence(),
                    null,
                    null,
                    null,
                    null,
                    e.eventTime(),
                    e.version(),
                    e.eventTime(),
                    e.eventTime());
        }
        // 观测事件更新最近观测；纯状态迁移事件（含 ID 切换回收）不改变观测信息。
        boolean observed = isObservation(e.eventType());
        TrackStatus status = e.toStatus() == null ? prev.status() : e.toStatus();
        String lastDetectionId = observed ? e.detectionId() : prev.lastDetectionId();
        Double x = observed ? e.positionX() : prev.x();
        Double y = observed ? e.positionY() : prev.y();
        Double z = observed ? e.positionZ() : prev.z();
        double confidence = observed && e.confidence() != null
                ? e.confidence() : prev.confidence();
        long lastObservedAt = observed ? e.eventTime() : prev.lastObservedAt();
        return new TrackSnapshot(
                prev.trackUid(),
                prev.category(),
                status,
                confidence,
                lastDetectionId,
                x,
                y,
                z,
                lastObservedAt,
                e.version(),
                prev.createdAt(),
                e.eventTime());
    }
}
