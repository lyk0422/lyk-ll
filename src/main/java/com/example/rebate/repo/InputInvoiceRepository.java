package com.example.rebate.repo;

import com.example.rebate.domain.InputInvoice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 进项发票数据访问。
 */
@Repository
public class InputInvoiceRepository {

    private static final RowMapper<InputInvoice> MAPPER = new InvoiceRowMapper();

    private final JdbcTemplate jdbc;

    public InputInvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 采集发票。按“报关单号＋发票号”唯一去重，重复采集返回 {@code false}。
     */
    @Transactional
    public boolean insertIfAbsent(InputInvoice inv) {
        try {
            jdbc.update("""
                    INSERT INTO t_input_invoice
                        (invoice_no, declaration_no, product_code, amount, collected_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    inv.invoiceNo(), inv.declarationNo(), inv.productCode(), inv.amount(),
                    java.sql.Timestamp.from(inv.collectedAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 按报关单号查询其全部进项发票。
     */
    public List<InputInvoice> findByDeclarationNo(String declarationNo) {
        return jdbc.query(
                "SELECT id, invoice_no, declaration_no, product_code, amount, collected_at "
                        + "FROM t_input_invoice WHERE declaration_no = ? ORDER BY id",
                MAPPER, declarationNo);
    }

    /**
     * 按“报关单号＋发票号”查询。
     */
    public Optional<InputInvoice> find(String declarationNo, String invoiceNo) {
        List<InputInvoice> list = jdbc.query(
                "SELECT id, invoice_no, declaration_no, product_code, amount, collected_at "
                        + "FROM t_input_invoice WHERE declaration_no = ? AND invoice_no = ?",
                MAPPER, declarationNo, invoiceNo);
        return list.stream().findFirst();
    }

    private static final class InvoiceRowMapper implements RowMapper<InputInvoice> {
        @Override
        public InputInvoice mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new InputInvoice(
                    rs.getLong("id"),
                    rs.getString("invoice_no"),
                    rs.getString("declaration_no"),
                    rs.getString("product_code"),
                    rs.getBigDecimal("amount"),
                    rs.getTimestamp("collected_at").toInstant());
        }
    }
}
