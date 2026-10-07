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

import com.example.starter.domain.ControlledPlan;
import com.example.starter.domain.ControlledPlanStatus;
import com.example.starter.domain.ControlledPlanStep;
import com.example.starter.domain.ControlledPlanTurbine;

/**
 * 受控分段计划数据访问：计划版本、机组参数、冻结阶梯、机组占用与整分钟确认记录。
 */
@Repository
public class ControlledPlanRepository {

    private final JdbcTemplate jdbc;

    public ControlledPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ControlledPlan> PLAN_MAPPER = (rs, n) -> {
        ControlledPlan p = new ControlledPlan();
        p.setId(rs.getLong("id"));
        p.setOrderId(rs.getLong("order_id"));
        p.setPlanVersion(rs.getInt("plan_version"));
        p.setStatus(ControlledPlanStatus.valueOf(rs.getString("status")));
        p.setStartAt(rs.getTimestamp("start_at").toInstant());
        p.setDurationMinutes(rs.getInt("duration_minutes"));
        p.setConfirmedMinute(rs.getInt("confirmed_minute"));
        p.setRequestId(rs.getString("request_id"));
        p.setFingerprint(rs.getString("fingerprint"));
        p.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp closed = rs.getTimestamp("closed_at");
        p.setClosedAt(closed == null ? null : closed.toInstant());
        return p;
    };

    private static final RowMapper<ControlledPlanTurbine> TURBINE_MAPPER = (rs, n) -> {
        ControlledPlanTurbine t = new ControlledPlanTurbine();
        t.setId(rs.getLong("id"));
        t.setPlanId(rs.getLong("plan_id"));
        t.setTurbineId(rs.getLong("turbine_id"));
        t.setStartPowerKw(rs.getInt("start_power_kw"));
        t.setRampRateKwPerMin(rs.getInt("ramp_rate_kw_per_min"));
        t.setDurationMinutes(rs.getInt("duration_minutes"));
        t.setTargetPowerKw(rs.getInt("target_power_kw"));
        return t;
    };

    private static final RowMapper<ControlledPlanStep> STEP_MAPPER = (rs, n) -> {
        ControlledPlanStep s = new ControlledPlanStep();
        s.setId(rs.getLong("id"));
        s.setPlanId(rs.getLong("plan_id"));
        s.setTurbineId(rs.getLong("turbine_id"));
        s.setMinuteNo(rs.getInt("minute_no"));
        s.setPowerKw(rs.getInt("power_kw"));
        return s;
    };

