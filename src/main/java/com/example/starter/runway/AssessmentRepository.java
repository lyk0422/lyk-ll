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
 * 评估记录数据访问：assessment_record 表。
 */
@Repository
public class AssessmentRepository {

    private static final RowMapper<AssessmentRecord> MAPPER = (rs, rowNum) -> new AssessmentRecord(
            rs.getLong("id"),
            rs.getLong("event_id"),
            rs.getBigDecimal("friction_coefficient"),
            AssessmentType.valueOf(rs.getString("assessment_type")),
            rs.getTimestamp("assessed_at").toInstant(),
            rs.getBoolean("passed"),
            rs.getBoolean("retest"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public AssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AssessmentRecord insert(AssessmentRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO assessment_record (event_id, friction_coefficient, assessment_type, assessed_at,"
                            + " passed, retest, request_id, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, record.eventId());
            ps.setBigDecimal(2, record.frictionCoefficient());
            ps.setString(3, record.assessmentType().name());
            ps.setTimestamp(4, Timestamp.from(record.assessedAt()));
            ps.setBoolean(5, record.passed());
            ps.setBoolean(6, record.retest());
            ps.setString(7, record.requestId());
            ps.setTimestamp(8, Timestamp.from(record.createdAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new AssessmentRecord(id, record.eventId(), record.frictionCoefficient(), record.assessmentType(),
                record.assessedAt(), record.passed(), record.retest(), record.requestId(), record.createdAt());
    }

    public List<AssessmentRecord> findByEventIdOrderByAssessedAt(long eventId) {
        return jdbc.query("SELECT * FROM assessment_record WHERE event_id = ? ORDER BY assessed_at, id",
                MAPPER, eventId);
    }
}
