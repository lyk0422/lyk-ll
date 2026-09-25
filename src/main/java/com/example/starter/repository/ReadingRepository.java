package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.example.starter.domain.VibrationReading;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 振动监测回传数据访问；按爆区+测点+采样时刻去重。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入读数；命中去重键时返回 false（幂等去重，不报错）。
     */
    public boolean insertIfAbsent(long permitId, long zoneId, String pointCode,
            Instant sampledAt, java.math.BigDecimal velocity, Instant receivedAt) {
        try {
            jdbc.update(
                    "INSERT INTO vibration_reading"
                            + " (permit_id, zone_id, point_code, sampled_at, velocity, received_at)"
                            + " VALUES (?,?,?,?,?,?)",
                    permitId, zoneId, pointCode,
                    Timestamp.from(sampledAt), velocity, Timestamp.from(receivedAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 查询指定申请单已回传的测点编码。
     */
    public List<String> findPointCodesByPermit(long permitId) {
        return jdbc.queryForList(
                "SELECT DISTINCT point_code FROM vibration_reading WHERE permit_id = ?",
                String.class, permitId);
    }

    /**
     * 记录封档后到达的迟到读数事件。
     */
    public void insertLateEvent(long permitId, long zoneId, String pointCode,
            Instant sampledAt, Instant recordedAt) {
        jdbc.update(
                "INSERT INTO late_event (permit_id, zone_id, point_code, sampled_at, recorded_at)"
                        + " VALUES (?,?,?,?,?)",
                permitId, zoneId, pointCode, Timestamp.from(sampledAt), Timestamp.from(recordedAt));
    }

    /**
     * 查询申请单的迟到事件数（供测试与排查）。
     */
    public int countLateEvents(long permitId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE permit_id = ?", Integer.class, permitId);
        return count == null ? 0 : count;
    }

    @SuppressWarnings("unused")
    private VibrationReading map(ResultSet rs, int rowNum) throws SQLException {
        return new VibrationReading(
                rs.getLong("id"),
                rs.getLong("permit_id"),
                rs.getLong("zone_id"),
                rs.getString("point_code"),
                rs.getTimestamp("sampled_at").toInstant(),
                rs.getBigDecimal("velocity"));
    }
}
