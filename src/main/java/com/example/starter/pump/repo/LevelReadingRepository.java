package com.example.starter.pump.repo;

import com.example.starter.pump.web.dto.ReplayResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 液位数据仓储：按“站点＋采集时刻”去重，并记录归档后迟到事件。
 */
@Repository
public class LevelReadingRepository {

    private final JdbcTemplate jdbc;

    public LevelReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入液位数据。
     *
     * @return 数据ID；站点＋时刻重复返回 null（去重，保留首次数据）
     */
    public Long insert(String stationCode, Long pumpdownId, LocalDateTime readingTime,
                       BigDecimal level, LocalDateTime receivedAt) {
        try {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        """
                        INSERT INTO level_reading (station_code, pumpdown_id, reading_time,
                            level, received_at) VALUES (?, ?, ?, ?, ?)
                        """,
                        new String[]{"id"});
                ps.setString(1, stationCode);
                ps.setObject(2, pumpdownId);
                ps.setObject(3, readingTime);
                ps.setBigDecimal(4, level);
                ps.setObject(5, receivedAt);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("液位数据自增ID回填失败");
            }
            return key.longValue();
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    /** 按站点＋采集时刻查既有数据ID（去重命中时返回首次ID）。 */
    public Long findIdByStationAndTime(String stationCode, LocalDateTime readingTime) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM level_reading WHERE station_code = ? AND reading_time = ?",
                (rs, n) -> rs.getLong("id"), stationCode, readingTime);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 记录归档后迟到液位事件，不进入预降曲线。重复站点＋时刻返回 false。 */
    public boolean insertLate(String stationCode, LocalDateTime readingTime, BigDecimal level,
                              LocalDateTime receivedAt) {
        try {
            jdbc.update(
                    """
                    INSERT INTO late_reading_event (station_code, reading_time, level, received_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    stationCode, readingTime, level, receivedAt);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 按站点＋采集时刻查迟到事件是否存在。 */
    public boolean lateExists(String stationCode, LocalDateTime readingTime) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_reading_event WHERE station_code = ? AND reading_time = ?",
                Integer.class, stationCode, readingTime);
        return cnt != null && cnt > 0;
    }

    /** 某站点在预降期内、指定时刻之前（不含）最近一条液位时刻。 */
    public LocalDateTime latestReadingTimeBefore(String stationCode, Long pumpdownId,
                                                 LocalDateTime before) {
        List<LocalDateTime> times = jdbc.query(
                """
                SELECT reading_time FROM level_reading
                WHERE station_code = ? AND pumpdown_id = ? AND reading_time < ?
                ORDER BY reading_time DESC LIMIT 1
                """,
                (rs, n) -> rs.getObject("reading_time", LocalDateTime.class),
                stationCode, pumpdownId, before);
        return times.isEmpty() ? null : times.get(0);
    }

    /** 复算：预降事件全部站点的水位曲线，按站点、时刻排序。 */
    public List<ReplayResult.LevelPoint> loadCurve(Long pumpdownId) {
        return jdbc.query(
                """
                SELECT station_code, reading_time, level FROM level_reading
                WHERE pumpdown_id = ? ORDER BY station_code, reading_time, id
                """,
                (rs, n) -> new ReplayResult.LevelPoint(
                        rs.getString("station_code"),
                        rs.getObject("reading_time", LocalDateTime.class),
                        rs.getBigDecimal("level")),
                pumpdownId);
    }
}
