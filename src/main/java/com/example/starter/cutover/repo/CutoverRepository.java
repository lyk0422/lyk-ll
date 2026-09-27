package com.example.starter.cutover.repo;

import com.example.starter.cutover.model.CutoverBatchEdgePO;
import com.example.starter.cutover.model.CutoverBatchMemberPO;
import com.example.starter.cutover.model.CutoverBatchPO;
import com.example.starter.cutover.model.CutoverBlockPO;
import com.example.starter.cutover.model.CutoverEventPO;
import com.example.starter.cutover.model.CutoverRequestPO;
import com.example.starter.cutover.model.CutoverStepPO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
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
                   rollback_cause, version_no, open_batch_id, blocked_flag, blocked_sources,
                   created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                po.getRequestId(), po.getTitle(), po.getDeviceCode(), po.getPriority(),
                Timestamp.valueOf(po.getWindowStart()), Timestamp.valueOf(po.getWindowEnd()),
                po.getStatus(), po.getImpactSystems(), po.getFingerprint(), po.getRejectReason(),
                po.getExecStartedAt() == null ? null : Timestamp.valueOf(po.getExecStartedAt()),
                po.getRollbackCause(),
                po.getVersionNo(), po.getOpenBatchId(), po.isBlockedFlag(), po.getBlockedSources(),
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
                  exec_started_at = ?, rollback_cause = ?,
                  version_no = ?, open_batch_id = ?, blocked_flag = ?, blocked_sources = ?,
                  updated_at = ?
                WHERE request_id = ?
                """,
                po.getStatus(), po.getImpactSystems(), po.getRejectReason(),
                po.getExecStartedAt() == null ? null : Timestamp.valueOf(po.getExecStartedAt()),
                po.getRollbackCause(),
                po.getVersionNo(), po.getOpenBatchId(), po.isBlockedFlag(), po.getBlockedSources(),
                Timestamp.valueOf(po.getUpdatedAt()),
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

    // ---------------- 批次 ----------------

    /**
     * 批次相关写操作的全局互斥锁：所有涉及“批次发布 / 成员启动 / 失败传播 / 收口”的
     * 事务都先取此锁，杜绝发布（先成员行）与成员操作（先批次行）之间的跨事务死锁。
     */
    public void lockGlobal() {
        jdbc.queryForObject("SELECT id FROM cutover_mutex WHERE id = 1 FOR UPDATE",
                Integer.class);
    }

    public void insertBatch(CutoverBatchPO po) {
        jdbc.update("""
                INSERT INTO cutover_batch
                  (batch_id, frozen_at, members_json, edges_json, status, fingerprint,
                   result_snapshot, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                po.getBatchId(), Timestamp.valueOf(po.getFrozenAt()), po.getMembersJson(),
                po.getEdgesJson(), po.getStatus(), po.getFingerprint(), po.getResultSnapshot(),
                Timestamp.valueOf(po.getCreatedAt()), Timestamp.valueOf(po.getUpdatedAt()));
    }

    public CutoverBatchPO findBatch(String batchId) {
        List<CutoverBatchPO> list = jdbc.query(
                "SELECT * FROM cutover_batch WHERE batch_id = ?", BATCH_MAPPER, batchId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 事务内锁定批次行。 */
    public CutoverBatchPO lockBatch(String batchId) {
        List<CutoverBatchPO> list = jdbc.query(
                "SELECT * FROM cutover_batch WHERE batch_id = ? FOR UPDATE",
                BATCH_MAPPER, batchId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateBatchResultAndStatus(CutoverBatchPO po) {
        jdbc.update("""
                UPDATE cutover_batch SET status = ?, result_snapshot = ?, updated_at = ?
                WHERE batch_id = ?
                """, po.getStatus(), po.getResultSnapshot(),
                Timestamp.valueOf(po.getUpdatedAt()), po.getBatchId());
    }

    public void insertBatchMember(CutoverBatchMemberPO po) {
        jdbc.update("""
                INSERT INTO cutover_batch_member
                  (batch_id, request_id, expected_version, window_start, window_end, ord, created_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                po.getBatchId(), po.getRequestId(), po.getExpectedVersion(),
                Timestamp.valueOf(po.getWindowStart()), Timestamp.valueOf(po.getWindowEnd()),
                po.getOrd(), Timestamp.valueOf(po.getCreatedAt()));
    }

    /** 冻结成员，按申请编号稳定排序。 */
    public List<CutoverBatchMemberPO> listBatchMembers(String batchId) {
        return jdbc.query(
                "SELECT * FROM cutover_batch_member WHERE batch_id = ? ORDER BY ord, request_id",
                MEMBER_MAPPER, batchId);
    }

    /** 锁定批次全部成员申请行，按申请编号排序，供传播/关闭判定串行化。 */
    public List<CutoverRequestPO> lockMemberRequests(String batchId) {
        return jdbc.query("""
                SELECT r.* FROM cutover_request r
                JOIN cutover_batch_member m ON m.request_id = r.request_id
                WHERE m.batch_id = ?
                ORDER BY r.request_id
                FOR UPDATE
                """, REQUEST_MAPPER, batchId);
    }

    /** 非锁定读取批次全部成员申请行，按申请编号排序，供只读视图使用。 */
    public List<CutoverRequestPO> listMemberRequests(String batchId) {
        return jdbc.query("""
                SELECT r.* FROM cutover_request r
                JOIN cutover_batch_member m ON m.request_id = r.request_id
                WHERE m.batch_id = ?
                ORDER BY r.request_id
                """, REQUEST_MAPPER, batchId);
    }

    public void insertBatchEdge(CutoverBatchEdgePO po) {
        jdbc.update("""
                INSERT INTO cutover_batch_edge
                  (batch_id, from_request, to_request, ord, created_at)
                VALUES (?,?,?,?,?)
                """,
                po.getBatchId(), po.getFromRequest(), po.getToRequest(), po.getOrd(),
                Timestamp.valueOf(po.getCreatedAt()));
    }

    /** 冻结依赖边，按 (from,to) 稳定排序。 */
    public List<CutoverBatchEdgePO> listBatchEdges(String batchId) {
        return jdbc.query(
                "SELECT * FROM cutover_batch_edge WHERE batch_id = ? ORDER BY ord, from_request, to_request",
                EDGE_MAPPER, batchId);
    }

    public void insertBlock(CutoverBlockPO po) {
        jdbc.update("""
                INSERT INTO cutover_block
                  (batch_id, request_id, source_request, created_at)
                VALUES (?,?,?,?)
                """,
                po.getBatchId(), po.getRequestId(), po.getSourceRequest(),
                Timestamp.valueOf(po.getCreatedAt()));
    }

    /** 批次阻断明细，按被阻断申请、阻断来源稳定排序。 */
    public List<CutoverBlockPO> listBlocks(String batchId) {
        return jdbc.query("""
                SELECT * FROM cutover_block WHERE batch_id = ?
                ORDER BY request_id, source_request
                """, BLOCK_MAPPER, batchId);
    }

    /** 事务内按申请编号升序锁定一批申请行，供发布时统一版本/状态校验与绑定。 */
    public List<CutoverRequestPO> lockRequestsByIds(Collection<String> requestIds) {
        List<String> sorted = requestIds.stream().distinct().sorted().toList();
        String placeholders = String.join(",", sorted.stream().map(x -> "?").toList());
        Object[] args = sorted.toArray();
        return jdbc.query(
                "SELECT * FROM cutover_request WHERE request_id IN (" + placeholders
                        + ") ORDER BY request_id FOR UPDATE",
                REQUEST_MAPPER, args);
    }

    /** 关闭批次：置空全部成员的 open_batch_id，释放重新组批名额。 */
    public int clearOpenBatchForMembers(String batchId) {
        return jdbc.update("""
                UPDATE cutover_request SET open_batch_id = NULL, updated_at = ?
                WHERE request_id IN (SELECT request_id FROM cutover_batch_member WHERE batch_id = ?)
                  AND open_batch_id = ?
                """, Timestamp.valueOf(LocalDateTime.now()), batchId, batchId);
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
        po.setVersionNo(rs.getLong("version_no"));
        po.setOpenBatchId(rs.getString("open_batch_id"));
        po.setBlockedFlag(rs.getBoolean("blocked_flag"));
        po.setBlockedSources(rs.getString("blocked_sources"));
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

    private static final RowMapper<CutoverBatchPO> BATCH_MAPPER = (rs, n) -> {
        CutoverBatchPO po = new CutoverBatchPO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setFrozenAt(toLdt(rs, "frozen_at"));
        po.setMembersJson(rs.getString("members_json"));
        po.setEdgesJson(rs.getString("edges_json"));
        po.setStatus(rs.getString("status"));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setResultSnapshot(rs.getString("result_snapshot"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        po.setUpdatedAt(toLdt(rs, "updated_at"));
        return po;
    };

    private static final RowMapper<CutoverBatchMemberPO> MEMBER_MAPPER = (rs, n) -> {
        CutoverBatchMemberPO po = new CutoverBatchMemberPO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setRequestId(rs.getString("request_id"));
        po.setExpectedVersion(rs.getLong("expected_version"));
        po.setWindowStart(toLdt(rs, "window_start"));
        po.setWindowEnd(toLdt(rs, "window_end"));
        po.setOrd(rs.getInt("ord"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        return po;
    };

    private static final RowMapper<CutoverBatchEdgePO> EDGE_MAPPER = (rs, n) -> {
        CutoverBatchEdgePO po = new CutoverBatchEdgePO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setFromRequest(rs.getString("from_request"));
        po.setToRequest(rs.getString("to_request"));
        po.setOrd(rs.getInt("ord"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        return po;
    };

    private static final RowMapper<CutoverBlockPO> BLOCK_MAPPER = (rs, n) -> {
        CutoverBlockPO po = new CutoverBlockPO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setRequestId(rs.getString("request_id"));
        po.setSourceRequest(rs.getString("source_request"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        return po;
    };
}
