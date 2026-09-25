package com.example.starter.ventilation.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 通风联动数据访问层：全部 SQL 参数化，时间列使用毫秒精度 DATETIME。
 */
@Repository
public class VentilationRepository {

    private final JdbcTemplate jdbc;

    public VentilationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 配置查询 ----------------

    /** 预案设备行（合成配置）。 */
    public record ZoneDeviceRow(
            String deviceCode,
            String deviceType,
            int stepOrder,
            String purgeTarget,
            String resetTarget) {
    }

    public boolean zoneExists(String zoneCode) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM fire_zone WHERE zone_code = ?",
                Integer.class, zoneCode);
        return cnt != null && cnt > 0;
    }

    /** 设备是否为跨分区共享设备（共享风阀）。 */
    public boolean isSharedDevice(String deviceCode) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM device WHERE device_code = ? AND shared_flag = 1",
                Integer.class, deviceCode);
        return cnt != null && cnt > 0;
    }

    /**
     * 查询其他仍 ACTIVE 的模式对某设备在指定阶段已实际占用（未跳过）的指令，
     * 用于共享风阀“先激活分区目标状态为准”的冲突判定。
     */
    public List<CommandRow> listEffectiveActiveCommands(String deviceCode, String phase,
                                                        long excludeModeId) {
        return jdbc.query("""
                        SELECT dc.* FROM device_command dc
                        JOIN fire_mode fm ON fm.id = dc.mode_id
                        WHERE dc.device_code = ? AND dc.phase = ?
                          AND fm.status = 'ACTIVE' AND dc.mode_id <> ?
                          AND dc.status <> 'SKIPPED'
                        ORDER BY dc.mode_id, dc.step_order
                        """,
                this::mapCommand, deviceCode, phase, excludeModeId);
    }

    public List<ZoneDeviceRow> listZoneDevices(String zoneCode) {
        return jdbc.query("""
                        SELECT zd.device_code, d.device_type, zd.step_order,
                               zd.purge_target, zd.reset_target
                        FROM zone_device zd
                        JOIN device d ON d.device_code = zd.device_code
                        WHERE zd.zone_code = ?
                        ORDER BY zd.step_order, zd.device_code
                        """,
                (rs, n) -> new ZoneDeviceRow(
                        rs.getString("device_code"),
                        rs.getString("device_type"),
                        rs.getInt("step_order"),
                        rs.getString("purge_target"),
                        rs.getString("reset_target")),
                zoneCode);
    }

    // ---------------- 火灾模式 ----------------

    public long insertFireMode(String requestId, String zoneCode, String smokeDirection,
                               String targetCondition, String fingerprint,
                               String status, LocalDateTime now) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                            INSERT INTO fire_mode
                            (request_id, zone_code, smoke_flow_direction, target_condition,
                             fingerprint, status, conflict_detail, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, zoneCode);
            ps.setString(3, smokeDirection);
            ps.setString(4, targetCondition);
            ps.setString(5, fingerprint);
            ps.setString(6, status);
            ps.setObject(7, now);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<FireModeRow> findFireModeByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM fire_mode WHERE request_id = ?",
                this::mapFireMode, requestId).stream().findFirst();
    }

    public Optional<FireModeRow> findFireModeById(long modeId) {
        return jdbc.query("SELECT * FROM fire_mode WHERE id = ?",
                this::mapFireMode, modeId).stream().findFirst();
    }

    public List<FireModeRow> listActiveModesByDevice(String deviceCode) {
        return jdbc.query("""
                        SELECT fm.* FROM fire_mode fm
                        JOIN device_command dc ON dc.mode_id = fm.id
                        WHERE dc.device_code = ? AND fm.status = 'ACTIVE'
                        """,
                this::mapFireMode, deviceCode);
    }

    public void updateModeStatus(long modeId, String status) {
        jdbc.update("UPDATE fire_mode SET status = ? WHERE id = ?", status, modeId);
    }

    public void updateModeConflictDetail(long modeId, String detail) {
        jdbc.update("UPDATE fire_mode SET conflict_detail = ? WHERE id = ?", detail, modeId);
    }

    public void markResetStarted(long modeId, LocalDateTime at) {
        jdbc.update("UPDATE fire_mode SET status = 'RESETTING', reset_started_at = ? WHERE id = ?",
                at, modeId);
    }

    public void markResetCompleted(long modeId, LocalDateTime at) {
        jdbc.update("UPDATE fire_mode SET status = 'RESET_COMPLETE', reset_completed_at = ? WHERE id = ?",
                at, modeId);
    }

    public void markArchived(long modeId, LocalDateTime at) {
        jdbc.update("UPDATE fire_mode SET status = 'ARCHIVED', archived_at = ? WHERE id = ?",
                at, modeId);
    }

    private FireModeRow mapFireMode(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new FireModeRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("zone_code"),
                rs.getString("smoke_flow_direction"),
                rs.getString("target_condition"),
                rs.getString("fingerprint"),
                rs.getString("status"),
                rs.getString("conflict_detail"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("reset_started_at", LocalDateTime.class),
                rs.getObject("reset_completed_at", LocalDateTime.class),
                rs.getObject("archived_at", LocalDateTime.class));
    }

    // ---------------- 分区激活锁 ----------------

    /**
     * 尝试获取分区激活锁；依靠主键唯一约束，并发时只有一个插入成功。
     *
     * @return 是否抢锁成功
     */
    public boolean tryAcquireZoneLock(String zoneCode, long modeId, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO active_zone_lock (zone_code, mode_id, locked_at) VALUES (?, ?, ?)",
                    zoneCode, modeId, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void releaseZoneLock(String zoneCode) {
        jdbc.update("DELETE FROM active_zone_lock WHERE zone_code = ?", zoneCode);
    }

    public boolean zoneLocked(String zoneCode) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM active_zone_lock WHERE zone_code = ?",
                Integer.class, zoneCode);
        return cnt != null && cnt > 0;
    }

    // ---------------- 设备指令 ----------------

    public long insertCommand(String commandUid, long modeId, String zoneCode, String deviceCode,
                              String deviceType, String targetState, String phase, int stepOrder,
                              String status, LocalDateTime now) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                            INSERT INTO device_command
                            (command_uid, mode_id, zone_code, device_code, device_type,
                             target_state, phase, step_order, attempt, status, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, commandUid);
            ps.setLong(2, modeId);
            ps.setString(3, zoneCode);
            ps.setString(4, deviceCode);
            ps.setString(5, deviceType);
            ps.setString(6, targetState);
            ps.setString(7, phase);
            ps.setInt(8, stepOrder);
            ps.setString(9, status);
            ps.setObject(10, now);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<CommandRow> listCommandsByMode(long modeId) {
        return jdbc.query("SELECT * FROM device_command WHERE mode_id = ? ORDER BY phase, step_order, id",
                this::mapCommand, modeId);
    }

    public Optional<CommandRow> findCommandByUid(String commandUid) {
        return jdbc.query("SELECT * FROM device_command WHERE command_uid = ?",
                this::mapCommand, commandUid).stream().findFirst();
    }

    /** 已下发且未收到到位结论（含待超时判定）的指令。 */
    public List<CommandRow> listIssuedCommands() {
        return jdbc.query("SELECT * FROM device_command WHERE status = 'ISSUED' ORDER BY issued_at, id",
                this::mapCommand);
    }

    /** 已生成但尚未下发的指令（等待前序设备到位）。 */
    public List<CommandRow> listPendingCommandsByMode(long modeId) {
        return jdbc.query(
                "SELECT * FROM device_command WHERE mode_id = ? AND status = 'PENDING' ORDER BY step_order, id",
                this::mapCommand, modeId);
    }

    public void markCommandIssued(long commandId, int attempt, LocalDateTime issuedAt) {
        jdbc.update("UPDATE device_command SET status = 'ISSUED', attempt = ?, issued_at = ? WHERE id = ?",
                attempt, issuedAt, commandId);
    }

    /**
     * 条件重发：仅当指令仍 ISSUED 且 attempt 与预期一致时生效，
     * 防止扫描并发导致同一指令被重发两次。
     *
     * @return 受影响行数；0 表示已被其他处理抢先改变
     */
    public int resendCommandIfAttempt(long commandId, int expectedAttempt,
                                      int newAttempt, LocalDateTime issuedAt) {
        return jdbc.update("""
                        UPDATE device_command
                        SET status = 'ISSUED', attempt = ?, issued_at = ?
                        WHERE id = ? AND status = 'ISSUED' AND attempt = ?
                        """,
                newAttempt, issuedAt, commandId, expectedAttempt);
    }

    /** 条件置故障：仅仍 ISSUED 且 attempt 与预期一致时生效。 */
    public int failCommandIfAttempt(long commandId, int expectedAttempt,
                                    LocalDateTime failedAt, String reason) {
        return jdbc.update("""
                        UPDATE device_command
                        SET status = 'FAILED', failed_at = ?, blocked_reason = ?
                        WHERE id = ? AND status = 'ISSUED' AND attempt = ?
                        """,
                failedAt, reason, commandId, expectedAttempt);
    }

    public void markCommandInPosition(long commandId, LocalDateTime ackAt) {
        jdbc.update("UPDATE device_command SET status = 'IN_POSITION', ack_at = ? WHERE id = ?",
                ackAt, commandId);
    }

    public void markCommandFailed(long commandId, LocalDateTime failedAt, String reason) {
        jdbc.update("UPDATE device_command SET status = 'FAILED', failed_at = ?, blocked_reason = ? WHERE id = ?",
                failedAt, reason, commandId);
    }

    public void markCommandSkipped(long commandId, String reason) {
        jdbc.update("UPDATE device_command SET status = 'SKIPPED', blocked_reason = ? WHERE id = ?",
                reason, commandId);
    }

    private CommandRow mapCommand(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new CommandRow(
                rs.getLong("id"),
                rs.getString("command_uid"),
                rs.getLong("mode_id"),
                rs.getString("zone_code"),
                rs.getString("device_code"),
                rs.getString("device_type"),
                rs.getString("target_state"),
                rs.getString("phase"),
                rs.getInt("step_order"),
                rs.getInt("attempt"),
                rs.getString("status"),
                rs.getString("blocked_reason"),
                rs.getObject("issued_at", LocalDateTime.class),
                rs.getObject("ack_at", LocalDateTime.class),
                rs.getObject("failed_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class));
    }

    // ---------------- 设备反馈 ----------------

    public void insertFeedback(String commandUid, Long modeId, String deviceCode, boolean inPosition,
                               String observedState, boolean late, String detail, LocalDateTime receivedAt) {
        jdbc.update("""
                        INSERT INTO device_feedback
                        (command_uid, mode_id, device_code, in_position, observed_state,
                         late_flag, detail, received_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                commandUid, modeId, deviceCode, inPosition ? 1 : 0, observedState,
                late ? 1 : 0, detail, receivedAt);
    }

    // ---------------- 事件流 ----------------

    public void insertEvent(Long modeId, String zoneCode, String requestId,
                            String eventType, String detail, LocalDateTime at) {
        jdbc.update("""
                        INSERT INTO event_log (mode_id, zone_code, request_id, event_type, detail, event_time)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                modeId, zoneCode, requestId, eventType, detail, at);
    }

    public List<EventRow> listEventsByMode(long modeId) {
        return jdbc.query("SELECT * FROM event_log WHERE mode_id = ? ORDER BY id",
                this::mapEvent, modeId);
    }

    public List<EventRow> listEventsByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM event_log WHERE request_id = ? ORDER BY id",
                this::mapEvent, requestId);
    }

    public List<EventRow> listAllEvents() {
        return jdbc.query("SELECT * FROM event_log ORDER BY id", this::mapEvent);
    }

    private EventRow mapEvent(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long modeId = rs.getLong("mode_id");
        return new EventRow(
                rs.getLong("id"),
                rs.wasNull() ? null : modeId,
                rs.getString("zone_code"),
                rs.getString("request_id"),
                rs.getString("event_type"),
                rs.getString("detail"),
                rs.getObject("event_time", LocalDateTime.class));
    }

    // ---------------- 幂等记录 ----------------

    public Optional<IdemRow> findIdem(String requestId, String kind) {
        return jdbc.query(
                        "SELECT * FROM idem_request WHERE request_id = ? AND request_kind = ?",
                        this::mapIdem, requestId, kind)
                .stream().findFirst();
    }

    /**
     * 写入幂等记录；并发重放时唯一键冲突说明另一个事务已完成首写，交由调用方重查。
     *
     * @return false 表示已存在同键记录（可能同参也可能异参）
     */
    public boolean insertIdemIfAbsent(String requestId, String kind, String fingerprint,
                                      int httpStatus, String responseJson, LocalDateTime now) {
        try {
            jdbc.update("""
                            INSERT INTO idem_request
                            (request_id, request_kind, fingerprint, http_status, response_json, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    requestId, kind, fingerprint, httpStatus, responseJson, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 覆盖幂等记录的首次结论。并发同键请求中业务失败者可能先登记 4xx，
     * 业务成功者提交后用 200 真相对齐，保证同键同参始终重放首次成功结果。
     */
    public void updateIdemOutcome(String requestId, String kind, String fingerprint,
                                  int httpStatus, String responseJson) {
        jdbc.update("""
                        UPDATE idem_request
                        SET fingerprint = ?, http_status = ?, response_json = ?
                        WHERE request_id = ? AND request_kind = ?
                        """,
                fingerprint, httpStatus, responseJson, requestId, kind);
    }

    private IdemRow mapIdem(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new IdemRow(
                rs.getString("request_id"),
                rs.getString("request_kind"),
                rs.getString("fingerprint"),
                rs.getInt("http_status"),
                rs.getString("response_json"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
