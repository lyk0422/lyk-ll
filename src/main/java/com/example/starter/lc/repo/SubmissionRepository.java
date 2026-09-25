package com.example.starter.lc.repo;

import com.example.starter.lc.LcBusinessException;
import com.example.starter.lc.LcErrorCode;
import com.example.starter.lc.dto.DiscrepancyItem;
import com.example.starter.lc.dto.DocumentItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 交单批次、单据与不符点数据访问（H2 MODE=MySQL，参数化 SQL）。
 */
@Repository
public class SubmissionRepository {

    private static final SubmissionRowMapper ROW_MAPPER = new SubmissionRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public SubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入交单批次及其单据清单（同一事务内完成）。 */
    public void insertBatch(SubmissionRow row, List<DocumentItem> documents) {
        jdbcTemplate.update("""
                INSERT INTO doc_submission
                    (batch_no, lc_no, beneficiary, draw_amount, status, presented_at, deadline_at,
                     refused_at, accepted_at, paid_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.batchNo(), row.lcNo(), row.beneficiary(), row.drawAmount(), row.status(),
                Timestamp.from(row.presentedAt()), Timestamp.from(row.deadlineAt()),
                row.refusedAt() == null ? null : Timestamp.from(row.refusedAt()),
                row.acceptedAt() == null ? null : Timestamp.from(row.acceptedAt()),
                row.paidAt() == null ? null : Timestamp.from(row.paidAt()),
                Timestamp.from(row.createdAt()));
        for (DocumentItem doc : documents) {
            jdbcTemplate.update(
                    "INSERT INTO submission_document (batch_no, doc_type, doc_ref) VALUES (?, ?, ?)",
                    row.batchNo(), doc.docType(), doc.docRef());
        }
    }

    /** 统计信用证下已有批次数，用于生成顺序批次号。 */
    public int countByLc(String lcNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM doc_submission WHERE lc_no = ?", Integer.class, lcNo);
        return count == null ? 0 : count;
    }

    /** 按批次号查询，不存在返回 null。 */
    public SubmissionRow find(String batchNo) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM doc_submission WHERE batch_no = ?", ROW_MAPPER, batchNo);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 行锁查询批次，不存在返回 404。调用方须处于事务中。 */
    public SubmissionRow findForUpdate(String batchNo) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM doc_submission WHERE batch_no = ? FOR UPDATE", ROW_MAPPER, batchNo);
        } catch (EmptyResultDataAccessException e) {
            throw new LcBusinessException(LcErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                    "交单批次不存在: " + batchNo);
        }
    }

    /** 行锁查询信用证下审单中的批次，无则返回 null。 */
    public SubmissionRow findUnderReviewForUpdate(String lcNo) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT * FROM doc_submission WHERE lc_no = ? AND status = 'UNDER_REVIEW' FOR UPDATE
                    """, ROW_MAPPER, lcNo);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 登记拒付不符点（仅在拒付事务内一次性写入，事后不再有新增入口）。 */
    public void insertDiscrepancies(String batchNo, List<DiscrepancyItem> discrepancies, Instant now) {
        for (DiscrepancyItem item : discrepancies) {
            jdbcTemplate.update("""
                    INSERT INTO discrepancy (batch_no, doc_type, description, waived, created_at)
                    VALUES (?, ?, ?, FALSE, ?)
                    """, batchNo, item.docType(), item.description(), Timestamp.from(now));
        }
    }

    /** 查询批次不符点（按单据类型、描述排序，作为放弃比对的规范化顺序）。 */
    public List<DiscrepancyRow> findDiscrepancies(String batchNo) {
        return jdbcTemplate.query("""
                SELECT * FROM discrepancy WHERE batch_no = ?
                ORDER BY doc_type ASC, description ASC, id ASC
                """, new DiscrepancyRowMapper(), batchNo);
    }

    /** 查询批次随附单据（按单据类型、编号排序）。 */
    public List<DocumentItem> findDocuments(String batchNo) {
        return jdbcTemplate.query("""
                SELECT doc_type, doc_ref FROM submission_document
                 WHERE batch_no = ? ORDER BY doc_type ASC, doc_ref ASC, id ASC
                """,
                (rs, rowNum) -> new DocumentItem(rs.getString("doc_type"), rs.getString("doc_ref")),
                batchNo);
    }

    /** 批次置为已拒付（乐观条件：仍处审单中）。 */
    public int markRefused(String batchNo, Instant refusedAt) {
        return jdbcTemplate.update("""
                UPDATE doc_submission SET status = 'REFUSED', refused_at = ?
                 WHERE batch_no = ? AND status = 'UNDER_REVIEW'
                """, Timestamp.from(refusedAt), batchNo);
    }

    /** 批次置为视为接受或放弃后接受（乐观条件：仍处审单中或已拒付）。 */
    public int markAccepted(String batchNo, String status, Instant acceptedAt) {
        return jdbcTemplate.update("""
                UPDATE doc_submission SET status = ?, accepted_at = ?
                 WHERE batch_no = ? AND status IN ('UNDER_REVIEW', 'REFUSED')
                """, status, Timestamp.from(acceptedAt), batchNo);
    }

    /** 批次置为已付款（乐观条件：已接受且未付款）。 */
    public int markPaid(String batchNo, Instant paidAt) {
        return jdbcTemplate.update("""
                UPDATE doc_submission SET status = 'PAID', paid_at = ?
                 WHERE batch_no = ? AND status IN ('ACCEPTED', 'DEEMED_ACCEPTED')
                """, Timestamp.from(paidAt), batchNo);
    }

    /** 将批次全部不符点标记为放弃。 */
    public int markAllDiscrepanciesWaived(String batchNo) {
        return jdbcTemplate.update("UPDATE discrepancy SET waived = TRUE WHERE batch_no = ?", batchNo);
    }

    private static final class SubmissionRowMapper implements RowMapper<SubmissionRow> {

        @Override
        public SubmissionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SubmissionRow(
                    rs.getLong("id"),
                    rs.getString("batch_no"),
                    rs.getString("lc_no"),
                    rs.getString("beneficiary"),
                    rs.getBigDecimal("draw_amount"),
                    rs.getString("status"),
                    rs.getTimestamp("presented_at").toInstant(),
                    rs.getTimestamp("deadline_at").toInstant(),
                    toInstant(rs.getTimestamp("refused_at")),
                    toInstant(rs.getTimestamp("accepted_at")),
                    toInstant(rs.getTimestamp("paid_at")),
                    rs.getTimestamp("created_at").toInstant());
        }

        private static Instant toInstant(Timestamp ts) {
            return ts == null ? null : ts.toInstant();
        }
    }

    private static final class DiscrepancyRowMapper implements RowMapper<DiscrepancyRow> {

        @Override
        public DiscrepancyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DiscrepancyRow(
                    rs.getLong("id"),
                    rs.getString("batch_no"),
                    rs.getString("doc_type"),
                    rs.getString("description"),
                    rs.getBoolean("waived"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
