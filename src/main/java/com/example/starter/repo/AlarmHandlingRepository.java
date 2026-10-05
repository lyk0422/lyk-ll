package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 告警处置数据访问。
 */
@Repository
public class AlarmHandlingRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlarmHandlingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 告警处置持久化模型。温度窗口单位摄氏度、湿度窗口单位 %RH，边界均含等号；
     * completedAt 为空表示处置仍在进行中。
     */
    public record Handling(
            Long id,
            String handlingCode,
            Long alarmId,
            String zoneCode,
            String status,
            BigDecimal tempMin,
            BigDecimal tempMax,
            BigDecimal humidityMin,
            BigDecimal humidityMax,
            String requestId,
            String completeRequestId,
            Instant createdAt,
            Instant completedAt) {
    }

    private static final RowMapper<Handling> MAPPER = (rs, n) -> new Handling(
            rs.getLong("id"),
            rs.getString("handling_code"),
            rs.getLong("alarm_id"),
            rs.getString("zone_code"),
            rs.getString("status"),
            rs.getBigDecimal("temp_min"),
            rs.getBigDecimal("temp_max"),
            rs.getBigDecimal("humidity_min"),
            rs.getBigDecimal("humidity_max"),
            rs.getString("request_id"),
            rs.getString("complete_request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());

    private static final String COLUMNS = "id, handling_code, alarm_id, zone_code, status, "
            + "temp_min, temp_max, humidity_min, humidity_max, request_id, complete_request_id, "
            + "created_at, completed_at";

    public void insert(Handling h) {
        jdbcTemplate.update("INSERT INTO alarm_handling (handling_code, alarm_id, zone_code, status, "
                        + "temp_min, temp_max, humidity_min, humidity_max, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                h.handlingCode(), h.alarmId(), h.zoneCode(), h.status(),
                h.tempMin(), h.tempMax(), h.humidityMin(), h.humidityMax(),
                h.requestId(), Timestamp.from(h.createdAt()));
    }

    public Optional<Handling> findByCode(String handlingCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM alarm_handling WHERE handling_code = ?",
                    MAPPER, handlingCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 该分区当前进行中的处置，用于新策略下发的兼容性仲裁。
     */
    public List<Handling> findInProgressByZone(String zoneCode) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM alarm_handling WHERE zone_code = ? AND status = 'IN_PROGRESS' "
                        + "ORDER BY id",
                MAPPER, zoneCode);
    }

    /**
     * 完结处置；仅当仍为 IN_PROGRESS 时生效，返回受影响行数（0 表示此前已完结或不存在）。
     */
    public int completeIfInProgress(String handlingCode, String completeRequestId, Instant completedAt) {
        return jdbcTemplate.update("UPDATE alarm_handling SET status = 'COMPLETED', complete_request_id = ?, "
                        + "completed_at = ? WHERE handling_code = ? AND status = 'IN_PROGRESS'",
                completeRequestId, Timestamp.from(completedAt), handlingCode);
    }
}
