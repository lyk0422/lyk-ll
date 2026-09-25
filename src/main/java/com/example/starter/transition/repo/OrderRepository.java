package com.example.starter.transition.repo;

import com.example.starter.transition.po.TransitionOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 改品种单数据访问；状态推进均使用带当前状态条件的 CAS 更新。
 */
@Repository
public class OrderRepository {

    /** 占用纸机的活动状态：已申请/执行中/已处置。 */
    public static final List<String> ACTIVE_STATUSES = List.of("APPLIED", "EXECUTING", "DISPOSED");

    private static final String COLUMNS = "id, order_no, machine_id, from_grade_code, to_grade_code, "
            + "basis_weight_from, basis_weight_to, ash_from, ash_to, status, switch_mode, wire_flush, "
            + "switch_seconds, machine_speed, consistency, transition_length_m, "
            + "created_at, executed_at, completed_at, archived_at";

    private static final RowMapper<TransitionOrder> MAPPER = new OrderRowMapper();

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TransitionOrder order) {
        jdbc.update("INSERT INTO transition_order (order_no, machine_id, from_grade_code, to_grade_code, "
                        + "basis_weight_from, basis_weight_to, ash_from, ash_to, status, switch_mode, wire_flush) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.orderNo, order.machineId, order.fromGradeCode, order.toGradeCode,
                order.basisWeightFrom, order.basisWeightTo, order.ashFrom, order.ashTo,
                order.status, order.switchMode, order.wireFlush ? 1 : 0);
    }

    public Optional<TransitionOrder> findByOrderNo(String orderNo) {
        List<TransitionOrder> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM transition_order WHERE order_no = ?", MAPPER, orderNo);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取，用于事务内状态推进。
     */
    public Optional<TransitionOrder> findByOrderNoForUpdate(String orderNo) {
        List<TransitionOrder> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM transition_order WHERE order_no = ? FOR UPDATE",
                MAPPER, orderNo);
        return list.stream().findFirst();
    }

    public boolean existsActiveByMachine(String machineId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM transition_order WHERE machine_id = ? AND status IN (?, ?, ?)",
                Integer.class, machineId,
                ACTIVE_STATUSES.get(0), ACTIVE_STATUSES.get(1), ACTIVE_STATUSES.get(2));
        return count != null && count > 0;
    }

    /**
     * 执行登记：仅 APPLIED 可推进到 EXECUTING，并写入关键参数与过渡料长度。
     *
     * @return 受影响行数；0 表示状态已被并发改动
     */
    public int casExecute(TransitionOrder order, LocalDateTime executedAt) {
        return jdbc.update("UPDATE transition_order SET status = 'EXECUTING', switch_seconds = ?, "
                        + "machine_speed = ?, consistency = ?, transition_length_m = ?, executed_at = ? "
                        + "WHERE order_no = ? AND status = 'APPLIED'",
                order.switchSeconds, order.machineSpeed, order.consistency,
                order.transitionLengthM, Timestamp.valueOf(executedAt), order.orderNo);
    }

    /** 执行中 → 过渡料已处置。 */
    public int casDispose(String orderNo) {
        return jdbc.update("UPDATE transition_order SET status = 'DISPOSED' "
                + "WHERE order_no = ? AND status = 'EXECUTING'", orderNo);
    }

    /** 完成：执行中或已处置均可，写入完成时间。 */
    public int casComplete(String orderNo, LocalDateTime at) {
        return jdbc.update("UPDATE transition_order SET status = 'COMPLETED', completed_at = ? "
                        + "WHERE order_no = ? AND status IN ('EXECUTING', 'DISPOSED')",
                Timestamp.valueOf(at), orderNo);
    }

    /** 归档（终态）：仅已完成可归档。 */
    public int casArchive(String orderNo, LocalDateTime at) {
        return jdbc.update("UPDATE transition_order SET status = 'ARCHIVED', archived_at = ? "
                        + "WHERE order_no = ? AND status = 'COMPLETED'",
                Timestamp.valueOf(at), orderNo);
    }

    private static final class OrderRowMapper implements RowMapper<TransitionOrder> {
        @Override
        public TransitionOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            TransitionOrder o = new TransitionOrder();
            o.id = rs.getLong("id");
            o.orderNo = rs.getString("order_no");
            o.machineId = rs.getString("machine_id");
            o.fromGradeCode = rs.getString("from_grade_code");
            o.toGradeCode = rs.getString("to_grade_code");
            o.basisWeightFrom = rs.getBigDecimal("basis_weight_from");
            o.basisWeightTo = rs.getBigDecimal("basis_weight_to");
            o.ashFrom = rs.getInt("ash_from");
            o.ashTo = rs.getInt("ash_to");
            o.status = rs.getString("status");
            o.switchMode = rs.getString("switch_mode");
            o.wireFlush = rs.getInt("wire_flush") == 1;
            o.switchSeconds = (Integer) rs.getObject("switch_seconds");
            o.machineSpeed = rs.getBigDecimal("machine_speed");
            o.consistency = rs.getBigDecimal("consistency");
            o.transitionLengthM = rs.getBigDecimal("transition_length_m");
            o.createdAt = rs.getTimestamp("created_at").toLocalDateTime();
            Timestamp executed = rs.getTimestamp("executed_at");
            o.executedAt = executed == null ? null : executed.toLocalDateTime();
            Timestamp completed = rs.getTimestamp("completed_at");
            o.completedAt = completed == null ? null : completed.toLocalDateTime();
            Timestamp archived = rs.getTimestamp("archived_at");
            o.archivedAt = archived == null ? null : archived.toLocalDateTime();
            return o;
        }
    }
}
