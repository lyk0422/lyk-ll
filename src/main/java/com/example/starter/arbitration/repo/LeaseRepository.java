package com.example.starter.arbitration.repo;

import com.example.starter.arbitration.domain.LeasePo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 租约表 t_lease 数据访问（历史表：每次申请每资源一行，撤销仅置 active=FALSE）。
 * 当前唯一持有者约束由 t_active_claim 主键强制。
 */
@Repository
public class LeaseRepository {

    private static final RowMapper<LeasePo> MAPPER = (rs, n) -> {
        LeasePo po = new LeasePo();
        po.setId(rs.getLong("id"));
        po.setControllerId(rs.getString("controller_id"));
        po.setResourceName(rs.getString("resource_name"));
        po.setRequestId(rs.getString("request_id"));
        po.setPriority(rs.getInt("priority"));
        po.setAcquiredAtMs(rs.getLong("acquired_at_ms"));
        po.setExpiresAtMs(rs.getLong("expires_at_ms"));
        po.setActive(rs.getBoolean("active"));
        po.setRevokedReason(rs.getString("revoked_reason"));
        return po;
    };

    private final JdbcTemplate jdbc;

    public LeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增租约行，返回自增主键。 */
    public long insert(LeasePo lease) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_lease "
                            + "(controller_id, resource_name, request_id, priority, acquired_at_ms, "
                            + "expires_at_ms, active, revoked_reason) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lease.getControllerId());
            ps.setString(2, lease.getResourceName());
            ps.setString(3, lease.getRequestId());
            ps.setInt(4, lease.getPriority());
            ps.setLong(5, lease.getAcquiredAtMs());
            ps.setLong(6, lease.getExpiresAtMs());
            ps.setBoolean(7, lease.isActive());
            ps.setString(8, lease.getRevokedReason());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查询。 */
    public Optional<LeasePo> findById(long id) {
        return jdbc.query("SELECT * FROM t_lease WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** 按控制器、幂等键、资源查询（同参重放判定）。 */
    public Optional<LeasePo> find(String controllerId, String requestId, String resourceName) {
        return jdbc.query(
                "SELECT * FROM t_lease WHERE controller_id = ? AND request_id = ? AND resource_name = ?",
                MAPPER, controllerId, requestId, resourceName).stream().findFirst();
    }

    /** 条件置失效：仅当行仍有效时更新，返回受影响行数（CAS 防重复撤销）。 */
    public int deactivateIfActive(long id, String revokedReason) {
        return jdbc.update(
                "UPDATE t_lease SET active = FALSE, revoked_reason = ? WHERE id = ? AND active = TRUE",
                revokedReason, id);
    }

    /** 续期：仅当行仍有效时延长到期时间，返回受影响行数。 */
    public int renewIfActive(long id, long newExpiresAtMs) {
        return jdbc.update("UPDATE t_lease SET expires_at_ms = ? WHERE id = ? AND active = TRUE",
                newExpiresAtMs, id);
    }

    /** 全部有效租约（不加锁）。 */
    public List<LeasePo> findAllActive() {
        return jdbc.query("SELECT * FROM t_lease WHERE active = TRUE ORDER BY id", MAPPER);
    }

    /** 某控制器全部有效租约（不加锁）。 */
    public List<LeasePo> findActiveByController(String controllerId) {
        return jdbc.query(
                "SELECT * FROM t_lease WHERE controller_id = ? AND active = TRUE ORDER BY id",
                MAPPER, controllerId);
    }

    /** 按控制器与幂等键查询全部租约（含历史，按 id 升序）。 */
    public List<LeasePo> findByControllerAndRequest(String controllerId, String requestId) {
        return jdbc.query(
                "SELECT * FROM t_lease WHERE controller_id = ? AND request_id = ? ORDER BY id",
                MAPPER, controllerId, requestId);
    }

    /** 急停：撤销全部有效租约，返回受影响行数。 */
    public int deactivateAllActive(String reason) {
        return jdbc.update(
                "UPDATE t_lease SET active = FALSE, revoked_reason = ? WHERE active = TRUE", reason);
    }
}
