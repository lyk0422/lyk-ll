package com.example.starter.transition.repo;

import com.example.starter.transition.model.TransitionOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 改品种单仓储。
 */
@Repository
public class TransitionOrderRepository {

    private static final RowMapper<TransitionOrder> MAPPER = (rs, n) -> {
        TransitionOrder o = new TransitionOrder();
        o.setId(rs.getLong("id"));
        o.setOrderNo(rs.getString("order_no"));
        o.setPaperMachine(rs.getString("paper_machine"));
        o.setFromGrade(rs.getString("from_grade"));
        o.setToGrade(rs.getString("to_grade"));
        o.setBasisWeightFrom(rs.getBigDecimal("basis_weight_from"));
        o.setBasisWeightTo(rs.getBigDecimal("basis_weight_to"));
        o.setAshLevelFrom(rs.getInt("ash_level_from"));
        o.setAshLevelTo(rs.getInt("ash_level_to"));
        o.setBasisWeightDeltaPct(rs.getBigDecimal("basis_weight_delta_pct"));
        Integer ashDelta = rs.getObject("ash_level_delta", Integer.class);
        o.setAshLevelDelta(ashDelta);
        o.setDirectSwitch(getNullableBoolean(rs, "direct_switch"));
        o.setWireFlush(getNullableBoolean(rs, "wire_flush"));
        o.setSwitchTimeSec(rs.getObject("switch_time_sec", Integer.class));
        o.setMachineSpeedMMin(rs.getBigDecimal("machine_speed_m_min"));
        o.setStockConsistencyPct(rs.getBigDecimal("stock_consistency_pct"));
        o.setTransitionLengthM(rs.getBigDecimal("transition_length_m"));
        o.setCurrentBlendVersion(rs.getObject("current_blend_version", Integer.class));
        o.setConsecutiveOutOfTolerance(rs.getInt("consecutive_out_of_tolerance"));
        Timestamp paused = rs.getTimestamp("paused_at");
        o.setPausedAt(paused == null ? null : paused.toLocalDateTime());
        Timestamp resumed = rs.getTimestamp("resumed_at");
        o.setResumedAt(resumed == null ? null : resumed.toLocalDateTime());
        o.setStatus(rs.getString("status"));
        o.setAppliedRequestId(rs.getString("applied_request_id"));
        o.setAppliedAt(rs.getTimestamp("applied_at").toLocalDateTime());
        Timestamp decided = rs.getTimestamp("decided_at");
        o.setDecidedAt(decided == null ? null : decided.toLocalDateTime());
        Timestamp executed = rs.getTimestamp("executed_at");
        o.setExecutedAt(executed == null ? null : executed.toLocalDateTime());
        Timestamp disposed = rs.getTimestamp("disposed_at");
        o.setDisposedAt(disposed == null ? null : disposed.toLocalDateTime());
        Timestamp completed = rs.getTimestamp("completed_at");
        o.setCompletedAt(completed == null ? null : completed.toLocalDateTime());
        Timestamp archived = rs.getTimestamp("archived_at");
        o.setArchivedAt(archived == null ? null : archived.toLocalDateTime());
        return o;
    };

    private final JdbcTemplate jdbc;

