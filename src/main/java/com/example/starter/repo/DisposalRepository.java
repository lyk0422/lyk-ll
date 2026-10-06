package com.example.starter.repo;

import com.example.starter.domain.CommandType;
import com.example.starter.domain.DisposalCommand;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 联动处置指令数据访问。
 */
@Repository
public class DisposalRepository {

    private final JdbcClient jdbc;

    public DisposalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 下发处置指令并返回主键，初始状态 PENDING。
     */
    public long insert(long zoneId, long alarmId, CommandType type, LocalDateTime createdAt,
                       LocalDateTime confirmDeadline) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO disposal_command (zone_id, alarm_id, command_type, status, created_at,"
                        + " confirm_deadline) VALUES (:zoneId, :alarmId, :type, 'PENDING', :createdAt, :deadline)")
                .param("zoneId", zoneId)
                .param("alarmId", alarmId)
                .param("type", type.name())
                .param("createdAt", createdAt)
                .param("deadline", confirmDeadline)
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public Optional<DisposalCommand> findById(long id) {
        return jdbc.sql("SELECT * FROM disposal_command WHERE id = :id")
                .param("id", id)
                .query(DisposalCommand.class)
                .optional();
    }

    public List<DisposalCommand> findByZone(long zoneId) {
        return jdbc.sql("SELECT * FROM disposal_command WHERE zone_id = :zoneId ORDER BY id")
                .param("zoneId", zoneId)
                .query(DisposalCommand.class)
                .list();
    }

    /**
     * 查询活动状态（未完成）的指令。
     */
    public List<DisposalCommand> findActiveByZone(long zoneId) {
        return jdbc.sql("SELECT * FROM disposal_command WHERE zone_id = :zoneId AND status <> 'COMPLETED'"
                        + " ORDER BY id")
                .param("zoneId", zoneId)
                .query(DisposalCommand.class)
                .list();
    }

    /**
     * 查询恰满或超过回执时限仍未确认的指令。
     */
    public List<DisposalCommand> findTimedOut(LocalDateTime now) {
        return jdbc.sql("SELECT * FROM disposal_command WHERE status = 'PENDING' AND confirm_deadline <= :now"
                        + " ORDER BY id")
                .param("now", now)
                .query(DisposalCommand.class)
                .list();
    }

    /**
     * 回执确认。
     */
    public void confirm(long id, LocalDateTime confirmedAt) {
        jdbc.sql("UPDATE disposal_command SET status = 'CONFIRMED', confirmed_at = :confirmedAt WHERE id = :id")
                .param("id", id)
                .param("confirmedAt", confirmedAt)
                .update();
    }

    /**
     * 超时升级。
     */
    public void escalate(long id, LocalDateTime escalatedAt) {
        jdbc.sql("UPDATE disposal_command SET status = 'ESCALATED', escalated_at = :escalatedAt WHERE id = :id")
                .param("id", id)
                .param("escalatedAt", escalatedAt)
                .update();
    }

    /**
     * 恢复后完成指令。
     */
    public void complete(long id) {
        jdbc.sql("UPDATE disposal_command SET status = 'COMPLETED' WHERE id = :id")
                .param("id", id)
                .update();
    }
}
