package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.domain.Permit;
import com.example.starter.domain.PermitStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 爆破作业申请单数据访问。
 */
@Repository
public class PermitRepository {

    private final JdbcTemplate jdbc;

    public PermitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入申请单并返回生成主键。
     */
    public long insert(Permit permit, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blast_permit (request_id, fingerprint, zone_id, applicant,"
                            + " planned_blast_time, charge_per_delay, status, reject_reason,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, permit.requestId());
            ps.setString(2, permit.fingerprint());
            ps.setLong(3, permit.zoneId());
            ps.setString(4, permit.applicant());
            ps.setTimestamp(5, Timestamp.from(permit.plannedBlastTime()));
            ps.setBigDecimal(6, permit.chargePerDelay());
            ps.setString(7, permit.status().name());
            ps.setString(8, permit.rejectReason());
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等键查询。
     */
    public Optional<Permit> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM blast_permit WHERE request_id = ?", this::map, requestId)
                .stream().findFirst();
    }

    /**
     * 按主键查询。
     */
    public Optional<Permit> findById(long id) {
        return jdbc.query("SELECT * FROM blast_permit WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    /**
     * 更新状态（可带原因），返回受影响行数用于状态前置校验。
     */
    public int updateStatus(long id, PermitStatus from, PermitStatus to, String reason, Instant now) {
        return jdbc.update(
                "UPDATE blast_permit SET status = ?, reject_reason = ?, updated_at = ?"
                        + " WHERE id = ? AND status = ?",
                to.name(), reason, Timestamp.from(now), id, from.name());
    }

    /**
     * 占用已批准槽位：同一爆区同一时刻唯一，冲突时抛出主键异常。
     */
    public void occupySlot(long zoneId, Instant plannedBlastTime, long permitId) {
        jdbc.update("INSERT INTO approved_slot (zone_id, planned_blast_time, permit_id) VALUES (?,?,?)",
                zoneId, Timestamp.from(plannedBlastTime), permitId);
    }

    /**
     * 释放指定申请单占用的槽位。
     */
    public void releaseSlot(long permitId) {
        jdbc.update("DELETE FROM approved_slot WHERE permit_id = ?", permitId);
    }

    /**
     * 冻结级联：矿区内全部在途申请驳回。
     */
    public int rejectPendingByMine(long mineId, String reason, Instant now) {
        return jdbc.update(
                "UPDATE blast_permit SET status = 'REJECTED', reject_reason = ?, updated_at = ?"
                        + " WHERE status = 'PENDING'"
                        + " AND zone_id IN (SELECT id FROM blast_zone WHERE mine_id = ?)",
                reason, Timestamp.from(now), mineId);
    }

    /**
     * 冻结级联：矿区内已批准未起爆作业作废并释放槽位。
     */
    public int voidApprovedByMine(long mineId, String reason, Instant now) {
        jdbc.update(
                "DELETE FROM approved_slot WHERE permit_id IN"
                        + " (SELECT id FROM blast_permit WHERE status = 'APPROVED'"
                        + " AND zone_id IN (SELECT id FROM blast_zone WHERE mine_id = ?))",
                mineId);
        return jdbc.update(
                "UPDATE blast_permit SET status = 'VOID', reject_reason = ?, updated_at = ?"
                        + " WHERE status = 'APPROVED'"
                        + " AND zone_id IN (SELECT id FROM blast_zone WHERE mine_id = ?)",
                reason, Timestamp.from(now), mineId);
    }

    private Permit map(ResultSet rs, int rowNum) throws SQLException {
        return new Permit(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("zone_id"),
                rs.getString("applicant"),
                rs.getTimestamp("planned_blast_time").toInstant(),
                rs.getBigDecimal("charge_per_delay"),
                PermitStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason"));
    }
}
