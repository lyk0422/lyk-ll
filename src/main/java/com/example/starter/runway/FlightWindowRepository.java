package com.example.starter.runway;

import com.example.starter.runway.dto.FlightOccupancyInput;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 航班占用窗快照数据访问：flight_window 表，按版本冻结、不可变。
 */
@Repository
public class FlightWindowRepository {

    private static final RowMapper<FlightWindowRecord> MAPPER = (rs, rowNum) -> new FlightWindowRecord(
            rs.getLong("id"),
            rs.getInt("calendar_version"),
            rs.getString("runway_id"),
            rs.getString("flight_no"),
            rs.getTimestamp("occupy_start").toInstant(),
            rs.getTimestamp("occupy_end").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public FlightWindowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批量冻结某日历版本的占用窗快照。
     */
    public void insertBatch(int calendarVersion, List<FlightOccupancyInput> windows, Instant now) {
        for (FlightOccupancyInput window : windows) {
            jdbc.update("INSERT INTO flight_window (calendar_version, runway_id, flight_no, occupy_start,"
                            + " occupy_end, created_at) VALUES (?,?,?,?,?,?)",
                    calendarVersion, window.runwayId(), window.flightNo(),
                    Timestamp.from(window.occupyStart()), Timestamp.from(window.occupyEnd()),
                    Timestamp.from(now));
        }
    }

    /**
     * 查询某日历版本下指定跑道的全部占用窗（证据须覆盖全部受检航班窗）。
     */
    public List<FlightWindowRecord> findByVersionAndRunway(int calendarVersion, String runwayId) {
        return jdbc.query(
                "SELECT * FROM flight_window WHERE calendar_version = ? AND runway_id = ?"
                        + " ORDER BY occupy_start, id",
                MAPPER, calendarVersion, runwayId);
    }

    /**
     * 查询某日历版本的全部占用窗。
     */
    public List<FlightWindowRecord> findByVersion(int calendarVersion) {
        return jdbc.query("SELECT * FROM flight_window WHERE calendar_version = ? ORDER BY occupy_start, id",
                MAPPER, calendarVersion);
    }
}
