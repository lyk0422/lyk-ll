package com.example.starter.waterlogging.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.waterlogging.po.LossContactOrder;

/**
 * 失联派单台账数据访问：(station_code, open_flag) 唯一约束保证同站同时仅一张开放派单。
 */
@Repository
public class LossContactOrderRepository {

    private static final RowMapper<LossContactOrder> MAPPER = (rs, n) -> new LossContactOrder(
            rs.getLong("id"),
            rs.getString("station_code"),
            readTime(rs, "last_reading_time"),
            readTime(rs, "declared_at"),
            rs.getString("status"),
            rs.getString("open_flag"),
            rs.getString("request_id"),
            readTime(rs, "created_at"));

    public LossContactOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected final JdbcTemplate jdbcTemplate;

    private static LocalDateTime readTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    /** 若站点尚无开放派单则派单；已有时返回既有单（幂等/唯一受理）。 */
    public OpenResult openIfAbsent(String stationCode, LocalDateTime lastReadingTime,
                                   LocalDateTime declaredAt, String requestId, LocalDateTime createdAt) {
        Optional<LossContactOrder> open = findOpen(stationCode);
        if (open.isPresent()) {
            return new OpenResult(open.get(), false);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO loss_contact_order
                            (station_code, last_reading_time, declared_at, status, open_flag,
                             request_id, created_at)
                        VALUES (?, ?, ?, 'OPEN', ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, stationCode);
                ps.setTimestamp(2, lastReadingTime == null ? null : Timestamp.valueOf(lastReadingTime));
                ps.setTimestamp(3, Timestamp.valueOf(declaredAt));
                ps.setString(4, stationCode);
                ps.setString(5, requestId);
                ps.setTimestamp(6, Timestamp.valueOf(createdAt));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            // 并发派单唯一受理：负方读取先到派单。
            return new OpenResult(findOpen(stationCode).orElseThrow(), false);
        }
        return new OpenResult(new LossContactOrder(
                keyHolder.getKey().longValue(), stationCode, lastReadingTime, declaredAt,
                "OPEN", stationCode, requestId, createdAt), true);
    }

    /** 查询站点当前开放派单。 */
    public Optional<LossContactOrder> findOpen(String stationCode) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                            SELECT id, station_code, last_reading_time, declared_at, status,
                                   open_flag, request_id, created_at
                            FROM loss_contact_order
                            WHERE station_code = ? AND open_flag = ?
                            """, MAPPER, stationCode, stationCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 复算某站全部失联派单台账。 */
    public List<LossContactOrder> findByStation(String stationCode) {
        return jdbcTemplate.query("""
                SELECT id, station_code, last_reading_time, declared_at, status,
                       open_flag, request_id, created_at
                FROM loss_contact_order WHERE station_code = ?
                ORDER BY declared_at, id
                """, MAPPER, stationCode);
    }

    /** 派单结果：created=false 表示站点已有开放派单。 */
    public record OpenResult(LossContactOrder order, boolean created) {
    }
}
