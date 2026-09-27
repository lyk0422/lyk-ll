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
import com.example.starter.domain.DayAheadPlanItem;

/**
 * 日前计划数据访问：版本行与 96 段 × 机组明细。版本发布后不可变，
 * 当前指针（is_current='Y'）的替换须在持有风场行锁的事务内完成。
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
        p.setCurrent("Y".equals(rs.getString("is_current")));
        p.setPublishedAt(rs.getTimestamp("published_at").toInstant());
        return p;
    };

    private static final RowMapper<DayAheadPlanItem> ITEM_MAPPER = (rs, n) -> {
        DayAheadPlanItem item = new DayAheadPlanItem();
        item.setId(rs.getLong("id"));
        item.setPlanId(rs.getLong("plan_id"));
        item.setPeriodIndex(rs.getInt("period_index"));
        item.setTurbineId(rs.getLong("turbine_id"));
        item.setPlannedKw(rs.getInt("planned_kw"));
        return item;
    };

    /** 插入新版本行（is_current 由服务在原子替换当前指针时显式设置）。 */
    public long insertPlan(DayAheadPlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO day_ahead_plan
                        (farm_code, plan_date, version, request_id, fingerprint, is_current, published_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.getFarmCode());
            ps.setDate(2, Date.valueOf(plan.getPlanDate()));
            ps.setInt(3, plan.getVersion());
            ps.setString(4, plan.getRequestId());
            ps.setString(5, plan.getFingerprint());
            ps.setString(6, plan.isCurrent() ? "Y" : null);
            ps.setTimestamp(7, Timestamp.from(plan.getPublishedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        plan.setId(id);
        return id;
    }

    public void insertItems(long planId, List<DayAheadPlanItem> items) {
        jdbc.batchUpdate("""
                INSERT INTO day_ahead_plan_item (plan_id, period_index, turbine_id, planned_kw)
                VALUES (?, ?, ?, ?)
                """,
                items, items.size(), (ps, item) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, item.getPeriodIndex());
                    ps.setLong(3, item.getTurbineId());
                    ps.setInt(4, item.getPlannedKw());
                });
    }

    /** 查询同场同日当前版本（is_current='Y'），无则返回 null。 */
    public DayAheadPlan findCurrent(String farmCode, LocalDate planDate) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM day_ahead_plan WHERE farm_code = ? AND plan_date = ? AND is_current = 'Y'",
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

    /** 同场同日全部版本号，按版本升序（历史版本保留可查）。 */
    public List<Integer> listVersions(String farmCode, LocalDate planDate) {
        return jdbc.queryForList(
                "SELECT version FROM day_ahead_plan WHERE farm_code = ? AND plan_date = ? ORDER BY version",
                Integer.class, farmCode, Date.valueOf(planDate));
    }

    /** 撤下同场同日当前指针（is_current 置 NULL）；与 markCurrent 在同一事务内原子替换。 */
    public void clearCurrent(String farmCode, LocalDate planDate) {
        jdbc.update(
                "UPDATE day_ahead_plan SET is_current = NULL WHERE farm_code = ? AND plan_date = ?",
                farmCode, Date.valueOf(planDate));
    }

    public void markCurrent(long planId) {
        jdbc.update("UPDATE day_ahead_plan SET is_current = 'Y' WHERE id = ?", planId);
    }

    /** 某版本全部明细，按时段、机组排序。 */
    public List<DayAheadPlanItem> findItems(long planId) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan_item WHERE plan_id = ? ORDER BY period_index, turbine_id",
                ITEM_MAPPER, planId);
    }

    /** 某版本某时段的全部机组核定计划。 */
    public List<DayAheadPlanItem> findItemsByPeriod(long planId, int periodIndex) {
        return jdbc.query(
                "SELECT * FROM day_ahead_plan_item WHERE plan_id = ? AND period_index = ? ORDER BY turbine_id",
                ITEM_MAPPER, planId, periodIndex);
    }
}
