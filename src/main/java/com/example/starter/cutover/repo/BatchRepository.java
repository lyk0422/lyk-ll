package com.example.starter.cutover.repo;

import com.example.starter.cutover.model.CutoverBatchMemberPO;
import com.example.starter.cutover.model.CutoverBatchEdgePO;
import com.example.starter.cutover.model.CutoverBatchPO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 批次数据访问：批次主表、成员快照、依赖边。参数化 SQL；
 * 批次行锁使用 SELECT ... FOR UPDATE，与申请行锁共同保证发布/启动/阻断串行化。
 */
@Repository
public class BatchRepository {

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 批次主表 ----------------

    public void insertBatch(CutoverBatchPO po) {
        jdbc.update("""
                INSERT INTO cutover_batch
                  (batch_id, batch_name, status, version, fingerprint, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                po.getBatchId(), po.getBatchName(), po.getStatus(), po.getVersion(),
                po.getFingerprint(),
                Timestamp.valueOf(po.getCreatedAt()), Timestamp.valueOf(po.getUpdatedAt()));
    }

    public CutoverBatchPO findBatch(String batchId) {
        List<CutoverBatchPO> list = jdbc.query(
                "SELECT * FROM cutover_batch WHERE batch_id = ?",
                BATCH_MAPPER, batchId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 事务内批次行锁，不存在返回 null。 */
    public CutoverBatchPO lockBatch(String batchId) {
        List<CutoverBatchPO> list = jdbc.query(
                "SELECT * FROM cutover_batch WHERE batch_id = ? FOR UPDATE",
                BATCH_MAPPER, batchId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 结束批次：仅当版本匹配时置 CLOSED 并 version+1，返回更新行数。 */
    public int closeBatch(String batchId, int expectedVersion, LocalDateTime updatedAt) {
        return jdbc.update("""
                UPDATE cutover_batch SET status = 'CLOSED', version = version + 1, updated_at = ?
                WHERE batch_id = ? AND version = ? AND status = 'PUBLISHED'
                """, Timestamp.valueOf(updatedAt), batchId, expectedVersion);
    }

    // ---------------- 批次成员 ----------------

    public void insertMember(CutoverBatchMemberPO po) {
        jdbc.update("""
                INSERT INTO cutover_batch_member
                  (batch_id, request_id, request_version, window_start, window_end,
                   member_status, block_sources, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                po.getBatchId(), po.getRequestId(), po.getRequestVersion(),
                Timestamp.valueOf(po.getWindowStart()), Timestamp.valueOf(po.getWindowEnd()),
                po.getMemberStatus(), po.getBlockSources(),
                Timestamp.valueOf(po.getCreatedAt()), Timestamp.valueOf(po.getUpdatedAt()));
    }

    /** 批次成员按申请编号稳定升序。 */
    public List<CutoverBatchMemberPO> listMembers(String batchId) {
        return jdbc.query(
                "SELECT * FROM cutover_batch_member WHERE batch_id = ? ORDER BY request_id",
                MEMBER_MAPPER, batchId);
    }

    public CutoverBatchMemberPO findMember(String batchId, String requestId) {
        List<CutoverBatchMemberPO> list = jdbc.query(
                "SELECT * FROM cutover_batch_member WHERE batch_id = ? AND request_id = ?",
                MEMBER_MAPPER, batchId, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询申请所在的未结束（PUBLISHED）批次成员行，不存在返回 null。 */
    public CutoverBatchMemberPO findOpenMembership(String requestId) {
        List<CutoverBatchMemberPO> list = jdbc.query("""
                SELECT m.* FROM cutover_batch_member m
                JOIN cutover_batch b ON b.batch_id = m.batch_id
                WHERE m.request_id = ? AND b.status = 'PUBLISHED'
                """, MEMBER_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询申请在任意批次（含已结束）中的成员行，按批次号升序，用于阻断门禁。 */
    public List<CutoverBatchMemberPO> listMemberships(String requestId) {
        return jdbc.query(
                "SELECT * FROM cutover_batch_member WHERE request_id = ? ORDER BY batch_id",
                MEMBER_MAPPER, requestId);
    }

    /** 更新成员状态；blockSources 非空时写入首个阻断来源集合（仅 READY->BLOCKED 调用）。 */
    public int updateMemberStatus(String batchId, String requestId, String memberStatus,
                                  String blockSources, LocalDateTime updatedAt) {
        return jdbc.update("""
                UPDATE cutover_batch_member
                SET member_status = ?, block_sources = ?, updated_at = ?
                WHERE batch_id = ? AND request_id = ?
                """,
                memberStatus, blockSources, Timestamp.valueOf(updatedAt),
                batchId, requestId);
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

    /** 批次依赖边按 predecessor、successor 稳定升序。 */
    public List<CutoverBatchEdgePO> listEdges(String batchId) {
        return jdbc.query("""
                SELECT * FROM cutover_batch_edge WHERE batch_id = ?
                ORDER BY predecessor, successor
                """, EDGE_MAPPER, batchId);
    }

    /** 某后继在未结束批次中的全部直接前置边。 */
    public List<CutoverBatchEdgePO> listInboundOpenEdges(String successor) {
        return jdbc.query("""
                SELECT e.* FROM cutover_batch_edge e
                JOIN cutover_batch b ON b.batch_id = e.batch_id
                WHERE e.successor = ? AND b.status = 'PUBLISHED'
                ORDER BY e.predecessor
                """, EDGE_MAPPER, successor);
    }

    /** 未结束批次中的全部依赖边（阻断传播按图遍历）。 */
    public List<CutoverBatchEdgePO> listAllOpenEdges() {
        return jdbc.query("""
                SELECT e.* FROM cutover_batch_edge e
                JOIN cutover_batch b ON b.batch_id = e.batch_id
                WHERE b.status = 'PUBLISHED'
                ORDER BY e.predecessor, e.successor
                """, EDGE_MAPPER);
    }

    // ---------------- RowMapper ----------------

    private static final RowMapper<CutoverBatchPO> BATCH_MAPPER = (rs, n) -> {
        CutoverBatchPO po = new CutoverBatchPO();
        po.setId(rs.getLong("id"));
        po.setBatchId(rs.getString("batch_id"));
        po.setBatchName(rs.getString("batch_name"));
        po.setStatus(rs.getString("status"));
        po.setVersion(rs.getInt("version"));
        po.setFingerprint(rs.getString("fingerprint"));
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
        po.setMemberStatus(rs.getString("member_status"));
        po.setBlockSources(rs.getString("block_sources"));
        po.setCreatedAt(toLdt(rs, "created_at"));
        po.setUpdatedAt(toLdt(rs, "updated_at"));
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

    private static LocalDateTime toLdt(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
