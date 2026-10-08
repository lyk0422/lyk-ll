package com.example.starter.repo;

import com.example.starter.model.MaintenanceAcceptance;
import com.example.starter.model.MaintenanceAcceptanceRole;
import com.example.starter.model.MaintenanceClearance;
import com.example.starter.model.MaintenanceOrder;
import com.example.starter.model.MaintenanceOrderStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 检修封锁数据访问（检修单、分段清单、清空证据、双岗验收）。
 */
@Repository
public class MaintenanceRepository {

    private static final RowMapper<MaintenanceOrder> ORDER_MAPPER = (rs, n) -> new MaintenanceOrder(
            rs.getLong("id"), rs.getLong("line_id"), rs.getInt("version"),
            MaintenanceOrderStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
            rs.getString("reason"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant());

    private static final String ORDER_COLUMNS =
            "id, line_id, version, status, start_at, end_at, reason, created_at, completed_at, cancelled_at";

    private static final RowMapper<MaintenanceClearance> CLEARANCE_MAPPER = (rs, n) ->
            new MaintenanceClearance(rs.getLong("order_id"), rs.getLong("segment_id"),
                    rs.getString("operator_id"), rs.getString("opinion"),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<MaintenanceAcceptance> ACCEPTANCE_MAPPER = (rs, n) ->
            new MaintenanceAcceptance(rs.getLong("order_id"),
                    MaintenanceAcceptanceRole.valueOf(rs.getString("role")),
                    rs.getString("operator_id"), rs.getString("opinion"),
                    rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public MaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记检修单（版本 1），返回生成主键。
     */
    public long insertOrder(long lineId, Instant startAt, Instant endAt, String reason) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO maintenance_order (line_id, version, status, start_at, end_at, reason) "
                            + "VALUES (?, 1, 'SCHEDULED', ?, ?, ?)", new String[]{"id"});
            ps.setLong(1, lineId);
            ps.setTimestamp(2, Timestamp.from(startAt));
            ps.setTimestamp(3, Timestamp.from(endAt));
            ps.setString(4, reason);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 登记分段清单。
     */
    public void insertSegments(long orderId, List<Long> segmentIds) {
        jdbc.batchUpdate("INSERT INTO maintenance_segment (order_id, segment_id) VALUES (?, ?)",
                segmentIds, segmentIds.size(), (ps, segmentId) -> {
                    ps.setLong(1, orderId);
                    ps.setLong(2, segmentId);
                });
    }

    /**
     * 按 id 查询检修单。
     */
    public Optional<MaintenanceOrder> findById(long id) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM maintenance_order WHERE id = ?",
                ORDER_MAPPER, id).stream().findFirst();
    }

