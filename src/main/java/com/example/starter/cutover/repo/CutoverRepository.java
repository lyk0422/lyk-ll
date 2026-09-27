package com.example.starter.cutover.repo;

import com.example.starter.cutover.model.CutoverBatchEdgePO;
import com.example.starter.cutover.model.CutoverBatchMemberPO;
import com.example.starter.cutover.model.CutoverBatchPO;
import com.example.starter.cutover.model.CutoverEventPO;
import com.example.starter.cutover.model.CutoverRequestPO;
import com.example.starter.cutover.model.CutoverStepPO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.StringJoiner;
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
                   rollback_cause, version, batch_id, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                po.getRequestId(), po.getTitle(), po.getDeviceCode(), po.getPriority(),
                Timestamp.valueOf(po.getWindowStart()), Timestamp.valueOf(po.getWindowEnd()),
                po.getStatus(), po.getImpactSystems(), po.getFingerprint(), po.getRejectReason(),
                po.getExecStartedAt() == null ? null : Timestamp.valueOf(po.getExecStartedAt()),
                po.getRollbackCause(), po.getVersion(), po.getBatchId(),
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
     * 按申请编号升序逐行加锁（FOR UPDATE），供批次发布串行化成员校验与绑定。
     * 固定升序加锁顺序，避免与单申请操作形成循环等待。
     */
    public List<CutoverRequestPO> lockRequestsSorted(List<String> requestIds) {
        StringJoiner placeholders = new StringJoiner(",");
        for (int i = 0; i < requestIds.size(); i++) {
            placeholders.add("?");
        }
        return jdbc.query(
                "SELECT * FROM cutover_request WHERE request_id IN (" + placeholders
                        + ") ORDER BY request_id FOR UPDATE",
                REQUEST_MAPPER, requestIds.toArray());
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

    /** 成功变更：业务字段更新同事务内版本加 1（乐观锁版本，从 1 开始）。 */
    public void updateMutable(CutoverRequestPO po) {
        int nextVersion = po.getVersion() + 1;
        jdbc.update("""
                UPDATE cutover_request SET
                  status = ?, impact_systems = ?, reject_reason = ?,
                  exec_started_at = ?, rollback_cause = ?, version = ?, updated_at = ?
                WHERE request_id = ?
                """,
                po.getStatus(), po.getImpactSystems(), po.getRejectReason(),
                po.getExecStartedAt() == null ? null : Timestamp.valueOf(po.getExecStartedAt()),
                po.getRollbackCause(), nextVersion, Timestamp.valueOf(po.getUpdatedAt()),
                po.getRequestId());
        po.setVersion(nextVersion);
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

    // ---------------- 依赖批次 ----------------

    public void insertBatch(CutoverBatchPO po) {
        jdbc.update("""
                INSERT INTO cutover_batch
                  (batch_id, fingerprint, published_at, result_snapshot, created_at)
                VALUES (?,?,?,?,?)
                """,
                po.getBatchId(), po.getFingerprint(), Timestamp.valueOf(po.getPublishedAt()),
                po.getResultSnapshot(), Timestamp.valueOf(po.getCreatedAt()));
    }

    public CutoverBatchPO findBatch(String batchId) {
        List<CutoverBatchPO> list = jdbc.query(
                "SELECT * FROM cutover_batch WHERE batch_id = ?", BATCH_MAPPER, batchId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insertBatchMember(CutoverBatchMemberPO po) {
        jdbc.update("""
                INSERT INTO cutover_batch_member
                  (batch_id, request_id, expected_version, window_start, window_end,
                   blocked_by, created_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                po.getBatchId(), po.getRequestId(), po.getExpectedVersion(),
                Timestamp.valueOf(po.getWindowStart()), Timestamp.valueOf(po.getWindowEnd()),
                po.getBlockedBy(), Timestamp.valueOf(po.getCreatedAt()));
    }

    public void insertBatchEdge(CutoverBatchEdgePO po) {
        jdbc.update("""
                INSERT INTO cutover_batch_edge
                  (batch_id, from_request_id, to_request_id, created_at)
                VALUES (?,?,?,?)
                """,
                po.getBatchId(), po.getFromRequestId(), po.getToRequestId(),
                Timestamp.valueOf(po.getCreatedAt()));
    }

    /** 批次成员按申请编号稳定升序。 */
    public List<CutoverBatchMemberPO> listBatchMembers(String batchId) {
        return jdbc.query(
                "SELECT * FROM cutover_batch_member WHERE batch_id = ? ORDER BY request_id",
                MEMBER_MAPPER, batchId);
    }

    /** 依赖边按 (from,to) 稳定升序。 */
    public List<CutoverBatchEdgePO> listBatchEdges(String batchId) {
        return jdbc.query(
                "SELECT * FROM cutover_batch_edge WHERE batch_id = ?"
                        + " ORDER BY from_request_id, to_request_id",
                EDGE_MAPPER, batchId);
    }

    /** 指定申请的直接后继申请编号（升序），供阻断传播 BFS。 */
    public List<String> listSuccessorIds(String batchId, String fromRequestId) {
        return jdbc.queryForList(
                "SELECT to_request_id FROM cutover_batch_edge"
                        + " WHERE batch_id = ? AND from_request_id = ? ORDER BY to_request_id",
                String.class, batchId, fromRequestId);
    }

    /** 指定申请尚未验证封板（非 SEALED）的直接前置申请编号（升序），供执行门禁判定。 */
    public List<String> listUnsealedPrerequisites(String batchId, String toRequestId) {
        return jdbc.queryForList("""
                SELECT e.from_request_id FROM cutover_batch_edge e
                JOIN cutover_request r ON r.request_id = e.from_request_id
                WHERE e.batch_id = ? AND e.to_request_id = ? AND r.status <> 'SEALED'
                ORDER BY e.from_request_id
                """, String.class, batchId, toRequestId);
    }

    /** 批次发布：绑定申请到批次并版本加 1（调用方须已持有申请行锁）。 */
    public void bindBatch(String requestId, String batchId, LocalDateTime updatedAt) {
        jdbc.update("""
                UPDATE cutover_request SET batch_id = ?, version = version + 1, updated_at = ?
                WHERE request_id = ?
                """, batchId, Timestamp.valueOf(updatedAt), requestId);
    }

    /**
     * 依赖阻断传播：仅当申请仍处于 APPROVED（尚未开始）时原子转 DEPENDENCY_BLOCKED
     * 并版本加 1；已开始/已封板成员不受影响。返回受影响行数。
     */
    public int blockIfApproved(String requestId, LocalDateTime updatedAt) {
        return jdbc.update("""
                UPDATE cutover_request SET status = 'DEPENDENCY_BLOCKED',
                  version = version + 1, updated_at = ?
                WHERE request_id = ? AND status = 'APPROVED'
                """, Timestamp.valueOf(updatedAt), requestId);
    }

    /** 记录首个阻断来源；已有来源（更早的阻断波次）不覆盖。 */
    public int markMemberBlocked(String batchId, String requestId, String rootRequestId) {
        return jdbc.update("""
                UPDATE cutover_batch_member SET blocked_by = ?
                WHERE batch_id = ? AND request_id = ? AND blocked_by IS NULL
                """, rootRequestId, batchId, requestId);
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
        po.setVersion(rs.getInt("version"));
        po.setBatchId(rs.getString("batch_id"));
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

    private static final RowMapper<CutoverBatchPO> BATCH_MAPPER = (rs, n) -> {
        CutoverBatchPO po = new CutoverBatchPO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setPublishedAt(toLdt(rs, "published_at"));
        po.setResultSnapshot(rs.getString("result_snapshot"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        return po;
    };

    private static final RowMapper<CutoverBatchMemberPO> MEMBER_MAPPER = (rs, n) -> {
        CutoverBatchMemberPO po = new CutoverBatchMemberPO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setRequestId(rs.getString("request_id"));
        po.setExpectedVersion(rs.getInt("expected_version"));
        po.setWindowStart(toLdt(rs, "window_start"));
        po.setWindowEnd(toLdt(rs, "window_end"));
        po.setBlockedBy(rs.getString("blocked_by"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        return po;
    };

    private static final RowMapper<CutoverBatchEdgePO> EDGE_MAPPER = (rs, n) -> {
        CutoverBatchEdgePO po = new CutoverBatchEdgePO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setFromRequestId(rs.getString("from_request_id"));
        po.setToRequestId(rs.getString("to_request_id"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        return po;
    };

    private static LocalDateTime toLdt(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
