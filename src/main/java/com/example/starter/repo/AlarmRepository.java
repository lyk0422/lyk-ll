package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 偏差告警数据访问。
 */
@Repository
public class AlarmRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlarmRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Alarm(
            Long id,
            String zoneCode,
            String strategyCode,
            Instant startSampleTime,
            Instant triggerTime,
            int readingCount,
            String status,
            String ackRequestId,
            Instant createdAt,
            Instant acknowledgedAt) {
    }

    private static final RowMapper<Alarm> MAPPER = (rs, n) -> new Alarm(
            rs.getLong("id"),
            rs.getString("zone_code"),
            rs.getString("strategy_code"),
            rs.getTimestamp("start_sample_time").toInstant(),
            rs.getTimestamp("trigger_time").toInstant(),
            rs.getInt("reading_count"),
            rs.getString("status"),
            rs.getString("ack_request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("acknowledged_at") == null
                    ? null : rs.getTimestamp("acknowledged_at").toInstant());

    private static final String COLUMNS = "id, zone_code, strategy_code, start_sample_time, trigger_time, "
            + "reading_count, status, ack_request_id, created_at, acknowledged_at";

    public long insert(String zoneCode, String strategyCode, Instant startSampleTime,
                       Instant triggerTime, int readingCount, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO deviation_alarm (zone_code, strategy_code, start_sample_time, "
                            + "trigger_time, reading_count, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, zoneCode);
            ps.setString(2, strategyCode);
            ps.setTimestamp(3, Timestamp.from(startSampleTime));
            ps.setTimestamp(4, Timestamp.from(triggerTime));
            ps.setInt(5, readingCount);
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 是否已存在相同分区、策略、连续偏离起点的告警，避免乱序归位重算时重复告警。
     */
    public boolean existsWithStart(String zoneCode, String strategyCode, Instant startSampleTime) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM deviation_alarm WHERE zone_code = ? AND strategy_code = ? "
                        + "AND start_sample_time = ?",
                Integer.class, zoneCode, strategyCode, Timestamp.from(startSampleTime));
        return count != null && count > 0;
    }

    public Optional<Alarm> findById(Long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM deviation_alarm WHERE id = ?", MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Alarm> findActive(String zoneCode) {
        if (zoneCode == null || zoneCode.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM deviation_alarm WHERE status = 'ACTIVE' ORDER BY id", MAPPER);
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM deviation_alarm WHERE status = 'ACTIVE' AND zone_code = ? ORDER BY id",
                MAPPER, zoneCode);
    }

    /**
     * 确认告警；仅当仍为 ACTIVE 时生效，返回受影响行数（0 表示此前已确认或不存在）。
     */
    public int acknowledgeIfActive(Long id, String requestId, Instant acknowledgedAt) {
        return jdbcTemplate.update("UPDATE deviation_alarm SET status = 'ACKNOWLEDGED', ack_request_id = ?, "
                        + "acknowledged_at = ? WHERE id = ? AND status = 'ACTIVE'",
                requestId, Timestamp.from(acknowledgedAt), id);
    }
}
