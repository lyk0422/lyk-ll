package com.example.starter.cutover.repo;

import com.example.starter.cutover.model.CutoverEventPO;
import com.example.starter.cutover.model.CutoverRequestPO;
import com.example.starter.cutover.model.CutoverStepPO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 割接领域数据访问：参数化 SQL，所有锁定查询使用 SELECT ... FOR UPDATE，
 * 供业务层在同一事务内完成“判定+状态推进”原子操作。
 */
@Repository
public class CutoverRepository {

    private final JdbcTemplate jdbc;

    public CutoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 申请主表 ----------------

    public void insertRequest(CutoverRequestPO po) {
        jdbc.update("""
                INSERT INTO cutover_request
                  (request_id, title, device_code, priority, window_start, window_end,
                   status, impact_systems, fingerprint, reject_reason, exec_started_at,
                   rollback_cause, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                po.getRequestId(), po.getTitle(), po.getDeviceCode(), po.getPriority(),
                Timestamp.valueOf(po.getWindowStart()), Timestamp.valueOf(po.getWindowEnd()),
                po.getStatus(), po.getImpactSystems(), po.getFingerprint(), po.getRejectReason(),
                po.getExecStartedAt() == null ? null : Timestamp.valueOf(po.getExecStartedAt()),
                po.getRollbackCause(),
                Timestamp.valueOf(po.getCreatedAt()), Timestamp.valueOf(po.getUpdatedAt()));
    }

    public CutoverRequestPO findRequest(String requestId) {
        List<CutoverRequestPO> list = jdbc.query(
                "SELECT * FROM cutover_request WHERE request_id = ?",
                REQUEST_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 事务内行锁，不存在返回 null。 */
    public CutoverRequestPO lockRequest(String requestId) {
        List<CutoverRequestPO> list = jdbc.query(
                "SELECT * FROM cutover_request WHERE request_id = ? FOR UPDATE",
                REQUEST_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 半开区间 [start,end) 重叠判定：a.start &lt; b.end AND b.start &lt; a.end，
     * 端点相接（a.end = b.start）不重叠。含已申请/已分析/已批准/执行中全部在途状态，
     * 保证同设备同一窗口只放行一个割接申请。
     */
    public List<CutoverRequestPO> findOverlappingActive(String deviceCode,
                                                        LocalDateTime start,
                                                        LocalDateTime end,
                                                        String excludeRequestId) {
        return jdbc.query("""
                SELECT * FROM cutover_request
                WHERE device_code = ?
                  AND status IN ('SUBMITTED','IMPACT_ANALYZED','APPROVED','EXECUTING')
                  AND window_start < ?
                  AND ? < window_end
                  AND request_id <> ?
                """, REQUEST_MAPPER, deviceCode, Timestamp.valueOf(end),
                Timestamp.valueOf(start), excludeRequestId);
    }

    public void updateMutable(CutoverRequestPO po) {
        jdbc.update("""
                UPDATE cutover_request SET
                  status = ?, impact_systems = ?, reject_reason = ?,
                  exec_started_at = ?, rollback_cause = ?, updated_at = ?
                WHERE request_id = ?
                """,
                po.getStatus(), po.getImpactSystems(), po.getRejectReason(),
                po.getExecStartedAt() == null ? null : Timestamp.valueOf(po.getExecStartedAt()),
                po.getRollbackCause(), Timestamp.valueOf(po.getUpdatedAt()),
                po.getRequestId());
    }

    // ---------------- 步骤 ----------------

    public void insertStep(CutoverStepPO po) {
        jdbc.update("""
                INSERT INTO cutover_step
                  (request_id, seq_no, step_name, step_type, status,
                   started_at, reported_at, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                po.getRequestId(), po.getSeqNo(), po.getStepName(), po.getStepType(),
                po.getStatus(),
                po.getStartedAt() == null ? null : Timestamp.valueOf(po.getStartedAt()),
                po.getReportedAt() == null ? null : Timestamp.valueOf(po.getReportedAt()),
                Timestamp.valueOf(po.getCreatedAt()), Timestamp.valueOf(po.getUpdatedAt()));
    }

    public List<CutoverStepPO> listSteps(String requestId) {
        return jdbc.query(
                "SELECT * FROM cutover_step WHERE request_id = ? ORDER BY seq_no",
                STEP_MAPPER, requestId);
    }

    /** 锁定该申请的全部步骤行并按序号排序，供原子冻结/回报。 */
    public List<CutoverStepPO> lockSteps(String requestId) {
        return jdbc.query(
                "SELECT * FROM cutover_step WHERE request_id = ? ORDER BY seq_no FOR UPDATE",
                STEP_MAPPER, requestId);
    }

    public int updateStepState(String requestId, int seqNo, String status,
                               LocalDateTime startedAt, LocalDateTime reportedAt) {
        return jdbc.update("""
                UPDATE cutover_step SET
                  status = ?, started_at = ?, reported_at = ?, updated_at = ?
                WHERE request_id = ? AND seq_no = ?
                """,
                status,
                startedAt == null ? null : Timestamp.valueOf(startedAt),
                reportedAt == null ? null : Timestamp.valueOf(reportedAt),
                Timestamp.valueOf(LocalDateTime.now()),
                requestId, seqNo);
    }

    /** 将该申请所有尚未终态（PENDING/RUNNING）的步骤原子冻结。 */
    public int freezeUnfinishedSteps(String requestId) {
        return jdbc.update("""
                UPDATE cutover_step SET status = 'FROZEN', updated_at = ?
                WHERE request_id = ? AND status IN ('PENDING','RUNNING')
                """, Timestamp.valueOf(LocalDateTime.now()), requestId);
    }

    public int countUnfinishedSteps(String requestId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cutover_step WHERE request_id = ? AND status IN ('PENDING','RUNNING')",
                Integer.class, requestId);
        return c == null ? 0 : c;
    }

