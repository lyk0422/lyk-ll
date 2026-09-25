package com.example.starter.berth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 靠泊窗口数据访问（H2，MODE=MySQL）。所有 SQL 均参数化。
 */
@Repository
public class BerthWindowRepository {

    private static final RowMapper<BerthWindow> WINDOW_MAPPER = (rs, rowNum) -> new BerthWindow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("vessel_name"),
            rs.getString("berth_id"),
            rs.getTimestamp("start_utc").toInstant(),
            rs.getTimestamp("end_utc").toInstant(),
            rs.getBigDecimal("draft_m"),
            rs.getInt("pilots_required"),
            WindowStatus.valueOf(rs.getString("status")),
            rs.getString("replaces_request_id"),
            rs.getTimestamp("created_utc").toInstant(),
            rs.getTimestamp("updated_utc").toInstant());

    private final JdbcTemplate jdbc;

    public BerthWindowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询泊位限深（米）；泊位不存在时返回空。
     */
    public Optional<BigDecimal> findBerthMaxDraft(String berthId) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT max_draft_m FROM berth WHERE id = ?",
                (rs, rowNum) -> rs.getBigDecimal(1), berthId);
        return rows.stream().findFirst();
    }

    /**
     * 判断申请起止是否完整落在某段允许靠泊时段内（端点恰等于边界视为合法）。
     */
    public boolean isWithinAllowedWindow(Instant startUtc, Instant endUtc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allowed_window WHERE start_utc <= ? AND end_utc >= ?",
                Integer.class, Timestamp.from(startUtc), Timestamp.from(endUtc));
        return count != null && count > 0;
    }

    /**
     * 按幂等请求编号查询窗口。
     */
    public Optional<BerthWindow> findByRequestId(String requestId) {
        List<BerthWindow> rows = jdbc.query(
                "SELECT * FROM berth_window WHERE request_id = ?", WINDOW_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询窗口。
     */
    public Optional<BerthWindow> findById(long id) {
        List<BerthWindow> rows = jdbc.query(
                "SELECT * FROM berth_window WHERE id = ?", WINDOW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 插入新窗口（初始状态 PENDING）。request_id 唯一约束冲突时抛出 DuplicateKeyException。
     */
    public BerthWindow insert(ApplyWindowRequest req, String fingerprint, Instant now) {
        jdbc.update(
                "INSERT INTO berth_window (request_id, fingerprint, vessel_name, berth_id, start_utc, end_utc,"
                        + " draft_m, pilots_required, status, replaces_request_id, created_utc, updated_utc)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                req.requestId(), fingerprint, req.vesselName(), req.berthId(),
                Timestamp.from(req.startUtc()), Timestamp.from(req.endUtc()),
                req.draftMeters(), req.pilotsRequired(), WindowStatus.PENDING.name(),
                req.replacesRequestId(), Timestamp.from(now), Timestamp.from(now));
        return findByRequestId(req.requestId()).orElseThrow();
    }

    /**
     * 更新窗口状态与变更时刻。
     */
    public void updateStatus(long id, WindowStatus status, Instant now) {
        jdbc.update("UPDATE berth_window SET status = ?, updated_utc = ? WHERE id = ?",
                status.name(), Timestamp.from(now), id);
    }

    /**
     * 统计同一泊位与指定半开区间 [start, end) 存在内部重叠的「已批准或已生效」窗口数。
     * 端点恰好相接不算冲突（严格不等式）；排除自身与改期来源窗口。
     */
    public int countConflictingApprovedOrActive(String berthId, Instant startUtc, Instant endUtc,
                                                String selfRequestId, String replacesRequestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM berth_window"
                        + " WHERE berth_id = ? AND status IN ('APPROVED','ACTIVE')"
                        + " AND start_utc < ? AND end_utc > ?"
                        + " AND request_id <> ? AND request_id <> ?",
                Integer.class, berthId, Timestamp.from(endUtc), Timestamp.from(startUtc),
                selfRequestId, replacesRequestId == null ? "" : replacesRequestId);
        return count == null ? 0 : count;
    }

    /**
     * 查询指定时段内未被任何「已批准或已生效」窗口占用的引航员编号（按编号升序）。
     */
    public List<String> findAvailablePilotIds(Instant startUtc, Instant endUtc) {
        return jdbc.query(
                "SELECT p.id FROM pilot p WHERE p.id NOT IN ("
                        + " SELECT wp.pilot_id FROM window_pilot wp"
                        + " JOIN berth_window w ON w.id = wp.window_id"
                        + " WHERE w.status IN ('APPROVED','ACTIVE')"
                        + " AND w.start_utc < ? AND w.end_utc > ?"
                        + " ) ORDER BY p.id",
                (rs, rowNum) -> rs.getString(1),
                Timestamp.from(endUtc), Timestamp.from(startUtc));
    }

    /**
     * 为窗口分配引航员。
     */
    public void assignPilots(long windowId, List<String> pilotIds) {
        for (String pilotId : pilotIds) {
            jdbc.update("INSERT INTO window_pilot (window_id, pilot_id) VALUES (?,?)", windowId, pilotId);
        }
    }

    /**
     * 查询窗口已分配的引航员编号。
     */
    public List<String> findAssignedPilotIds(long windowId) {
        return jdbc.query("SELECT pilot_id FROM window_pilot WHERE window_id = ? ORDER BY pilot_id",
                (rs, rowNum) -> rs.getString(1), windowId);
    }

    /**
     * 获取审批串行化锁（单行 SELECT ... FOR UPDATE），须在事务内调用。
     */
    public void lockApprovals() {
        jdbc.queryForObject("SELECT id FROM approval_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    /**
     * 将到达起始时刻的已批准窗口批量转为已生效，返回转换条数。
     */
    public int activateDue(Instant now) {
        return jdbc.update(
                "UPDATE berth_window SET status = ?, updated_utc = ? WHERE status = ? AND start_utc <= ?",
                WindowStatus.ACTIVE.name(), Timestamp.from(now),
                WindowStatus.APPROVED.name(), Timestamp.from(now));
    }
}