    public long insertPlan(ControlledPlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO controlled_plan
                        (order_id, plan_version, status, start_at, duration_minutes,
                         confirmed_minute, request_id, fingerprint, created_at, closed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, plan.getOrderId());
            ps.setInt(2, plan.getPlanVersion());
            ps.setString(3, plan.getStatus().name());
            ps.setTimestamp(4, Timestamp.from(plan.getStartAt()));
            ps.setInt(5, plan.getDurationMinutes());
            ps.setInt(6, plan.getConfirmedMinute());
            ps.setString(7, plan.getRequestId());
            ps.setString(8, plan.getFingerprint());
            ps.setTimestamp(9, Timestamp.from(plan.getCreatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        plan.setId(id);
        return id;
    }

    /** 指令最新（版本号最大）的分段计划版本；无则 null。 */
    public ControlledPlan findLatestPlan(long orderId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM controlled_plan WHERE order_id = ? ORDER BY plan_version DESC LIMIT 1",
                    PLAN_MAPPER, orderId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<ControlledPlan> findPlans(long orderId) {
        return jdbc.query(
                "SELECT * FROM controlled_plan WHERE order_id = ? ORDER BY plan_version",
                PLAN_MAPPER, orderId);
    }

    public void updateProgress(long planId, int confirmedMinute) {
        jdbc.update("UPDATE controlled_plan SET confirmed_minute = ? WHERE id = ?",
                confirmedMinute, planId);
    }

    public void updateStatus(long planId, ControlledPlanStatus status, Instant closedAt) {
        jdbc.update("UPDATE controlled_plan SET status = ?, closed_at = ? WHERE id = ?",
                status.name(), closedAt == null ? null : Timestamp.from(closedAt), planId);
    }

    public void insertPlanTurbines(long planId, List<ControlledPlanTurbine> turbines) {
        jdbc.batchUpdate("""
                INSERT INTO controlled_plan_turbine
                    (plan_id, turbine_id, start_power_kw, ramp_rate_kw_per_min,
                     duration_minutes, target_power_kw)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                turbines, turbines.size(), (ps, t) -> {
                    ps.setLong(1, planId);
                    ps.setLong(2, t.getTurbineId());
                    ps.setInt(3, t.getStartPowerKw());
                    ps.setInt(4, t.getRampRateKwPerMin());
                    ps.setInt(5, t.getDurationMinutes());
                    ps.setInt(6, t.getTargetPowerKw());
                });
    }

    public List<ControlledPlanTurbine> findPlanTurbines(long planId) {
        return jdbc.query(
                "SELECT * FROM controlled_plan_turbine WHERE plan_id = ? ORDER BY id",
                TURBINE_MAPPER, planId);
    }

    public void insertSteps(long planId, List<ControlledPlanStep> steps) {
        jdbc.batchUpdate("""
                INSERT INTO controlled_plan_step (plan_id, turbine_id, minute_no, power_kw)
                VALUES (?, ?, ?, ?)
                """,
                steps, steps.size(), (ps, s) -> {
                    ps.setLong(1, planId);
                    ps.setLong(2, s.getTurbineId());
                    ps.setInt(3, s.getMinuteNo());
                    ps.setInt(4, s.getPowerKw());
                });
    }

    public List<ControlledPlanStep> findSteps(long planId) {
        return jdbc.query(
                "SELECT * FROM controlled_plan_step WHERE plan_id = ? ORDER BY turbine_id, minute_no",
                STEP_MAPPER, planId);
    }

    public List<ControlledPlanStep> findSteps(long planId, int minuteNo) {
        return jdbc.query(
                "SELECT * FROM controlled_plan_step WHERE plan_id = ? AND minute_no = ? ORDER BY turbine_id",
                STEP_MAPPER, planId, minuteNo);
    }

    /** 登记机组占用；主键冲突（同机组已有执行中/暂停中计划）返回 false。 */
    public boolean tryOccupy(long turbineId, long orderId, long planId, Instant occupiedAt) {
        try {
            jdbc.update(
                    "INSERT INTO controlled_occupation (turbine_id, order_id, plan_id, occupied_at) "
                            + "VALUES (?, ?, ?, ?)",
                    turbineId, orderId, planId, Timestamp.from(occupiedAt));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return false;
        }
    }

    /** 占用随计划版本迁移（重规划不释放占用，仅换绑新版本）。 */
    public void moveOccupationToPlan(long orderId, long newPlanId) {
        jdbc.update("UPDATE controlled_occupation SET plan_id = ? WHERE order_id = ?",
                newPlanId, orderId);
    }

    /** 释放指令全部机组占用（取消/完成/被替换/被新指令作废）。 */
    public void releaseOccupations(long orderId) {
        jdbc.update("DELETE FROM controlled_occupation WHERE order_id = ?", orderId);
    }

    public int countOccupations(long orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_occupation WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    /**
     * 关闭指令仍有效的计划版本（EXECUTING/PAUSED → REPLACED）并释放占用；
     * 用于被新指令原子切换或日结封存时。无有效版本时不动作。
     */
    public void closeOpenPlanAndRelease(long orderId, Instant closedAt) {
        jdbc.update("""
                UPDATE controlled_plan SET status = 'REPLACED', closed_at = ?
                WHERE order_id = ? AND status IN ('EXECUTING', 'PAUSED')
                """, Timestamp.from(closedAt), orderId);
        releaseOccupations(orderId);
    }

    /** 登记整分钟确认；唯一索引兜底并发重复确认同一分钟。 */
    public void insertMinuteConfirm(long planId, int minuteNo, String requestId, Instant confirmedAt) {
        jdbc.update(
                "INSERT INTO controlled_minute_confirm (plan_id, minute_no, request_id, confirmed_at) "
                        + "VALUES (?, ?, ?, ?)",
                planId, minuteNo, requestId, Timestamp.from(confirmedAt));
    }

    public int countMinuteConfirms(long planId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_minute_confirm WHERE plan_id = ?",
                Integer.class, planId);
        return count == null ? 0 : count;
    }
}
