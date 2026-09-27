package com.example.rebate.repo;

import com.example.rebate.domain.ClawbackReceipt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 追缴到账回执数据访问：流水号全局唯一；按冲回单列出全部到账。
 */
@Repository
public class ClawbackReceiptRepository {

    private static final RowMapper<ClawbackReceipt> MAPPER = new ReceiptRowMapper();

    private final JdbcTemplate jdbc;

    public ClawbackReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条回执；流水号唯一冲突由调用方按“同流水异金额拒绝/同金额去重”处理。
     */
    public void insert(long reversalId, String receiptSerial, long amountCents, Instant now) {
        jdbc.update("""
                INSERT INTO t_clawback_receipt
                    (reversal_id, receipt_serial, amount_cents, received_at)
                VALUES (?, ?, ?, ?)
                """,
                reversalId, receiptSerial, amountCents, Timestamp.from(now));
    }

    public Optional<ClawbackReceipt> findBySerial(String receiptSerial) {
        return jdbc.query(
                "SELECT id, reversal_id, receipt_serial, amount_cents, received_at "
                        + "FROM t_clawback_receipt WHERE receipt_serial = ?",
                MAPPER, receiptSerial).stream().findFirst();
    }

    public List<ClawbackReceipt> findByReversalId(long reversalId) {
        return jdbc.query(
                "SELECT id, reversal_id, receipt_serial, amount_cents, received_at "
                        + "FROM t_clawback_receipt WHERE reversal_id = ? ORDER BY id",
                MAPPER, reversalId);
    }

    private static final class ReceiptRowMapper implements RowMapper<ClawbackReceipt> {
        @Override
        public ClawbackReceipt mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClawbackReceipt(
                    rs.getLong("id"),
                    rs.getLong("reversal_id"),
                    rs.getString("receipt_serial"),
                    rs.getLong("amount_cents"),
                    rs.getTimestamp("received_at").toInstant());
        }
    }
}
