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
 * 临时解封单数据访问：批准、行锁重查、额度消费、撤销与复封终结。
 */
@Repository
public class UnsealOrderRepository {

    private static final RowMapper<UnsealOrder> MAPPER = (rs, n) -> new UnsealOrder(
            rs.getLong("id"), rs.getLong("valve_id"), rs.getString("seal_no"),
            rs.getInt("expected_valve_version"),
            rs.getString("authorizer_a"), rs.getString("authorizer_b"),
            rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant(),
            rs.getInt("quota_total"), rs.getInt("quota_used"),
            UnsealOrder.OrderStatus.valueOf(rs.getString("status")),
            rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getString("revoke_request_id"), rs.getString("revoke_fingerprint"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final String COLUMNS = "id, valve_id, seal_no, expected_valve_version,"
            + " authorizer_a, authorizer_b, window_start, window_end, quota_total, quota_used,"
            + " status, request_id, fingerprint, revoke_request_id, revoke_fingerprint,"
            + " version, created_at, updated_at";

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
                            + " (valve_id, seal_no, expected_valve_version, authorizer_a, authorizer_b,"
                            + " window_start, window_end, quota_total, quota_used,"
                            + " status, request_id, fingerprint, version, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, order.valveId());
            ps.setString(2, order.sealNo());
            ps.setInt(3, order.expectedValveVersion());
            ps.setString(4, order.authorizerA());
            ps.setString(5, order.authorizerB());
            ps.setTimestamp(6, Timestamp.from(order.windowStart()));
            ps.setTimestamp(7, Timestamp.from(order.windowEnd()));
            ps.setInt(8, order.quotaTotal());
            ps.setInt(9, order.quotaUsed());
            ps.setString(10, order.status().name());
            ps.setString(11, order.requestId());
            ps.setString(12, order.fingerprint());
            ps.setInt(13, order.version());
            ps.setTimestamp(14, Timestamp.from(order.createdAt()));
            ps.setTimestamp(15, Timestamp.from(order.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等键查询解封单。
     */
    public Optional<UnsealOrder> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_order WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 按 ID 查询解封单。
     */
    public Optional<UnsealOrder> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_order WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 按 ID 查询并加行级写锁（SELECT ... FOR UPDATE），用于撤销/复封与受控调节互斥。
     */
    public Optional<UnsealOrder> lockById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_order WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 查询阀门当前生效中（ACTIVE）的解封单并加行级写锁，用于受控调节事务内重查；
     * 与复封终结竞争同一行锁，保证复封后不可再追加调节版本。
     */
    public Optional<UnsealOrder> lockActiveByValveId(long valveId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unseal_order"
                        + " WHERE valve_id = ? AND status = 'ACTIVE' FOR UPDATE",
                MAPPER, valveId).stream().findFirst();
    }

    /**
     * 消费一次额度：已用额度与期望开度版本同步加 1，行版本加 1；乐观锁不匹配时返回 false。
     */
    public boolean consumeQuota(long orderId, int expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("UPDATE unseal_order"
                        + " SET quota_used = quota_used + 1,"
                        + " expected_valve_version = expected_valve_version + 1,"
                        + " version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'"
                        + " AND quota_used < quota_total",
                Timestamp.from(updatedAt), orderId, expectedVersion);
        return updated == 1;
    }

    /**
     * 撤销解封单，记录撤销幂等键与指纹，行版本加 1；乐观锁不匹配或已非 ACTIVE 时返回 false。
     */
    public boolean revoke(long orderId, int expectedVersion,
                          String revokeRequestId, String revokeFingerprint, Instant updatedAt) {
        int updated = jdbc.update("UPDATE unseal_order SET status = 'REVOKED',"
                        + " revoke_request_id = ?, revoke_fingerprint = ?,"
                        + " version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                revokeRequestId, revokeFingerprint,
                Timestamp.from(updatedAt), orderId, expectedVersion);
        return updated == 1;
    }

    /**
     * 复封时原子终结解封单，行版本加 1；乐观锁不匹配或已非 ACTIVE 时返回 false。
     */
    public boolean terminate(long orderId, int expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("UPDATE unseal_order SET status = 'TERMINATED',"
                        + " version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                Timestamp.from(updatedAt), orderId, expectedVersion);
        return updated == 1;
    }
}
