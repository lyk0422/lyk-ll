package com.example.starter.tailings.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 监测读数与迟到事件存取。读数按 测点+类型+采样时刻 唯一去重，查询按采样时刻归位。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 监测读数行。
     *
     * @param id          主键
     * @param pondId      尾矿库ID
     * @param pointCode   测点编号
     * @param readingType 读数类型
     * @param sampleTime  采样时刻(Asia/Shanghai)
     * @param value       读数值，单位随类型
     */
    public record ReadingRow(long id, long pondId, String pointCode, String readingType,
                             LocalDateTime sampleTime, BigDecimal value) {
    }

    /**
     * 迟到事件行：归档后到达的读数只记事件，不入读数表。
     */
    public record LateEventRow(long id, long pondId, String pointCode, String readingType,
                               LocalDateTime sampleTime, BigDecimal value, String detail,
                               LocalDateTime createdAt) {
    }

    public long insertReading(long pondId, String pointCode, String readingType,
                              LocalDateTime sampleTime, BigDecimal value) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reading (pond_id, point_code, reading_type, sample_time, reading_value)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, pondId);
            ps.setString(2, pointCode);
            ps.setString(3, readingType);
            ps.setTimestamp(4, Timestamp.valueOf(sampleTime));
            ps.setBigDecimal(5, value);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReadingRow> findByNaturalKey(long pondId, String pointCode, String readingType,
                                                 LocalDateTime sampleTime) {
        return jdbc.query(
                        "SELECT id, pond_id, point_code, reading_type, sample_time, reading_value FROM reading"
                                + " WHERE pond_id = ? AND point_code = ? AND reading_type = ? AND sample_time = ?",
                        (rs, rowNum) -> new ReadingRow(
                                rs.getLong("id"),
                                rs.getLong("pond_id"),
                                rs.getString("point_code"),
                                rs.getString("reading_type"),
                                rs.getObject("sample_time", LocalDateTime.class),
                                rs.getBigDecimal("reading_value")),
                        pondId, pointCode, readingType, Timestamp.valueOf(sampleTime))
                .stream()
                .findFirst();
    }

    /**
     * 按采样时刻升序归位（乱序到达不影响输出顺序）。
     */
    public List<ReadingRow> listByPoint(long pondId, String pointCode, String readingType) {
        return jdbc.query(
                "SELECT id, pond_id, point_code, reading_type, sample_time, reading_value FROM reading"
                        + " WHERE pond_id = ? AND point_code = ? AND reading_type = ? ORDER BY sample_time",
                (rs, rowNum) -> new ReadingRow(
                        rs.getLong("id"),
                        rs.getLong("pond_id"),
                        rs.getString("point_code"),
                        rs.getString("reading_type"),
                        rs.getObject("sample_time", LocalDateTime.class),
                        rs.getBigDecimal("reading_value")),
                pondId, pointCode, readingType);
    }

    public long insertLateEvent(long pondId, String pointCode, String readingType,
                                LocalDateTime sampleTime, BigDecimal value, String detail) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO late_event (pond_id, point_code, reading_type, sample_time, reading_value, detail)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, pondId);
            ps.setString(2, pointCode);
            ps.setString(3, readingType);
            ps.setTimestamp(4, Timestamp.valueOf(sampleTime));
            ps.setBigDecimal(5, value);
            ps.setString(6, detail);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<LateEventRow> listLateEvents(long pondId) {
        return jdbc.query(
                "SELECT id, pond_id, point_code, reading_type, sample_time, reading_value, detail, created_at"
                        + " FROM late_event WHERE pond_id = ? ORDER BY id",
                (rs, rowNum) -> new LateEventRow(
                        rs.getLong("id"),
                        rs.getLong("pond_id"),
                        rs.getString("point_code"),
                        rs.getString("reading_type"),
                        rs.getObject("sample_time", LocalDateTime.class),
                        rs.getBigDecimal("reading_value"),
                        rs.getString("detail"),
                        rs.getObject("created_at", LocalDateTime.class)),
                pondId);
    }
}
