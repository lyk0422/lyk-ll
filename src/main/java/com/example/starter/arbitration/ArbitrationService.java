package com.example.starter.arbitration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.arbitration.api.ApiException;
import com.example.starter.arbitration.dto.ClaimRequest;
import com.example.starter.arbitration.dto.ClaimResponse;
import com.example.starter.arbitration.dto.CommandRequest;
import com.example.starter.arbitration.dto.CommandResponse;
import com.example.starter.arbitration.dto.ReleaseRequest;
import com.example.starter.arbitration.dto.RenewRequest;
import com.example.starter.arbitration.dto.ResourceView;
import com.example.starter.arbitration.dto.SafeStateEventView;

/**
 * 指令仲裁与安全降级核心服务。
 *
 * <p>仲裁规则：任一资源同一时刻只允许一个 ACTIVE 持有者；新申请优先级严格大于当前持有者时抢占，
 * 否则返回 409；租约申请/续期/释放均幂等，requestId 异参重放返回 409。
 *
 * <p>并发控制：对 resource 行使用 SELECT ... FOR UPDATE 串行化同一资源的仲裁操作；
 * 安全降级通过 safe_applied 条件更新与 (resource_id, claim_id) 唯一约束保证同一租约只降级一次。
 */
@Service
public class ArbitrationService {

    private static final String STATE_ACTIVE = "ACTIVE";
    private static final String STATE_RELEASED = "RELEASED";
    private static final String STATE_PREEMPTED = "PREEMPTED";
    private static final String STATE_EXPIRED = "EXPIRED";
    private static final String STATE_ESTOPPED = "ESTOPPED";

    private static final String CMD_QUEUED = "QUEUED";
    private static final String CMD_DROPPED = "DROPPED";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ArbitrationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private record ResourceRow(String resourceId, double minPosition, double maxPosition,
                               double maxVelocity, double maxAcceleration, long commandTtlMs,
                               String safeMode, Double presetPosition,
                               Long holderClaimId, String holderControllerId, Integer holderPriority,
                               boolean safeApplied, Long lastCommandSeq, Long lastCommandTs) {
    }

    private static final RowMapper<ResourceRow> RESOURCE_MAPPER = (rs, n) -> new ResourceRow(
            rs.getString("resource_id"),
            rs.getDouble("min_position"),
            rs.getDouble("max_position"),
            rs.getDouble("max_velocity"),
            rs.getDouble("max_acceleration"),
            rs.getLong("command_ttl_ms"),
            rs.getString("safe_mode"),
            rs.getObject("preset_position", Double.class),
            rs.getObject("holder_claim_id", Long.class),
            rs.getString("holder_controller_id"),
            rs.getObject("holder_priority", Integer.class),
            rs.getBoolean("safe_applied"),
            rs.getObject("last_command_seq", Long.class),
            rs.getObject("last_command_ts", Long.class));

    private long now() {
        return clock.millis();
    }

