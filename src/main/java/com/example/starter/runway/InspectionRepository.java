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
 * 巡检记录数据访问：inspection_record 表。
 */
@Repository
public class InspectionRepository {

    private static final RowMapper<InspectionRecord> MAPPER = (rs, rowNum) -> new InspectionRecord(
            rs.getLong("id"),
            rs.getLong("event_id"),
            rs.getTimestamp("started_at").toInstant(),
            rs.getInt("inspector_count"),
            rs.getBoolean("late"),
            rs.getBoolean("recorded_after_archive"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public InspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public InspectionRecord insert(InspectionRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO inspection_record (event_id, started_at, inspector_count, late,"
                            + " recorded_after_archive, created_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, record.eventId());
            ps.setTimestamp(2, Timestamp.from(record.startedAt()));
            ps.setInt(3, record.inspectorCount());
            ps.setBoolean(4, record.late());
            ps.setBoolean(5, record.recordedAfterArchive());
            ps.setTimestamp(6, Timestamp.from(record.createdAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new InspectionRecord(id, record.eventId(), record.startedAt(), record.inspectorCount(),
                record.late(), record.recordedAfterArchive(), record.createdAt());
    }

    public List<InspectionRecord> findByEventId(long eventId) {
        return jdbc.query("SELECT * FROM inspection_record WHERE event_id = ? ORDER BY started_at, id",
                MAPPER, eventId);
    }
}
