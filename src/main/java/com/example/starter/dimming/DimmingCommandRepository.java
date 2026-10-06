package com.example.starter.dimming;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 调光指令与留痕数据访问。
 */
@Repository
public class DimmingCommandRepository {

    private final JdbcTemplate jdbc;

    public DimmingCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入执行中指令；active_circuit_code 唯一约束保证同一回路同时刻仅一条执行中指令 */
    public long insertExecuting(String requestId, String circuitCode, String source,
            int fromLevel, int toLevel, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dimming_command (request_id, circuit_code, source, from_level, to_level,"
                            + " status, active_circuit_code, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, circuitCode);
            ps.setString(3, source);
            ps.setInt(4, fromLevel);
            ps.setInt(5, toLevel);
            ps.setString(6, "EXECUTING");
            ps.setString(7, circuitCode);
            ps.setTimestamp(8, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void complete(long commandId, LocalDateTime completedAt) {
        jdbc.update(
                "UPDATE dimming_command SET status = 'COMPLETED', active_circuit_code = NULL,"
                        + " completed_at = ? WHERE id = ?",
                completedAt, commandId);
    }

    /** 调光留痕：含前后档位与触发来源 */
    public void insertRecord(String circuitCode, long commandId, int fromLevel, int toLevel,
            String source, LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO dimming_record (circuit_code, command_id, from_level, to_level, source, created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                circuitCode, commandId, fromLevel, toLevel, source, createdAt);
    }
}
