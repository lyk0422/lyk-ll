package com.example.starter.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 调光指令数据访问。executing 标记与 circuit_id 组成唯一键，
 * 保证同一回路同时刻只允许一条执行中指令。
 */
@Repository
public class CommandRepository {

    private final JdbcTemplate jdbc;

    public CommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入一条执行中指令，返回主键。 */
    public long insertExecuting(String requestId, long circuitId, String source, int targetLevel, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dimming_command (request_id, circuit_id, source, target_level, status, executing, created_at)"
                            + " VALUES (?, ?, ?, ?, 'EXECUTING', 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, circuitId);
            ps.setString(3, source);
            ps.setInt(4, targetLevel);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 指令执行完成：记录执行前档位并清除执行中标记。 */
    public void markExecuted(long commandId, int fromLevel, String message) {
        jdbc.update("UPDATE dimming_command SET status = 'EXECUTED', from_level = ?, executing = NULL, message = ? WHERE id = ?",
                fromLevel, message, commandId);
    }

    /** 统计回路指令数（测试核验用）。 */
    public int countByCircuit(long circuitId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dimming_command WHERE circuit_id = ?", Integer.class, circuitId);
        return count == null ? 0 : count;
    }

    /** 统计某幂等键的指令数（测试核验用）。 */
    public int countByRequestId(String requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dimming_command WHERE request_id = ?", Integer.class, requestId);
        return count == null ? 0 : count;
    }
}
