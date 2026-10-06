package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 缓停放在途吊厢逐段放行记录数据访问。
 */
@Repository
public class SegmentReleaseRepository {

    /** 放行记录行。 */
    public record SegmentReleaseRow(long id, long eventId, long segmentId, int segmentSeq, LocalDateTime releasedAt) {
    }

    private final JdbcTemplate jdbc;

    public SegmentReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long eventId, long segmentId, int segmentSeq, LocalDateTime releasedAt) {
        jdbc.update("INSERT INTO segment_release (event_id, segment_id, segment_seq, released_at) VALUES (?, ?, ?, ?)",
                eventId, segmentId, segmentSeq, Timestamp.valueOf(releasedAt));
    }

    public List<SegmentReleaseRow> findByEventId(long eventId) {
        return jdbc.query("SELECT id, event_id, segment_id, segment_seq, released_at FROM segment_release "
                + "WHERE event_id = ? ORDER BY segment_seq", (rs, n) -> map(rs), eventId);
    }

    /** 事件内已放行的最大分段顺序号；无记录时为 0。 */
    public int maxSeqByEventId(long eventId) {
        Integer seq = jdbc.queryForObject("SELECT COALESCE(MAX(segment_seq), 0) FROM segment_release WHERE event_id = ?",
                Integer.class, eventId);
        return seq == null ? 0 : seq;
    }

    public Optional<SegmentReleaseRow> findByEventAndSegment(long eventId, long segmentId) {
        return jdbc.query("SELECT id, event_id, segment_id, segment_seq, released_at FROM segment_release "
                + "WHERE event_id = ? AND segment_id = ?", (rs, n) -> map(rs), eventId, segmentId)
                .stream().findFirst();
    }

    private SegmentReleaseRow map(ResultSet rs) throws SQLException {
        return new SegmentReleaseRow(rs.getLong("id"), rs.getLong("event_id"), rs.getLong("segment_id"),
                rs.getInt("segment_seq"), rs.getTimestamp("released_at").toLocalDateTime());
    }
}
