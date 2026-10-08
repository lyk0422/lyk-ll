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
            PlanStatus status,
            long version) {
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

    /**
     * 引航员实际责任区间（半开区间）：受控计划按交班责任段计工时，
     * 未交班计划沿用整段，避免把整计划重复计给两人或清掉各人其他计划工时。
     */
    public record ResponsibilitySegment(
            long planId,
            LocalDateTime start,
            LocalDateTime end) {
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
            PlanStatus.valueOf(rs.getString("status")),
            rs.getLong("version"));

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

    /** 改期：更新潮水窗口与计划时间，状态保持不变，版本加 1（交班切换据此重查来源版本）。 */
    public void updateReschedule(long planId, LocalDateTime tideStart, LocalDateTime tideEnd,
                                 LocalDateTime planStart, LocalDateTime planEnd, LocalDateTime now) {
        jdbc.update("UPDATE pilot_plan SET tide_start = ?, tide_end = ?,"
                        + " plan_start = ?, plan_end = ?, version = version + 1, updated_at = ? WHERE id = ?",
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

    /**
     * 交班切换：乐观锁条件下把计划引航员改为接班员并将版本加 1。
     * 仅当来源计划版本与申请时记录的版本一致时生效，返回受影响行数（0 表示版本过期）。
     */
    public int switchPilotIfVersion(long planId, long newPilotId, long expectedVersion,
                                    LocalDateTime now) {
        return jdbc.update("UPDATE pilot_plan SET pilot_id = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ?",
                newPilotId, Timestamp.valueOf(now), planId, expectedVersion);
    }

    /** 删除某计划指定资源类型的锁（交班切换/撤回时只处理接班预占，保留拖轮与原人锁）。 */
    public int deleteLocks(long planId, String resourceType, long resourceId) {
        return jdbc.update(
                "DELETE FROM resource_lock WHERE plan_id = ? AND resource_type = ? AND resource_id = ?",
                planId, resourceType, resourceId);
    }

    /** 交班切换：把出班引航员的整段 PILOT 锁收窄为 [lockStart, newEnd)。 */
    public int narrowPilotLockEnd(long planId, long pilotId, LocalDateTime newEnd) {
        return jdbc.update(
                "UPDATE resource_lock SET lock_end = ? WHERE plan_id = ?"
                        + " AND resource_type = 'PILOT' AND resource_id = ?",
                Timestamp.valueOf(newEnd), planId, pilotId);
    }

    /**
     * 查询引航员在时间范围内的实际责任区间（半开区间），用于工时统计与交班重查：
     * <ul>
     *   <li>未交班计划（无 SWITCHED 交班）：整段 [plan_start, plan_end) 归当前 pilot_id；</li>
     *   <li>已交班计划：出班员承担 [plan_start, handover_at)，接班员承担 [handover_at, plan_end)，
     *       不因 plan.pilot_id 改挂接班员而清掉出班员的责任工时；</li>
     *   <li>PENDING 交班尚未形成责任转移，接班预占不计工时（仅以资源锁参与冲突）。</li>
     * </ul>
     */
    public List<ResponsibilitySegment> findResponsibilitySegments(long pilotId,
                                                                   LocalDateTime rangeStart,
                                                                   LocalDateTime rangeEnd) {
        String active = "'SCHEDULED','CONFIRMED','EXECUTING'";
        String sql =
                "SELECT t.plan_id AS id, t.seg_start AS plan_start, t.seg_end AS plan_end FROM ("
                + " SELECT p.id AS plan_id, p.plan_start AS seg_start, p.plan_end AS seg_end"
                + " FROM pilot_plan p"
                + " WHERE p.pilot_id = ? AND p.status IN (" + active + ")"
                + " AND NOT EXISTS (SELECT 1 FROM pilot_handover h"
                + " WHERE h.plan_id = p.id AND h.status = 'SWITCHED')"
                + " UNION ALL"
                + " SELECT p.id AS plan_id, p.plan_start AS seg_start, h.handover_at AS seg_end"
                + " FROM pilot_plan p JOIN pilot_handover h ON h.plan_id = p.id"
                + " WHERE h.status = 'SWITCHED' AND h.from_pilot_id = ? AND p.status IN (" + active + ")"
                + " UNION ALL"
                + " SELECT p.id AS plan_id, h.handover_at AS seg_start, p.plan_end AS seg_end"
                + " FROM pilot_plan p JOIN pilot_handover h ON h.plan_id = p.id"
                + " WHERE h.status = 'SWITCHED' AND h.to_pilot_id = ? AND p.status IN (" + active + ")"
                + ") t WHERE t.seg_start < ? AND t.seg_end > ? ORDER BY t.seg_start";
        return jdbc.query(sql,
                (rs, n) -> new ResponsibilitySegment(
                        rs.getLong("id"),
                        toLdt(rs, "plan_start"),
                        toLdt(rs, "plan_end")),
                pilotId, pilotId, pilotId,
                Timestamp.valueOf(rangeEnd), Timestamp.valueOf(rangeStart));
    }
}
