package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 航班日历版本数据访问：flight_calendar 表（仅追加版本）。
 */
@Repository
public class FlightCalendarRepository {

    private static final RowMapper<FlightCalendarVersion> MAPPER = (rs, rowNum) -> new FlightCalendarVersion(
            rs.getLong("id"),
            rs.getInt("version"),
            rs.getTimestamp("published_at").toInstant(),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public FlightCalendarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一个新版本并取回行。 */
    public FlightCalendarVersion insert(int version, java.time.Instant publishedAt, String requestId,
                                        java.time.Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO flight_calendar (version, published_at, request_id, created_at)"
                            + " VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, version);
            ps.setTimestamp(2, Timestamp.from(publishedAt));
            ps.setString(3, requestId);
            ps.setTimestamp(4, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return findByVersion(version).orElseThrow();
    }

    public Optional<FlightCalendarVersion> findByVersion(int version) {
        List<FlightCalendarVersion> rows = jdbc.query(
                "SELECT * FROM flight_calendar WHERE version = ?", MAPPER, version);
        return rows.stream().findFirst();
    }

    /** 当前最大版本号；表为空时返回空（正常情况下种子版本1恒在）。 */
    public Optional<Integer> findMaxVersion() {
        Integer max = jdbc.queryForObject("SELECT MAX(version) FROM flight_calendar", Integer.class);
        return Optional.ofNullable(max);
    }

    /** 行级锁定取最大版本号，供并发发布串行化版本号分配（锁最大版本行本身）。 */
    public Optional<Integer> findMaxVersionForUpdate() {
        List<Integer> rows = jdbc.queryForList(
                "SELECT version FROM flight_calendar ORDER BY version DESC LIMIT 1 FOR UPDATE", Integer.class);
        return rows.stream().findFirst();
    }
}
