package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/**
 * 航行通告数据访问：notam_record 表（合成通告）。
 */
@Repository
public class NotamRepository {

    private static final RowMapper<NotamRecord> MAPPER = (rs, rowNum) -> new NotamRecord(
            rs.getLong("id"),
            rs.getLong("event_id"),
            rs.getString("runway_id"),
            NotamAction.valueOf(rs.getString("action")),
            rs.getString("notice_no"),
            rs.getTimestamp("published_at").toInstant());

    private final JdbcTemplate jdbc;

    public NotamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public NotamRecord insert(NotamRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO notam_record (event_id, runway_id, action, notice_no, published_at)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, record.eventId());
            ps.setString(2, record.runwayId());
            ps.setString(3, record.action().name());
            ps.setString(4, record.noticeNo());
            ps.setTimestamp(5, Timestamp.from(record.publishedAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new NotamRecord(id, record.eventId(), record.runwayId(), record.action(),
                record.noticeNo(), record.publishedAt());
    }

    public List<NotamRecord> findByEventId(long eventId) {
        return jdbc.query("SELECT * FROM notam_record WHERE event_id = ? ORDER BY published_at, id",
                MAPPER, eventId);
    }
}
