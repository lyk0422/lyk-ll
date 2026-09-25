package com.example.starter.ili;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 管段与焊缝台帐的数据访问。
 */
@Repository
public class SegmentRepository {

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 管段行记录。 */
    public record SegmentRow(long id, String segmentCode, String segmentName) {
    }

    public long insertSegment(String segmentCode, String segmentName, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pipeline_segment (segment_code, segment_name, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, segmentCode);
            ps.setString(2, segmentName);
            ps.setTimestamp(3, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertWeld(long segmentId, String weldNo, BigDecimal absMileageM) {
        jdbc.update("INSERT INTO pipeline_weld (segment_id, weld_no, abs_mileage_m) VALUES (?, ?, ?)",
                segmentId, weldNo, absMileageM);
    }

    public Optional<SegmentRow> findByCode(String segmentCode) {
        try {
            SegmentRow row = jdbc.queryForObject(
                    "SELECT id, segment_code, segment_name FROM pipeline_segment WHERE segment_code = ?",
                    (rs, rowNum) -> new SegmentRow(rs.getLong("id"), rs.getString("segment_code"),
                            rs.getString("segment_name")),
                    segmentCode);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<SegmentRow> findById(long segmentId) {
        try {
            SegmentRow row = jdbc.queryForObject(
                    "SELECT id, segment_code, segment_name FROM pipeline_segment WHERE id = ?",
                    (rs, rowNum) -> new SegmentRow(rs.getLong("id"), rs.getString("segment_code"),
                            rs.getString("segment_name")),
                    segmentId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
