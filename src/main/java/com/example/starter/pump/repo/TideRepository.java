package com.example.starter.pump.repo;

import com.example.starter.pump.domain.TideControlConfig;
import com.example.starter.pump.domain.TideReading;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 潮汐顶托配置与潮位数据仓储。
 *
 * <p>潮位按“分区+UTC时刻”唯一（{@code uk_zone_tide_time}），自增ID即固定潮位版本。
 * 迟到的较旧潮位写入 {@code late_tide_event}，只留证据不让工况倒退。
 */
@Repository
public class TideRepository {

    private final JdbcTemplate jdbc;

    public TideRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ============ 配置 ============

    /** 查询分区潮汐控制配置；未配置返回空（分区保持原预降行为）。 */
    public Optional<TideControlConfig> findConfig(String zoneCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT zone_code, threshold_level, backoff, regime_version, updated_at
                    FROM tide_control_config WHERE zone_code = ?
                    """,
                    CONFIG_MAPPER, zoneCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 行级锁定分区配置（SELECT ... FOR UPDATE），串行化同分区并发确认/配置变更。 */
    public Optional<TideControlConfig> lockConfig(String zoneCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT zone_code, threshold_level, backoff, regime_version, updated_at
                    FROM tide_control_config WHERE zone_code = ? FOR UPDATE
                    """,
                    CONFIG_MAPPER, zoneCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static final org.springframework.jdbc.core.RowMapper<TideControlConfig> CONFIG_MAPPER =
            (rs, n) -> new TideControlConfig(
                    rs.getString("zone_code"),
                    rs.getBigDecimal("threshold_level"),
                    rs.getBigDecimal("backoff"),
                    rs.getInt("regime_version"),
                    rs.getObject("updated_at", LocalDateTime.class));

    /**
     * 条件更新配置：仅当版本等于 expectedVersion 时才把版本加1并更新门槛。
     *
     * @return 更新后版本；版本失配（行数0）返回空
     */
    public Optional<Integer> updateConfigIfVersion(String zoneCode, BigDecimal thresholdLevel,
                                                   BigDecimal backoff, int expectedVersion,
                                                   LocalDateTime now) {
        int rows = jdbc.update(
                """
                UPDATE tide_control_config
                SET threshold_level = ?, backoff = ?, regime_version = regime_version + 1,
                    updated_at = ?
                WHERE zone_code = ? AND regime_version = ?
                """,
                thresholdLevel, backoff, now, zoneCode, expectedVersion);
        return rows == 0 ? Optional.empty() : Optional.of(expectedVersion + 1);
    }

    /** 首次创建配置：版本从1开始。 */
    public TideControlConfig insertConfig(String zoneCode, BigDecimal thresholdLevel,
                                          BigDecimal backoff, LocalDateTime now) {
        jdbc.update(
                """
                INSERT INTO tide_control_config (zone_code, threshold_level, backoff,
                    regime_version, updated_at)
                VALUES (?, ?, ?, 1, ?)
                """,
                zoneCode, thresholdLevel, backoff, now);
        return new TideControlConfig(zoneCode, thresholdLevel, backoff, 1, now);
    }

    // ============ 潮位 ============

    /**
     * 插入潮位数据，自增ID即潮位版本。
     *
     * @return 潮位记录；分区+UTC时刻重复返回空（去重，重复时刻不产生新版本）
     */
    public Optional<TideReading> insertReading(String zoneCode, OffsetDateTime tideTime,
                                               BigDecimal tideLevel, LocalDateTime receivedAt) {
        try {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        """
                        INSERT INTO tide_reading (zone_code, tide_time, tide_level, received_at)
                        VALUES (?, ?, ?, ?)
                        """,
                        new String[]{"id"});
                ps.setString(1, zoneCode);
                ps.setObject(2, tideTime);
                ps.setBigDecimal(3, tideLevel);
                ps.setObject(4, receivedAt);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("潮位数据自增ID回填失败");
            }
            return Optional.of(new TideReading(key.longValue(), zoneCode, tideTime, tideLevel,
                    receivedAt));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    /** 分区最新一条潮位（版本最大）。 */
    public Optional<TideReading> findLatestReading(String zoneCode) {
        List<TideReading> list = jdbc.query(
                """
                SELECT id, zone_code, tide_time, tide_level, received_at
                FROM tide_reading WHERE zone_code = ? ORDER BY id DESC LIMIT 1
                """,
                (rs, n) -> new TideReading(
                        rs.getLong("id"),
                        rs.getString("zone_code"),
                        rs.getObject("tide_time", OffsetDateTime.class),
                        rs.getBigDecimal("tide_level"),
                        rs.getObject("received_at", LocalDateTime.class)),
                zoneCode);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 按潮位版本ID查询。 */
    public Optional<TideReading> findReadingById(Long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, zone_code, tide_time, tide_level, received_at
                    FROM tide_reading WHERE id = ?
                    """,
                    (rs, n) -> new TideReading(
                            rs.getLong("id"),
                            rs.getString("zone_code"),
                            rs.getObject("tide_time", OffsetDateTime.class),
                            rs.getBigDecimal("tide_level"),
                            rs.getObject("received_at", LocalDateTime.class)),
                    id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 分区潮位轨迹，按版本升序。 */
    public List<TideReading> loadReadings(String zoneCode) {
        return jdbc.query(
                """
                SELECT id, zone_code, tide_time, tide_level, received_at
                FROM tide_reading WHERE zone_code = ? ORDER BY id
                """,
                (rs, n) -> new TideReading(
                        rs.getLong("id"),
                        rs.getString("zone_code"),
                        rs.getObject("tide_time", OffsetDateTime.class),
                        rs.getBigDecimal("tide_level"),
                        rs.getObject("received_at", LocalDateTime.class)),
                zoneCode);
    }

    /** 记录迟到较旧潮位证据；分区+时刻重复返回 false。 */
    public boolean insertLate(String zoneCode, OffsetDateTime tideTime, BigDecimal tideLevel,
                              LocalDateTime receivedAt) {
        try {
            jdbc.update(
                    """
                    INSERT INTO late_tide_event (zone_code, tide_time, tide_level, received_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    zoneCode, tideTime, tideLevel, receivedAt);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