    public TransitionOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TransitionOrder o) {
        jdbc.update("""
                        INSERT INTO transition_order (
                            order_no, paper_machine, from_grade, to_grade,
                            basis_weight_from, basis_weight_to, ash_level_from, ash_level_to,
                            status, applied_request_id, applied_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)
                        """,
                o.getOrderNo(), o.getPaperMachine(), o.getFromGrade(), o.getToGrade(),
                o.getBasisWeightFrom(), o.getBasisWeightTo(),
                o.getAshLevelFrom(), o.getAshLevelTo(),
                o.getStatus(), o.getAppliedRequestId(), Timestamp.valueOf(o.getAppliedAt()));
    }

    public Optional<TransitionOrder> findByOrderNo(String orderNo) {
        List<TransitionOrder> list = jdbc.query(
                "SELECT * FROM transition_order WHERE order_no = ?", MAPPER, orderNo);
        return list.stream().findFirst();
    }

    public TransitionOrder getByOrderNo(String orderNo) {
        return findByOrderNo(orderNo).orElseThrow(() ->
                new IllegalArgumentException("改品种单不存在: " + orderNo));
    }

    /** 方式判定结果回写。 */
    public void updateDecision(TransitionOrder o) {
        jdbc.update("""
                        UPDATE transition_order
                        SET basis_weight_delta_pct = ?, ash_level_delta = ?,
                            direct_switch = ?, wire_flush = ?,
                            status = ?, decided_at = ?
                        WHERE order_no = ?
                        """,
                o.getBasisWeightDeltaPct(), o.getAshLevelDelta(),
                o.getDirectSwitch(), o.getWireFlush(),
                o.getStatus(), Timestamp.valueOf(o.getDecidedAt()), o.getOrderNo());
    }

    /** 执行结果回写：工艺参数、切换时间、过渡料长度。 */
    public void updateExecution(TransitionOrder o) {
        jdbc.update("""
                        UPDATE transition_order
                        SET switch_time_sec = ?, machine_speed_m_min = ?,
                            stock_consistency_pct = ?, transition_length_m = ?,
                            status = ?, executed_at = ?
                        WHERE order_no = ?
                        """,
                o.getSwitchTimeSec(), o.getMachineSpeedMMin(),
                o.getStockConsistencyPct(), o.getTransitionLengthM(),
                o.getStatus(), Timestamp.valueOf(o.getExecutedAt()), o.getOrderNo());
    }

    public void updateStatus(String orderNo, String status, String timeColumn,
                             java.time.LocalDateTime time) {
        jdbc.update("UPDATE transition_order SET status = ?, " + timeColumn + " = ? WHERE order_no = ?",
                status, Timestamp.valueOf(time), orderNo);
    }

    /** 申请携带比例：判定结论同事务写入当前比例版本号。 */
    public void updateBlendVersion(String orderNo, int versionNo) {
        jdbc.update("UPDATE transition_order SET current_blend_version = ? WHERE order_no = ?",
                versionNo, orderNo);
    }

    /** 比例调整：切换当前版本并清零连续超差计数；仅执行中允许，返回更新行数。 */
    public int updateBlendAdjust(String orderNo, int versionNo) {
        return jdbc.update("""
                        UPDATE transition_order
                        SET current_blend_version = ?, consecutive_out_of_tolerance = 0
                        WHERE order_no = ? AND status = 'EXECUTED'
                        """,
                versionNo, orderNo);
    }

    /** 读数登记后回写连续超差计数；达到失控阈值时同事务置 PAUSED 转人工。仅执行中允许，返回更新行数。 */
    public int updateBlendControl(TransitionOrder o) {
        return jdbc.update("""
                        UPDATE transition_order
                        SET consecutive_out_of_tolerance = ?, status = ?, paused_at = ?
                        WHERE order_no = ? AND status = 'EXECUTED'
                        """,
                o.getConsecutiveOutOfTolerance(), o.getStatus(),
                o.getPausedAt() == null ? null : Timestamp.valueOf(o.getPausedAt()),
                o.getOrderNo());
    }

    /** 复核确认恢复：回到 EXECUTED，清零连续超差计数并登记恢复时间；仅暂停中允许，返回更新行数。 */
    public int updateResume(String orderNo, java.time.LocalDateTime resumedAt) {
        return jdbc.update("""
                        UPDATE transition_order
                        SET status = 'EXECUTED', consecutive_out_of_tolerance = 0, resumed_at = ?
                        WHERE order_no = ? AND status = 'PAUSED'
                        """,
                Timestamp.valueOf(resumedAt), orderNo);
    }

    private static Boolean getNullableBoolean(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Integer v = rs.getObject(column, Integer.class);
        return v == null ? null : v != 0;
    }
}
