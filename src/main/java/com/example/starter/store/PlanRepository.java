package com.example.starter.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.PlanStatus;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;

/**
 * 引航计划数据访问：计划、拖轮组合、资源锁与违约记录。
 * 所有时间字段为本地时间（Asia/Shanghai），占用/窗口区间均为半开区间 [start, end)。
 */
@Repository
public class PlanRepository {

    /** 计划行映射。 */
    public record PlanRow(
            long id,
            String requestId,
            String applicant,
            String shipName,
            Long pilotId,
            LocalDateTime tideStart,
            LocalDateTime tideEnd,
            LocalDateTime planStart,
            LocalDateTime planEnd,
            PlanStatus status) {
    }

    /** 资源锁行映射。 */
    public record LockRow(
            long id,
            long planId,
            String resourceType,
            long resourceId,
            LocalDateTime lockStart,
            LocalDateTime lockEnd) {
    }

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("applicant"),
            rs.getString("ship_name"),
            rs.getObject("pilot_id", Long.class),
            toLdt(rs, "tide_start"),
            toLdt(rs, "tide_end"),
            toLdt(rs, "plan_start"),
            toLdt(rs, "plan_end"),
            PlanStatus.valueOf(rs.getString("status")));

    private static final RowMapper<LockRow> LOCK_MAPPER = (rs, n) -> new LockRow(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("resource_type"),
            rs.getLong("resource_id"),
            toLdt(rs, "lock_start"),
            toLdt(rs, "lock_end"));

    private static LocalDateTime toLdt(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入申请中的计划，返回自增主键。 */
    public long insertPlan(String requestId, String applicant, String shipName,
                           LocalDateTime planStart, LocalDateTime planEnd, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pilot_plan (request_id, applicant, ship_name, plan_start, plan_end, status, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, applicant);
            ps.setString(3, shipName);
            ps.setTimestamp(4, Timestamp.valueOf(planStart));
            ps.setTimestamp(5, Timestamp.valueOf(planEnd));
            ps.setString(6, PlanStatus.APPLIED.name());
            ps.setTimestamp(7, Timestamp.valueOf(now));
            ps.setTimestamp(8, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查询。 */
    public Optional<PlanRow> findById(long planId) {
        List<PlanRow> rows = jdbc.query(
                "SELECT * FROM pilot_plan WHERE id = ?", PLAN_MAPPER, planId);
        return rows.stream().findFirst();
    }

    /** 按主键加行锁查询（同事务内串行化确认/改期/取消）。 */
    public Optional<PlanRow> findByIdForUpdate(long planId) {
        List<PlanRow> rows = jdbc.query(
                "SELECT * FROM pilot_plan WHERE id = ? FOR UPDATE", PLAN_MAPPER, planId);
        return rows.stream().findFirst();
    }

    /** 更新计划状态。 */
    public void updateStatus(long planId, PlanStatus status, LocalDateTime now) {
        jdbc.update("UPDATE pilot_plan SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.valueOf(now), planId);
    }

    /** 写入编排结果：引航员、潮水窗口与计划时间，状态置为 SCHEDULED。 */
    public void updateSchedule(long planId, long pilotId, LocalDateTime tideStart, LocalDateTime tideEnd,
                               LocalDateTime planStart, LocalDateTime planEnd, LocalDateTime now) {
        jdbc.update("UPDATE pilot_plan SET pilot_id = ?, tide_start = ?, tide_end = ?,"
                        + " plan_start = ?, plan_end = ?, status = ?, updated_at = ? WHERE id = ?",
                pilotId, Timestamp.valueOf(tideStart), Timestamp.valueOf(tideEnd),
                Timestamp.valueOf(planStart), Timestamp.valueOf(planEnd),
                PlanStatus.SCHEDULED.name(), Timestamp.valueOf(now), planId);
    }

    /** 改期：更新潮水窗口与计划时间，状态保持不变。 */
    public void updateReschedule(long planId, LocalDateTime tideStart, LocalDateTime tideEnd,
                                 LocalDateTime planStart, LocalDateTime planEnd, LocalDateTime now) {
        jdbc.update("UPDATE pilot_plan SET tide_start = ?, tide_end = ?,"
                        + " plan_start = ?, plan_end = ?, updated_at = ? WHERE id = ?",
                Timestamp.valueOf(tideStart), Timestamp.valueOf(tideEnd),
                Timestamp.valueOf(planStart), Timestamp.valueOf(planEnd),
                Timestamp.valueOf(now), planId);
    }

    /** 重建计划的拖轮组合。 */
    public void replacePlanTugs(long planId, List<Long> tugIds) {
        jdbc.update("DELETE FROM plan_tug WHERE plan_id = ?", planId);
        for (Long tugId : tugIds) {
            jdbc.update("INSERT INTO plan_tug (plan_id, tug_id) VALUES (?, ?)", planId, tugId);
        }
    }

    /** 查询计划绑定的拖轮组合。 */
    public List<Long> findTugIds(long planId) {
        return jdbc.queryForList(
                "SELECT tug_id FROM plan_tug WHERE plan_id = ? ORDER BY tug_id", Long.class, planId);
    }

    /** 拖轮是否存在。 */
    public boolean tugExists(long tugId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tug WHERE id = ?", Integer.class, tugId);
        return count != null && count > 0;
    }

    /** 引航员是否存在。 */
    public boolean pilotExists(long pilotId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pilot WHERE id = ?", Integer.class, pilotId);
        return count != null && count > 0;
    }

    /** 对引航员行加行锁（串行化同一引航员的确认/改期）。 */
    public void lockPilotRow(long pilotId) {
        jdbc.queryForList("SELECT id FROM pilot WHERE id = ? FOR UPDATE", Long.class, pilotId);
    }

    /** 对拖轮行加行锁（串行化同一拖轮的确认/改期）。 */
    public void lockTugRow(long tugId) {
        jdbc.queryForList("SELECT id FROM tug WHERE id = ? FOR UPDATE", Long.class, tugId);
    }

    /** 写入资源锁。 */
    public void insertLock(long planId, String resourceType, long resourceId,
                           LocalDateTime lockStart, LocalDateTime lockEnd) {
        jdbc.update("INSERT INTO resource_lock (plan_id, resource_type, resource_id, lock_start, lock_end)"
                        + " VALUES (?, ?, ?, ?, ?)",
                planId, resourceType, resourceId,
                Timestamp.valueOf(lockStart), Timestamp.valueOf(lockEnd));
    }

    /** 删除计划的全部资源锁（改期重新锁定或取消释放）。 */
    public void deleteLocksByPlanId(long planId) {
        jdbc.update("DELETE FROM resource_lock WHERE plan_id = ?", planId);
    }

    /**
     * 查询某资源在指定半开区间内与其他计划的重叠锁。
     * 半开区间判定：内部重叠冲突，端点相接（lockEnd == start 或 lockStart == end）不冲突。
     */
    public List<LockRow> findOverlappingLocks(String resourceType, long resourceId,
                                              LocalDateTime start, LocalDateTime end, long excludePlanId) {
        return jdbc.query("SELECT * FROM resource_lock"
                        + " WHERE resource_type = ? AND resource_id = ? AND plan_id <> ?"
                        + " AND lock_start < ? AND lock_end > ?",
                LOCK_MAPPER, resourceType, resourceId, excludePlanId,
                Timestamp.valueOf(end), Timestamp.valueOf(start));
    }

    /**
     * 查询引航员在指定时间范围内、处于有效状态（已编排/已确认/执行中）的计划，
     * 用于连续作业与单日累计工时校验。
     */
    public List<PlanRow> findActivePlansForPilot(long pilotId, LocalDateTime rangeStart,
                                                 LocalDateTime rangeEnd, long excludePlanId) {
        return jdbc.query("SELECT * FROM pilot_plan"
                        + " WHERE pilot_id = ? AND id <> ?"
                        + " AND status IN ('SCHEDULED', 'CONFIRMED', 'EXECUTING')"
                        + " AND plan_start < ? AND plan_end > ?"
                        + " ORDER BY plan_start",
                PLAN_MAPPER, pilotId, excludePlanId,
                Timestamp.valueOf(rangeEnd), Timestamp.valueOf(rangeStart));
    }

    /**
     * 判断引航员在指定半开区间内是否已存在另一条执行中计划（同一引航员同时刻只允许一条执行中计划）。
     */
    public boolean existsExecutingOverlap(long pilotId, LocalDateTime start, LocalDateTime end,
                                          long excludePlanId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pilot_plan"
                        + " WHERE pilot_id = ? AND id <> ? AND status = 'EXECUTING'"
                        + " AND plan_start < ? AND plan_end > ?",
                Integer.class, pilotId, excludePlanId,
                Timestamp.valueOf(end), Timestamp.valueOf(start));
        return count != null && count > 0;
    }

    /** 写入合成违约记录。 */
    public void insertViolation(long planId, String reason, long leadMinutes, LocalDateTime now) {
        jdbc.update("INSERT INTO violation_record (plan_id, reason, lead_minutes, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                planId, reason, leadMinutes, Timestamp.valueOf(now));
    }

    /** 查询计划的违约记录数（测试与核对用）。 */
    public int countViolations(long planId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM violation_record WHERE plan_id = ?", Integer.class, planId);
        return count == null ? 0 : count;
    }

    /** 查询计划的资源锁数量（测试与核对用）。 */
    public int countLocks(long planId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE plan_id = ?", Integer.class, planId);
        return count == null ? 0 : count;
    }
}
