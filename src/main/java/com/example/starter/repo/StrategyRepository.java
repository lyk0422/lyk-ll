package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 气候策略数据访问。
 */
@Repository
public class StrategyRepository {

    private final JdbcTemplate jdbcTemplate;

    public StrategyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 气候策略持久化模型。温度字段单位摄氏度，湿度设定点单位 %RH；effectiveTime 为空表示尚未下发。
     */
    public record Strategy(
            Long id,
            String strategyCode,
            String zoneCode,
            String cropCode,
            String stageCode,
            BigDecimal dayTemp,
            BigDecimal nightTemp,
            BigDecimal humiditySetpoint,
            BigDecimal tempTolerance,
            BigDecimal humidityTolerance,
            String status,
            String requestId,
            Instant effectiveTime,
            Instant createdAt,
            Instant validatedAt,
            Instant dispatchedAt,
            Instant expiredAt) {
    }

    private static final RowMapper<Strategy> MAPPER = (rs, n) -> new Strategy(
            rs.getLong("id"),
            rs.getString("strategy_code"),
            rs.getString("zone_code"),
            rs.getString("crop_code"),
            rs.getString("stage_code"),
            rs.getBigDecimal("day_temp"),
            rs.getBigDecimal("night_temp"),
            rs.getBigDecimal("humidity_setpoint"),
            rs.getBigDecimal("temp_tolerance"),
            rs.getBigDecimal("humidity_tolerance"),
            rs.getString("status"),
            rs.getString("request_id"),
            rs.getTimestamp("effective_time") == null ? null : rs.getTimestamp("effective_time").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("validated_at") == null ? null : rs.getTimestamp("validated_at").toInstant(),
            rs.getTimestamp("dispatched_at") == null ? null : rs.getTimestamp("dispatched_at").toInstant(),
            rs.getTimestamp("expired_at") == null ? null : rs.getTimestamp("expired_at").toInstant());

    private static final String COLUMNS = "id, strategy_code, zone_code, crop_code, stage_code, "
            + "day_temp, night_temp, humidity_setpoint, temp_tolerance, humidity_tolerance, status, "
            + "request_id, effective_time, created_at, validated_at, dispatched_at, expired_at";

    public void insertDraft(Strategy s, Instant validatedAt) {
        jdbcTemplate.update("INSERT INTO climate_strategy (strategy_code, zone_code, crop_code, stage_code, "
                        + "day_temp, night_temp, humidity_setpoint, temp_tolerance, humidity_tolerance, "
                        + "status, created_at, validated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                s.strategyCode(), s.zoneCode(), s.cropCode(), s.stageCode(),
                s.dayTemp(), s.nightTemp(), s.humiditySetpoint(), s.tempTolerance(), s.humidityTolerance(),
                s.status(), Timestamp.from(s.createdAt()), Timestamp.from(validatedAt));
    }

    public Optional<Strategy> findByCode(String strategyCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM climate_strategy WHERE strategy_code = ?",
                    MAPPER, strategyCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 锁定分区行以串行化同分区下发（调用方须处于事务内）。
     */
    public void lockZone(String zoneCode) {
        jdbcTemplate.queryForObject(
                "SELECT zone_code FROM zone_info WHERE zone_code = ? FOR UPDATE", String.class, zoneCode);
    }

    /**
     * 该分区最近一次已下发策略的生效时刻，用于 30 分钟最小变更间隔校验。
     */
    public Optional<Instant> findLatestEffectiveTime(String zoneCode) {
        try {
            Timestamp ts = jdbcTemplate.queryForObject(
                    "SELECT MAX(effective_time) FROM climate_strategy "
                            + "WHERE zone_code = ? AND effective_time IS NOT NULL",
                    Timestamp.class, zoneCode);
            return Optional.ofNullable(ts).map(Timestamp::toInstant);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 当前生效中的策略（已下发且未失效），用于下发时原子替换。
     */
    public Optional<Strategy> findActiveByZone(String zoneCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM climate_strategy "
                            + "WHERE zone_code = ? AND status = 'DISPATCHED' ORDER BY effective_time DESC LIMIT 1",
                    MAPPER, zoneCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 截至采样时刻 t 对该分区生效的策略（含之后才失效的旧策略），按生效时刻倒序取最近一条。
     */
    public Optional<Strategy> findEffectiveAt(String zoneCode, Instant t) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM climate_strategy "
                            + "WHERE zone_code = ? AND effective_time IS NOT NULL AND effective_time <= ? "
                            + "ORDER BY effective_time DESC LIMIT 1",
                    MAPPER, zoneCode, Timestamp.from(t)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 下发新生效：同一事务内把旧策略置为 EXPIRED，再把目标策略置为 DISPATCHED。
     */
    public void expireStrategy(Long id, Instant expiredAt) {
        jdbcTemplate.update(
                "UPDATE climate_strategy SET status = 'EXPIRED', expired_at = ? WHERE id = ?",
                Timestamp.from(expiredAt), id);
    }

    public void markDispatched(Long id, String requestId, Instant effectiveTime, Instant dispatchedAt) {
        jdbcTemplate.update("UPDATE climate_strategy SET status = 'DISPATCHED', request_id = ?, "
                        + "effective_time = ?, dispatched_at = ? WHERE id = ?",
                requestId, Timestamp.from(effectiveTime), Timestamp.from(dispatchedAt), id);
    }

    /**
     * 下发挂起：仅推进状态为 SUSPENDED，不写生效时刻、不失效旧策略，不产生任何副作用。
     */
    public void markSuspended(Long id) {
        jdbcTemplate.update("UPDATE climate_strategy SET status = 'SUSPENDED' WHERE id = ?", id);
    }
}
