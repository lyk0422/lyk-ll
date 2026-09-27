package com.example.starter.repository;

import com.example.starter.domain.UnsealOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 临时解封单数据访问。
 */
@Repository
public class UnsealOrderRepository {

    private static final RowMapper<UnsealOrder> MAPPER = (rs, n) -> new UnsealOrder(
            rs.getLong("id"), rs.getLong("seal_id"), rs.getLong("valve_id"),
            rs.getString("seal_no"), rs.getInt("valve_version"),
            rs.getString("approver_a"), rs.getString("approver_b"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getInt("quota_total"), rs.getInt("quota_used"),
            UnsealOrder.UnsealStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("revoked_at")).map(Timestamp::toInstant).orElse(null),
            rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getString("revoke_request_id"), rs.getString("revoke_fingerprint"));

    private static final String COLUMNS = "id, seal_id, valve_id, seal_no, valve_version,"
            + " approver_a, approver_b, window_start, window_end, quota_total, quota_used,"
            + " status, version, created_at, revoked_at, request_id, fingerprint,"
            + " revoke_request_id, revoke_fingerprint";

    private final JdbcTemplate jdbc;

    public UnsealOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入解封单并返回生成的主键。
     */
    public long insert(UnsealOrder order) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO unseal_order"
                            + " (seal_id, valve_id, seal_no, valve_version, approver_a, approver_b,"
                            + " window_start, window_end, quota_total, quota_used, status, version,"
                            + " created_at, revoked_at, request_id, fingerprint)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, order.sealId());
            ps.setLong(2, order.valveId());
            ps.setString(3, order.sealNo());
            ps.setInt(4, order.valveVersion());
            ps.setString(5, order.approverA());
            ps.setString(6, order.approverB());
            ps.setTimestamp(7, Timestamp.from(order.windowStart()));
            ps.setTimestamp(8, Timestamp.from(order.windowEnd()));
            ps.setInt(9, order.quotaTotal());
            ps.setInt(10, order.quotaUsed());
            ps.setString(11, order.status().name());
            ps.setInt(12, order.version());
            ps.setTimestamp(13, Timestamp.from(order.createdAt()));
            ps.setTimestamp(14, order.revokedAt() == null ? null : Timestamp.from(order.revokedAt()));
            ps.setString(15, order.requestId());
            ps.setString(16, order.fingerprint());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按 ID 查询解封单。
     */
    public Optional<UnsealOrder> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_order WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行级写锁（SELECT ... FOR UPDATE），用于撤销与受控调节的重查串行化。
     */
    public Optional<UnsealOrder> lockById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_order WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 按批准幂等键查询解封单。
     */
    public Optional<UnsealOrder> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_order WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 查询铅封当前有效（ACTIVE）的解封单，无则空。
     */
    public Optional<UnsealOrder> findActiveBySealId(long sealId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_order"
                        + " WHERE seal_id = ? AND status = 'ACTIVE'",
                MAPPER, sealId).stream().findFirst();
    }

    /**
     * 原子消费一次额度：仅当版本一致、仍 ACTIVE 且额度足够时生效，返回更新行数。
     */
    public int consumeQuota(long id, int expectedVersion) {
        return jdbc.update("UPDATE unseal_order SET quota_used = quota_used + 1,"
                        + " version = version + 1"
                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'"
                        + " AND quota_used < quota_total",
                id, expectedVersion);
    }

    /**
     * 原子撤销：仅当版本一致且仍 ACTIVE 时生效，返回更新行数。
     */
    public int revoke(long id, int expectedVersion, Instant revokedAt,
                      String revokeRequestId, String revokeFingerprint) {
        return jdbc.update("UPDATE unseal_order SET status = 'REVOKED', revoked_at = ?,"
                        + " version = version + 1, revoke_request_id = ?, revoke_fingerprint = ?"
                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                Timestamp.from(revokedAt), revokeRequestId, revokeFingerprint, id, expectedVersion);
    }

    /**
     * 复封时原子终结铅封下仍有效的解封单，返回更新行数。
     */
    public int closeActiveBySealId(long sealId) {
        return jdbc.update("UPDATE unseal_order SET status = 'CLOSED', version = version + 1"
                + " WHERE seal_id = ? AND status = 'ACTIVE'", sealId);
    }
}
