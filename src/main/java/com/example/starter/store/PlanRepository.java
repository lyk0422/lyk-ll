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

import com.example.starter.domain.HandoverStatus;
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
            int version) {
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

    /** 交班记录行映射。 */
    public record HandoverRow(
            long id,
            long planId,
            String requestId,
            long outPilotId,
            long inPilotId,
            LocalDateTime handoverAt,
            boolean outAck,
            String outComment,
            boolean inAck,
            String inComment,
            HandoverStatus status,
            int planVersion,
            int version) {
    }

    /** 引航员责任段（工时分段计算与占用校验用，半开区间 [start, end)）。 */
    public record SegmentRow(long planId, LocalDateTime start, LocalDateTime end) {
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
            rs.getInt("version"));

    private static final RowMapper<LockRow> LOCK_MAPPER = (rs, n) -> new LockRow(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("resource_type"),
            rs.getLong("resource_id"),
            toLdt(rs, "lock_start"),
            toLdt(rs, "lock_end"));

    private static final RowMapper<HandoverRow> HANDOVER_MAPPER = (rs, n) -> new HandoverRow(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("request_id"),
            rs.getLong("out_pilot_id"),
            rs.getLong("in_pilot_id"),
            toLdt(rs, "handover_at"),
            rs.getBoolean("out_ack"),
            rs.getString("out_comment"),
            rs.getBoolean("in_ack"),
            rs.getString("in_comment"),
            HandoverStatus.valueOf(rs.getString("status")),
            rs.getInt("plan_version"),
            rs.getInt("version"));

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("plan_id"),
            toLdt(rs, "seg_start"),
            toLdt(rs, "seg_end"));

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
        jdbc.update("UPDATE pilot_plan SET status = ?, version = version + 1, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.valueOf(now), planId);
    }

    /** 写入编排结果：引航员、潮水窗口与计划时间，状态置为 SCHEDULED。 */
    public void updateSchedule(long planId, long pilotId, LocalDateTime tideStart, LocalDateTime tideEnd,
                               LocalDateTime planStart, LocalDateTime planEnd, LocalDateTime now) {
        jdbc.update("UPDATE pilot_plan SET pilot_id = ?, tide_start = ?, tide_end = ?,"
                        + " plan_start = ?, plan_end = ?, status = ?, version = version + 1,"
                        + " updated_at = ? WHERE id = ?",
                pilotId, Timestamp.valueOf(tideStart), Timestamp.valueOf(tideEnd),
                Timestamp.valueOf(planStart), Timestamp.valueOf(planEnd),
                PlanStatus.SCHEDULED.name(), Timestamp.valueOf(now), planId);
    }

    /** 改期：更新潮水窗口与计划时间，状态保持不变。 */
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

    // ---------- 执行中引航交班 ----------

    /** 插入交班申请（状态 OPEN，版本 1），返回自增主键。 */
    public long insertHandover(long planId, String requestId, long outPilotId, long inPilotId,
                               LocalDateTime handoverAt, int planVersion, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pilot_handover (plan_id, request_id, out_pilot_id, in_pilot_id,"
                            + " handover_at, status, plan_version, version, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, planId);
            ps.setString(2, requestId);
            ps.setLong(3, outPilotId);
            ps.setLong(4, inPilotId);
            ps.setTimestamp(5, Timestamp.valueOf(handoverAt));
            ps.setString(6, HandoverStatus.OPEN.name());
            ps.setInt(7, planVersion);
            ps.setTimestamp(8, Timestamp.valueOf(now));
            ps.setTimestamp(9, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查询交班记录。 */
    public Optional<HandoverRow> findHandoverById(long handoverId) {
        List<HandoverRow> rows = jdbc.query(
                "SELECT * FROM pilot_handover WHERE id = ?", HANDOVER_MAPPER, handoverId);
        return rows.stream().findFirst();
    }

    /** 按主键加行锁查询交班记录（签认/撤回/切换串行化）。 */
    public Optional<HandoverRow> findHandoverByIdForUpdate(long handoverId) {
        List<HandoverRow> rows = jdbc.query(
                "SELECT * FROM pilot_handover WHERE id = ? FOR UPDATE", HANDOVER_MAPPER, handoverId);
        return rows.stream().findFirst();
    }

    /** 查询计划当前未结束（OPEN）的交班；同计划至多一条，由计划行锁串行化保证。 */
    public Optional<HandoverRow> findOpenHandoverForPlan(long planId) {
        List<HandoverRow> rows = jdbc.query(
                "SELECT * FROM pilot_handover WHERE plan_id = ? AND status = 'OPEN'",
                HANDOVER_MAPPER, planId);
        return rows.stream().findFirst();
    }

    /** 查询计划最新一条交班记录（查询展示用）。 */
    public Optional<HandoverRow> findLatestHandoverForPlan(long planId) {
        List<HandoverRow> rows = jdbc.query(
                "SELECT * FROM pilot_handover WHERE plan_id = ? ORDER BY id DESC LIMIT 1",
                HANDOVER_MAPPER, planId);
        return rows.stream().findFirst();
    }

    /** 查询计划已切换的交班记录（责任区间与工时分段用）。 */
    public Optional<HandoverRow> findSwitchedHandoverForPlan(long planId) {
        List<HandoverRow> rows = jdbc.query(
                "SELECT * FROM pilot_handover WHERE plan_id = ? AND status = 'SWITCHED'"
                        + " ORDER BY id DESC LIMIT 1",
                HANDOVER_MAPPER, planId);
        return rows.stream().findFirst();
    }

    /** 记录一方签认与意见，版本加 1。side 取值 OUT（出班）/ IN（接班）。 */
    public void updateHandoverAck(long handoverId, String side, String comment,
                                  int newVersion, LocalDateTime now) {
        String sql = "OUT".equals(side)
                ? "UPDATE pilot_handover SET out_ack = 1, out_comment = ?, version = ?, updated_at = ? WHERE id = ?"
                : "UPDATE pilot_handover SET in_ack = 1, in_comment = ?, version = ?, updated_at = ? WHERE id = ?";
        jdbc.update(sql, comment, newVersion, Timestamp.valueOf(now), handoverId);
    }

    /** 更新交班状态，版本加 1（切换/撤回/核销关闭）。 */
    public void updateHandoverStatus(long handoverId, HandoverStatus status,
                                     int newVersion, LocalDateTime now) {
        jdbc.update("UPDATE pilot_handover SET status = ?, version = ?, updated_at = ? WHERE id = ?",
                status.name(), newVersion, Timestamp.valueOf(now), handoverId);
    }

    /** 切换责任：计划当前引航员改为接班员，计划版本加 1。 */
    public void updatePlanPilot(long planId, long pilotId, LocalDateTime now) {
        jdbc.update("UPDATE pilot_plan SET pilot_id = ?, version = version + 1, updated_at = ? WHERE id = ?",
                pilotId, Timestamp.valueOf(now), planId);
    }

    /** 截断指定引航员在计划上的锁终点（出班方责任段截止到交班时刻）。 */
    public void truncatePilotLock(long planId, long pilotId, LocalDateTime newEnd) {
        jdbc.update("UPDATE resource_lock SET lock_end = ?"
                        + " WHERE plan_id = ? AND resource_type = 'PILOT' AND resource_id = ?",
                Timestamp.valueOf(newEnd), planId, pilotId);
    }

    /** 删除指定引航员在计划上的锁（撤回/关闭交班时释放接班预占）。 */
    public void deletePilotLock(long planId, long pilotId) {
        jdbc.update("DELETE FROM resource_lock"
                        + " WHERE plan_id = ? AND resource_type = 'PILOT' AND resource_id = ?",
                planId, pilotId);
    }

    /**
     * 查询引航员在指定时间范围内的实际责任段（半开区间），用于工时分段计算与占用校验：
     * <ul>
     *   <li>本人名下有效计划：未切换为整段 [plan_start, plan_end)，已切换自交班时刻起；</li>
     *   <li>本人作为出班方已切换的计划：责任段 [plan_start, handover_at)；</li>
     *   <li>本人作为接班方的未结束交班：预占段 [handover_at, plan_end)。</li>
     * </ul>
     * 未使用交班的计划责任段与计划时段一致，沿用旧语义。
     */
    public List<SegmentRow> findResponsibilitySegments(long pilotId, LocalDateTime rangeStart,
                                                       LocalDateTime rangeEnd, long excludePlanId) {
        String sql = "SELECT plan_id, seg_start, seg_end FROM ("
                + "SELECT p.id AS plan_id, COALESCE(h.handover_at, p.plan_start) AS seg_start,"
                + " p.plan_end AS seg_end"
                + " FROM pilot_plan p"
                + " LEFT JOIN pilot_handover h ON h.plan_id = p.id AND h.status = 'SWITCHED'"
                + " WHERE p.pilot_id = ? AND p.id <> ?"
                + " AND p.status IN ('SCHEDULED', 'CONFIRMED', 'EXECUTING')"
                + " AND p.plan_start < ? AND p.plan_end > ?"
                + " UNION ALL "
                + "SELECT p.id, p.plan_start, h.handover_at"
                + " FROM pilot_handover h JOIN pilot_plan p ON p.id = h.plan_id"
                + " WHERE h.out_pilot_id = ? AND h.status = 'SWITCHED' AND p.id <> ?"
                + " AND p.status IN ('SCHEDULED', 'CONFIRMED', 'EXECUTING')"
                + " AND p.plan_start < ? AND p.plan_end > ?"
                + " UNION ALL "
                + "SELECT p.id, h.handover_at, p.plan_end"
                + " FROM pilot_handover h JOIN pilot_plan p ON p.id = h.plan_id"
                + " WHERE h.in_pilot_id = ? AND h.status = 'OPEN' AND p.id <> ?"
                + " AND p.status = 'EXECUTING'"
                + " AND p.plan_start < ? AND p.plan_end > ?"
                + ") t ORDER BY seg_start";
        Timestamp rangeStartTs = Timestamp.valueOf(rangeStart);
        Timestamp rangeEndTs = Timestamp.valueOf(rangeEnd);
        return jdbc.query(sql, SEGMENT_MAPPER,
                pilotId, excludePlanId, rangeEndTs, rangeStartTs,
                pilotId, excludePlanId, rangeEndTs, rangeStartTs,
                pilotId, excludePlanId, rangeEndTs, rangeStartTs);
    }
}
