package com.example.starter.pump.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 潮位数据仓储：按“分区＋UTC观测时刻”唯一；乱序/归档后潮位只留迟到证据。
 * observed_at 列统一存 UTC 时刻。
 */
@Repository
public class TideReadingRepository {

    /** 潮位观测点（查询视图）。 */
    public record TidePoint(Long id, LocalDateTime observedAtUtc, BigDecimal tideLevel,
                            LocalDateTime receivedAt) {
    }

    /** 迟到潮位证据（查询视图）。 */
    public record LateTidePoint(Long id, LocalDateTime observedAtUtc, BigDecimal tideLevel,
                                LocalDateTime receivedAt, String reason) {
    }

    private final JdbcTemplate jdbc;

    public TideReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入潮位数据。
     *
     * @return 数据ID；分区＋UTC时刻重复返回 null（去重，保留首次数据）
     */
    public Long insert(String zoneCode, LocalDateTime observedAtUtc, BigDecimal tideLevel,
                       LocalDateTime receivedAt) {
        try {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        """
                        INSERT INTO tide_level_reading (zone_code, observed_at, tide_level,
                            received_at) VALUES (?, ?, ?, ?)
                        """,
                        new String[]{"id"});
                ps.setString(1, zoneCode);
                ps.setObject(2, observedAtUtc);
                ps.setBigDecimal(3, tideLevel);
                ps.setObject(4, receivedAt);
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
    public Long findIdByZoneAndTime(String zoneCode, LocalDateTime observedAtUtc) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM tide_level_reading WHERE zone_code = ? AND observed_at = ?",
                (rs, n) -> rs.getLong("id"), zoneCode, observedAtUtc);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 分区已受理的最新观测时刻（UTC），无数据返回 null。 */
    public LocalDateTime latestObservedAt(String zoneCode) {
        List<LocalDateTime> times = jdbc.query(
                """
                SELECT observed_at FROM tide_level_reading
                WHERE zone_code = ? ORDER BY observed_at DESC LIMIT 1
                """,
                (rs, n) -> rs.getObject("observed_at", LocalDateTime.class), zoneCode);
        return times.isEmpty() ? null : times.get(0);
    }

    /** 记录迟到潮位证据，不参与工况判定。重复分区＋时刻返回 false。 */
    public boolean insertLate(String zoneCode, LocalDateTime observedAtUtc, BigDecimal tideLevel,
                              LocalDateTime receivedAt, String reason) {
        try {
            jdbc.update(
                    """
                    INSERT INTO late_tide_event (zone_code, observed_at, tide_level, received_at,
                        reason) VALUES (?, ?, ?, ?, ?)
                    """,
                    zoneCode, observedAtUtc, tideLevel, receivedAt, reason);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 分区潮位曲线，按观测时刻排序。 */
    public List<TidePoint> loadReadings(String zoneCode) {
        return jdbc.query(
                """
                SELECT id, observed_at, tide_level, received_at FROM tide_level_reading
                WHERE zone_code = ? ORDER BY observed_at, id
                """,
                (rs, n) -> new TidePoint(
                        rs.getLong("id"),
                        rs.getObject("observed_at", LocalDateTime.class),
                        rs.getBigDecimal("tide_level"),
                        rs.getObject("received_at", LocalDateTime.class)),
                zoneCode);
    }

    /** 分区迟到潮位证据，按观测时刻排序。 */
    public List<LateTidePoint> loadLateEvents(String zoneCode) {
        return jdbc.query(
                """
                SELECT id, observed_at, tide_level, received_at, reason FROM late_tide_event
                WHERE zone_code = ? ORDER BY observed_at, id
                """,
                (rs, n) -> new LateTidePoint(
                        rs.getLong("id"),
                        rs.getObject("observed_at", LocalDateTime.class),
                        rs.getBigDecimal("tide_level"),
                        rs.getObject("received_at", LocalDateTime.class),
                        rs.getString("reason")),
                zoneCode);
    }
}
