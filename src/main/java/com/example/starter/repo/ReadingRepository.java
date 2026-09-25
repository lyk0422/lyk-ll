package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.api.dto.ReadingItem;
import com.example.starter.domain.EmissionReading;

/**
 * 排放读数访问：去重唯一约束 (plan_id, measure_point, sample_time)。
 */
@Repository
public class ReadingRepository {

    private static final RowMapper<EmissionReading> MAPPER = (ResultSet rs, int n) -> new EmissionReading(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("kiln_id"),
            rs.getString("measure_point"),
            rs.getObject("sample_time", LocalDateTime.class),
            rs.getBigDecimal("nox"),
            rs.getBigDecimal("so2"),
            rs.getBigDecimal("dust"),
            rs.getBoolean("is_late"),
            rs.getObject("received_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入单条读数。命中“方案+测点+采样时刻”唯一约束返回 false（乱序重复上报去重）。
     */
    public boolean tryInsert(long planId, String kilnId, ReadingItem item, boolean late) {
        try {
            jdbc.update("""
                    INSERT INTO emission_reading
                        (plan_id, kiln_id, measure_point, sample_time, nox, so2, dust, is_late)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    planId, kilnId, item.measurePoint(), Timestamp.valueOf(item.sampleTime()),
                    item.nox(), item.so2(), item.dust(), late);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void insertLateEvent(long planId, ReadingItem item, String payload) {
        jdbc.update("""
                INSERT INTO late_reading_event (plan_id, measure_point, sample_time, payload)
                VALUES (?, ?, ?, ?)
                """, planId, item.measurePoint(), Timestamp.valueOf(item.sampleTime()), payload);
    }

    public int countLateEvents(long planId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_reading_event WHERE plan_id = ?", Integer.class, planId);
        return c == null ? 0 : c;
    }

    /** 取某日期（按上海时区截断到日）内的全部读数，乱序数据由 ORDER BY 采样时刻归位。 */
    public List<EmissionReading> findByPlanAndDate(long planId, java.time.LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        return jdbc.query("""
                SELECT id, plan_id, kiln_id, measure_point, sample_time, nox, so2, dust, is_late, received_at
                FROM emission_reading
                WHERE plan_id = ? AND sample_time >= ? AND sample_time < ?
                ORDER BY sample_time, measure_point
                """, MAPPER, planId, Timestamp.valueOf(start), Timestamp.valueOf(end));
    }
}