    private boolean estopActive() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT active FROM estop_state WHERE id = 1", Boolean.class));
    }

    private ResourceRow lockResource(String resourceId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM resource WHERE resource_id = ? FOR UPDATE",
                    RESOURCE_MAPPER, resourceId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "资源不存在: " + resourceId);
        }
    }

    /**
     * 申请资源租约：空闲直接授予；优先级严格大于当前持有者时抢占；否则 409。
     * 同 requestId 同参重放返回原租约当前状态，异参重放返回 409。
     */
    @Transactional
    public ClaimResponse claim(ClaimRequest req) {
        long now = now();
        ClaimResponse replay = replayedResponse(req);
        if (replay != null) {
            return replay;
        }

        if (estopActive()) {
            throw ApiException.estopActive("紧急停止生效中，拒绝普通申请");
        }

        ResourceRow res = lockResource(req.resourceId());
        // 资源行锁内二次检查：并发下同 requestId 可能在本事务加锁期间由其他事务提交
        ClaimResponse replayUnderLock = replayedResponse(req);
        if (replayUnderLock != null) {
            return replayUnderLock;
        }
        if (res.holderClaimId() == null) {
            return grant(req, res, now);
        }
        if (req.priority() > res.holderPriority()) {
            jdbc.update("UPDATE claim SET state = ?, updated_at = ? WHERE claim_id = ? AND state = ?",
                    STATE_PREEMPTED, now, res.holderClaimId(), STATE_ACTIVE);
            return grant(req, res, now);
        }
        throw ApiException.conflict("RESOURCE_BUSY",
                "资源已被持有且优先级不足: holder=" + res.holderControllerId());
    }

    /**
     * 按 requestId 查找既有申请：不存在返回 null；同参数返回幂等重放结果；异参数抛出 409。
     */
    private ClaimResponse replayedResponse(ClaimRequest req) {
        List<Object[]> rows = jdbc.query(
                "SELECT claim_id, controller_id, resource_id, priority, lease_ms, state, lease_expires_at "
                        + "FROM claim WHERE request_id = ?",
                (rs, n) -> new Object[]{rs.getLong("claim_id"), rs.getString("controller_id"),
                        rs.getString("resource_id"), rs.getInt("priority"), rs.getLong("lease_ms"),
                        rs.getString("state"), rs.getLong("lease_expires_at")},
                req.requestId());
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.get(0);
        boolean sameParams = row[1].equals(req.controllerId())
                && row[2].equals(req.resourceId())
                && row[3].equals(req.priority())
                && row[4].equals(req.leaseMs());
        if (!sameParams) {
            throw ApiException.conflict("REPLAY_CONFLICT",
                    "requestId 已存在且参数不一致: " + req.requestId());
        }
        return new ClaimResponse((Long) row[0], (String) row[5], (Long) row[6], true);
    }

    private ClaimResponse grant(ClaimRequest req, ResourceRow res, long now) {
        long expiresAt = now + req.leaseMs();
        try {
            jdbc.update("INSERT INTO claim (request_id, controller_id, resource_id, priority, lease_ms, "
                            + "state, lease_expires_at, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)",
                    req.requestId(), req.controllerId(), req.resourceId(), req.priority(), req.leaseMs(),
                    STATE_ACTIVE, expiresAt, now, now);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 已被其他事务写入：同参数按幂等重放处理，异参数返回 409
            ClaimResponse replay = replayedResponse(req);
            if (replay == null) {
                throw ApiException.conflict("REPLAY_CONFLICT",
                        "requestId 并发冲突: " + req.requestId());
            }
            return replay;
        }
        Long claimId = jdbc.queryForObject(
                "SELECT claim_id FROM claim WHERE request_id = ?", Long.class, req.requestId());
        // 新持有周期开始：重置降级标志，允许下一次到期重新执行安全降级
        jdbc.update("UPDATE resource SET holder_claim_id = ?, holder_controller_id = ?, "
                        + "holder_priority = ?, safe_applied = FALSE WHERE resource_id = ?",
                claimId, req.controllerId(), req.priority(), req.resourceId());
        return new ClaimResponse(claimId, STATE_ACTIVE, expiresAt, false);
    }

    /**
     * 租约续期：仅当前持有者可续；新到期时刻不晚于当前到期时刻时为幂等无操作。
     */
    @Transactional
    public ClaimResponse renew(RenewRequest req) {
        long now = now();
        ResourceRow res = lockResource(req.resourceId());
        if (res.holderClaimId() == null || !res.holderControllerId().equals(req.controllerId())) {
            throw ApiException.conflict("NOT_HOLDER", "不是资源当前持有者: " + req.controllerId());
        }
        Long currentExpiry = jdbc.queryForObject(
                "SELECT lease_expires_at FROM claim WHERE claim_id = ?", Long.class, res.holderClaimId());
        long newExpiry = now + req.leaseMs();
        if (newExpiry <= currentExpiry) {
            return new ClaimResponse(res.holderClaimId(), STATE_ACTIVE, currentExpiry, true);
        }
        jdbc.update("UPDATE claim SET lease_expires_at = ?, updated_at = ? WHERE claim_id = ?",
                newExpiry, now, res.holderClaimId());
        return new ClaimResponse(res.holderClaimId(), STATE_ACTIVE, newExpiry, false);
    }

    /**
     * 释放租约：幂等，重复释放成功；非持有者释放返回 409。
     */
    @Transactional
    public void release(ReleaseRequest req) {
        long now = now();
        ResourceRow res = lockResource(req.resourceId());
        if (res.holderClaimId() == null) {
            return;
        }
        if (!res.holderControllerId().equals(req.controllerId())) {
            throw ApiException.conflict("NOT_HOLDER", "不是资源当前持有者: " + req.controllerId());
        }
        jdbc.update("UPDATE claim SET state = ?, updated_at = ? WHERE claim_id = ? AND state = ?",
                STATE_RELEASED, now, res.holderClaimId(), STATE_ACTIVE);
        jdbc.update("UPDATE resource SET holder_claim_id = NULL, holder_controller_id = NULL, "
                + "holder_priority = NULL WHERE resource_id = ?", req.resourceId());
    }

    /**
     * 提交运动指令：校验持有者、租约未到期、指令未过期、时戳单调（回拨可区分）、
     * 序列号递增、位置边界、限速与限加速度；全部通过才进入待执行队列。
     */
    @Transactional
    public CommandResponse submit(CommandRequest req) {
        long now = now();
        if (estopActive()) {
            throw ApiException.estopActive("紧急停止生效中，拒绝指令提交");
        }
        ResourceRow res = lockResource(req.resourceId());
        if (res.holderClaimId() == null || !res.holderControllerId().equals(req.controllerId())) {
            throw ApiException.conflict("NOT_HOLDER", "不是资源当前持有者: " + req.controllerId());
        }
        Long leaseExpiresAt = jdbc.queryForObject(
                "SELECT lease_expires_at FROM claim WHERE claim_id = ?", Long.class, res.holderClaimId());
        if (now > leaseExpiresAt) {
            throw ApiException.conflict("LEASE_EXPIRED", "租约已到期，请先续期");
        }
        validateCommand(req, res, now);
        try {
            jdbc.update("INSERT INTO command (resource_id, controller_id, seq, ts, position, velocity, "
                            + "acceleration, status, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    req.resourceId(), req.controllerId(), req.seq(), req.ts(), req.position(),
                    req.velocity(), req.acceleration(), CMD_QUEUED, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SEQ_CONFLICT", "序列号并发冲突: " + req.seq());
        }
        Long commandId = jdbc.queryForObject(
                "SELECT command_id FROM command WHERE resource_id = ? AND controller_id = ? AND seq = ?",
                Long.class, req.resourceId(), req.controllerId(), req.seq());
        jdbc.update("UPDATE resource SET last_command_seq = ?, last_command_ts = ? WHERE resource_id = ?",
                req.seq(), req.ts(), req.resourceId());
        return new CommandResponse(commandId, CMD_QUEUED);
    }

    private void validateCommand(CommandRequest req, ResourceRow res, long now) {
        if (Double.isNaN(req.position()) || Double.isNaN(req.velocity()) || Double.isNaN(req.acceleration())) {
            throw ApiException.badRequest("INVALID_VALUE", "位置/速度/加速度不允许为 NaN");
        }
        if (req.ts() < now - res.commandTtlMs()) {
            throw ApiException.badRequest("COMMAND_EXPIRED", "指令已过期，不得进入执行队列");
        }
        if (res.lastCommandTs() != null && req.ts() < res.lastCommandTs()) {
            throw ApiException.badRequest("CLOCK_ROLLBACK",
                    "检测到时钟回拨: ts=" + req.ts() + " < last=" + res.lastCommandTs());
        }
        if (res.lastCommandSeq() != null && req.seq() <= res.lastCommandSeq()) {
            throw ApiException.badRequest("SEQ_NOT_MONOTONIC", "序列号必须严格递增");
        }
        if (req.position() < res.minPosition() || req.position() > res.maxPosition()) {
            throw ApiException.badRequest("POSITION_OUT_OF_BOUNDS", "目标位置超出边界");
        }
        if (Math.abs(req.velocity()) > res.maxVelocity()) {
            throw ApiException.badRequest("VELOCITY_LIMIT_EXCEEDED", "速度超过限速");
        }
        if (Math.abs(req.acceleration()) > res.maxAcceleration()) {
            throw ApiException.badRequest("ACCELERATION_LIMIT_EXCEEDED", "加速度超过限值");
        }
    }

    /**
     * 紧急停止：立即撤销全部写权限、清空待执行指令；恢复前拒绝普通申请与指令。
     */
    @Transactional
    public void estop() {
        long now = now();
        jdbc.update("UPDATE estop_state SET active = TRUE, updated_at = ? WHERE id = 1", now);
        jdbc.update("UPDATE claim SET state = ?, updated_at = ? WHERE state = ?",
                STATE_ESTOPPED, now, STATE_ACTIVE);
        jdbc.update("UPDATE resource SET holder_claim_id = NULL, holder_controller_id = NULL, "
                + "holder_priority = NULL");
        jdbc.update("UPDATE command SET status = ? WHERE status = ?", CMD_DROPPED, CMD_QUEUED);
    }

    /**
     * 显式恢复：解除紧急停止，此后可重新申请资源。
     */
    @Transactional
    public void resume() {
        jdbc.update("UPDATE estop_state SET active = FALSE, updated_at = ? WHERE id = 1", now());
    }

    /**
     * 到期检测与安全降级：租约到期（含控制器失联无法续期）的资源回收写权限，
     * 丢弃超时待执行指令，并按资源配置一次性进入安全状态；重复检测不重复执行降级动作。
     *
     * @return 本次新执行的安全降级事件
     */
    @Transactional
    public List<SafeStateEventView> degradeExpired() {
        long now = now();
        List<Object[]> expired = jdbc.query(
                "SELECT r.resource_id FROM resource r JOIN claim c ON r.holder_claim_id = c.claim_id "
                        + "WHERE c.lease_expires_at < ?",
                (rs, n) -> new Object[]{rs.getString("resource_id")}, now);
        // 丢弃超时待执行指令（指令时戳早于 now - 资源指令有效期）
        jdbc.update("UPDATE command cmd SET status = ? WHERE status = ? AND EXISTS ("
                        + "SELECT 1 FROM resource r WHERE r.resource_id = cmd.resource_id "
                        + "AND cmd.ts < ? - r.command_ttl_ms)",
                CMD_DROPPED, CMD_QUEUED, now);
        List<SafeStateEventView> events = new java.util.ArrayList<>();
        for (Object[] row : expired) {
            String resourceId = (String) row[0];
            ResourceRow res = lockResource(resourceId);
            Long claimId = res.holderClaimId();
            jdbc.update("UPDATE claim SET state = ?, updated_at = ? WHERE claim_id = ? AND state = ?",
                    STATE_EXPIRED, now, claimId, STATE_ACTIVE);
            jdbc.update("UPDATE resource SET holder_claim_id = NULL, holder_controller_id = NULL, "
                    + "holder_priority = NULL WHERE resource_id = ?", resourceId);
            // 一次性降级：仅当本周期尚未执行过降级时才执行
            int applied = jdbc.update("UPDATE resource SET safe_applied = TRUE "
                    + "WHERE resource_id = ? AND safe_applied = FALSE", resourceId);
            if (applied == 1) {
                events.add(applySafeState(res, claimId, now));
            }
        }
        return events;
    }

    private SafeStateEventView applySafeState(ResourceRow res, long claimId, long now) {
        Double position = null;
        Double velocity = null;
        switch (res.safeMode()) {
            case "HOLD" -> position = lastAcceptedPosition(res.resourceId());
            case "ZERO_VELOCITY" -> velocity = 0.0;
            case "PRESET_POSITION" -> {
                position = res.presetPosition();
                velocity = 0.0;
            }
            default -> throw ApiException.badRequest("INVALID_SAFE_MODE", "未知安全模式: " + res.safeMode());
        }
        jdbc.update("INSERT INTO safe_state_event (resource_id, claim_id, trigger_reason, "
                        + "applied_position, applied_velocity, applied_at) VALUES (?,?,?,?,?,?)",
                res.resourceId(), claimId, "LEASE_EXPIRED", position, velocity, now);
        return new SafeStateEventView(res.resourceId(), claimId, "LEASE_EXPIRED",
                position, velocity, now);
    }

    private Double lastAcceptedPosition(String resourceId) {
        List<Double> positions = jdbc.query(
                "SELECT position FROM command WHERE resource_id = ? ORDER BY ts DESC, seq DESC LIMIT 1",
                (rs, n) -> rs.getDouble("position"), resourceId);
        return positions.isEmpty() ? null : positions.get(0);
    }

    /**
     * 查询资源当前状态。
     */
    @Transactional(readOnly = true)
    public ResourceView resourceView(String resourceId) {
        ResourceRow res;
        try {
            res = jdbc.queryForObject("SELECT * FROM resource WHERE resource_id = ?",
                    RESOURCE_MAPPER, resourceId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "资源不存在: " + resourceId);
        }
        return new ResourceView(res.resourceId(), res.holderControllerId(), res.holderPriority(),
                res.safeMode(), res.safeApplied(), res.lastCommandSeq(), res.lastCommandTs());
    }

    /**
     * 查询全部安全降级事件（按执行时刻升序）。
     */
    @Transactional(readOnly = true)
    public List<SafeStateEventView> safeEvents() {
        return jdbc.query("SELECT * FROM safe_state_event ORDER BY applied_at, event_id",
                (rs, n) -> mapSafeEvent(rs));
    }

    private static SafeStateEventView mapSafeEvent(ResultSet rs) throws SQLException {
        return new SafeStateEventView(
                rs.getString("resource_id"),
                rs.getLong("claim_id"),
                rs.getString("trigger_reason"),
                rs.getObject("applied_position", Double.class),
                rs.getObject("applied_velocity", Double.class),
                rs.getLong("applied_at"));
    }
}
