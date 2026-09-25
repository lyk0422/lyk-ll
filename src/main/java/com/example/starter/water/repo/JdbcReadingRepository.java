package com.example.starter.water.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.water.domain.ReadingRepository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 计量读数仓储的 JdbcTemplate/H2 实现。
 */
@Repository
public class JdbcReadingRepository implements ReadingRepository {

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"),
            rs.getLong("meter_point_id"),
            rs.getTimestamp("reading_time").toInstant(),
            rs.getString("reading_month"),
            rs.getBigDecimal("cumulative"),
            rs.getBigDecimal("delta"),
            rs.getTimestamp("received_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public JdbcReadingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ReadingRow> findByMeterAndTime(Long meterPointId, Instant readingTime) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, meter_point_id, reading_time, reading_month, cumulative, delta, received_at "
                            + "FROM meter_reading WHERE meter_point_id = ? AND reading_time = ?",
                    READING_MAPPER, meterPointId, Timestamp.from(readingTime)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public ReadingRow insert(Long meterPointId, Instant readingTime, String month, BigDecimal cumulative,
                             BigDecimal delta, Instant receivedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO meter_reading (meter_point_id, reading_time, reading_month, cumulative, delta, received_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, meterPointId);
            ps.setTimestamp(2, Timestamp.from(readingTime));
            ps.setString(3, month);
            ps.setBigDecimal(4, cumulative);
            ps.setBigDecimal(5, delta);
            ps.setTimestamp(6, Timestamp.from(receivedAt));
            return ps;
        }, keyHolder);
        return new ReadingRow(keyHolder.getKey().longValue(), meterPointId, readingTime, month,
                cumulative, delta, receivedAt);
    }

    @Override
    public List<ReadingRow> lockOrderedReadings(Long meterPointId) {
        // 先锁计量点父行：即使尚无读数，也能保证同计量点并发上报串行化，避免乱序插入时增量重算交叉。
        Integer locked = jdbcTemplate.queryForObject(
                "SELECT id FROM meter_point WHERE id = ? FOR UPDATE", Integer.class, meterPointId);
        if (locked == null) {
            throw new EmptyResultDataAccessException("计量点不存在: " + meterPointId, 1);
        }
        return jdbcTemplate.query(
                "SELECT id, meter_point_id, reading_time, reading_month, cumulative, delta, received_at "
                        + "FROM meter_reading WHERE meter_point_id = ? ORDER BY reading_time, id FOR UPDATE",
                READING_MAPPER, meterPointId);
    }

    @Override
    public void updateDelta(Long id, BigDecimal delta) {
        jdbcTemplate.update("UPDATE meter_reading SET delta = ? WHERE id = ?", delta, id);
    }

    @Override
    public List<MonthTotal> monthlyTotalsOfUserYear(Long userId, int year) {
        return jdbcTemplate.query(
                "SELECT r.reading_month AS month_label, SUM(r.delta) AS total "
                        + "FROM meter_reading r JOIN meter_point m ON r.meter_point_id = m.id "
                        + "WHERE m.user_id = ? AND r.reading_month LIKE ? GROUP BY r.reading_month "
                        + "ORDER BY r.reading_month",
                (rs, n) -> new MonthTotal(rs.getString("month_label"), rs.getBigDecimal("total")),
                userId, year + "-%");
    }
}
