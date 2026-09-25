package com.example.starter.pv;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 递交批次及快照项数据访问。request_id 唯一索引保证幂等，批次项创建后不再变更。
 */
@Repository
public class BatchRepository {

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PvBatch> BATCH_MAPPER = (rs, rowNum) -> new PvBatch(
            rs.getLong("id"),
            rs.getString("batch_no"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<PvBatchItem> ITEM_MAPPER = (rs, rowNum) -> mapItem(rs);

    private static PvBatchItem mapItem(ResultSet rs) throws SQLException {
        Timestamp initial = rs.getTimestamp("initial_submitted_at");
        Timestamp lastFollowUp = rs.getTimestamp("last_follow_up_at");
        return new PvBatchItem(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getLong("case_id"),
                rs.getString("case_no"),
                CaseStatus.valueOf(rs.getString("case_status")),
                rs.getInt("case_version"),
                initial == null ? null : initial.toInstant(),
                lastFollowUp == null ? null : lastFollowUp.toInstant());
    }

    /**
     * 插入批次头。request_id 冲突时抛出 DuplicateKeyException，由上层按幂等重放处理。
     */
    public PvBatch insertBatch(String batchNo, String requestId, String fingerprint, java.time.Instant now) {
        String sql = "INSERT INTO pv_submission_batch (batch_no, request_id, fingerprint, created_at)"
                + " VALUES (?,?,?,?)";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setString(2, requestId);
            ps.setString(3, fingerprint);
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keys);
        Number key = keys.getKey();
        return new PvBatch(key == null ? null : key.longValue(), batchNo, requestId, fingerprint, now);
    }

    /**
     * 插入批次快照项。
     */
    public void insertItem(long batchId, PvCase snapshot) {
        jdbc.update(
                "INSERT INTO pv_submission_batch_item (batch_id, case_id, case_no, case_status, case_version,"
                + " initial_submitted_at, last_follow_up_at) VALUES (?,?,?,?,?,?,?)",
                batchId,
                snapshot.id(),
                snapshot.caseNo(),
                snapshot.status().name(),
                snapshot.version(),
                snapshot.initialSubmittedAt() == null ? null : Timestamp.from(snapshot.initialSubmittedAt()),
                snapshot.lastFollowUpAt() == null ? null : Timestamp.from(snapshot.lastFollowUpAt()));
    }

    /**
     * 按幂等键查询批次。
     */
    public Optional<PvBatch> findByRequestId(String requestId) {
        List<PvBatch> list = jdbc.query(
                "SELECT id, batch_no, request_id, fingerprint, created_at"
                + " FROM pv_submission_batch WHERE request_id = ?",
                BATCH_MAPPER, requestId);
        return list.stream().findFirst();
    }

    /**
     * 按批次号查询批次。
     */
    public Optional<PvBatch> findByBatchNo(String batchNo) {
        List<PvBatch> list = jdbc.query(
                "SELECT id, batch_no, request_id, fingerprint, created_at"
                + " FROM pv_submission_batch WHERE batch_no = ?",
                BATCH_MAPPER, batchNo);
        return list.stream().findFirst();
    }

    /**
     * 查询批次快照项（按个例顺序，结果稳定）。
     */
    public List<PvBatchItem> findItems(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, case_id, case_no, case_status, case_version,"
                + " initial_submitted_at, last_follow_up_at"
                + " FROM pv_submission_batch_item WHERE batch_id = ? ORDER BY id",
                ITEM_MAPPER, batchId);
    }

    /**
     * 写入正式批次号（插入后按自增主键生成，保证唯一且可读）。
     */
    public void assignBatchNo(long id, String batchNo) {
        jdbc.update("UPDATE pv_submission_batch SET batch_no = ? WHERE id = ?", batchNo, id);
    }
}
