package com.example.starter.repo;

import com.example.starter.domain.PoseReading;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 位姿读数数据访问。（塔机，采集时刻）唯一约束支撑去重，重复读数不重复触发预警。
 */
@Repository
public class PoseRepository {

    private final JdbcTemplate jdbc;

    public PoseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入读数；返回 false 表示同（塔机，时刻）读数已存在（重复上报）。
     */
    public boolean insertIfAbsent(String craneId, Instant tsUtc, double hookXM, double hookYM, Instant receivedAtUtc) {
        try {
            jdbc.update("INSERT INTO pose_reading (crane_id, ts_utc, hook_x_m, hook_y_m, received_at_utc)"
                            + " VALUES (?,?,?,?,?)",
                    craneId, Timestamp.from(tsUtc), hookXM, hookYM, Timestamp.from(receivedAtUtc));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<PoseReading> findByCraneAndTs(String craneId, Instant tsUtc) {
        return jdbc.query("SELECT * FROM pose_reading WHERE crane_id = ? AND ts_utc = ?",
                (rs, n) -> new PoseReading(rs.getLong("id"), rs.getString("crane_id"),
                        rs.getTimestamp("ts_utc").toInstant(),
                        rs.getDouble("hook_x_m"), rs.getDouble("hook_y_m"),
                        rs.getTimestamp("received_at_utc").toInstant()),
                craneId, Timestamp.from(tsUtc)).stream().findFirst();
    }
}
