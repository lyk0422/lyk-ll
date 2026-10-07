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

import com.example.starter.domain.StagedMinuteConfirm;
import com.example.starter.domain.StagedPlan;
import com.example.starter.domain.StagedPlanStatus;
import com.example.starter.domain.StagedPlanStep;
import com.example.starter.domain.StagedPlanTurbine;

/**
 * 受控分段计划数据访问：计划头（版本/状态/进度）、参与机组（占用标记）、
 * 冻结阶梯明细与分钟确认记录。占用唯一索引兜底每机组同一时刻至多一份执行中计划。
 */
@Repository
public class StagedPlanRepository {

    private final JdbcTemplate jdbc;

    public StagedPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<StagedPlan> PLAN_MAPPER = (rs, n) -> {
        StagedPlan p = new StagedPlan();
        p.setId(rs.getLong("id"));
        p.setOrderId(rs.getLong("order_id"));
        p.setPlanVersion(rs.getInt("plan_version"));
        p.setStatus(StagedPlanStatus.valueOf(rs.getString("status")));
        p.setStartAt(rs.getTimestamp("start_at").toInstant());
        p.setCurrentMinute(rs.getInt("current_minute"));
        p.setDurationMinutes(rs.getInt("duration_minutes"));
        p.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        p.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return p;
    };

    private static final RowMapper<StagedPlanTurbine> TURBINE_MAPPER = (rs, n) -> {
        StagedPlanTurbine t = new StagedPlanTurbine();
        t.setId(rs.getLong("id"));
        t.setPlanId(rs.getLong("plan_id"));
        t.setTurbineId(rs.getLong("turbine_id"));
        t.setStartPowerKw(rs.getInt("start_power_kw"));
        t.setTargetPowerKw(rs.getInt("target_power_kw"));
        t.setRampKwPerMin(rs.getInt("ramp_kw_per_min"));
        t.setDeadlineMinutes(rs.getInt("deadline_minutes"));
        t.setOccupiedFlag(rs.getString("occupied_flag"));
        return t;
    };

    private static final RowMapper<StagedPlanStep> STEP_MAPPER = (rs, n) -> {
        StagedPlanStep s = new StagedPlanStep();
        s.setId(rs.getLong("id"));
        s.setPlanId(rs.getLong("plan_id"));
        s.setPlanVersion(rs.getInt("plan_version"));
        s.setMinuteNo(rs.getInt("minute_no"));
        s.setTurbineId(rs.getLong("turbine_id"));
        s.setPowerKw(rs.getInt("power_kw"));
        return s;
    };

    private static final RowMapper<StagedMinuteConfirm> CONFIRM_MAPPER = (rs, n) -> {
        StagedMinuteConfirm c = new StagedMinuteConfirm();
        c.setId(rs.getLong("id"));
        c.setPlanId(rs.getLong("plan_id"));
        c.setPlanVersion(rs.getInt("plan_version"));
        c.setMinuteNo(rs.getInt("minute_no"));
        c.setRequestId(rs.getString("request_id"));
        c.setConfirmedAt(rs.getTimestamp("confirmed_at").toInstant());
        return c;
    };

