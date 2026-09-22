package com.example.starter.persistence;

import com.example.starter.domain.AssociationEvidence;
import com.example.starter.domain.EvidenceType;
import com.example.starter.domain.TrackEvent;
import com.example.starter.domain.TrackStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 事件流/证据/检测去重 JdbcTemplate 数据访问。
 */
@Repository
public class JdbcEventStore implements EventStore {

    private final JdbcTemplate jdbc;

    public JdbcEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<TrackEvent> EVENT_MAPPER = (rs, n) -> new TrackEvent(
            rs.getLong("id"),
            rs.getLong("event_time"),
            rs.getString("track_uid"),
            rs.getString("target_category"),
            rs.getString("event_type"),
            parseStatus(rs.getString("from_status")),
            parseStatus(rs.getString("to_status")),
            rs.getLong("version"),
            rs.getString("detection_id"),
            rs.getString("source"),
            (Double) rs.getObject("confidence"),
            (Double) rs.getObject("position_x"),
            (Double) rs.getObject("position_y"),
            (Double) rs.getObject("position_z"),
            parseEvidence(rs.getString("evidence_type")),
            rs.getString("linked_track_uid"),
            (Double) rs.getObject("distance"),
            (Double) rs.getObject("gate_threshold"),
            rs.getString("detail"),
            rs.getString("payload"),
            rs.getLong("recorded_at"));

    static final RowMapper<ProcessedDetectionRecord> PROCESSED_MAPPER = (rs, n) ->
            new ProcessedDetectionRecord(
                    rs.getString("detection_id"),
                    rs.getString("track_uid"),
                    rs.getString("source"),
                    rs.getLong("observed_at"),
                    rs.getString("category"),
                    rs.getDouble("confidence"),
                    rs.getString("result"),
                    rs.getString("reject_reason"),
                    rs.getLong("created_at"));

    static final RowMapper<AssociationEvidence> EVIDENCE_MAPPER = (rs, n) -> new AssociationEvidence(
            rs.getLong("id"),
            rs.getString("track_uid"),
            EvidenceType.valueOf(rs.getString("evidence_type")),
            rs.getString("linked_track_uid"),
            rs.getString("detection_id"),
            rs.getLong("event_time"),
            rs.getLong("version"),
            (Double) rs.getObject("distance"),
            (Double) rs.getObject("gate_threshold"),
            rs.getString("detail"),
            rs.getLong("created_at"));

    private static TrackStatus parseStatus(String value) {
        return value == null ? null : TrackStatus.valueOf(value);
    }

    private static EvidenceType parseEvidence(String value) {
        return value == null ? null : EvidenceType.valueOf(value);
    }

    @Override
    public void appendEvent(TrackEvent e) {
        jdbc.update("""
                        INSERT INTO track_event (event_time, track_uid, target_category, event_type,
                            from_status, to_status, version, detection_id, source, confidence,
                            position_x, position_y, position_z, evidence_type,
                            linked_track_uid, distance, gate_threshold, detail, payload, recorded_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                e.eventTime(), e.trackUid(), e.category(), e.eventType(),
                e.fromStatus() == null ? null : e.fromStatus().name(),
                e.toStatus() == null ? null : e.toStatus().name(),
                e.version(), e.detectionId(), e.source(), e.confidence(),
                e.positionX(), e.positionY(), e.positionZ(),
                e.evidenceType() == null ? null : e.evidenceType().name(),
                e.linkedTrackUid(), e.distance(), e.gateThreshold(), e.detail(),
                e.payload(), e.recordedAt());
    }

    @Override
    public void appendEvidence(AssociationEvidence ev) {
        jdbc.update("""
                        INSERT INTO track_evidence (track_uid, evidence_type, linked_track_uid,
                            detection_id, event_time, version, distance, gate_threshold,
                            detail, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ev.trackUid(), ev.evidenceType().name(), ev.linkedTrackUid(),
                ev.detectionId(), ev.eventTime(), ev.version(), ev.distance(),
                ev.gateThreshold(), ev.detail(), ev.createdAt());
    }

    @Override
    public List<TrackEvent> loadEventsUpTo(Long asOfInclusive) {
        if (asOfInclusive == null) {
            return jdbc.query("SELECT * FROM track_event ORDER BY event_time ASC, id ASC",
                    EVENT_MAPPER);
        }
        return jdbc.query(
                "SELECT * FROM track_event WHERE event_time <= ? ORDER BY event_time ASC, id ASC",
                EVENT_MAPPER, asOfInclusive);
    }

    @Override
    public List<AssociationEvidence> findEvidence(String trackUid) {
        return jdbc.query(
                "SELECT * FROM track_evidence WHERE track_uid = ? ORDER BY id ASC",
                EVIDENCE_MAPPER, trackUid);
    }

    @Override
    public Optional<ProcessedDetectionRecord> findProcessed(String detectionId) {
        List<ProcessedDetectionRecord> rows = jdbc.query(
                "SELECT * FROM processed_detection WHERE detection_id = ?",
                PROCESSED_MAPPER, detectionId);
        return rows.stream().findFirst();
    }

    @Override
    public void insertProcessed(ProcessedDetectionRecord r) {
        try {
            jdbc.update("""
                            INSERT INTO processed_detection (detection_id, track_uid, source,
                                observed_at, category, confidence, result, reject_reason, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    r.detectionId(), r.trackUid(), r.source(), r.observedAt(),
                    r.category(), r.confidence(), r.result(), r.rejectReason(), r.createdAt());
        } catch (DuplicateKeyException alreadyExists) {
            // 由上层在事务内先查后插；并发下唯一约束是最后防线，转换为明确语义。
            throw new com.example.starter.domain.BusinessException(
                    "DUPLICATE_DETECTION", "检测 " + r.detectionId() + " 已处理过");
        }
    }
}
