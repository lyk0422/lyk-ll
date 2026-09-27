package com.example.starter.pump.repo;

import com.example.starter.pump.domain.TideControl;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 分区潮汐控制配置仓储：顶托门槛与工况版本。
 *
 * <p>版本为乐观锁：所有变更以 {@code WHERE zone_code = ? AND version = ?} 提交，
 * 影响行数为 0 即版本失配（并发修改），由服务层映射为 409 并回传最新版本。
 */
@Repository
public class TideControlRepository {

    private static final RowMapper<TideControl> MAPPER = (rs, n) -> new TideControl(
            rs.getString("zone_code"),
            rs.getBigDecimal("threshold"),
            rs.getString("mode"),
            rs.getInt("version"),
            rs.getObject("updated_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public TideControlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按分区查询配置，不存在返回空（未配置的分区保持原行为）。 */
    public Optional<TideControl> find(String zoneCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT zone_code, threshold, mode, version, updated_at
                    FROM tide_control WHERE zone_code = ?
                    """,
                    MAPPER, zoneCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 创建配置：初始工况 NORMAL，版本从1开始。 */
    public void insert(String zoneCode, BigDecimal threshold, LocalDateTime now) {
        jdbc.update(
                """
                INSERT INTO tide_control (zone_code, threshold, mode, version, updated_at)
                VALUES (?, ?, 'NORMAL', 1, ?)
                """,
                zoneCode, threshold, now);
    }

    /**
     * 按期望版本更新门槛：版本失配影响行数为 0。
     *
     * @return true 更新成功（版本加1）；false 版本失配
     */
    public boolean updateThreshold(String zoneCode, BigDecimal threshold, int expectedVersion,
                                   LocalDateTime now) {
        int rows = jdbc.update(
                """
                UPDATE tide_control SET threshold = ?, version = version + 1, updated_at = ?
                WHERE zone_code = ? AND version = ?
                """,
                threshold, now, zoneCode, expectedVersion);
        return rows == 1;
    }

    /**
     * 按期望版本切换工况：版本失配影响行数为 0。
     *
     * @return true 切换成功（版本加1）；false 版本失配
     */
    public boolean applyModeSwitch(String zoneCode, String newMode, int expectedVersion,
                                   LocalDateTime now) {
        int rows = jdbc.update(
                """
                UPDATE tide_control SET mode = ?, version = version + 1, updated_at = ?
                WHERE zone_code = ? AND version = ?
                """,
                newMode, now, zoneCode, expectedVersion);
        return rows == 1;
    }
}
