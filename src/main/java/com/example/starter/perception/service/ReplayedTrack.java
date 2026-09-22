package com.example.starter.perception.service;

import com.example.starter.perception.domain.TrackEvent;
import com.example.starter.perception.domain.TrackState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 事件流重放器：把一条轨迹截至指定时刻的有序事件折叠成单个快照。
 *
 * <p>重放是纯函数：输入同一事件集合，输出状态与版本必然一致；不读写当前态表。
 * 事件已由仓储按 (event_time, id) 升序返回，同刻事件的先后由自增 id 决定。
 */
final class ReplayedTrack {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final String trackId;
    private String category;
    private TrackState state;
    private double confidence;
    private double x;
    private double y;
    private Instant lastObservedAt;
    private long version;
    private Map<String, Object> evidence;
    private Instant createdAt;

    private ReplayedTrack(String trackId) {
        this.trackId = trackId;
    }

    /**
     * 按顺序折叠事件，返回指定时刻（含）的轨迹快照。
     */
    static ReplayedTrack replay(List<TrackEvent> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("cannot replay empty event stream");
        }
        ObjectMapper mapper = MAPPER;
        ReplayedTrack track = new ReplayedTrack(events.get(0).getTrackId());
        for (TrackEvent event : events) {
            Map<String, Object> payload = parsePayload(mapper, event.getPayload());
            switch (event.getEventType()) {
                case "TRACK_CREATED" -> {
                    track.category = (String) payload.get("category");
                    track.confidence = asDouble(payload.get("confidence"));
                    track.x = asDouble(payload.get("x"));
                    track.y = asDouble(payload.get("y"));
                    track.lastObservedAt = Instant.parse((String) payload.get("timestamp"));
                    track.createdAt = event.getCreatedAt();
                }
                case "ASSOCIATED", "REIDENTIFIED" -> {
                    track.confidence = asDouble(payload.get("confidence"));
                    track.x = asDouble(payload.get("x"));
                    track.y = asDouble(payload.get("y"));
                    track.lastObservedAt = Instant.parse((String) payload.get("timestamp"));
                }
                default -> {
                    // STATE_CHANGED / RECYCLED：位置、置信度、最近观测均保持不变。
                }
            }
            track.state = event.getToState();
            track.version = event.getVersion();
            Object eventEvidence = payload.get("evidence");
            if (eventEvidence instanceof Map<?, ?>) {
                track.evidence = mapper.convertValue(eventEvidence, new TypeReference<Map<String, Object>>() {
                });
            }
        }
        return track;
    }

    private static Map<String, Object> parsePayload(ObjectMapper mapper, String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse event payload", e);
        }
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    String trackId() {
        return trackId;
    }

    String category() {
        return category;
    }

    TrackState state() {
        return state;
    }

    double confidence() {
        return confidence;
    }

    double x() {
        return x;
    }

    double y() {
        return y;
    }

    Instant lastObservedAt() {
        return lastObservedAt;
    }

    long version() {
        return version;
    }

    Map<String, Object> evidence() {
        return evidence;
    }

    Instant createdAt() {
        return createdAt;
    }
}
