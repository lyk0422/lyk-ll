package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 紧急停车救援双人确认数据访问。
 */
@Repository
public class RescueConfirmationRepository {

    private final JdbcTemplate jdbc;

    public RescueConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条确认；同一事件同一分段同一人员重复写入返回 false（唯一约束去重）。
     */
    public boolean insertIfAbsent(long eventId, long segmentId, String operator, LocalDateTime confirmedAt) {
        try {
            jdbc.update("INSERT INTO rescue_confirmation (event_id, segment_id, operator, confirmed_at) "
                    + "VALUES (?, ?, ?, ?)", eventId, segmentId, operator, Timestamp.valueOf(confirmedAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 事件内某分段已确认的不同人员数。 */
    public int countDistinctOperators(long eventId, long segmentId) {
        Integer cnt = jdbc.queryForObject("SELECT COUNT(DISTINCT operator) FROM rescue_confirmation "
                + "WHERE event_id = ? AND segment_id = ?", Integer.class, eventId, segmentId);
        return cnt == null ? 0 : cnt;
    }

    /** 事件内达到双人确认的分段 id 列表。 */
    public List<Long> findClearedSegmentIds(long eventId) {
        return jdbc.query("SELECT segment_id FROM rescue_confirmation WHERE event_id = ? "
                        + "GROUP BY segment_id HAVING COUNT(DISTINCT operator) >= 2",
                (rs, n) -> rs.getLong("segment_id"), eventId);
    }
}
