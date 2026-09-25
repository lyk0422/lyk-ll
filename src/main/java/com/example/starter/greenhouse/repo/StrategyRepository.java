package com.example.starter.greenhouse.repo;

import com.example.starter.greenhouse.domain.StrategyStatus;
import com.example.starter.greenhouse.po.ClimateStrategy;
import com.example.starter.greenhouse.po.StrategyChangeLog;
import com.example.starter.greenhouse.po.ZoneActiveStrategy;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/**
 * 气候策略、分区生效指针与变更流水数据访问。
 */
@Repository
public class StrategyRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert strategyInsert;
    private final SimpleJdbcInsert changeLogInsert;

    public StrategyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.strategyInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("climate_strategy").usingGeneratedKeyColumns("id");
        this.changeLogInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("strategy_change_log").usingGeneratedKeyColumns("id");
    }

    public ClimateStrategy insert(ClimateStrategy s) {
        var p = new HashMap<String, Object>();
        p.put("strategy_code", s.getStrategyCode());
        p.put("crop_id", s.getCropId());
        p.put("zone_id", s.getZoneId());
        p.put("day_temp_target", s.getDayTempTarget());
        p.put("day_temp_tolerance", s.getDayTempTolerance());
        p.put("day_humidity_target", s.getDayHumidityTarget());
        p.put("day_humidity_tolerance", s.getDayHumidityTolerance());
        p.put("night_temp_target", s.getNightTempTarget());
        p.put("night_temp_tolerance", s.getNightTempTolerance());
        p.put("night_humidity_target", s.getNightHumidityTarget());
        p.put("night_humidity_tolerance", s.getNightHumidityTolerance());
        p.put("status", s.getStatus().name());
        p.put("validation_message", s.getValidationMessage());
        p.put("created_at", Timestamp.from(s.getCreatedAt()));
        p.put("validated_at", s.getValidatedAt() == null ? null : Timestamp.from(s.getValidatedAt()));
        Number key = strategyInsert.executeAndReturnKey(p);
        s.setId(key.longValue());
        return s;
    }

    public Optional<ClimateStrategy> findByCode(String strategyCode) {
        return jdbc.query("SELECT * FROM climate_strategy WHERE strategy_code = ?",
                MAPPER, strategyCode).stream().findFirst();
    }

    public Optional<ClimateStrategy> findById(Long id) {
        return jdbc.query("SELECT * FROM climate_strategy WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 更新状态与校验信息（DRAFT→VALIDATED / VALIDATED→ACTIVE / ACTIVE→SUPERSEDED）。 */
    public void updateStatus(Long strategyId, StrategyStatus status, String message, Instant at) {
        jdbc.update("UPDATE climate_strategy SET status = ?, validation_message = COALESCE(?, validation_message), "
                + "validated_at = COALESCE(?, validated_at) WHERE id = ?",
                status.name(), message, at == null ? null : Timestamp.from(at), strategyId);
    }

    /** 取分区当前生效策略；同分区同时刻最多一个。 */
    public Optional<ZoneActiveStrategy> findActiveByZone(Long zoneId) {
        return jdbc.query("SELECT * FROM zone_active_strategy WHERE zone_id = ?",
                ACTIVE_MAPPER, zoneId).stream().findFirst();
    }

    /**
     * 取分区生效指针并加行锁（FOR UPDATE），串行化同分区并发下发；
     * 指针行尚不存在时依赖主键唯一约束保证首笔插入唯一成功。
     */
    public Optional<ZoneActiveStrategy> findActiveByZoneForUpdate(Long zoneId) {
        return jdbc.query("SELECT * FROM zone_active_strategy WHERE zone_id = ? FOR UPDATE",
                ACTIVE_MAPPER, zoneId).stream().findFirst();
    }

    /**
     * 首笔下发：向生效指针表插入。zone_id 为主键，并发首笔仅一行成功，
     * 重复键抛出 DuplicateKeyException，由上层判定并发失败。
     */
    public void insertActive(Long zoneId, Long strategyId, Instant at) {
        jdbc.update("INSERT INTO zone_active_strategy (zone_id, strategy_id, activated_at) VALUES (?, ?, ?)",
                zoneId, strategyId, Timestamp.from(at));
    }

    /** 切换：生效指针指向新策略（与旧策略置 SUPERSEDED 在同一事务内）。 */
    public void replaceActive(Long zoneId, Long strategyId, Instant at) {
        int updated = jdbc.update(
                "UPDATE zone_active_strategy SET strategy_id = ?, activated_at = ? WHERE zone_id = ?",
                strategyId, Timestamp.from(at), zoneId);
        if (updated == 0) {
            insertActive(zoneId, strategyId, at);
        }
    }

    /** 取同分区最近一次策略变更生效时刻，用于 30 分钟最小间隔校验。 */
    public Optional<Instant> findLastChangeEffectiveAt(Long zoneId) {
        return Optional.ofNullable(jdbc.query(
                con -> {
                    java.sql.PreparedStatement ps = con.prepareStatement(
                            "SELECT MAX(effective_at) AS last_at FROM strategy_change_log WHERE zone_id = ?");
                    ps.setLong(1, zoneId);
                    return ps;
                },
                rs -> rs.next() ? Optional.ofNullable(rs.getTimestamp("last_at"))
                        .map(Timestamp::toInstant).orElse(null) : null));
    }

    public void insertChangeLog(StrategyChangeLog log) {
        var p = new HashMap<String, Object>();
        p.put("zone_id", log.getZoneId());
        p.put("old_strategy_id", log.getOldStrategyId());
        p.put("new_strategy_id", log.getNewStrategyId());
        p.put("effective_at", Timestamp.from(log.getEffectiveAt()));
        p.put("request_id", log.getRequestId());
        p.put("created_at", Timestamp.from(log.getCreatedAt()));
        Number key = changeLogInsert.executeAndReturnKey(p);
        log.setId(key.longValue());
    }

    /**
     * 查某分区在指定采样时刻当时生效的策略 id（按变更流水回溯）：
     * effective_at 不晚于采样时刻的最近一次变更对应的新策略。
     */
    public Optional<Long> findEffectiveStrategyIdAt(Long zoneId, Instant at) {
        return jdbc.query("SELECT new_strategy_id FROM strategy_change_log "
                        + "WHERE zone_id = ? AND effective_at <= ? ORDER BY effective_at DESC, id DESC LIMIT 1",
                (rs, n) -> rs.getLong("new_strategy_id"), zoneId, Timestamp.from(at))
                .stream().findFirst();
    }

    static final RowMapper<ZoneActiveStrategy> ACTIVE_MAPPER = (ResultSet rs, int rowNum) -> {
        ZoneActiveStrategy a = new ZoneActiveStrategy();
        a.setZoneId(rs.getLong("zone_id"));
        a.setStrategyId(rs.getLong("strategy_id"));
        a.setActivatedAt(rs.getTimestamp("activated_at").toInstant());
        return a;
    };

    static final RowMapper<ClimateStrategy> MAPPER = (ResultSet rs, int rowNum) -> {        ClimateStrategy s = new ClimateStrategy();
        s.setId(rs.getLong("id"));
        s.setStrategyCode(rs.getString("strategy_code"));
        s.setCropId(rs.getLong("crop_id"));
        s.setZoneId(rs.getLong("zone_id"));
        s.setDayTempTarget(rs.getBigDecimal("day_temp_target"));
        s.setDayTempTolerance(rs.getBigDecimal("day_temp_tolerance"));
        s.setDayHumidityTarget(rs.getBigDecimal("day_humidity_target"));
        s.setDayHumidityTolerance(rs.getBigDecimal("day_humidity_tolerance"));
        s.setNightTempTarget(rs.getBigDecimal("night_temp_target"));
        s.setNightTempTolerance(rs.getBigDecimal("night_temp_tolerance"));
        s.setNightHumidityTarget(rs.getBigDecimal("night_humidity_target"));
        s.setNightHumidityTolerance(rs.getBigDecimal("night_humidity_tolerance"));
        s.setStatus(StrategyStatus.valueOf(rs.getString("status")));
        s.setValidationMessage(rs.getString("validation_message"));
        Timestamp created = rs.getTimestamp("created_at");
        s.setCreatedAt(created == null ? null : created.toInstant());
        Timestamp validated = rs.getTimestamp("validated_at");
        s.setValidatedAt(validated == null ? null : validated.toInstant());
        return s;
    };
}
