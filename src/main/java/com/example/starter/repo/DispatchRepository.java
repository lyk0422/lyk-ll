package com.example.starter.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.AlertEvent;
import com.example.starter.domain.AlertType;
import com.example.starter.domain.Allocation;
import com.example.starter.domain.CurtailOrder;
import com.example.starter.domain.DispatchCycle;
import com.example.starter.domain.ExecutionReport;
import com.example.starter.domain.LateReportEvent;
import com.example.starter.domain.OrderStatus;

/**
 * 限电指令聚合数据访问：指令、轮次、分摊、回报、告警、迟到事件。
 */
@Repository
public class DispatchRepository {

    private final JdbcTemplate jdbc;

    public DispatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CurtailOrder> ORDER_MAPPER = (rs, n) -> {
        CurtailOrder o = new CurtailOrder();
        o.setId(rs.getLong("id"));
        o.setOrderNo(rs.getString("order_no"));
        o.setFarmCode(rs.getString("farm_code"));
        o.setRequestId(rs.getString("request_id"));
        o.setFingerprint(rs.getString("fingerprint"));
        o.setTargetPowerKw(rs.getInt("target_power_kw"));
        o.setStatus(OrderStatus.valueOf(rs.getString("status")));
        o.setCurrentCycleNo(rs.getInt("current_cycle_no"));
        long sup = rs.getLong("superseded_by_order_id");
        o.setSupersededByOrderId(rs.wasNull() ? null : sup);
        o.setReceivedAt(rs.getTimestamp("received_at").toInstant());
        Timestamp settled = rs.getTimestamp("settled_at");
        o.setSettledAt(settled == null ? null : settled.toInstant());
        long planVersionId = rs.getLong("plan_version_id");
        o.setPlanVersionId(rs.wasNull() ? null : planVersionId);
        int planVersionNo = rs.getInt("plan_version_no");
        o.setPlanVersionNo(rs.wasNull() ? null : planVersionNo);
        java.sql.Date planDate = rs.getDate("plan_date");
        o.setPlanDate(planDate == null ? null : planDate.toLocalDate());
        int segmentIndex = rs.getInt("segment_index");
        o.setSegmentIndex(rs.wasNull() ? null : segmentIndex);
        o.setControlledFlag(rs.getString("controlled_flag"));
        return o;
    };

    private static final RowMapper<DispatchCycle> CYCLE_MAPPER = (rs, n) -> {
        DispatchCycle c = new DispatchCycle();
        c.setId(rs.getLong("id"));
        c.setOrderId(rs.getLong("order_id"));
        c.setCycleNo(rs.getInt("cycle_no"));
        c.setDispatchedAt(rs.getTimestamp("dispatched_at").toInstant());
        c.setDueAt(rs.getTimestamp("due_at").toInstant());
        return c;
    };

    private static final RowMapper<Allocation> ALLOCATION_MAPPER = (rs, n) -> {
        Allocation a = new Allocation();
        a.setId(rs.getLong("id"));
        a.setOrderId(rs.getLong("order_id"));
        a.setCycleNo(rs.getInt("cycle_no"));
        a.setTurbineId(rs.getLong("turbine_id"));
        a.setBaseAvailableKw(rs.getInt("base_available_kw"));
        a.setTargetPowerKw(rs.getInt("target_power_kw"));
        a.setCurtailKw(rs.getInt("curtail_kw"));
        int planned = rs.getInt("planned_power_kw");
        a.setPlannedPowerKw(rs.wasNull() ? null : planned);
        int realtime = rs.getInt("realtime_available_kw");
        a.setRealtimeAvailableKw(rs.wasNull() ? null : realtime);
        return a;
    };

    private static final RowMapper<ExecutionReport> REPORT_MAPPER = (rs, n) -> {
        ExecutionReport r = new ExecutionReport();
        r.setId(rs.getLong("id"));
        r.setRequestId(rs.getString("request_id"));
        r.setOrderId(rs.getLong("order_id"));
        r.setTurbineId(rs.getLong("turbine_id"));
        r.setMeasuredAt(rs.getTimestamp("measured_at").toInstant());
        r.setPowerKw(rs.getInt("power_kw"));
        r.setReceivedAt(rs.getTimestamp("received_at").toInstant());
        return r;
    };

