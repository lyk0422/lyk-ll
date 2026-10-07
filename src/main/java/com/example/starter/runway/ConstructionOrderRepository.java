package com.example.starter.runway;

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
import java.util.Objects;
import java.util.Optional;

/**
 * 施工单数据访问：construction_order 表。
 */
@Repository
public class ConstructionOrderRepository {

    private static final RowMapper<ConstructionOrder> MAPPER = (rs, rowNum) -> new ConstructionOrder(
            rs.getLong("id"),
            rs.getString("runway_id"),
            ConstructionOrderStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getTimestamp("activated_at") == null ? null : rs.getTimestamp("activated_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getTimestamp("withdrawn_at") == null ? null : rs.getTimestamp("withdrawn_at").toInstant(),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public ConstructionOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入施工单，初始聚合版本为1，状态 PENDING。 */
    public ConstructionOrder insert(ConstructionOrder order) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO construction_order (runway_id, status, version, activated_at, completed_at,"
                            + " withdrawn_at, request_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, order.runwayId());
            ps.setString(2, order.status().name());
            ps.setInt(3, order.version() <= 0 ? 1 : order.version());
            ps.setTimestamp(4, order.activatedAt() == null ? null : Timestamp.from(order.activatedAt()));
            ps.setTimestamp(5, order.completedAt() == null ? null : Timestamp.from(order.completedAt()));
            ps.setTimestamp(6, order.withdrawnAt() == null ? null : Timestamp.from(order.withdrawnAt()));
            ps.setString(7, order.requestId());
            ps.setTimestamp(8, Timestamp.from(order.createdAt()));
            ps.setTimestamp(9, Timestamp.from(order.updatedAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return findById(id).orElseThrow();
    }

    public Optional<ConstructionOrder> findById(long id) {
        List<ConstructionOrder> rows = jdbc.query("SELECT * FROM construction_order WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 行级锁定取施工单，供生效/修改/撤回/验收串行化。 */
    public Optional<ConstructionOrder> findByIdForUpdate(long id) {
        List<ConstructionOrder> rows = jdbc.query("SELECT * FROM construction_order WHERE id = ? FOR UPDATE",
                MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按跑道列出全部施工单（含终态），按主键排序。 */
    public List<ConstructionOrder> findByRunway(String runwayId) {
        return jdbc.query("SELECT * FROM construction_order WHERE runway_id = ? ORDER BY id", MAPPER, runwayId);
    }

    /** 全量更新施工单状态并把聚合乐观锁版本加1。 */
    public void update(ConstructionOrder order) {
        jdbc.update("UPDATE construction_order SET status = ?, activated_at = ?, completed_at = ?,"
                        + " withdrawn_at = ?, version = version + 1, updated_at = ? WHERE id = ?",
                order.status().name(),
                order.activatedAt() == null ? null : Timestamp.from(order.activatedAt()),
                order.completedAt() == null ? null : Timestamp.from(order.completedAt()),
                order.withdrawnAt() == null ? null : Timestamp.from(order.withdrawnAt()),
                Timestamp.from(order.updatedAt()), order.id());
    }

    /** 查询某跑道是否存在已生效（ACTIVE）施工单（只读）。 */
    public boolean existsActiveByRunway(String runwayId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM construction_order WHERE runway_id = ? AND status = 'ACTIVE'",
                Integer.class, runwayId);
        return count != null && count > 0;
    }
}
