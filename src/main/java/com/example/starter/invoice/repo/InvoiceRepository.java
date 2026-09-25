package com.example.starter.invoice.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.invoice.domain.InvoiceDirection;
import com.example.starter.invoice.domain.InvoiceStatus;

/**
 * 发票主表与行项目数据访问。
 */
@Repository
public class InvoiceRepository {

    private final JdbcClient jdbc;

    public InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入发票主记录，返回生成的主键。
     */
    public long insert(String requestId, String fingerprint, String invoiceNo, InvoiceDirection direction,
                       InvoiceStatus status, String sellerTaxNo, String buyerTaxNo, boolean buyerBooked,
                       BigDecimal totalAmount, Long originalInvoiceId, Long infoTableId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO invoice (request_id, fingerprint, invoice_no, direction, status, seller_tax_no,"
                        + " buyer_tax_no, buyer_booked, total_amount, red_flushed_amount, original_invoice_id,"
                        + " info_table_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)")
                .params(requestId, fingerprint, invoiceNo, direction.name(), status.name(), sellerTaxNo,
                        buyerTaxNo, buyerBooked, totalAmount, originalInvoiceId, infoTableId, now, now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 插入发票行项目。
     */
    public void insertLine(long invoiceId, int lineNo, String itemName, BigDecimal quantity,
                           BigDecimal unitPrice, BigDecimal amount) {
        jdbc.sql("INSERT INTO invoice_line (invoice_id, line_no, item_name, quantity, unit_price, amount,"
                        + " red_flushed_amount) VALUES (?, ?, ?, ?, ?, ?, 0)")
                .params(invoiceId, lineNo, itemName, quantity, unitPrice, amount)
                .update();
    }

    /**
     * 按幂等请求号查询发票。
     */
    public Optional<InvoiceRow> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM invoice WHERE request_id = ?")
                .param(requestId)
                .query(InvoiceRow.class)
                .optional();
    }

    /**
     * 按发票号码查询发票（不加锁）。
     */
    public Optional<InvoiceRow> findByInvoiceNo(String invoiceNo) {
        return jdbc.sql("SELECT * FROM invoice WHERE invoice_no = ?")
                .param(invoiceNo)
                .query(InvoiceRow.class)
                .optional();
    }

    /**
     * 按发票号码查询并锁定发票行，红冲累计校验时串行化。
     */
    public Optional<InvoiceRow> lockByInvoiceNo(String invoiceNo) {
        return jdbc.sql("SELECT * FROM invoice WHERE invoice_no = ? FOR UPDATE")
                .param(invoiceNo)
                .query(InvoiceRow.class)
                .optional();
    }

    /**
     * 按主键查询发票。
     */
    public Optional<InvoiceRow> findById(long id) {
        return jdbc.sql("SELECT * FROM invoice WHERE id = ?")
                .param(id)
                .query(InvoiceRow.class)
                .optional();
    }

    /**
     * 按主键查询并锁定发票行，红票确认时串行校验累计红冲金额。
     */
    public Optional<InvoiceRow> lockById(long id) {
        return jdbc.sql("SELECT * FROM invoice WHERE id = ? FOR UPDATE")
                .param(id)
                .query(InvoiceRow.class)
                .optional();
    }

    /**
     * 查询发票行项目，按行号升序。
     */
    public List<InvoiceLineRow> findLines(long invoiceId) {
        return jdbc.sql("SELECT * FROM invoice_line WHERE invoice_id = ? ORDER BY line_no")
                .param(invoiceId)
                .query(InvoiceLineRow.class)
                .list();
    }

    /**
     * 更新发票状态与累计红冲金额。
     */
    public void updateStatusAndRedFlushed(long id, InvoiceStatus status, BigDecimal redFlushedAmount,
                                          LocalDateTime now) {
        jdbc.sql("UPDATE invoice SET status = ?, red_flushed_amount = ?, updated_at = ? WHERE id = ?")
                .params(status.name(), redFlushedAmount, now, id)
                .update();
    }

    /**
     * 仅更新发票状态（交付、归档）。
     */
    public void updateStatus(long id, InvoiceStatus status, LocalDateTime now) {
        jdbc.sql("UPDATE invoice SET status = ?, updated_at = ? WHERE id = ?")
                .params(status.name(), now, id)
                .update();
    }

    /**
     * 更新行项目累计红冲金额。
     */
    public void updateLineRedFlushed(long lineId, BigDecimal redFlushedAmount) {
        jdbc.sql("UPDATE invoice_line SET red_flushed_amount = ? WHERE id = ?")
                .params(redFlushedAmount, lineId)
                .update();
    }

    /**
     * 查询某蓝票的全部红票，按主键升序，形成红冲链。
     */
    public List<InvoiceRow> findRedInvoicesByOriginalId(long originalInvoiceId) {
        return jdbc.sql("SELECT * FROM invoice WHERE original_invoice_id = ? ORDER BY id")
                .param(originalInvoiceId)
                .query(InvoiceRow.class)
                .list();
    }
}
