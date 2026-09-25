package com.example.rebate.repo;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.domain.DocType;
import com.example.rebate.domain.LinkStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 申报批次、批次-单证关联及有效批次占用登记的数据访问。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<DeclarationBatch> MAPPER = new BatchRowMapper();

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建批次。
     */
    public long insert(String batchNo, BatchStatus status, java.time.LocalDate declareDate,
                       java.math.BigDecimal totalTax, Instant now) {
        jdbc.update("""
                INSERT INTO t_declaration_batch
                    (batch_no, status, declare_date, total_declared_tax, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                batchNo, status.name(), java.sql.Date.valueOf(declareDate), totalTax,
                Timestamp.from(now), Timestamp.from(now));
        return jdbc.queryForObject(
                "SELECT id FROM t_declaration_batch WHERE batch_no = ?", Long.class, batchNo);
    }

    /**
     * 更新状态与税额。
     */
    public void updateStatus(long batchId, BatchStatus status,
                             java.math.BigDecimal totalTax, Instant now) {
        jdbc.update(
                "UPDATE t_declaration_batch SET status = ?, total_declared_tax = ?, updated_at = ? "
                        + "WHERE id = ?",
                status.name(), totalTax, Timestamp.from(now), batchId);
    }

    /**
     * 正式申报冻结。
     */
    public void freeze(long batchId, Instant now) {
        jdbc.update(
                "UPDATE t_declaration_batch SET status = ?, frozen_at = ?, updated_at = ? WHERE id = ?",
                BatchStatus.DECLARED.name(), Timestamp.from(now), Timestamp.from(now), batchId);
    }

    /**
     * 撤批整体作废。
     */
    public void markVoid(long batchId, Instant now) {
        jdbc.update(
                "UPDATE t_declaration_batch SET status = ?, voided_at = ?, updated_at = ? WHERE id = ?",
                BatchStatus.VOID.name(), Timestamp.from(now), Timestamp.from(now), batchId);
    }

    /**
     * 结案（终态）。
     */
    public void markClosed(long batchId, Instant now) {
        jdbc.update(
                "UPDATE t_declaration_batch SET status = ?, closed_at = ?, updated_at = ? WHERE id = ?",
                BatchStatus.CLOSED.name(), Timestamp.from(now), Timestamp.from(now), batchId);
    }

    public Optional<DeclarationBatch> findById(long id) {
        List<DeclarationBatch> list = jdbc.query(BASE_SELECT + " WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<DeclarationBatch> findByNo(String batchNo) {
        List<DeclarationBatch> list = jdbc.query(BASE_SELECT + " WHERE batch_no = ?", MAPPER, batchNo);
        return list.stream().findFirst();
    }

    private static final String BASE_SELECT =
            "SELECT id, batch_no, status, declare_date, total_declared_tax, frozen_at, voided_at, "
                    + "closed_at, created_at, updated_at FROM t_declaration_batch";

    /**
     * 关联单证明细行。
     */
    public void linkDocument(long batchId, DocType docType, String declarationNo,
                             String invoiceNo, String productCode, Instant now) {
        jdbc.update("""
                INSERT INTO t_batch_document
                    (batch_id, doc_type, declaration_no, invoice_no, product_code, link_status, linked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                batchId, docType.name(), declarationNo, invoiceNo, productCode,
                LinkStatus.LINKED.name(), Timestamp.from(now));
    }

    /**
     * 撤批：把批次下全部 LINKED 单证明细置为 RELEASED。整体作废不得部分撤出。
     *
     * @return 释放的单证行数
     */
    public int releaseAllDocuments(long batchId, Instant now) {
        return jdbc.update(
                "UPDATE t_batch_document SET link_status = ?, released_at = ? "
                        + "WHERE batch_id = ? AND link_status = ?",
                LinkStatus.RELEASED.name(), Timestamp.from(now), batchId, LinkStatus.LINKED.name());
    }

    /**
     * 登记报关单被某有效批次占用。主键冲突表示该报关单已参与其它有效批次。
     *
     * @return 是否登记成功
     */
    public boolean registerDeclaration(String declarationNo, long batchId) {
        try {
            jdbc.update("INSERT INTO t_declaration_registry (declaration_no, batch_id) VALUES (?, ?)",
                    declarationNo, batchId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 登记发票被某有效批次占用。
     */
    public boolean registerInvoice(String invoiceNo, long batchId) {
        try {
            jdbc.update("INSERT INTO t_invoice_registry (invoice_no, batch_id) VALUES (?, ?)",
                    invoiceNo, batchId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 撤批：释放该批次占用的全部报关单与发票登记。
     */
    public void clearRegistrations(long batchId) {
        jdbc.update("DELETE FROM t_declaration_registry WHERE batch_id = ?", batchId);
        jdbc.update("DELETE FROM t_invoice_registry WHERE batch_id = ?", batchId);
    }

    /**
     * 查询报关单当前是否被有效批次占用及批次号。
     */
    public Optional<Long> occupiedBatchOfDeclaration(String declarationNo) {
        List<Long> list = jdbc.queryForList(
                "SELECT batch_id FROM t_declaration_registry WHERE declaration_no = ?",
                Long.class, declarationNo);
        return list.stream().findFirst();
    }

    private static final class BatchRowMapper implements RowMapper<DeclarationBatch> {
        @Override
        public DeclarationBatch mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DeclarationBatch(
                    rs.getLong("id"),
                    rs.getString("batch_no"),
                    BatchStatus.valueOf(rs.getString("status")),
                    rs.getDate("declare_date").toLocalDate(),
                    rs.getBigDecimal("total_declared_tax").setScale(4,
                            java.math.RoundingMode.HALF_UP),
                    toInstant(rs.getTimestamp("frozen_at")),
                    toInstant(rs.getTimestamp("voided_at")),
                    toInstant(rs.getTimestamp("closed_at")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }

        private static Instant toInstant(Timestamp ts) {
            return ts == null ? null : ts.toInstant();
        }
    }
}