    /**
     * 按 id 查询并对检修单加行锁（事务内串行化同一检修单写操作）。
     */
    public Optional<MaintenanceOrder> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM maintenance_order WHERE id = ? FOR UPDATE",
                ORDER_MAPPER, id).stream().findFirst();
    }

    /**
     * 查询线路未终态检修单并加行锁（按起点升序），用于上客/复开即时门禁与重叠校验。
     */
    public List<MaintenanceOrder> findScheduledByLineForUpdate(long lineId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM maintenance_order "
                + "WHERE line_id = ? AND status = 'SCHEDULED' ORDER BY start_at, id FOR UPDATE",
                ORDER_MAPPER, lineId);
    }

    /**
     * 查询线路未终态检修单（不加锁，只读视图用）。
     */
    public List<MaintenanceOrder> findScheduledByLine(long lineId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM maintenance_order "
                        + "WHERE line_id = ? AND status = 'SCHEDULED' ORDER BY start_at, id",
                ORDER_MAPPER, lineId);
    }

    /**
     * 同线路窗口重叠判定：[a.start,a.end) 与 [b.start,b.end) 有重叠（端点相接不算），
     * 可排除指定检修单自身。
     */
    public boolean existsOverlappingScheduled(long lineId, Instant startAt, Instant endAt, Long excludeOrderId) {
        String sql = "SELECT COUNT(*) FROM maintenance_order WHERE line_id = ? AND status = 'SCHEDULED' "
                + "AND start_at < ? AND end_at > ?";
        Integer count;
        if (excludeOrderId == null) {
            count = jdbc.queryForObject(sql, Integer.class, lineId,
                    Timestamp.from(endAt), Timestamp.from(startAt));
        } else {
            count = jdbc.queryForObject(sql + " AND id <> ?", Integer.class, lineId,
                    Timestamp.from(endAt), Timestamp.from(startAt), excludeOrderId);
        }
        return count != null && count > 0;
    }

    /**
     * 修改窗口、原因与版本（乐观锁）。返回受影响行数：0 表示 expectedVersion 已过期或状态不允许。
     */
    public int updateWindow(long id, int expectedVersion, Instant startAt, Instant endAt, String reason) {
        return jdbc.update("UPDATE maintenance_order SET start_at = ?, end_at = ?, reason = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ? AND status = 'SCHEDULED'",
                Timestamp.from(startAt), Timestamp.from(endAt), reason, id, expectedVersion);
    }

    /**
     * 删除检修单分段清单（修改窗口时随新清单整体替换）。
     */
    public void deleteSegments(long orderId) {
        jdbc.update("DELETE FROM maintenance_segment WHERE order_id = ?", orderId);
    }

    /**
     * 未生效取消（乐观锁），返回受影响行数。
     */
    public int cancel(long id, int expectedVersion, Instant cancelledAt) {
        return jdbc.update("UPDATE maintenance_order SET status = 'CANCELLED', cancelled_at = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ? AND status = 'SCHEDULED'",
                Timestamp.from(cancelledAt), id, expectedVersion);
    }

    /**
     * 双岗验收完成（乐观锁）：仅解除检修自身阻断，返回受影响行数。
     */
    public int complete(long id, int expectedVersion, Instant completedAt) {
        return jdbc.update("UPDATE maintenance_order SET status = 'COMPLETED', completed_at = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ? AND status = 'SCHEDULED'",
                Timestamp.from(completedAt), id, expectedVersion);
    }

    /**
     * 查询检修单分段清单（按分段顺序号升序）。
     */
    public List<Long> listSegmentIds(long orderId) {
        return jdbc.queryForList(
                "SELECT ms.segment_id FROM maintenance_segment ms "
                        + "JOIN wind_segment ws ON ws.id = ms.segment_id "
                        + "WHERE ms.order_id = ? ORDER BY ws.seq_no, ms.segment_id",
                Long.class, orderId);
    }

    /**
     * 登记分段清空证据；同一检修单同一分段重复登记返回 false。
     */
    public boolean insertClearance(long orderId, long segmentId, String operatorId, String opinion,
                                   Instant createdAt) {
        try {
            jdbc.update("INSERT INTO maintenance_clearance "
                            + "(order_id, segment_id, operator_id, opinion, created_at) VALUES (?, ?, ?, ?, ?)",
                    orderId, segmentId, operatorId, opinion, Timestamp.from(createdAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 检修单全部清空证据。
     */
    public List<MaintenanceClearance> listClearances(long orderId) {
        return jdbc.query("SELECT order_id, segment_id, operator_id, opinion, created_at "
                        + "FROM maintenance_clearance WHERE order_id = ? ORDER BY segment_id",
                CLEARANCE_MAPPER, orderId);
    }

    /**
     * 已登记清空证据的分段数。
     */
    public int countClearedSegments(long orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_clearance WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    /**
     * 登记验收意见；同一检修单同一岗位重复验收返回 false。
     */
    public boolean insertAcceptance(long orderId, MaintenanceAcceptanceRole role, String operatorId,
                                    String opinion, Instant createdAt) {
        try {
            jdbc.update("INSERT INTO maintenance_acceptance "
                            + "(order_id, role, operator_id, opinion, created_at) VALUES (?, ?, ?, ?, ?)",
                    orderId, role.name(), operatorId, opinion, Timestamp.from(createdAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 检修单全部验收意见（岗位升序）。
     */
    public List<MaintenanceAcceptance> listAcceptances(long orderId) {
        return jdbc.query("SELECT order_id, role, operator_id, opinion, created_at "
                        + "FROM maintenance_acceptance WHERE order_id = ? ORDER BY role",
                ACCEPTANCE_MAPPER, orderId);
    }
}
