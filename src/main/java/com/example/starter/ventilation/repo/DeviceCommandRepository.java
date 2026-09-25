package com.example.starter.ventilation.repo;

import com.example.starter.ventilation.CommandStatus;
import com.example.starter.ventilation.DeviceAction;
import com.example.starter.ventilation.DeviceType;
import com.example.starter.ventilation.domain.DeviceCommandRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备指令序列仓储。
 */
@Repository
public class DeviceCommandRepository {

    private static final RowMapper<DeviceCommandRow> MAPPER = (rs, n) -> {
        DeviceCommandRow row = new DeviceCommandRow();
        row.setId(rs.getLong("id"));
        row.setSessionId(rs.getLong("session_id"));
        row.setRequestId(rs.getString("request_id"));
        row.setZoneCode(rs.getString("zone_code"));
        row.setDeviceCode(rs.getString("device_code"));
        row.setDeviceType(DeviceType.valueOf(rs.getString("device_type")));
        row.setAction(DeviceAction.valueOf(rs.getString("action")));
        row.setPhase(rs.getString("phase"));
        row.setSeqNo(rs.getInt("seq_no"));
        row.setAttempt(rs.getInt("attempt"));
        row.setStatus(CommandStatus.valueOf(rs.getString("status")));
        Long sentAt = nullableLong(rs, "sent_at");
        row.setSentAt(sentAt);
        row.setAckDueAt(nullableLong(rs, "ack_due_at"));
        row.setAckedAt(nullableLong(rs, "acked_at"));
        row.setFaultAt(nullableLong(rs, "fault_at"));
        return row;
    };

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private final JdbcTemplate jdbc;

    public DeviceCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按预案顺序插入一条 PENDING 指令，返回自增主键。 */
    public long insert(long sessionId, String requestId, String zoneCode, String deviceCode,
                       DeviceType deviceType, DeviceAction action, String phase, int seqNo) {
        org.springframework.jdbc.core.PreparedStatementCreator creator = con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO device_command (session_id, request_id, zone_code, device_code, "
                            + "device_type, action, phase, seq_no, attempt, status) "
                            + "VALUES (?,?,?,?,?,?,?,?,1,'PENDING')",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sessionId);
            ps.setString(2, requestId);
            ps.setString(3, zoneCode);
            ps.setString(4, deviceCode);
            ps.setString(5, deviceType.name());
            ps.setString(6, action.name());
            ps.setString(7, phase);
            ps.setInt(8, seqNo);
            return ps;
        };
        org.springframework.jdbc.support.KeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(creator, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** 按会话与阶段查询指令，seq_no 升序（先阀后机 / 逆序复位顺序）。 */
    public List<DeviceCommandRow> findBySessionAndPhase(long sessionId, String phase) {
        return jdbc.query(
                "SELECT id, session_id, request_id, zone_code, device_code, device_type, action, "
                        + "phase, seq_no, attempt, status, sent_at, ack_due_at, acked_at, fault_at "
                        + "FROM device_command WHERE session_id = ? AND phase = ? ORDER BY seq_no ASC, id ASC",
                MAPPER, sessionId, phase);
    }

    /** 查询会话全部指令，按下发顺序。 */
    public List<DeviceCommandRow> findBySession(long sessionId) {
        return jdbc.query(
                "SELECT id, session_id, request_id, zone_code, device_code, device_type, action, "
                        + "phase, seq_no, attempt, status, sent_at, ack_due_at, acked_at, fault_at "
                        + "FROM device_command WHERE session_id = ? ORDER BY id ASC",
                MAPPER, sessionId);
    }

    /** 按主键查指令。 */
    public DeviceCommandRow findById(long commandId) {
        List<DeviceCommandRow> list = jdbc.query(
                "SELECT id, session_id, request_id, zone_code, device_code, device_type, action, "
                        + "phase, seq_no, attempt, status, sent_at, ack_due_at, acked_at, fault_at "
                        + "FROM device_command WHERE id = ?", MAPPER, commandId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询会话某设备当前阶段的指令（用于到位反馈匹配）。 */
    public DeviceCommandRow findActiveByDevice(long sessionId, String deviceCode) {
        List<DeviceCommandRow> list = jdbc.query(
                "SELECT id, session_id, request_id, zone_code, device_code, device_type, action, "
                        + "phase, seq_no, attempt, status, sent_at, ack_due_at, acked_at, fault_at "
                        + "FROM device_command WHERE session_id = ? AND device_code = ? "
                        + "AND status IN ('SENT','PENDING') ORDER BY id ASC LIMIT 1",
                MAPPER, sessionId, deviceCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 指令下发（首发或重发），记录下发时间、截止时间、尝试次数与状态。 */
    public void markSent(long commandId, int attempt, long sentAt, long ackDueAt) {
        jdbc.update(
                "UPDATE device_command SET status = 'SENT', attempt = ?, sent_at = ?, "
                        + "ack_due_at = ? WHERE id = ?",
                attempt, sentAt, ackDueAt, commandId);
    }

    /** 到位确认。 */
    public void markAcked(long commandId, long ackedAt) {
        jdbc.update(
                "UPDATE device_command SET status = 'ACKED', acked_at = ? WHERE id = ? AND status = 'SENT'",
                ackedAt, commandId);
    }

    /** 两次下发均超时，判设备故障。 */
    public void markFault(long commandId, long faultAt) {
        jdbc.update("UPDATE device_command SET status = 'FAULT', fault_at = ? WHERE id = ?",
                faultAt, commandId);
    }

    /** 置为共享风阀终态（CONFLICT 冲突跳过 / ALIGNED 已同向），不记录下发时间。 */
    public void markTerminal(long commandId, CommandStatus status, long atTime) {
        jdbc.update("UPDATE device_command SET status = ? WHERE id = ?",
                status.name(), commandId);
    }

    /**
     * 查询所有已下发且截止时间已到（now &gt;= ack_due_at，恰满判超时）的指令，
     * 供超时巡检使用。
     */
    public List<DeviceCommandRow> findTimedOutSent(long now) {
        return jdbc.query(
                "SELECT id, session_id, request_id, zone_code, device_code, device_type, action, "
                        + "phase, seq_no, attempt, status, sent_at, ack_due_at, acked_at, fault_at "
                        + "FROM device_command WHERE status = 'SENT' AND ack_due_at <= ? ORDER BY id ASC",
                MAPPER, now);
    }

    /** 查询会话某阶段是否还有未完成（非到位、非故障）指令。 */
    public boolean hasUnfinished(long sessionId, String phase) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_command WHERE session_id = ? AND phase = ? "
                        + "AND status IN ('PENDING','SENT')",
                Integer.class, sessionId, phase);
        return count != null && count > 0;
    }
}
