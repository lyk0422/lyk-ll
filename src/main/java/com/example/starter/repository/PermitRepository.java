package com.example.starter.repository;

import com.example.starter.domain.UnsealPermit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 临时解封单数据访问：批准、撤销、复封终结与额度的条件消费。
 */
@Repository
public class PermitRepository {

    private static final RowMapper<UnsealPermit> MAPPER = (rs, n) -> new UnsealPermit(
            rs.getLong("id"), rs.getLong("seal_id"), rs.getLong("valve_id"),
            rs.getInt("valve_version"),
            List.of(rs.getString("approver1"), rs.getString("approver2")),
            rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant(),
            rs.getInt("quota_total"), rs.getInt("quota_used"),
            UnsealPermit.PermitStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            Optional.ofNullable(rs.getTimestamp("revoked_at")).map(Timestamp::toInstant).orElse(null),
            Optional.ofNullable(rs.getTimestamp("closed_at")).map(Timestamp::toInstant).orElse(null),
            rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS = "id, seal_id, valve_id, valve_version, approver1, approver2,"
            + " window_start, window_end, quota_total, quota_used, status, version,"
            + " revoked_at, closed_at, created_at";

    private final JdbcTemplate jdbc;

    public PermitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入解封单（批准），返回主键。
     */
    public long insert(UnsealPermit permit) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO unseal_permit"
                            + " (seal_id, valve_id, valve_version, approver1, approver2,"
                            + " window_start, window_end, quota_total, quota_used, status, version,"
                            + " revoked_at, closed_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, permit.sealId());
            ps.setLong(2, permit.valveId());
            ps.setInt(3, permit.valveVersion());
            ps.setString(4, permit.approvers().get(0));
            ps.setString(5, permit.approvers().get(1));
            ps.setTimestamp(6, Timestamp.from(permit.windowStart()));
            ps.setTimestamp(7, Timestamp.from(permit.windowEnd()));
            ps.setInt(8, permit.quotaTotal());
            ps.setInt(9, permit.quotaUsed());
            ps.setString(10, permit.status().name());
            ps.setInt(11, permit.version());
            ps.setTimestamp(12, permit.revokedAt() == null ? null : Timestamp.from(permit.revokedAt()));
            ps.setTimestamp(13, permit.closedAt() == null ? null : Timestamp.from(permit.closedAt()));
            ps.setTimestamp(14, Timestamp.from(permit.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询。
     */
    public Optional<UnsealPermit> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_permit WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按主键查询并加写锁，用于撤销、复封终结与额度消费的事务内重查。
     */
    public Optional<UnsealPermit> lockById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_permit WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 查询铅封下当前生效解封单。
     */
    public Optional<UnsealPermit> findActiveBySealId(long sealId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_permit"
                        + " WHERE seal_id = ? AND status = 'ACTIVE'", MAPPER, sealId)
                .stream().findFirst();
    }

    /**
     * 复封终结：原子关闭铅封下全部生效解封单，每单版本 +1，返回更新行数。
     */
    public int closeActiveBySealId(long sealId, Instant closedAt) {
        return jdbc.update("UPDATE unseal_permit SET status = 'CLOSED', closed_at = ?,"
                        + " version = version + 1 WHERE seal_id = ? AND status = 'ACTIVE'",
                Timestamp.from(closedAt), sealId);
    }

    /**
     * 撤销：仅当解封单仍生效且版本一致时成功，版本 +1，返回更新行数。
     */
    public int revoke(long permitId, int expectedVersion, String revokedBy, Instant revokedAt) {
        return jdbc.update("UPDATE unseal_permit SET status = 'REVOKED', revoked_at = ?,"
                        + " revoked_by = ?, version = version + 1"
                        + " WHERE id = ? AND status = 'ACTIVE' AND version = ?",
                Timestamp.from(revokedAt), revokedBy, permitId, expectedVersion);
    }

    /**
     * 条件消费一次额度：仅当解封单未撤销/未终结、在半开窗口 [nowStart, nowEnd) 内、
     * 额度仍有余量且版本一致时原子更新，版本 +1，返回更新行数。
     */
    public int consumeQuota(long permitId, int expectedVersion, int newValveVersion, Instant now) {
        return jdbc.update("UPDATE unseal_permit SET quota_used = quota_used + 1,"
                        + " valve_version = ?, version = version + 1"
                        + " WHERE id = ? AND status = 'ACTIVE' AND version = ?"
                        + " AND quota_used < quota_total"
                        + " AND window_start <= ? AND ? < window_end",
                newValveVersion, permitId, expectedVersion, Timestamp.from(now), Timestamp.from(now));
    }
}
