package com.example.starter.command;

import com.example.starter.api.dto.CommandResponse;
import com.example.starter.api.dto.CommandResponse;
import com.example.starter.config.CementProperties;
import com.example.starter.core.ApiException;
import com.example.starter.domain.CommandStatus;
import com.example.starter.domain.PlanStatus;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 降掺指令链 JDBC 仓储：触发下发、恰满 30 分钟超时升级（强制保底）、时限内确认。
 */
@Repository
public class JdbcReductionCommandRepository implements ReductionCommandRepository {

    private final JdbcTemplate jdbcTemplate;
    private final CementProperties properties;

    public JdbcReductionCommandRepository(JdbcTemplate jdbcTemplate, CementProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    @Transactional
    public CommandResponse create(Long planId, LocalDateTime hourStart,
                                  java.math.BigDecimal targetHeatRatio, LocalDateTime issuedAt) {
        LocalDateTime deadline = issuedAt.plusMinutes(properties.confirmDeadlineMinutes());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO reduction_command (plan_id, hour_start, status, target_heat_ratio,
                        issued_at, confirm_deadline)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, planId);
            ps.setTimestamp(2, Timestamp.valueOf(hourStart));
            ps.setString(3, CommandStatus.ISSUED.name());
            ps.setBigDecimal(4, targetHeatRatio);
            ps.setTimestamp(5, Timestamp.valueOf(issuedAt));
            ps.setTimestamp(6, Timestamp.valueOf(deadline));
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKeyAs(Long.class);
        return find(planId, id);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int escalateDue(LocalDateTime now) {
        // 恰满确认时限（confirm_deadline <= now）的已下发指令全部升级；行锁避免与确认竞争。
        List<Long> dueIds = jdbcTemplate.queryForList("""
                SELECT id FROM reduction_command
                 WHERE status = ? AND confirm_deadline <= ?
                 ORDER BY id
                 FOR UPDATE
                """, Long.class, CommandStatus.ISSUED.name(), Timestamp.valueOf(now));
        for (Long commandId : dueIds) {
            jdbcTemplate.update("""
                    UPDATE reduction_command SET status = ?, escalated_at = ? WHERE id = ?
                    """, CommandStatus.ESCALATED.name(), Timestamp.valueOf(now), commandId);
            // 升级同时强制降掺至方案保底比例（仅执行中方案生效）。
            jdbcTemplate.update("""
                    UPDATE co_firing_plan p
                       SET p.current_heat_ratio = p.floor_heat_ratio
                     WHERE p.id = (SELECT c.plan_id FROM reduction_command c WHERE c.id = ?)
                       AND p.status = ?
                    """, commandId, PlanStatus.EXECUTING.name());
        }
        return dueIds.size();
    }

    @Override
    public CommandResponse find(Long planId, Long commandId) {
        List<CommandResponse> commands = jdbcTemplate.query(
                "SELECT * FROM reduction_command WHERE id = ? AND plan_id = ?",
                CommandRowMapper.INSTANCE, commandId, planId);
        if (commands.isEmpty()) {
            throw ApiException.notFound("COMMAND_NOT_FOUND",
                    "降掺指令不存在：planId=" + planId + ", commandId=" + commandId);
        }
        return commands.get(0);
    }

    @Override
    public List<CommandResponse> findByPlan(Long planId) {
        return jdbcTemplate.query(
                "SELECT * FROM reduction_command WHERE plan_id = ? ORDER BY id",
                CommandRowMapper.INSTANCE, planId);
    }

    @Override
    @Transactional
    public CommandResponse confirm(Long planId, Long commandId, LocalDateTime confirmedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE reduction_command SET status = ?, confirmed_at = ?
                 WHERE id = ? AND plan_id = ? AND status = ?
                """, CommandStatus.CONFIRMED.name(), Timestamp.valueOf(confirmedAt),
                commandId, planId, CommandStatus.ISSUED.name());
        if (updated == 0) {
            CommandResponse current = find(planId, commandId);
            if (CommandStatus.ESCALATED.name().equals(current.status())) {
                throw ApiException.conflict("COMMAND_ALREADY_ESCALATED",
                        "指令已超时升级并强制降掺至保底比例，不可再确认：commandId=" + commandId);
            }
            throw ApiException.conflict("COMMAND_ALREADY_CONFIRMED",
                    "指令已执行确认，请勿重复受理：commandId=" + commandId);
        }
        return find(planId, commandId);
    }
}
