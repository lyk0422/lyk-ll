package com.example.starter.blast.repo;

import com.example.starter.blast.domain.BlastZone;
import com.example.starter.blast.domain.Mine;
import com.example.starter.blast.domain.MonitoringPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 矿区、爆区、测点基础数据与冻结状态仓储。
 */
@Repository
public class ReferenceDataRepository {

    private static final RowMapper<Mine> MINE_MAPPER = (rs, rowNum) -> new Mine(
            rs.getString("mine_code"),
            rs.getString("mine_name"),
            rs.getString("time_zone"),
            rs.getBoolean("frozen"),
            toInstant(rs.getTimestamp("frozen_at")),
            rs.getString("frozen_reason"),
            toInstant(rs.getTimestamp("unfrozen_at")),
            rs.getString("unfreeze_reviewer"),
            rs.getLong("version"));

    private static final RowMapper<BlastZone> ZONE_MAPPER = (rs, rowNum) -> new BlastZone(
            rs.getString("zone_code"),
            rs.getString("mine_code"),
            rs.getString("zone_name"),
            rs.getBigDecimal("max_charge_per_delay_kg"));

    private static final RowMapper<MonitoringPoint> POINT_MAPPER = (rs, rowNum) -> new MonitoringPoint(
            rs.getString("zone_code"),
            rs.getString("point_code"),
            rs.getString("point_name"),
            rs.getBigDecimal("velocity_limit_mm_s"));

    private final JdbcTemplate jdbcTemplate;

    public ReferenceDataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Mine> findMine(String mineCode) {
        return jdbcTemplate.query(
                "SELECT mine_code, mine_name, time_zone, frozen, frozen_at, frozen_reason, "
                        + "unfrozen_at, unfreeze_reviewer, version FROM mine WHERE mine_code = ?",
                MINE_MAPPER, mineCode).stream().findFirst();
    }

    public Optional<BlastZone> findZone(String zoneCode) {
        return jdbcTemplate.query(
                "SELECT zone_code, mine_code, zone_name, max_charge_per_delay_kg "
                        + "FROM blast_zone WHERE zone_code = ?",
                ZONE_MAPPER, zoneCode).stream().findFirst();
    }

    public Optional<MonitoringPoint> findPoint(String zoneCode, String pointCode) {
        return jdbcTemplate.query(
                "SELECT zone_code, point_code, point_name, velocity_limit_mm_s "
                        + "FROM monitoring_point WHERE zone_code = ? AND point_code = ?",
                POINT_MAPPER, zoneCode, pointCode).stream().findFirst();
    }

    public List<MonitoringPoint> findPointsOfZone(String zoneCode) {
        return jdbcTemplate.query(
                "SELECT zone_code, point_code, point_name, velocity_limit_mm_s "
                        + "FROM monitoring_point WHERE zone_code = ? ORDER BY point_code",
                POINT_MAPPER, zoneCode);
    }

    /**
     * 仅当矿区当前未冻结时置为冻结（条件更新），返回是否由本调用完成状态翻转。
     * 并发超限读数只有一个调用能翻转成功，避免重复冻结事件。
     */
    public boolean freezeIfActive(String mineCode, String reason, Instant now) {
        int rows = jdbcTemplate.update(
                "UPDATE mine SET frozen = TRUE, frozen_at = ?, frozen_reason = ?, "
                        + "unfrozen_at = NULL, unfreeze_reviewer = NULL, version = version + 1 "
                        + "WHERE mine_code = ? AND frozen = FALSE",
                Timestamp.from(now), reason, mineCode);
        return rows == 1;
    }

    /**
     * 仅当矿区当前处于冻结时解除冻结（条件更新），返回是否由本调用完成解冻。
     */
    public boolean unfreezeIfFrozen(String mineCode, String reviewer, Instant now) {
        int rows = jdbcTemplate.update(
                "UPDATE mine SET frozen = FALSE, unfrozen_at = ?, unfreeze_reviewer = ?, "
                        + "version = version + 1 WHERE mine_code = ? AND frozen = TRUE",
                Timestamp.from(now), reviewer, mineCode);
        return rows == 1;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
