package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 航班日历数据访问：flight_calendar_version 与 flight_occupancy 表。
 */
@Repository
public class FlightCalendarRepository {

    private static final RowMapper<FlightCalendarVersion> VERSION_MAPPER = (rs, rowNum) ->
            new FlightCalendarVersion(rs.getInt("version"), rs.getTimestamp("published_at").toInstant(),
                    rs.getString("request_id"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<FlightOccupancy> OCCUPANCY_MAPPER = (rs, rowNum) -> new FlightOccupancy(
            rs.getLong("id"), rs.getInt("version"), rs.getString("runway_id"), rs.getString("flight_no"),
            rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant());

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public FlightCalendarRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<FlightCalendarVersion> findVersion(int version) {
        List<FlightCalendarVersion> rows = jdbc.query(
                "SELECT * FROM flight_calendar_version WHERE version = ?", VERSION_MAPPER, version);
        return rows.stream().findFirst();
    }

    public Optional<FlightCalendarVersion> findLatestVersion() {
        List<FlightCalendarVersion> rows = jdbc.query(
                "SELECT * FROM flight_calendar_version ORDER BY version DESC LIMIT 1", VERSION_MAPPER);
        return rows.stream().findFirst();
    }

    public void insertVersion(FlightCalendarVersion version) {
        jdbc.update("INSERT INTO flight_calendar_version (version, published_at, request_id, created_at)"
                + " VALUES (?,?,?,?)", version.version(), Timestamp.from(version.publishedAt()),
                version.requestId(), Timestamp.from(version.createdAt() == null ? clock.instant()
                        : version.createdAt()));
    }

    public void insertOccupancy(int version, String runwayId, String flightNo, Instant startAt, Instant endAt) {
        jdbc.update("INSERT INTO flight_occupancy (version, runway_id, flight_no, start_at, end_at)"
                + " VALUES (?,?,?,?,?)", version, runwayId, flightNo, Timestamp.from(startAt),
                Timestamp.from(endAt));
    }

    /**
     * 查询指定版本、指定跑道与半开窗口 [from,to) 正长度相交的全部占用窗，按开始时刻排序。
     */
    public List<FlightOccupancy> findConflicts(int version, String runwayId, Instant from, Instant to) {
        return jdbc.query("SELECT * FROM flight_occupancy WHERE version = ? AND runway_id = ?"
                        + " AND start_at < ? AND end_at > ? ORDER BY start_at, id",
                OCCUPANCY_MAPPER, version, runwayId, Timestamp.from(to), Timestamp.from(from));
    }

    public List<FlightOccupancy> findByRunwayAndVersion(int version, String runwayId) {
        return jdbc.query("SELECT * FROM flight_occupancy WHERE version = ? AND runway_id = ?"
                + " ORDER BY start_at, id", OCCUPANCY_MAPPER, version, runwayId);
    }
}
