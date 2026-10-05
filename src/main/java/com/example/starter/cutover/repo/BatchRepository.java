package com.example.starter.cutover.repo;

import com.example.starter.cutover.model.CutoverBatchEdgePO;
import com.example.starter.cutover.model.CutoverBatchMemberPO;
import com.example.starter.cutover.model.CutoverBatchPO;
import com.example.starter.cutover.model.CutoverBlockRecordPO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 割接批次数据访问：批次、成员、依赖边、阻断明细。
 * 参数化 SQL；多行读取按申请编号稳定排序，供拓扑查询与有序加锁使用。
 */
@Repository
public class BatchRepository {

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 批次 ----------------

    public void insertBatch(CutoverBatchPO po) {
        jdbc.update("""
                INSERT INTO cutover_batch
                  (batch_id, published_at, status, fingerprint, version, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                po.getBatchId(), Timestamp.valueOf(po.getPublishedAt()), po.getStatus(),
                po.getFingerprint(), po.getVersion(),
                Timestamp.valueOf(po.getCreatedAt()), Timestamp.valueOf(po.getUpdatedAt()));
    }

    public CutoverBatchPO findBatch(String batchId) {
        List<CutoverBatchPO> list = jdbc.query(
                "SELECT * FROM cutover_batch WHERE batch_id = ?",
                BATCH_MAPPER, batchId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 事务内行锁，不存在返回 null。 */
    public CutoverBatchPO lockBatch(String batchId) {
        List<CutoverBatchPO> list = jdbc.query(
                "SELECT * FROM cutover_batch WHERE batch_id = ? FOR UPDATE",
                BATCH_MAPPER, batchId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 全部成员到达终态后关闭批次；版本号原子加 1。 */
    public void closeBatch(String batchId, LocalDateTime updatedAt) {
        jdbc.update("""
                UPDATE cutover_batch SET status = 'CLOSED',
                  version = version + 1, updated_at = ?
                WHERE batch_id = ? AND status = 'PUBLISHED'
                """, Timestamp.valueOf(updatedAt), batchId);
    }

    /** 未结束（PUBLISHED）批次中尚未到达终态的成员数。 */
    public int countNonTerminalMembers(String batchId) {
        Integer c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM cutover_batch_member m
                JOIN cutover_request r ON r.request_id = m.request_id
                WHERE m.batch_id = ?
                  AND r.status NOT IN ('SEALED','REJECTED','ROLLED_BACK',
                                       'WINDOW_EXPIRED','DEPENDENCY_BLOCKED')
                """, Integer.class, batchId);
        return c == null ? 0 : c;
    }

    // ---------------- 成员 ----------------

    public void insertMember(CutoverBatchMemberPO po) {
        jdbc.update("""
                INSERT INTO cutover_batch_member
                  (batch_id, request_id, request_version, window_start, window_end, created_at)
                VALUES (?,?,?,?,?,?)
                """,
                po.getBatchId(), po.getRequestId(), po.getRequestVersion(),
                Timestamp.valueOf(po.getWindowStart()), Timestamp.valueOf(po.getWindowEnd()),
                Timestamp.valueOf(po.getCreatedAt()));
    }

    /** 成员按申请编号稳定排序。 */
    public List<CutoverBatchMemberPO> listMembers(String batchId) {
        return jdbc.query(
                "SELECT * FROM cutover_batch_member WHERE batch_id = ? ORDER BY request_id",
                MEMBER_MAPPER, batchId);
    }

    /**
     * 查询申请当前所属的未结束（PUBLISHED）批次 ID；无则返回 null。
     * “一项申请最多属于一个未结束批次”的判定依据。
     */
    public String findActiveBatchId(String requestId) {
        List<String> list = jdbc.queryForList("""
                SELECT m.batch_id FROM cutover_batch_member m
                JOIN cutover_batch b ON b.batch_id = m.batch_id
                WHERE m.request_id = ? AND b.status = 'PUBLISHED'
                """, String.class, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    // ---------------- 依赖边 ----------------

    public void insertEdge(CutoverBatchEdgePO po) {
        jdbc.update("""
                INSERT INTO cutover_batch_edge (batch_id, predecessor, successor, created_at)
                VALUES (?,?,?,?)
                """,
                po.getBatchId(), po.getPredecessor(), po.getSuccessor(),
                Timestamp.valueOf(po.getCreatedAt()));
    }

    /** 依赖边按前置、后继申请编号稳定排序。 */
    public List<CutoverBatchEdgePO> listEdges(String batchId) {
        return jdbc.query(
                "SELECT * FROM cutover_batch_edge WHERE batch_id = ?"
                        + " ORDER BY predecessor, successor",
                EDGE_MAPPER, batchId);
    }

    // ---------------- 阻断明细 ----------------

    public void insertBlockRecord(CutoverBlockRecordPO po) {
        jdbc.update("""
                INSERT INTO cutover_block_record
                  (batch_id, request_id, source_request_id, cause, created_at)
                VALUES (?,?,?,?,?)
                """,
                po.getBatchId(), po.getRequestId(), po.getSourceRequestId(), po.getCause(),
                Timestamp.valueOf(po.getCreatedAt()));
    }

    /** 阻断明细按被阻断申请编号、来源申请编号稳定排序。 */
    public List<CutoverBlockRecordPO> listBlockRecords(String batchId) {
        return jdbc.query(
                "SELECT * FROM cutover_block_record WHERE batch_id = ?"
                        + " ORDER BY request_id, source_request_id",
                BLOCK_MAPPER, batchId);
    }

    /** 某成员的首个阻断来源集合（按来源申请编号排序）。 */
    public List<CutoverBlockRecordPO> listBlockRecordsFor(String batchId, String requestId) {
        return jdbc.query(
                "SELECT * FROM cutover_block_record WHERE batch_id = ? AND request_id = ?"
                        + " ORDER BY source_request_id",
                BLOCK_MAPPER, batchId, requestId);
    }

    // ---------------- RowMapper ----------------

    private static final RowMapper<CutoverBatchPO> BATCH_MAPPER = (rs, n) -> {
        CutoverBatchPO po = new CutoverBatchPO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setPublishedAt(toLdt(rs, "published_at"));
        po.setStatus(rs.getString("status"));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setVersion(rs.getInt("version"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        po.setUpdatedAt(toLdt(rs, "updated_at"));
        return po;
    };

    private static final RowMapper<CutoverBatchMemberPO> MEMBER_MAPPER = (rs, n) -> {
        CutoverBatchMemberPO po = new CutoverBatchMemberPO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setRequestId(rs.getString("request_id"));
        po.setRequestVersion(rs.getInt("request_version"));
        po.setWindowStart(toLdt(rs, "window_start"));
        po.setWindowEnd(toLdt(rs, "window_end"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        return po;
    };

    private static final RowMapper<CutoverBatchEdgePO> EDGE_MAPPER = (rs, n) -> {
        CutoverBatchEdgePO po = new CutoverBatchEdgePO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setPredecessor(rs.getString("predecessor"));
        po.setSuccessor(rs.getString("successor"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        return po;
    };

    private static final RowMapper<CutoverBlockRecordPO> BLOCK_MAPPER = (rs, n) -> {
        CutoverBlockRecordPO po = new CutoverBlockRecordPO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setRequestId(rs.getString("request_id"));
        po.setSourceRequestId(rs.getString("source_request_id"));
        po.setCause(rs.getString("cause"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        return po;
    };

    private static LocalDateTime toLdt(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
