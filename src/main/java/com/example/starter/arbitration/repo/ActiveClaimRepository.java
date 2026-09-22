package com.example.starter.arbitration.repo;

import com.example.starter.arbitration.domain.ActiveClaimPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 当前有效占用表 t_active_claim 数据访问。
 * resource_name 主键在数据库层强制“任一资源同一时刻只允许一个有效持有者”。
 */
@Repository
public class ActiveClaimRepository {

    private static final RowMapper<ActiveClaimPo> MAPPER = (rs, n) -> {
        ActiveClaimPo po = new ActiveClaimPo();
        po.setResourceName(rs.getString("resource_name"));
        po.setLeaseId(rs.getLong("lease_id"));
        po.setControllerId(rs.getString("controller_id"));
        po.setRequestId(rs.getString("request_id"));
        po.setPriority(rs.getInt("priority"));
        po.setAcquiredAtMs(rs.getLong("acquired_at_ms"));
        po.setExpiresAtMs(rs.getLong("expires_at_ms"));
        return po;
    };

    private final JdbcTemplate jdbc;

    public ActiveClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试写入占用。资源已被持有时抛 {@link DuplicateKeyException}，由调用方按抢占规则处理。
     */
    public void insert(ActiveClaimPo claim) {
        jdbc.update(
                "INSERT INTO t_active_claim "
                        + "(resource_name, lease_id, controller_id, request_id, priority, acquired_at_ms, expires_at_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                claim.getResourceName(), claim.getLeaseId(), claim.getControllerId(), claim.getRequestId(),
                claim.getPriority(), claim.getAcquiredAtMs(), claim.getExpiresAtMs());
    }

    /** 事务内锁定并返回资源占用。 */
    public Optional<ActiveClaimPo> findByResourceForUpdate(String resourceName) {
        return jdbc.query("SELECT * FROM t_active_claim WHERE resource_name = ? FOR UPDATE", MAPPER, resourceName)
                .stream().findFirst();
    }

    /** 不加锁查询资源占用。 */
    public Optional<ActiveClaimPo> findByResource(String resourceName) {
        return jdbc.query("SELECT * FROM t_active_claim WHERE resource_name = ?", MAPPER, resourceName)
                .stream().findFirst();
    }

    /** 按租约删除占用（释放/撤销时），返回删除行数，CAS 语义保证重复删除不产生额外效果。 */
    public int deleteByLeaseId(long leaseId) {
        return jdbc.update("DELETE FROM t_active_claim WHERE lease_id = ?", leaseId);
    }

    /** 删除某控制器的全部占用（失联/急停），返回删除行数。 */
    public int deleteByController(String controllerId) {
        return jdbc.update("DELETE FROM t_active_claim WHERE controller_id = ?", controllerId);
    }

    /** 清空全部占用（急停），返回删除行数。 */
    public int deleteAll() {
        return jdbc.update("DELETE FROM t_active_claim");
    }

    /** 事务内锁定并返回全部占用。 */
    public List<ActiveClaimPo> findAllForUpdate() {
        return jdbc.query("SELECT * FROM t_active_claim FOR UPDATE", MAPPER);
    }

    /** 不加锁返回全部占用。 */
    public List<ActiveClaimPo> findAll() {
        return jdbc.query("SELECT * FROM t_active_claim ORDER BY resource_name", MAPPER);
    }

    /** 不加锁返回某控制器全部占用。 */
    public List<ActiveClaimPo> findByController(String controllerId) {
        return jdbc.query("SELECT * FROM t_active_claim WHERE controller_id = ? ORDER BY resource_name",
                MAPPER, controllerId);
    }

    /** 续期：延长到期时间，返回受影响行数（占用不存在则为 0）。 */
    public int renew(long leaseId, long newExpiresAtMs) {
        return jdbc.update("UPDATE t_active_claim SET expires_at_ms = ? WHERE lease_id = ?",
                newExpiresAtMs, leaseId);
    }

    /** 已到期占用（expires_at_ms 小于给定时间），事务内加锁。 */
    public List<ActiveClaimPo> findExpiredForUpdate(long nowMs) {
        return jdbc.query("SELECT * FROM t_active_claim WHERE expires_at_ms < ? FOR UPDATE",
                MAPPER, nowMs);
    }
}
