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

import com.example.starter.domain.DayAheadPlan;
import com.example.starter.domain.DayAheadPlanEntry;
import com.example.starter.domain.OrderPlanBasis;

/**
 * 日前计划版本、计划条目与指令冻结基数数据访问。
 * 版本头不可变：仅发布时插入新版本并把旧当前版本 current_flag 置 NULL（同事务）。
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<DayAheadPlan> PLAN_MAPPER = (rs, n) -> {
        DayAheadPlan p = new DayAheadPlan();
        p.setId(rs.getLong("id"));
        p.setFarmCode(rs.getString("farm_code"));
        p.setPlanDate(rs.getString("plan_date"));
        p.setVersionNo(rs.getInt("version_no"));
        p.setCurrentFlag(rs.getString("current_flag"));
        p.setPublishedAt(rs.getTimestamp("published_at").toInstant());
        return p;
    };

    static final RowMapper<DayAheadPlanEntry> ENTRY_MAPPER = (rs, n) -> {
        DayAheadPlanEntry e = new DayAheadPlanEntry();
        e.setId(rs.getLong("id"));
        e.setPlanId(rs.getLong("plan_id"));
        e.setSegmentIndex(rs.getInt("segment_index"));
        e.setTurbineId(rs.getLong("turbine_id"));
        e.setTurbineCode(rs.getString("turbine_code"));
        e.setPlannedPowerKw(rs.getInt("planned_power_kw"));
        return e;
    };

    static final RowMapper<OrderPlanBasis> BASIS_MAPPER = (rs, n) -> {
        OrderPlanBasis b = new OrderPlanBasis();
        b.setId(rs.getLong("id"));
        b.setOrderId(rs.getLong("order_id"));
        b.setTurbineId(rs.getLong("turbine_id"));
        b.setTurbineCode(rs.getString("turbine_code"));
        b.setPlannedPowerKw(rs.getInt("planned_power_kw"));
        b.setRealtimeAvailableKw(rs.getInt("realtime_available_kw"));
        b.setParticipatingKw(rs.getInt("participating_kw"));
        return b;
    };

    public long insertPlan(DayAheadPlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO day_ahead_plan
                        (farm_code, plan_date, version_no, current_flag, published_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.getFarmCode());
            ps.setString(2, plan.getPlanDate());
            ps.setInt(3, plan.getVersionNo());
            ps.setString(4, plan.getCurrentFlag());
            ps.setTimestamp(5, Timestamp.from(plan.getPublishedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        plan.setId(id);
        return id;
    }

    public void insertEntries(List<DayAheadPlanEntry> entries) {
        jdbc.batchUpdate("""
                INSERT INTO day_ahead_plan_entry
                    (plan_id, segment_index, turbine_id, turbine_code, planned_power_kw)
                VALUES (?, ?, ?, ?, ?)
                """,
                entries, entries.size(), (ps, e) -> {
                    ps.setLong(1, e.getPlanId());
                    ps.setInt(2, e.getSegmentIndex());
                    ps.setLong(3, e.getTurbineId());
                    ps.setString(4, e.getTurbineCode());
                    ps.setInt(5, e.getPlannedPowerKw());
                });
    }

    /** 将该风场该日期现有当前版本置为历史版本（current_flag=NULL）。 */
    public void clearCurrentFlag(String farmCode, String planDate) {
        jdbc.update(
                "UPDATE day_ahead_plan SET current_flag = NULL WHERE farm_code = ? AND plan_date = ?",
                farmCode, planDate);
    }

    /** 锁定风场＋日期的版本头集合（SELECT ... FOR UPDATE），串行化并发发布。 */
    public void lockPlanGroup(String farmCode, String planDate) {
        jdbc.queryForList(
                "SELECT id FROM day_ahead_plan WHERE farm_code = ? AND plan_date = ? FOR UPDATE",
                Long.class, farmCode, planDate);
    }

    public DayAheadPlan findCurrentPlan(String farmCode, String planDate) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan WHERE farm_code = ? AND plan_date = ? AND current_flag = 'Y'",
                    PLAN_MAPPER, farmCode, planDate);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public DayAheadPlan findPlanByVersion(String farmCode, String planDate, int versionNo) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan WHERE farm_code = ? AND plan_date = ? AND version_no = ?",
                    PLAN_MAPPER, farmCode, planDate, versionNo);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public DayAheadPlan findPlanById(long planId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan WHERE id = ?", PLAN_MAPPER, planId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<DayAheadPlan> findVersions(String farmCode, String planDate) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan WHERE farm_code = ? AND plan_date = ? ORDER BY version_no",
                PLAN_MAPPER, farmCode, planDate);
    }

    public List<DayAheadPlanEntry> findEntries(long planId) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan_entry WHERE plan_id = ? ORDER BY segment_index, id",
                ENTRY_MAPPER, planId);
    }

    public List<DayAheadPlanEntry> findEntriesForSegment(long planId, int segmentIndex) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan_entry WHERE plan_id = ? AND segment_index = ? ORDER BY id",
                ENTRY_MAPPER, planId, segmentIndex);
    }

    public void insertBasis(List<OrderPlanBasis> basis) {
        jdbc.batchUpdate("""
                INSERT INTO order_plan_basis
                    (order_id, turbine_id, turbine_code, planned_power_kw,
                     realtime_available_kw, participating_kw)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                basis, basis.size(), (ps, b) -> {
                    ps.setLong(1, b.getOrderId());
                    ps.setLong(2, b.getTurbineId());
                    ps.setString(3, b.getTurbineCode());
                    ps.setInt(4, b.getPlannedPowerKw());
                    ps.setInt(5, b.getRealtimeAvailableKw());
                    ps.setInt(6, b.getParticipatingKw());
                });
    }

    public List<OrderPlanBasis> findBasisByOrder(long orderId) {
        return jdbc.query(
                "SELECT * FROM order_plan_basis WHERE order_id = ? ORDER BY id",
                BASIS_MAPPER, orderId);
    }
}
