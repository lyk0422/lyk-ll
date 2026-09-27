package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 航班日历版本指针数据访问：flight_calendar 单行表。
 * 首次发布以固定主键 1 插入（并发首次发布由主键裁决唯一），后续发布以版本号乐观更新。
 */
@Repository
public class FlightCalendarRepository {

    private static final RowMapper<FlightCalendarRecord> MAPPER = (rs, rowNum) -> new FlightCalendarRecord(
            rs.getInt("id"),
            rs.getInt("version"),
            rs.getTimestamp("published_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public FlightCalendarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FlightCalendarRecord> findCurrent() {
        List<FlightCalendarRecord> rows = jdbc.query("SELECT * FROM flight_calendar WHERE id = 1", MAPPER);
        return rows.stream().findFirst();
    }

    /**
     * 首次发布：插入版本1。并发首次插入由主键冲突裁决（调用方转为版本冲突业务错误）。
     */
    public void insertInitial(Instant publishedAt, Instant now) {
        jdbc.update("INSERT INTO flight_calendar (id, version, published_at, updated_at) VALUES (1, 1, ?, ?)",
                Timestamp.from(publishedAt), Timestamp.from(now));
    }

    /**
     * 乐观推进版本：仅当当前版本等于 expectedVersion 时加1，返回受影响行数。
     */
    public int advanceVersion(int expectedVersion, Instant publishedAt, Instant now) {
        return jdbc.update(
                "UPDATE flight_calendar SET version = version + 1, published_at = ?, updated_at = ?"
                        + " WHERE id = 1 AND version = ?",
                Timestamp.from(publishedAt), Timestamp.from(now), expectedVersion);
    }
}
