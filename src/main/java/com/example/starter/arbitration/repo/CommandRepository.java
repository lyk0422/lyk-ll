package com.example.starter.arbitration.repo;

import com.example.starter.arbitration.domain.CommandPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 指令表 t_command 数据访问。只有通过校验的指令才写入（非法/过期指令不进入执行队列）。
 */
@Repository
public class CommandRepository {

    private static final RowMapper<CommandPo> MAPPER = (rs, n) -> {
        CommandPo po = new CommandPo();
        po.setId(rs.getLong("id"));
        po.setControllerId(rs.getString("controller_id"));
        po.setResourceName(rs.getString("resource_name"));
        po.setRequestId(rs.getString("request_id"));
        po.setTimestampMs(rs.getLong("timestamp_ms"));
        po.setSequence(rs.getLong("sequence"));
        po.setTargetPosition(rs.getDouble("target_position"));
        po.setTargetVelocity(rs.getDouble("target_velocity"));
        po.setTargetAcceleration(rs.getDouble("target_acceleration"));
        po.setStatus(rs.getString("status"));
        po.setEnqueuedAtMs(rs.getLong("enqueued_at_ms"));
        po.setFinishedAtMs((Long) rs.getObject("finished_at_ms"));
        po.setRejectReason(rs.getString("reject_reason"));
        return po;
    };

    private final JdbcTemplate jdbc;

    public CommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入指令，返回自增主键。 */
    public long insert(CommandPo command) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_command "
                            + "(controller_id, resource_name, request_id, timestamp_ms, sequence, "
                            + "target_position, target_velocity, target_acceleration, status, "
                            + "enqueued_at_ms, finished_at_ms, reject_reason) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, command.getControllerId());
            ps.setString(2, command.getResourceName());
            ps.setString(3, command.getRequestId());
            ps.setLong(4, command.getTimestampMs());
            ps.setLong(5, command.getSequence());
            ps.setDouble(6, command.getTargetPosition());
            ps.setDouble(7, command.getTargetVelocity());
            ps.setDouble(8, command.getTargetAcceleration());
            ps.setString(9, command.getStatus());
            ps.setLong(10, command.getEnqueuedAtMs());
            if (command.getFinishedAtMs() == null) {
                ps.setObject(11, null);
            } else {
                ps.setLong(11, command.getFinishedAtMs());
            }
            ps.setString(12, command.getRejectReason());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按控制器与幂等键查询。 */
    public Optional<CommandPo> findByControllerAndRequest(String controllerId, String requestId) {
        return jdbc.query(
                "SELECT * FROM t_command WHERE controller_id = ? AND request_id = ?",
                MAPPER, controllerId, requestId).stream().findFirst();
    }

    /** 按主键查询。 */
    public Optional<CommandPo> findById(long id) {
        return jdbc.query("SELECT * FROM t_command WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** 事务内锁定查询某资源最近一条指令（序列号与时戳单调性校验依据）。 */
    public Optional<CommandPo> findLatestByResourceForUpdate(String resourceName) {
        return jdbc.query(
                "SELECT * FROM t_command WHERE id = "
                        + "(SELECT MAX(id) FROM t_command WHERE resource_name = ?) FOR UPDATE",
                MAPPER, resourceName).stream().findFirst();
    }

    /** 事务内锁定查询某控制器发往某资源的最近一条指令（单调性/时钟回拨校验依据）。 */
    public Optional<CommandPo> findLatestByControllerResourceForUpdate(String controllerId,
                                                                       String resourceName) {
        return jdbc.query(
                "SELECT * FROM t_command WHERE id = (SELECT MAX(id) FROM t_command "
                        + "WHERE controller_id = ? AND resource_name = ?) FOR UPDATE",
                MAPPER, controllerId, resourceName).stream().findFirst();
    }

    /** PENDING -> EXECUTED 条件更新，返回受影响行数。 */
    public int markExecutedIfPending(long id, long nowMs) {
        return jdbc.update(
                "UPDATE t_command SET status = 'EXECUTED', finished_at_ms = ? WHERE id = ? AND status = 'PENDING'",
                nowMs, id);
    }

    /** PENDING -> TIMED_OUT 条件更新，返回受影响行数。 */
    public int markTimedOutIfPending(long id, long nowMs) {
        return jdbc.update(
                "UPDATE t_command SET status = 'TIMED_OUT', finished_at_ms = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                nowMs, id);
    }

    /** 超时仍在队列中的指令（按入队时间，事务内加锁）。 */
    public List<CommandPo> findOverduePendingForUpdate(long nowMs, long queueTimeoutMs) {
        return jdbc.query(
                "SELECT * FROM t_command WHERE status = 'PENDING' AND enqueued_at_ms < ? FOR UPDATE",
                MAPPER, nowMs - queueTimeoutMs);
    }

    /** 到时可执行的队列指令（时戳不晚于给定时间，事务内加锁）。 */
    public List<CommandPo> findDuePendingForUpdate(long nowMs) {
        return jdbc.query(
                "SELECT * FROM t_command WHERE status = 'PENDING' AND timestamp_ms <= ? ORDER BY id FOR UPDATE",
                MAPPER, nowMs);
    }

    /** 急停：清空待执行指令（全部置为 TIMED_OUT 并记录原因），返回受影响行数。 */
    public int cancelAllPending(String reason, long nowMs) {
        return jdbc.update(
                "UPDATE t_command SET status = 'TIMED_OUT', finished_at_ms = ?, reject_reason = ? "
                        + "WHERE status = 'PENDING'",
                nowMs, reason);
    }

    /** 撤销某控制器对某资源的待执行指令（被抢占/失权时），返回受影响行数。 */
    public int cancelPendingByHolder(String controllerId, String resourceName, String reason, long nowMs) {
        return jdbc.update(
                "UPDATE t_command SET status = 'TIMED_OUT', finished_at_ms = ?, reject_reason = ? "
                        + "WHERE status = 'PENDING' AND controller_id = ? AND resource_name = ?",
                nowMs, reason, controllerId, resourceName);
    }

    /** PENDING 指令数量。 */
    public int countPending() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_command WHERE status = 'PENDING'", Integer.class);
        return count == null ? 0 : count;
    }
}
