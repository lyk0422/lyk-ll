package com.example.starter.pump.repo;

import com.example.starter.pump.domain.TideReading;
import com.example.starter.pump.web.dto.TideReadingListResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分区潮位数据仓储：按分区+UTC采集时刻唯一，非负由 CHECK 约束保证。
 */
@Repository
public class TideReadingRepository {

    private final JdbcTemplate jdbc;

    public TideReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入潮位数据。
     *
     * @return 数据ID；分区＋时刻重复返回 null（去重，保留首次数据）
     */
    public Long insert(String zoneCode, LocalDateTime readingTimeUtc, BigDecimal level,
                       boolean late, LocalDateTime receivedAt) {
        try {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        """
                        INSERT INTO tide_reading (zone_code, reading_time, level, late, received_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        new String[]{"id"});
                ps.setString(1, zoneCode);
                ps.setObject(2, readingTimeUtc);
                ps.setBigDecimal(3, level);
                ps.setInt(4, late ? 1 : 0);
                ps.setObject(5, receivedAt);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("潮位数据自增ID回填失败");
            }
            return key.longValue();
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    /** 按分区＋UTC时刻查既有数据ID（去重命中时返回首次ID）。 */
    public Long findIdByZoneAndTime(String zoneCode, LocalDateTime readingTimeUtc) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM tide_reading WHERE zone_code = ? AND reading_time = ?",
                (rs, n) -> rs.getLong("id"), zoneCode, readingTimeUtc);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 分区已收到的最新采集时刻（用于迟到判定），无数据返回 null。 */
    public LocalDateTime latestReadingTime(String zoneCode) {
        List<LocalDateTime> times = jdbc.query(
                "SELECT MAX(reading_time) AS mt FROM tide_reading WHERE zone_code = ?",
                (rs, n) -> rs.getObject("mt", LocalDateTime.class),
                zoneCode);
        return times.isEmpty() ? null : times.get(0);
    }

    /** 查询分区全部潮位数据，按采集时刻排序。 */
    public List<TideReadingListResult.TideReadingItem> listByZone(String zoneCode) {
        return jdbc.query(
                """
                SELECT id, reading_time, level, late, received_at FROM tide_reading
                WHERE zone_code = ? ORDER BY reading_time, id
                """,
                (rs, n) -> new TideReadingListResult.TideReadingItem(
                        rs.getLong("id"),
                        rs.getObject("reading_time", LocalDateTime.class),
                        rs.getBigDecimal("level"),
                        rs.getInt("late") == 1,
                        rs.getObject("received_at", LocalDateTime.class)),
                zoneCode);
    }

    /** 按ID查询潮位数据（候选生成后回读触发数据）。 */
    public TideReading findById(Long id) {
        return jdbc.queryForObject(
                """
                SELECT id, zone_code, reading_time, level, late, received_at
                FROM tide_reading WHERE id = ?
                """,
                (rs, n) -> new TideReading(
                        rs.getLong("id"),
                        rs.getString("zone_code"),
                        rs.getObject("reading_time", LocalDateTime.class),
                        rs.getBigDecimal("level"),
                        rs.getInt("late") == 1,
                        rs.getObject("received_at", LocalDateTime.class)),
                id);
    }
}
