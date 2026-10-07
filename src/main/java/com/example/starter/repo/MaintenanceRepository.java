package com.example.starter.repo;

import com.example.starter.model.AcceptanceRole;
import com.example.starter.model.MaintenanceAcceptance;
import com.example.starter.model.MaintenanceOrder;
import com.example.starter.model.MaintenanceOrderStatus;
import com.example.starter.model.MaintenanceSegmentClearance;
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
 * 检修封锁窗口数据访问（工单、涉及分段、分段清空证据、验收记录）。
 */
@Repository
public class MaintenanceRepository {

    private static final RowMapper<MaintenanceOrder> ORDER_MAPPER = (rs, n) -> new MaintenanceOrder(
            rs.getLong("id"), rs.getLong("line_id"),
            MaintenanceOrderStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getTimestamp("start_at").toInstant(),
            rs.getTimestamp("end_at").toInstant(),
            rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());

    private static final String ORDER_COLUMNS =
            "id, line_id, status, version, start_at, end_at, cancelled_at, completed_at";

    private final JdbcTemplate jdbc;

    public MaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新建检修单（初始版本 1，状态已登记），返回生成主键。
     */
    public long insertOrder(long lineId, Instant startAt, Instant endAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO maintenance_order (line_id, status, version, start_at, end_at) "
                            + "VALUES (?, ?, 1, ?, ?)", new String[]{"id"});
            ps.setLong(1, lineId);
            ps.setString(2, MaintenanceOrderStatus.SCHEDULED.name());
            ps.setTimestamp(3, Timestamp.from(startAt));
            ps.setTimestamp(4, Timestamp.from(endAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 登记工单涉及分段。
     */
    public void insertOrderSegment(long orderId, long segmentId) {
        jdbc.update("INSERT INTO maintenance_order_segment (order_id, segment_id) VALUES (?, ?)",
                orderId, segmentId);
    }

    /**
     * 按 id 查询检修单。
     */
    public Optional<MaintenanceOrder> findOrderById(long id) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM maintenance_order WHERE id = ?",
                        ORDER_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 id 查询并加行锁（事务内串行化工单级写操作）。
     */
    public Optional<MaintenanceOrder> findOrderByIdForUpdate(long id) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM maintenance_order WHERE id = ? FOR UPDATE",
                        ORDER_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询线路在指定时刻构成阻断的检修单（已登记且已到窗口起点），无则空。
     */
    public Optional<MaintenanceOrder> findBlockingOrder(long lineId, Instant now) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM maintenance_order "
                                + "WHERE line_id = ? AND status = ? AND start_at <= ? ORDER BY id",
                        ORDER_MAPPER, lineId, MaintenanceOrderStatus.SCHEDULED.name(), Timestamp.from(now))
                .stream().findFirst();
    }

    /**
     * 线路已登记（未取消未完成）工单中与给定窗口内部重叠的数量；端点相接不算重叠。
     */
    public int countOverlapping(long lineId, Instant startAt, Instant endAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_order WHERE line_id = ? AND status = ? "
                        + "AND start_at < ? AND end_at > ?",
                Integer.class, lineId, MaintenanceOrderStatus.SCHEDULED.name(),
                Timestamp.from(endAt), Timestamp.from(startAt));
        return count == null ? 0 : count;
    }

    /**
     * 工单涉及分段清单（按分段 id 排序）。
     */
    public List<Long> listOrderSegments(long orderId) {
        return jdbc.query("SELECT segment_id FROM maintenance_order_segment WHERE order_id = ? "
                        + "ORDER BY segment_id",
                (rs, n) -> rs.getLong("segment_id"), orderId);
    }

    /**
     * 取消工单并递增版本；版本或状态不匹配返回 false。
     */
    public boolean cancel(long orderId, int expectedVersion, Instant cancelledAt) {
        return jdbc.update("UPDATE maintenance_order SET status = ?, cancelled_at = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = ?",
                MaintenanceOrderStatus.CANCELLED.name(), Timestamp.from(cancelledAt),
                orderId, expectedVersion, MaintenanceOrderStatus.SCHEDULED.name()) == 1;
    }

    /**
     * 完成工单（验收齐全）并递增版本；版本或状态不匹配返回 false。
     */
    public boolean complete(long orderId, int expectedVersion, Instant completedAt) {
        return jdbc.update("UPDATE maintenance_order SET status = ?, completed_at = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = ?",
                MaintenanceOrderStatus.COMPLETED.name(), Timestamp.from(completedAt),
                orderId, expectedVersion, MaintenanceOrderStatus.SCHEDULED.name()) == 1;
    }

    /**
     * 仅递增版本；版本不匹配返回 false。
     */
    public boolean bumpVersion(long orderId, int expectedVersion) {
        return jdbc.update("UPDATE maintenance_order SET version = version + 1 "
                        + "WHERE id = ? AND version = ?", orderId, expectedVersion) == 1;
    }

    /**
     * 登记分段清空证据；同一工单同一分段重复登记返回 false。
     */
    public boolean insertClearance(long orderId, long segmentId, String operatorId, String note) {
        try {
            jdbc.update("INSERT INTO maintenance_segment_clearance "
                            + "(order_id, segment_id, operator_id, note) VALUES (?, ?, ?, ?)",
                    orderId, segmentId, operatorId, note);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 工单全部分段清空证据。
     */
    public List<MaintenanceSegmentClearance> listClearances(long orderId) {
        return jdbc.query("SELECT segment_id, operator_id, note, created_at "
                        + "FROM maintenance_segment_clearance WHERE order_id = ? ORDER BY segment_id",
                (rs, n) -> new MaintenanceSegmentClearance(rs.getLong("segment_id"),
                        rs.getString("operator_id"), rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()), orderId);
    }

    /**
     * 登记验收记录；同一工单同一岗位重复验收返回 false。
     */
    public boolean insertAcceptance(long orderId, AcceptanceRole role, String operatorId, String opinion) {
        try {
            jdbc.update("INSERT INTO maintenance_acceptance (order_id, role, operator_id, opinion) "
                            + "VALUES (?, ?, ?, ?)",
                    orderId, role.name(), operatorId, opinion);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 工单全部验收记录（按登记顺序）。
     */
    public List<MaintenanceAcceptance> listAcceptances(long orderId) {
        return jdbc.query("SELECT role, operator_id, opinion, created_at "
                        + "FROM maintenance_acceptance WHERE order_id = ? ORDER BY id",
                (rs, n) -> new MaintenanceAcceptance(AcceptanceRole.valueOf(rs.getString("role")),
                        rs.getString("operator_id"), rs.getString("opinion"),
                        rs.getTimestamp("created_at").toInstant()), orderId);
    }
}