    private static final RowMapper<AlertEvent> ALERT_MAPPER = (rs, n) -> {
        AlertEvent a = new AlertEvent();
        a.setId(rs.getLong("id"));
        a.setOrderId(rs.getLong("order_id"));
        a.setCycleNo(rs.getInt("cycle_no"));
        a.setType(AlertType.valueOf(rs.getString("type")));
        a.setFiredAt(rs.getTimestamp("fired_at").toInstant());
        int actual = rs.getInt("farm_actual_kw");
        a.setFarmActualKw(rs.wasNull() ? null : actual);
        int reallocated = rs.getInt("reallocated_cycle_no");
        a.setReallocatedCycleNo(rs.wasNull() ? null : reallocated);
        return a;
    };

    private static final RowMapper<LateReportEvent> LATE_MAPPER = (rs, n) -> {
        LateReportEvent e = new LateReportEvent();
        e.setId(rs.getLong("id"));
        e.setOrderId(rs.getLong("order_id"));
        e.setTurbineCode(rs.getString("turbine_code"));
        e.setMeasuredAt(rs.getTimestamp("measured_at").toInstant());
        int power = rs.getInt("power_kw");
        e.setPowerKw(rs.wasNull() ? null : power);
        e.setRequestId(rs.getString("request_id"));
        e.setRecordedAt(rs.getTimestamp("recorded_at").toInstant());
        return e;
    };

