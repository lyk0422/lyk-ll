package com.example.starter.repo;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.DayAheadPlan;
import com.example.starter.domain.OrderPlanBase;
import com.example.starter.domain.PlanSlot;

/**
 * 日前计划数据访问：版本、时段明细与绑定指令的冻结基数。
 * 发布与当前指针切换须在持有 wind_farm 行锁的事务内调用，保证同场同日版本序列唯一。
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DayAheadPlan> PLAN_MAPPER = (rs, n) -> {
        DayAheadPlan p = new DayAheadPlan();
        p.setId(rs.getLong("id"));
        p.setFarmCode(rs.getString("farm_code"));
        p.setPlanDate(rs.getDate("plan_date").toLocalDate());
        p.setVersion(rs.getInt("version"));
        p.setRequestId(rs.getString("request_id"));
        p.setFingerprint(rs.getString("fingerprint"));
        p.setCurrent("Y".equals(rs.getString("current_flag")));
        p.setPublishedAt(rs.getTimestamp("published_at").toInstant());
        return p;
    };

    private static final RowMapper<PlanSlot> SLOT_MAPPER = (rs, n) -> {
        PlanSlot s = new PlanSlot();
        s.setId(rs.getLong("id"));
        s.setPlanId(rs.getLong("plan_id"));
        s.setSlotIndex(rs.getInt("slot_index"));
        s.setTurbineId(rs.getLong("turbine_id"));
        s.setPlannedKw(rs.getInt("planned_kw"));
        return s;
    };

    private static final RowMapper<OrderPlanBase> BASE_MAPPER = (rs, n) -> {
        OrderPlanBase b = new OrderPlanBase();
        b.setId(rs.getLong("id"));
        b.setOrderId(rs.getLong("order_id"));
        b.setTurbineId(rs.getLong("turbine_id"));
        b.setPlannedKw(rs.getInt("planned_kw"));
        b.setAvailableKw(rs.getInt("available_kw"));
        return b;
    };

    /** 插入新版本（先以非当前插入拿 id，由服务在旧版指针释放后显式置当前）。 */
    public long insertPlan(DayAheadPlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO day_ahead_plan
                        (farm_code, plan_date, version, request_id, fingerprint,
                         current_flag, published_at)
                    VALUES (?, ?, ?, ?, ?, NULL, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.getFarmCode());
            ps.setDate(2, Date.valueOf(plan.getPlanDate()));
            ps.setInt(3, plan.getVersion());
            ps.setString(4, plan.getRequestId());
            ps.setString(5, plan.getFingerprint());
            ps.setTimestamp(6, Timestamp.from(plan.getPublishedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        plan.setId(id);
        return id;
    }

    public void insertSlots(long planId, List<PlanSlot> slots) {
        jdbc.batchUpdate("""
                INSERT INTO day_ahead_plan_slot (plan_id, slot_index, turbine_id, planned_kw)
                VALUES (?, ?, ?, ?)
                """,
                slots, slots.size(), (ps, s) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, s.getSlotIndex());
                    ps.setLong(3, s.getTurbineId());
                    ps.setInt(4, s.getPlannedKw());
                });
    }

    /** 释放同场同日旧当前版本指针（历史版本保留）。 */
    public void clearCurrent(String farmCode, LocalDate planDate) {
        jdbc.update(
                "UPDATE day_ahead_plan SET current_flag = NULL "
                        + "WHERE farm_code = ? AND plan_date = ? AND current_flag = 'Y'",
                farmCode, Date.valueOf(planDate));
    }

    /** 将新版本置为当前；唯一索引保证同场同日至多一个当前版本。 */
    public void markCurrent(long planId) {
        jdbc.update("UPDATE day_ahead_plan SET current_flag = 'Y' WHERE id = ?", planId);
    }

    /** 同场同日最新版本号；无已发布版本返回 null。 */
    public Integer findLatestVersion(String farmCode, LocalDate planDate) {
        return jdbc.query(
                "SELECT MAX(version) AS v FROM day_ahead_plan WHERE farm_code = ? AND plan_date = ?",
                rs -> rs.next() ? rs.getObject("v", Integer.class) : null,
                farmCode, Date.valueOf(planDate));
    }

    public DayAheadPlan findCurrent(String farmCode, LocalDate planDate) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan WHERE farm_code = ? AND plan_date = ? "
                            + "AND current_flag = 'Y'",
                    PLAN_MAPPER, farmCode, Date.valueOf(planDate));
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public DayAheadPlan findByVersion(String farmCode, LocalDate planDate, int version) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan WHERE farm_code = ? AND plan_date = ? AND version = ?",
                    PLAN_MAPPER, farmCode, Date.valueOf(planDate), version);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public DayAheadPlan findById(long planId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan WHERE id = ?", PLAN_MAPPER, planId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<DayAheadPlan> findVersions(String farmCode, LocalDate planDate) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan WHERE farm_code = ? AND plan_date = ? ORDER BY version",
                PLAN_MAPPER, farmCode, Date.valueOf(planDate));
    }

    public List<PlanSlot> findSlots(long planId) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan_slot WHERE plan_id = ? ORDER BY slot_index, turbine_id",
                SLOT_MAPPER, planId);
    }

    /** 指定版本指定时段的全部机组核定计划。 */
    public List<PlanSlot> findSlotsOfIndex(long planId, int slotIndex) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan_slot WHERE plan_id = ? AND slot_index = ?",
                SLOT_MAPPER, planId, slotIndex);
    }

    public void insertBases(long orderId, List<OrderPlanBase> bases) {
        jdbc.batchUpdate("""
                INSERT INTO order_plan_base (order_id, turbine_id, planned_kw, available_kw)
                VALUES (?, ?, ?, ?)
                """,
                bases, bases.size(), (ps, b) -> {
                    ps.setLong(1, orderId);
                    ps.setLong(2, b.getTurbineId());
                    ps.setInt(3, b.getPlannedKw());
                    ps.setInt(4, b.getAvailableKw());
                });
    }

    /** 指令冻结基数（受理时写入，之后不再变更）。 */
    public List<OrderPlanBase> findBases(long orderId) {
        return jdbc.query(
                "SELECT * FROM order_plan_base WHERE order_id = ? ORDER BY turbine_id",
                BASE_MAPPER, orderId);
    }
}