    public long insertPlan(StagedPlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO staged_plan
                        (order_id, plan_version, status, start_at, current_minute,
                         duration_minutes, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, plan.getOrderId());
            ps.setInt(2, plan.getPlanVersion());
            ps.setString(3, plan.getStatus().name());
            ps.setTimestamp(4, Timestamp.from(plan.getStartAt()));
            ps.setInt(5, plan.getCurrentMinute());
            ps.setInt(6, plan.getDurationMinutes());
            ps.setTimestamp(7, Timestamp.from(plan.getCreatedAt()));
            ps.setTimestamp(8, Timestamp.from(plan.getUpdatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        plan.setId(id);
        return id;
    }

    public StagedPlan findPlanByOrderId(long orderId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM staged_plan WHERE order_id = ?", PLAN_MAPPER, orderId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 锁定计划行（SELECT ... FOR UPDATE），串行化回执确认、修改与取消。 */
    public StagedPlan lockPlanById(long planId) {
        return jdbc.queryForObject(
                "SELECT * FROM staged_plan WHERE id = ? FOR UPDATE", PLAN_MAPPER, planId);
    }

    /** 进度推进：已确认分钟序号更新（同事务内与实际功率、幂等记录一起提交）。 */
    public void updateProgress(long planId, int currentMinute, Instant updatedAt) {
        jdbc.update("UPDATE staged_plan SET current_minute = ?, updated_at = ? WHERE id = ?",
                currentMinute, Timestamp.from(updatedAt), planId);
    }

    public void updateStatus(long planId, StagedPlanStatus status, Instant updatedAt) {
        jdbc.update("UPDATE staged_plan SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.from(updatedAt), planId);
    }

    /** 重排剩余计划：版本加 1、重启启动时刻与进度、更新期限。 */
    public void reformPlan(long planId, int newVersion, Instant startAt, int durationMinutes,
                           Instant updatedAt) {
        jdbc.update("""
                UPDATE staged_plan
                SET plan_version = ?, status = 'EXECUTING', start_at = ?, current_minute = 0,
                    duration_minutes = ?, updated_at = ?
                WHERE id = ?
                """,
                newVersion, Timestamp.from(startAt), durationMinutes,
                Timestamp.from(updatedAt), planId);
    }

    public void insertTurbines(List<StagedPlanTurbine> turbines) {
        jdbc.batchUpdate("""
                INSERT INTO staged_plan_turbine
                    (plan_id, turbine_id, start_power_kw, target_power_kw,
                     ramp_kw_per_min, deadline_minutes, occupied_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                turbines, turbines.size(), (ps, t) -> {
                    ps.setLong(1, t.getPlanId());
                    ps.setLong(2, t.getTurbineId());
                    ps.setInt(3, t.getStartPowerKw());
                    ps.setInt(4, t.getTargetPowerKw());
                    ps.setInt(5, t.getRampKwPerMin());
                    ps.setInt(6, t.getDeadlineMinutes());
                    ps.setString(7, t.getOccupiedFlag());
                });
    }

    public List<StagedPlanTurbine> findTurbines(long planId) {
        return jdbc.query(
                "SELECT * FROM staged_plan_turbine WHERE plan_id = ? ORDER BY id",
                TURBINE_MAPPER, planId);
    }

    /** 重排/修改时刷新机组斜率与目标：起始功率为当时最新实际功率。 */
    public void updateTurbineSlope(long planId, long turbineId, int startPowerKw,
                                   int targetPowerKw, int rampKwPerMin, int deadlineMinutes) {
        jdbc.update("""
                UPDATE staged_plan_turbine
                SET start_power_kw = ?, target_power_kw = ?, ramp_kw_per_min = ?,
                    deadline_minutes = ?
                WHERE plan_id = ? AND turbine_id = ?
                """,
                startPowerKw, targetPowerKw, rampKwPerMin, deadlineMinutes, planId, turbineId);
    }

    /** 释放计划全部机组占用（完成/取消/指令作废）；不改动任何实际功率。 */
    public void releaseOccupations(long planId) {
        jdbc.update("UPDATE staged_plan_turbine SET occupied_flag = NULL WHERE plan_id = ?", planId);
    }

    /** 机组当前是否被某份执行中/暂停中分段计划占用（占用唯一索引保证至多一行）。 */
    public StagedPlanTurbine findOccupiedByTurbine(long turbineId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM staged_plan_turbine WHERE turbine_id = ? AND occupied_flag = 'Y'",
                    TURBINE_MAPPER, turbineId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public void insertSteps(List<StagedPlanStep> steps) {
        jdbc.batchUpdate("""
                INSERT INTO staged_plan_step (plan_id, plan_version, minute_no, turbine_id, power_kw)
                VALUES (?, ?, ?, ?, ?)
                """,
                steps, steps.size(), (ps, s) -> {
                    ps.setLong(1, s.getPlanId());
                    ps.setInt(2, s.getPlanVersion());
                    ps.setInt(3, s.getMinuteNo());
                    ps.setLong(4, s.getTurbineId());
                    ps.setInt(5, s.getPowerKw());
                });
    }

    /** 某版本某分钟的全部冻结阶梯（回执逐机组比对用）。 */
    public List<StagedPlanStep> findStepsOfMinute(long planId, int planVersion, int minuteNo) {
        return jdbc.query(
                "SELECT * FROM staged_plan_step WHERE plan_id = ? AND plan_version = ? AND minute_no = ?",
                STEP_MAPPER, planId, planVersion, minuteNo);
    }

    public List<StagedPlanStep> findStepsOfVersion(long planId, int planVersion) {
        return jdbc.query("""
                SELECT * FROM staged_plan_step
                WHERE plan_id = ? AND plan_version = ?
                ORDER BY minute_no, turbine_id
                """,
                STEP_MAPPER, planId, planVersion);
    }

    public void insertConfirm(StagedMinuteConfirm confirm) {
        jdbc.update("""
                INSERT INTO staged_minute_confirm (plan_id, plan_version, minute_no, request_id, confirmed_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                confirm.getPlanId(),
                confirm.getPlanVersion(),
                confirm.getMinuteNo(),
                confirm.getRequestId(),
                Timestamp.from(confirm.getConfirmedAt()));
    }

    public List<StagedMinuteConfirm> findConfirms(long planId) {
        return jdbc.query(
                "SELECT * FROM staged_minute_confirm WHERE plan_id = ? ORDER BY plan_version, minute_no",
                CONFIRM_MAPPER, planId);
    }
}
