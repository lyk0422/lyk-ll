package com.example.starter.service;

import com.example.starter.domain.EvidenceType;
import com.example.starter.domain.TrackEvent;
import com.example.starter.domain.TrackSnapshot;
import com.example.starter.domain.TrackStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 事件流折叠为快照的纯单元测试：同一批事件重放结果确定，
 * 纯状态迁移不得回退最近观测。
 */
class TrackReplayerTest {

    private final TrackReplayer replayer = new TrackReplayer();

    private TrackEvent event(long id, long time, String uid, String category, String type,
                             TrackStatus from, TrackStatus to, long version,
                             String detectionId, Double conf, Double x) {
        return new TrackEvent(id, time, uid, category, type, from, to, version,
                detectionId, "cam-1", conf, x, 0.0, 0.0,
                EvidenceType.OBSERVATION, null, null, null, null, null, time);
    }

    @Test
    void foldCreateObserveAndTransition() {
        List<TrackEvent> events = List.of(
                event(1L, 1000L, "T-000001", "car", "TRACK_CREATED",
                        null, TrackStatus.CREATED, 1L, null, 0.9, null),
                event(2L, 1000L, "T-000001", "car", "OBSERVATION",
                        TrackStatus.CREATED, TrackStatus.TRACKING, 2L, "d1", 0.9, 1.0),
                event(3L, 2000L, "T-000001", "car", "STATUS_TRANSITION",
                        TrackStatus.TRACKING, TrackStatus.OCCLUDED, 3L, null, 0.9, 1.0),
                event(4L, 3000L, "T-000001", "car", "STATUS_TRANSITION",
                        TrackStatus.OCCLUDED, TrackStatus.LOST, 4L, null, 0.9, 1.0));

        List<TrackSnapshot> snapshots = replayer.replay(events);
        assertEquals(1, snapshots.size());
        TrackSnapshot s = snapshots.get(0);
        assertEquals("T-000001", s.trackUid());
        assertEquals(TrackStatus.LOST, s.status());
        assertEquals(4L, s.version());
        // 纯状态迁移不改变最近观测：仍停留在 d1@1000/位置 1.0。
        assertEquals("d1", s.lastDetectionId());
        assertEquals(1000L, s.lastObservedAt());
        assertEquals(1.0, s.x());
        assertEquals(1000L, s.createdAt());
        assertEquals(3000L, s.updatedAt());
    }

    @Test
    void replaySameEventsTwiceYieldsIdenticalResult() {
        List<TrackEvent> events = List.of(
                event(1L, 1000L, "T-000001", "car", "TRACK_CREATED",
                        null, TrackStatus.CREATED, 1L, null, 0.8, null),
                event(2L, 1000L, "T-000001", "car", "OBSERVATION",
                        TrackStatus.CREATED, TrackStatus.TRACKING, 2L, "d1", 0.8, 5.0),
                event(3L, 2000L, "T-000002", "car", "TRACK_CREATED",
                        null, TrackStatus.CREATED, 1L, null, 0.7, null),
                event(4L, 2000L, "T-000002", "car", "OBSERVATION",
                        TrackStatus.CREATED, TrackStatus.TRACKING, 2L, "d2", 0.7, 9.0));

        List<TrackSnapshot> first = replayer.replay(events);
        List<TrackSnapshot> second = replayer.replay(events);
        assertEquals(first, second);
        assertEquals(2, first.size());
        assertEquals("T-000001", first.get(0).trackUid());
        assertEquals("T-000002", first.get(1).trackUid());
    }

    @Test
    void reidentificationObservationMovesBackToTracking() {
        List<TrackEvent> events = List.of(
                event(1L, 1000L, "T-000001", "car", "TRACK_CREATED",
                        null, TrackStatus.CREATED, 1L, null, 0.9, null),
                event(2L, 1000L, "T-000001", "car", "OBSERVATION",
                        TrackStatus.CREATED, TrackStatus.TRACKING, 2L, "d1", 0.9, 1.0),
                event(3L, 2000L, "T-000001", "car", "STATUS_TRANSITION",
                        TrackStatus.TRACKING, TrackStatus.LOST, 3L, null, 0.9, 1.0),
                event(4L, 3000L, "T-000001", "car", "OBSERVATION",
                        TrackStatus.LOST, TrackStatus.REIDENTIFIED, 4L, "d2", 0.95, 8.0));

        TrackSnapshot s = replayer.replay(events).get(0);
        assertEquals(TrackStatus.REIDENTIFIED, s.status());
        assertEquals("d2", s.lastDetectionId());
        assertEquals(8.0, s.x());
        assertEquals(3000L, s.lastObservedAt());
    }

    @Test
    void emptyEventStreamReplaysToNothing() {
        assertNull(replayer.replay(List.of()).stream().findFirst().orElse(null));
    }
}
