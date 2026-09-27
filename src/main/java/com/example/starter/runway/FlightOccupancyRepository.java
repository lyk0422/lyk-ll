package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 合成航班占用窗数据访问：flight_occupancy 表。
 */
@Repository
public class FlightOccupancyRepository {

    private static final RowMapper<FlightOccupancyWindow> MAPPER = (rs, rowNum) -> new FlightOccupancyWindow(
            rs.getLong("id"),
            rs.getInt("version"),
            rs.getString("runway_id"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant());

    private final JdbcTemplate jdbc;

    public FlightOccupancyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量写入某日历版本的占用窗（合成数据）。 */
    public void insertBatch(int version, List<FlightOccupancyWindow> windows) {
        for (FlightOccupancyWindow window : windows) {
            jdbc.update("INSERT INTO flight_occupancy (version, runway_id, window_start, window_end)"
                            + " VALUES (?,?,?,?)",
                    version, window.runwayId(), Timestamp.from(window.windowStart()),
                    Timestamp.from(window.windowEnd()));
        }
    }

    /** 取某版本下指定跑道的全部占用窗。 */
    public List<FlightOccupancyWindow> findByVersionAndRunway(int version, String runwayId) {
        return jdbc.query(
                "SELECT * FROM flight_occupancy WHERE version = ? AND runway_id = ? ORDER BY window_start, id",
                MAPPER, version, runwayId);
    }

    /** 取某版本下全部跑道的占用窗。 */
    public List<FlightOccupancyWindow> findByVersion(int version) {
        return jdbc.query("SELECT * FROM flight_occupancy WHERE version = ? ORDER BY runway_id, window_start, id",
                MAPPER, version);
    }
}
