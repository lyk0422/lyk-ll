package com.example.starter.ventilation.repo;

import com.example.starter.ventilation.ModeStatus;
import com.example.starter.ventilation.domain.ModeSessionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 火灾模式会话仓储，并负责分区激活互斥锁与共享风阀目标状态占用。
 */
@Repository
public class ModeSessionRepository {

    private static final RowMapper<ModeSessionRow> MAPPER = (rs, n) -> {
        ModeSessionRow row = new ModeSessionRow();
        row.setId(rs.getLong("id"));
        row.setZoneCode(rs.getString("zone_code"));
        row.setActivateRequestId(rs.getString("activate_request_id"));
        row.setSmokeDirection(rs.getString("smoke_direction"));
        row.setTargetCondition(rs.getString("target_condition"));
        row.setStatus(ModeStatus.valueOf(rs.getString("status")));
        row.setFault(rs.getInt("fault_flag") == 1);
        row.setActivatedAt(rs.getLong("activated_at"));
        long resetAt = rs.getLong("reset_completed_at");
        row.setResetCompletedAt(rs.wasNull() ? null : resetAt);
        long archivedAt = rs.getLong("archived_at");
        row.setArchivedAt(rs.wasNull() ? null : archivedAt);
        return row;
    };

    private final JdbcTemplate jdbc;

    public ModeSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入会话，回填自增主键。 */
    public ModeSessionRow insert(String zoneCode, String activateRequestId, String smokeDirection,
                                 String targetCondition, long activatedAt) {
        org.springframework.jdbc.core.PreparedStatementCreator creator = con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO mode_session (zone_code, activate_request_id, smoke_direction, "
                            + "target_condition, status, fault_flag, activated_at) "
                            + "VALUES (?,?,?,?,'ACTIVE',0,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, zoneCode);
            ps.setString(2, activateRequestId);
            ps.setString(3, smokeDirection);
            ps.setString(4, targetCondition);
            ps.setLong(5, activatedAt);
            return ps;
        };
        org.springframework.jdbc.support.KeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(creator, keyHolder);
        Number key = keyHolder.getKey();
        return findById(key == null ? -1L : key.longValue());
    }

    /** 按主键查会话。 */
    public ModeSessionRow findById(long sessionId) {
        List<ModeSessionRow> list = jdbc.query(
                "SELECT id, zone_code, activate_request_id, smoke_direction, target_condition, "
                        + "status, fault_flag, activated_at, reset_completed_at, archived_at "
                        + "FROM mode_session WHERE id = ?", MAPPER, sessionId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按激活 requestId 查会话。 */
    public ModeSessionRow findByActivateRequest(String activateRequestId) {
        List<ModeSessionRow> list = jdbc.query(
                "SELECT id, zone_code, activate_request_id, smoke_direction, target_condition, "
                        + "status, fault_flag, activated_at, reset_completed_at, archived_at "
                        + "FROM mode_session WHERE activate_request_id = ?", MAPPER, activateRequestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询分区最近一次会话（按 id 倒序取首条）。 */
    public ModeSessionRow findLatestByZone(String zoneCode) {
        List<ModeSessionRow> list = jdbc.query(
                "SELECT id, zone_code, activate_request_id, smoke_direction, target_condition, "
                        + "status, fault_flag, activated_at, reset_completed_at, archived_at "
                        + "FROM mode_session WHERE zone_code = ? ORDER BY id DESC LIMIT 1",
                MAPPER, zoneCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 更新会话状态。 */
    public void updateStatus(long sessionId, ModeStatus status) {
        jdbc.update("UPDATE mode_session SET status = ? WHERE id = ?", status.name(), sessionId);
    }

    /** 标记复位完成时间与状态。 */
    public void markResetComplete(long sessionId, long completedAt) {
        jdbc.update(
                "UPDATE mode_session SET status = 'RESET_COMPLETE', reset_completed_at = ? WHERE id = ?",
                completedAt, sessionId);
    }

    /** 归档终态。 */
    public void markArchived(long sessionId, long archivedAt) {
        jdbc.update("UPDATE mode_session SET status = 'ARCHIVED', archived_at = ? WHERE id = ?",
                archivedAt, sessionId);
    }

    /** 标记会话存在设备故障。 */
    public void markFault(long sessionId) {
        jdbc.update("UPDATE mode_session SET fault_flag = 1 WHERE id = ?", sessionId);
    }

    /**
     * 抢占分区激活锁：依赖主键唯一约束，并发调用只有一个成功。
     *
     * @return true 获锁成功；false 该分区已有激活/复位中的模式
     */
    public boolean tryAcquireZoneLock(String zoneCode, long sessionId, long now) {
        try {
            jdbc.update(
                    "INSERT INTO zone_activation_lock (zone_code, session_id, locked_at) VALUES (?,?,?)",
                    zoneCode, sessionId, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 释放分区激活锁（复位完成或归档后允许该分区重新激活）。 */
    public void releaseZoneLock(String zoneCode) {
        jdbc.update("DELETE FROM zone_activation_lock WHERE zone_code = ?", zoneCode);
    }

    /** 会话落库后，把预占分区锁上的占位 session_id 更新为真实会话 id。 */
    public void updateLockSession(String zoneCode, long sessionId) {
        jdbc.update("UPDATE zone_activation_lock SET session_id = ? WHERE zone_code = ?",
                sessionId, zoneCode);
    }

    /**
     * 尝试占用共享风阀目标状态：同一风阀只允许一个目标动作。
     *
     * @return true 占用成功（含首次）；false 已被其他会话以相同或不同目标占用
     */
    public boolean tryCommitSharedDamper(String damperCode, String targetAction, long sessionId,
                                         String zoneCode, long now) {
        try {
            jdbc.update(
                    "INSERT INTO shared_damper_commitment (damper_code, target_action, session_id, "
                            + "zone_code, committed_at) VALUES (?,?,?,?,?)",
                    damperCode, targetAction, sessionId, zoneCode, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 查询共享风阀当前占用，未占用返回 null。 */
    public SharedCommitment findSharedCommitment(String damperCode) {
        List<SharedCommitment> list = jdbc.query(
                "SELECT damper_code, target_action, session_id, zone_code, committed_at "
                        + "FROM shared_damper_commitment WHERE damper_code = ?",
                (rs, n) -> new SharedCommitment(
                        rs.getString("damper_code"),
                        rs.getString("target_action"),
                        rs.getLong("session_id"),
                        rs.getString("zone_code"),
                        rs.getLong("committed_at")),
                damperCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 释放本会话占用的全部共享风阀（归档时调用，供后续火灾模式重新编排）。 */
    public void releaseSharedDampers(long sessionId) {
        jdbc.update("DELETE FROM shared_damper_commitment WHERE session_id = ?", sessionId);
    }

    /**
     * 共享风阀占用记录。
     *
     * @param damperCode   风阀编码
     * @param targetAction 先激活分区要求的目标动作 OPEN/CLOSE
     * @param sessionId    先占用的会话 id
     * @param zoneCode     先占用的分区编码
     * @param committedAt  占用时间 epoch 毫秒
     */
    public record SharedCommitment(String damperCode, String targetAction, long sessionId,
                                   String zoneCode, long committedAt) {
    }
}
