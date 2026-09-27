package com.example.rebate.repo;

import com.example.rebate.domain.ReversalReceipt;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 追缴回执明细数据访问：按 冲回单+流水号 唯一，允许多笔部分到账。
 */
@Repository
public class ReversalReceiptRepository {

    private static final RowMapper<ReversalReceipt> MAPPER = new ReceiptRowMapper();

    private final JdbcTemplate jdbc;

    public ReversalReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条回执明细，回填自增主键。
     */
    public long insert(long reversalId, String receiptSerial, long amountCents,
                       Instant occurredAt, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_reversal_receipt
                        (reversal_id, receipt_serial, amount_cents, occurred_at, registered_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, reversalId);
            ps.setString(2, receiptSerial);
            ps.setLong(3, amountCents);
            ps.setTimestamp(4, Timestamp.from(occurredAt));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<ReversalReceipt> findByReversalId(long reversalId) {
        return jdbc.query(
                "SELECT id, reversal_id, receipt_serial, amount_cents, occurred_at, registered_at "
                        + "FROM t_reversal_receipt WHERE reversal_id = ? ORDER BY id",
                MAPPER, reversalId);
    }

    /**
     * 按 冲回单＋流水号 查询回执（同流水重复登记判定用）。
     */
    public java.util.Optional<ReversalReceipt> findBySerial(long reversalId, String receiptSerial) {
        List<ReversalReceipt> list = jdbc.query(
                "SELECT id, reversal_id, receipt_serial, amount_cents, occurred_at, registered_at "
                        + "FROM t_reversal_receipt WHERE reversal_id = ? AND receipt_serial = ?",
                MAPPER, reversalId, receiptSerial);
        return list.stream().findFirst();
    }

    private static final class ReceiptRowMapper implements RowMapper<ReversalReceipt> {
        @Override
        public ReversalReceipt mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReversalReceipt(
                    rs.getLong("id"),
                    rs.getLong("reversal_id"),
                    rs.getString("receipt_serial"),
                    rs.getLong("amount_cents"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getTimestamp("registered_at").toInstant());
        }
    }
}