    // ---------------- 事件 ----------------

    public void insertEvent(CutoverEventPO po) {
        jdbc.update("""
                INSERT INTO cutover_event
                  (request_id, request_uid, event_type, seq_no, payload_fingerprint,
                   result_snapshot, late_flag, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                po.getRequestId(), po.getRequestUid(), po.getEventType(), po.getSeqNo(),
                po.getPayloadFingerprint(), po.getResultSnapshot(),
                po.getLateFlag() != null && po.getLateFlag(),
                Timestamp.valueOf(po.getCreatedAt()));
    }

    public CutoverEventPO findEvent(String requestUid) {
        List<CutoverEventPO> list = jdbc.query(
                "SELECT * FROM cutover_event WHERE request_uid = ?",
                EVENT_MAPPER, requestUid);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<CutoverEventPO> listEvents(String requestId) {
        return jdbc.query(
                "SELECT * FROM cutover_event WHERE request_id = ? ORDER BY id",
                EVENT_MAPPER, requestId);
    }

    // ---------------- 合成基础数据 ----------------

    public List<String> findSystemsByDevice(String deviceCode) {
        return jdbc.queryForList(
                "SELECT system_code FROM device_biz WHERE device_code = ? ORDER BY system_code",
                String.class, deviceCode);
    }

    public boolean deviceExists(String deviceCode) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device WHERE device_code = ?", Integer.class, deviceCode);
        return c != null && c > 0;
    }

    /** 锁住设备行，串行化同设备并发申请的窗口冲突/优先级判定。 */
    public void lockDevice(String deviceCode) {
        jdbc.queryForObject("SELECT device_code FROM device WHERE device_code = ? FOR UPDATE",
                String.class, deviceCode);
    }

    // ---------------- RowMapper ----------------

    private static final RowMapper<CutoverRequestPO> REQUEST_MAPPER = (rs, n) -> {
        CutoverRequestPO po = new CutoverRequestPO();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setTitle(rs.getString("title"));
        po.setDeviceCode(rs.getString("device_code"));
        po.setPriority(rs.getString("priority"));
        po.setWindowStart(toLdt(rs, "window_start"));
        po.setWindowEnd(toLdt(rs, "window_end"));
        po.setStatus(rs.getString("status"));
        po.setImpactSystems(rs.getString("impact_systems"));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setRejectReason(rs.getString("reject_reason"));
        po.setExecStartedAt(toLdt(rs, "exec_started_at"));
        po.setRollbackCause(rs.getString("rollback_cause"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        po.setUpdatedAt(toLdt(rs, "updated_at"));
        return po;
    };

    private static final RowMapper<CutoverStepPO> STEP_MAPPER = (rs, n) -> {
        CutoverStepPO po = new CutoverStepPO();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setSeqNo(rs.getInt("seq_no"));
        po.setStepName(rs.getString("step_name"));
        po.setStepType(rs.getString("step_type"));
        po.setStatus(rs.getString("status"));
        po.setStartedAt(toLdt(rs, "started_at"));
        po.setReportedAt(toLdt(rs, "reported_at"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        po.setUpdatedAt(toLdt(rs, "updated_at"));
        return po;
    };

    private static final RowMapper<CutoverEventPO> EVENT_MAPPER = (rs, n) -> {
        CutoverEventPO po = new CutoverEventPO();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setRequestUid(rs.getString("request_uid"));
        po.setEventType(rs.getString("event_type"));
        int seq = rs.getInt("seq_no");
        po.setSeqNo(rs.wasNull() ? null : seq);
        po.setPayloadFingerprint(rs.getString("payload_fingerprint"));
        po.setResultSnapshot(rs.getString("result_snapshot"));
        po.setLateFlag(rs.getBoolean("late_flag"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        return po;
    };

    private static LocalDateTime toLdt(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
