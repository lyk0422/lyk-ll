package com.example.starter.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.ComplaintRow;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 投诉登记数据访问。业务唯一键：监测站 + 频率 + 采样时刻。
 */
@Repository
public class ComplaintRepository {

    private static final RowMapper<ComplaintRow> MAPPER = (rs, n) -> new ComplaintRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("station_id"),
            rs.getBigDecimal("frequency_mhz"),
            rs.getTimestamp("sampled_at").toInstant(),
            rs.getBigDecimal("field_strength_dbuv"),
            rs.getString("evidence_ref"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS =
            "id, request_id, station_id, frequency_mhz, sampled_at, field_strength_dbuv, evidence_ref, fingerprint, created_at";

    private final JdbcTemplate jdbc;

    public ComplaintRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String requestId, String stationId, BigDecimal frequencyMhz, Instant sampledAt,
            BigDecimal fieldStrengthDbuv, String evidenceRef, String fingerprint, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO complaint (request_id, station_id, frequency_mhz, sampled_at,"
                            + " field_strength_dbuv, evidence_ref, fingerprint, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, stationId);
            ps.setBigDecimal(3, frequencyMhz);
            ps.setTimestamp(4, Timestamp.from(sampledAt));
            ps.setBigDecimal(5, fieldStrengthDbuv);
            ps.setString(6, evidenceRef);
            ps.setString(7, fingerprint);
            ps.setTimestamp(8, Timestamp.from(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<ComplaintRow> findByBizKey(String stationId, BigDecimal frequencyMhz, Instant sampledAt) {
        List<ComplaintRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM complaint"
                        + " WHERE station_id = ? AND frequency_mhz = ? AND sampled_at = ?",
                MAPPER, stationId, frequencyMhz, Timestamp.from(sampledAt));
        return rows.stream().findFirst();
    }

    public Optional<ComplaintRow> findById(long id) {
        List<ComplaintRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM complaint WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 统计同一频率在 [windowStart, windowEnd]（UTC，两端含边界）内投诉的不同监测站数量。
     */
    public int countDistinctStationsInWindow(BigDecimal frequencyMhz, Instant windowStart, Instant windowEnd) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT station_id) FROM complaint"
                        + " WHERE frequency_mhz = ? AND sampled_at >= ? AND sampled_at <= ?",
                Integer.class, frequencyMhz, Timestamp.from(windowStart), Timestamp.from(windowEnd));
        return count == null ? 0 : count;
    }
}
