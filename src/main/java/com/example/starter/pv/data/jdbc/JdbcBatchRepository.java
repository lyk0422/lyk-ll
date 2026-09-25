package com.example.starter.pv.data.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import com.example.starter.pv.data.BatchItemPo;
import com.example.starter.pv.data.BatchPo;
import com.example.starter.pv.data.BatchRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * pv_submission_batch / pv_batch_item 的 JDBC 实现。
 */
@Repository
public class JdbcBatchRepository implements BatchRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertBatch(BatchPo po) {
        jdbcTemplate.update("""
                INSERT INTO pv_submission_batch
                    (batch_no, request_id, request_fingerprint, case_count, created_at)
                VALUES (?,?,?,?,?)
                """,
                po.batchNo(), po.requestId(), po.requestFingerprint(), po.caseCount(),
                Timestamp.from(po.createdAt()));
    }

    @Override
    public void insertItem(BatchItemPo po) {
        jdbcTemplate.update("""
                INSERT INTO pv_batch_item (batch_no, case_no, version_no, snapshot_json, ordinal)
                VALUES (?,?,?,?,?)
                """,
                po.batchNo(), po.caseNo(), po.versionNo(), po.snapshotJson(), po.ordinal());
    }

    private Optional<BatchPo> queryBatch(String sql, String key) {
        List<BatchPo> list = jdbcTemplate.query(
                "SELECT id, batch_no, request_id, request_fingerprint, case_count, created_at"
                        + " FROM pv_submission_batch WHERE " + sql,
                new BatchRowMapper(), key);
        return list.stream().findFirst();
    }

    @Override
    public Optional<BatchPo> findBatchByNo(String batchNo) {
        return queryBatch("batch_no = ?", batchNo);
    }

    @Override
    public Optional<BatchPo> findBatchByRequestId(String requestId) {
        return queryBatch("request_id = ?", requestId);
    }

    @Override
    public List<BatchItemPo> findItems(String batchNo) {
        return jdbcTemplate.query(
                "SELECT id, batch_no, case_no, version_no, snapshot_json, ordinal"
                        + " FROM pv_batch_item WHERE batch_no = ? ORDER BY ordinal ASC",
                new BatchItemRowMapper(), batchNo);
    }

    /**
     * 批次头行映射器。
     */
    static class BatchRowMapper implements RowMapper<BatchPo> {
        @Override
        public BatchPo mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new BatchPo(
                    rs.getLong("id"),
                    rs.getString("batch_no"),
                    rs.getString("request_id"),
                    rs.getString("request_fingerprint"),
                    rs.getInt("case_count"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }

    /**
     * 批次明细行映射器。
     */
    static class BatchItemRowMapper implements RowMapper<BatchItemPo> {
        @Override
        public BatchItemPo mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new BatchItemPo(
                    rs.getLong("id"),
                    rs.getString("batch_no"),
                    rs.getString("case_no"),
                    rs.getInt("version_no"),
                    rs.getString("snapshot_json"),
                    rs.getInt("ordinal"));
        }
    }
}
