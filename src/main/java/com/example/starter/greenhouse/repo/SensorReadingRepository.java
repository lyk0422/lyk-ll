package com.example.starter.greenhouse.repo;

import com.example.starter.greenhouse.po.SensorReading;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/**
 * 传感器读数数据访问：(zone_id, sample_time) 唯一去重，乱序按时刻归位。
 */
@Repository
public class SensorReadingRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public SensorReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc)
                .withTableName("sensor_reading").usingGeneratedKeyColumns("id");
    }

    /**
     * 插入读数；重复（分区+采样时刻）抛 {@link DuplicateKeyException}，由上层幂等忽略。
     */
    public SensorReading insert(SensorReading r) {
        var p = new HashMap<String, Object>();
        p.put("zone_id", r.getZoneId());
        p.put("sample_time", Timestamp.from(r.getSampleTime()));
        p.put("temperature", r.getTemperature());
        p.put("humidity", r.getHumidity());
        p.put("late_flag", r.isLateFlag());
        p.put("ingested_at", Timestamp.from(r.getIngestedAt()));
        Number key = insert.executeAndReturnKey(p);
        r.setId(key.longValue());
        return r;
    }

    public void markLate(Long id) {
        jdbc.update("UPDATE sensor_reading SET late_flag = TRUE WHERE id = ?", id);
    }

    public Optional<SensorReading> findByZoneAndTime(Long zoneId, Instant sampleTime) {
        return jdbc.query("SELECT * FROM sensor_reading WHERE zone_id = ? AND sample_time = ?",
                MAPPER, zoneId, Timestamp.from(sampleTime)).stream().findFirst();
    }

    /**
     * 取某分区在 [from, to) 内按采样时刻升序的有效读数（排除封存后迟到数据），
     * 上界排他以保证 UTC 午夜 00:00 的读数归属次日，用于连续 3 点偏离判定与日评估复算。
     */
    public List<SensorReading> findRange(Long zoneId, Instant from, Instant to, boolean includeLate) {
        String sql = "SELECT * FROM sensor_reading WHERE zone_id = ? AND sample_time >= ? AND sample_time < ?";
        if (!includeLate) {
            sql += " AND late_flag = FALSE";
        }
        sql += " ORDER BY sample_time ASC, id ASC";
        return jdbc.query(sql, MAPPER, zoneId, Timestamp.from(from), Timestamp.from(to));
    }

    /** 取某时刻（含）之前最近的若干个有效读数，用于增量判定连续偏离。 */
    public List<SensorReading> findLatestBefore(Long zoneId, Instant upTo, int limit) {
        return jdbc.query("SELECT * FROM (SELECT * FROM sensor_reading WHERE zone_id = ? AND sample_time <= ? "
                        + "AND late_flag = FALSE ORDER BY sample_time DESC, id DESC LIMIT ?) t ORDER BY t.sample_time ASC",
                MAPPER, zoneId, Timestamp.from(upTo), limit);
    }

    static final RowMapper<SensorReading> MAPPER = (ResultSet rs, int rowNum) -> {
        SensorReading r = new SensorReading();
        r.setId(rs.getLong("id"));
        r.setZoneId(rs.getLong("zone_id"));
        r.setSampleTime(rs.getTimestamp("sample_time").toInstant());
        r.setTemperature(rs.getBigDecimal("temperature"));
        r.setHumidity(rs.getBigDecimal("humidity"));
        r.setLateFlag(rs.getBoolean("late_flag"));
        r.setIngestedAt(rs.getTimestamp("ingested_at").toInstant());
        return r;
    };
}
