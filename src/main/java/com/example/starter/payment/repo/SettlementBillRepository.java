package com.example.starter.payment.repo;

import com.example.starter.payment.SettlementBill;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;

/**
 * 清算单数据访问。
 */
@Repository
public class SettlementBillRepository {

    private static final RowMapper<SettlementBill> MAPPER = (rs, i) -> new SettlementBill(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("merchant_no"),
            rs.getInt("txn_count"), rs.getLong("total_amount"), rs.getLong("total_fee"),
            rs.getLong("net_amount"));

    private final JdbcTemplate jdbc;

    public SettlementBillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SettlementBill insert(long batchId, String merchantNo, int txnCount,
                                 long totalAmount, long totalFee) {
        long netAmount = totalAmount - totalFee;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement_bill (batch_id, merchant_no, txn_count, total_amount, total_fee,"
                            + " net_amount) VALUES (?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, batchId);
            ps.setString(2, merchantNo);
            ps.setInt(3, txnCount);
            ps.setLong(4, totalAmount);
            ps.setLong(5, totalFee);
            ps.setLong(6, netAmount);
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new SettlementBill(id, batchId, merchantNo, txnCount, totalAmount, totalFee, netAmount);
    }

    public List<SettlementBill> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM settlement_bill WHERE batch_id = ? ORDER BY merchant_no",
                MAPPER, batchId);
    }
}
