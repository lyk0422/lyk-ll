package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

import com.example.starter.domain.Complaint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 干扰投诉登记的数据访问。
 */
@Repository
public class ComplaintRepository {

    private static final RowMapper<Complaint> MAPPER = (rs, n) -> new Complaint(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("station_code"),
            rs.getBigDecimal("frequency_mhz"),
            rs.getTimestamp("sampled_at").toLocalDateTime(),
            rs.getBigDecimal("field_strength_dbuv"),
            rs.getString("evidence_id"),
            rs.getString("fingerprint"),
            rs.getObject("triggered_event_id", Long.class),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public ComplaintRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入投诉，唯一键冲突时抛出 DuplicateKeyException 由上层裁决。
     *
     * @return 新投诉主键
     */
    public long insert(String requestId, String stationCode, BigDecimal frequencyMhz, LocalDateTime sampledAt,
                       BigDecimal fieldStrengthDbuv, String evidenceId, String fingerprint, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO complaint (request_id, station_code, frequency_mhz, sampled_at,"
                            + " field_strength_dbuv, evidence_id, fingerprint, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, stationCode);
            ps.setBigDecimal(3, frequencyMhz);
            ps.setTimestamp(4, Timestamp.valueOf(sampledAt));
            ps.setBigDecimal(5, fieldStrengthDbuv);
            ps.setString(6, evidenceId);
            ps.setString(7, fingerprint);
            ps.setTimestamp(8, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Complaint> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM complaint WHERE request_id = ?", MAPPER, requestId).stream().findFirst();
    }

    public Optional<Complaint> findByNaturalKey(String stationCode, BigDecimal frequencyMhz, LocalDateTime sampledAt) {
        return jdbc.query("SELECT * FROM complaint WHERE station_code = ? AND frequency_mhz = ? AND sampled_at = ?",
                MAPPER, stationCode, frequencyMhz, Timestamp.valueOf(sampledAt)).stream().findFirst();
    }

    /**
     * 统计窗口内同一频率的不同监测站数量（含窗口起点，恰满 30 分钟计入）。
     */
    public int countDistinctStations(BigDecimal frequencyMhz, LocalDateTime fromInclusive, LocalDateTime toInclusive) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT station_code) FROM complaint"
                        + " WHERE frequency_mhz = ? AND sampled_at >= ? AND sampled_at <= ?",
                Integer.class, frequencyMhz, Timestamp.valueOf(fromInclusive), Timestamp.valueOf(toInclusive));
        return count == null ? 0 : count;
    }

    public void markTriggered(long complaintId, long eventId) {
        jdbc.update("UPDATE complaint SET triggered_event_id = ? WHERE id = ?", eventId, complaintId);
    }
}