    public long insertOrder(CurtailOrder order) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO curtail_order
                        (order_no, farm_code, request_id, fingerprint, target_power_kw,
                         status, current_cycle_no, superseded_by_order_id, active_flag,
                         received_at, settled_at, plan_version_id, plan_version_no,
                         plan_date, segment_index, controlled_flag)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, order.getOrderNo());
            ps.setString(2, order.getFarmCode());
            ps.setString(3, order.getRequestId());
            ps.setString(4, order.getFingerprint());
            ps.setInt(5, order.getTargetPowerKw());
            ps.setString(6, order.getStatus().name());
            ps.setInt(7, order.getCurrentCycleNo());
            if (order.getSupersededByOrderId() == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, order.getSupersededByOrderId());
            }
            // 新指令一律先以非执行中（active_flag=NULL）插入，由服务在旧指令作废后显式置 Y，
            // 保证同一事务内不会出现两个执行中指令。
            ps.setNull(9, java.sql.Types.CHAR);
            ps.setTimestamp(10, Timestamp.from(order.getReceivedAt()));
            ps.setTimestamp(11, order.getSettledAt() == null ? null : Timestamp.from(order.getSettledAt()));
            if (order.getPlanVersionId() == null) {
                ps.setNull(12, java.sql.Types.BIGINT);
            } else {
                ps.setLong(12, order.getPlanVersionId());
            }
            if (order.getPlanVersionNo() == null) {
                ps.setNull(13, java.sql.Types.INTEGER);
            } else {
                ps.setInt(13, order.getPlanVersionNo());
            }
            if (order.getPlanDate() == null) {
                ps.setNull(14, java.sql.Types.DATE);
            } else {
                ps.setDate(14, java.sql.Date.valueOf(order.getPlanDate()));
            }
            if (order.getSegmentIndex() == null) {
                ps.setNull(15, java.sql.Types.INTEGER);
            } else {
                ps.setInt(15, order.getSegmentIndex());
            }
            if (order.getControlledFlag() == null) {
                ps.setNull(16, java.sql.Types.CHAR);
            } else {
                ps.setString(16, order.getControlledFlag());
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        order.setId(id);
        return id;
    }

    public CurtailOrder findOrderById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM curtail_order WHERE id = ?", ORDER_MAPPER, id);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public CurtailOrder findOrderByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM curtail_order WHERE request_id = ?", ORDER_MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public CurtailOrder findOrderByNo(String orderNo) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM curtail_order WHERE order_no = ?", ORDER_MAPPER, orderNo);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询风场当前执行中（active_flag=Y）指令；必须在持有 wind_farm 行锁的事务内调用。 */
    public CurtailOrder findActiveOrder(String farmCode) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM curtail_order WHERE farm_code = ? AND active_flag = 'Y'",
                    ORDER_MAPPER, farmCode);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public CurtailOrder findLatestOrder(String farmCode) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM curtail_order WHERE farm_code = ? ORDER BY id DESC LIMIT 1",
                    ORDER_MAPPER, farmCode);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 锁定指令行（SELECT ... FOR UPDATE），串行化同指令的并发回报与告警重分摊。 */
    public CurtailOrder lockOrderById(long orderId) {
        return jdbc.queryForObject(
                "SELECT * FROM curtail_order WHERE id = ? FOR UPDATE", ORDER_MAPPER, orderId);
    }

    public void clearActiveFlag(long orderId) {
        jdbc.update("UPDATE curtail_order SET active_flag = NULL WHERE id = ?", orderId);
    }

    /** 将新指令置为执行中（active_flag=Y）；唯一索引保证全场至多一个 Y。 */
    public void activateOrder(long orderId) {
        jdbc.update("UPDATE curtail_order SET active_flag = 'Y' WHERE id = ?", orderId);
    }

    public void markSuperseded(long orderId, long newOrderId) {
        jdbc.update(
                "UPDATE curtail_order SET status = 'SUPERSEDED', active_flag = NULL, "
                        + "superseded_by_order_id = ? WHERE id = ?",
                newOrderId, orderId);
    }

    public void updateCurrentCycle(long orderId, int cycleNo) {
        jdbc.update("UPDATE curtail_order SET current_cycle_no = ? WHERE id = ?", cycleNo, orderId);
    }

    public void markSettled(long orderId, Instant settledAt) {
        jdbc.update(
                "UPDATE curtail_order SET status = 'SETTLED', active_flag = NULL, settled_at = ? WHERE id = ?",
                Timestamp.from(settledAt), orderId);
    }

    /** 受控分段阶梯最后一分钟确认完成：指令终态 COMPLETED 并释放执行中标记。 */
    public void markCompleted(long orderId) {
        jdbc.update(
                "UPDATE curtail_order SET status = 'COMPLETED', active_flag = NULL WHERE id = ?",
                orderId);
    }

    /** 受控分段计划暂停后取消：指令终态 CANCELLED 并释放执行中标记。 */
    public void markCancelled(long orderId) {
        jdbc.update(
                "UPDATE curtail_order SET status = 'CANCELLED', active_flag = NULL WHERE id = ?",
                orderId);
    }

    public void insertCycle(DispatchCycle cycle) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO dispatch_cycle (order_id, cycle_no, dispatched_at, due_at)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, cycle.getOrderId());
            ps.setInt(2, cycle.getCycleNo());
            ps.setTimestamp(3, Timestamp.from(cycle.getDispatchedAt()));
            ps.setTimestamp(4, Timestamp.from(cycle.getDueAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            cycle.setId(key.longValue());
        }
    }

    public List<DispatchCycle> findCycles(long orderId) {
        return jdbc.query(
                "SELECT * FROM dispatch_cycle WHERE order_id = ? ORDER BY cycle_no",
                CYCLE_MAPPER, orderId);
    }

    public void insertAllocations(long orderId, int cycleNo, List<Allocation> allocations) {
        jdbc.batchUpdate("""
                INSERT INTO allocation
                    (order_id, cycle_no, turbine_id, base_available_kw, target_power_kw, curtail_kw,
                     planned_power_kw, realtime_available_kw)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                allocations, allocations.size(), (ps, a) -> {
                    ps.setLong(1, orderId);
                    ps.setInt(2, cycleNo);
                    ps.setLong(3, a.getTurbineId());
                    ps.setInt(4, a.getBaseAvailableKw());
                    ps.setInt(5, a.getTargetPowerKw());
                    ps.setInt(6, a.getCurtailKw());
                    if (a.getPlannedPowerKw() == null) {
                        ps.setNull(7, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(7, a.getPlannedPowerKw());
                    }
                    if (a.getRealtimeAvailableKw() == null) {
                        ps.setNull(8, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(8, a.getRealtimeAvailableKw());
                    }
                });
    }

    public List<Allocation> findAllocations(long orderId, int cycleNo) {
        return jdbc.query(
                "SELECT * FROM allocation WHERE order_id = ? AND cycle_no = ? ORDER BY id",
                ALLOCATION_MAPPER, orderId, cycleNo);
    }

    public List<Allocation> findAllAllocations(long orderId) {
        return jdbc.query(
                "SELECT * FROM allocation WHERE order_id = ? ORDER BY cycle_no, id",
                ALLOCATION_MAPPER, orderId);
    }

    public long insertReport(ExecutionReport report) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO execution_report
                        (request_id, order_id, turbine_id, measured_at, power_kw, received_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, report.getRequestId());
            ps.setLong(2, report.getOrderId());
            ps.setLong(3, report.getTurbineId());
            ps.setTimestamp(4, Timestamp.from(report.getMeasuredAt()));
            ps.setInt(5, report.getPowerKw());
            ps.setTimestamp(6, Timestamp.from(report.getReceivedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        report.setId(id);
        return id;
    }

    public ExecutionReport findReportByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM execution_report WHERE request_id = ?", REPORT_MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public ExecutionReport findReportByTurbineAndTime(long turbineId, Instant measuredAt) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM execution_report WHERE turbine_id = ? AND measured_at = ?",
                    REPORT_MAPPER, turbineId, Timestamp.from(measuredAt));
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 风场全部执行回报，按机组、出力时刻排序——同机组乱序回报在此归位。 */
    public List<ExecutionReport> findReportsOfFarm(String farmCode) {
        return jdbc.query("""
                SELECT r.* FROM execution_report r
                JOIN wind_turbine t ON r.turbine_id = t.id
                WHERE t.farm_code = ?
                ORDER BY r.turbine_id, r.measured_at
                """, REPORT_MAPPER, farmCode);
    }

    public void insertAlert(AlertEvent alert) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO alert_event
                        (order_id, cycle_no, type, fired_at, farm_actual_kw, reallocated_cycle_no)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, alert.getOrderId());
            ps.setInt(2, alert.getCycleNo());
            ps.setString(3, alert.getType().name());
            ps.setTimestamp(4, Timestamp.from(alert.getFiredAt()));
            if (alert.getFarmActualKw() == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, alert.getFarmActualKw());
            }
            if (alert.getReallocatedCycleNo() == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, alert.getReallocatedCycleNo());
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            alert.setId(key.longValue());
        }
    }

    public void updateAlertReallocated(long orderId, int cycleNo, int newCycleNo) {
        jdbc.update(
                "UPDATE alert_event SET reallocated_cycle_no = ? WHERE order_id = ? AND cycle_no = ?",
                newCycleNo, orderId, cycleNo);
    }

    public AlertEvent findAlert(long orderId, int cycleNo) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM alert_event WHERE order_id = ? AND cycle_no = ?",
                    ALERT_MAPPER, orderId, cycleNo);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<AlertEvent> findAlerts(long orderId) {
        return jdbc.query(
                "SELECT * FROM alert_event WHERE order_id = ? ORDER BY cycle_no",
                ALERT_MAPPER, orderId);
    }

    public void insertLateReport(LateReportEvent event) {
        jdbc.update("""
                INSERT INTO late_report_event
                    (order_id, turbine_code, measured_at, power_kw, request_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                event.getOrderId(),
                event.getTurbineCode(),
                Timestamp.from(event.getMeasuredAt()),
                event.getPowerKw(),
                event.getRequestId(),
                Timestamp.from(event.getRecordedAt()));
    }

    public List<LateReportEvent> findLateReports(long orderId) {
        return jdbc.query(
                "SELECT * FROM late_report_event WHERE order_id = ? ORDER BY id",
                LATE_MAPPER, orderId);
    }
}
