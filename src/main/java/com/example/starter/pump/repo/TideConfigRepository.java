package com.example.starter.pump.repo;

import com.example.starter.pump.domain.TideZoneConfig;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 分区潮汐控制配置仓储。配置行是潮位上送、配置变更与切换确认的统一串行点：
 * 写操作先 {@code SELECT ... FOR UPDATE} 锁定配置行，保证并发下按一致提交顺序推进版本。
 */
@Repository
public class TideConfigRepository {

    private static final RowMapper<TideZoneConfig> MAPPER = (rs, n) -> new TideZoneConfig(
            rs.getString("zone_code"),
            rs.getBigDecimal("backwater_threshold"),
            rs.getString("current_mode"),
            rs.getInt("version"),
            rs.getObject("updated_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public TideConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按分区查询配置，未配置返回空。 */
    public Optional<TideZoneConfig> find(String zoneCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT zone_code, backwater_threshold, current_mode, version, updated_at
                    FROM tide_zone_config WHERE zone_code = ?
                    """,
                    MAPPER, zoneCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 锁定配置行并返回，未配置返回空；并发写操作在此串行。 */
    public Optional<TideZoneConfig> findForUpdate(String zoneCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT zone_code, backwater_threshold, current_mode, version, updated_at
                    FROM tide_zone_config WHERE zone_code = ? FOR UPDATE
                    """,
                    MAPPER, zoneCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 新建配置：工况 NORMAL，版本从1开始。 */
    public void create(String zoneCode, BigDecimal threshold, LocalDateTime now) {
        jdbc.update(
                """
                INSERT INTO tide_zone_config (zone_code, backwater_threshold, current_mode,
                    version, updated_at) VALUES (?, ?, 'NORMAL', 1, ?)
                """,
                zoneCode, threshold, now);
    }

    /** 更新顶托门槛，版本加1。 */
    public void updateThreshold(String zoneCode, BigDecimal threshold, int newVersion,
                                LocalDateTime now) {
        jdbc.update(
                """
                UPDATE tide_zone_config SET backwater_threshold = ?, version = ?, updated_at = ?
                WHERE zone_code = ?
                """,
                threshold, newVersion, now, zoneCode);
    }

    /** 切换工况，版本加1。 */
    public void switchMode(String zoneCode, String newMode, int newVersion, LocalDateTime now) {
        jdbc.update(
                """
                UPDATE tide_zone_config SET current_mode = ?, version = ?, updated_at = ?
                WHERE zone_code = ?
                """,
                newMode, newVersion, now, zoneCode);
    }
}
