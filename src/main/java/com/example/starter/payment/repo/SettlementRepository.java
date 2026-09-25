package com.example.starter.payment.repo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.payment.dto.SettlementItem;

/**
 * 清算单仓储：封账时按商户生成，唯一键 (batch_date, merchant_no)。
 */
@Repository
public class SettlementRepository {

    private static final RowMapper<SettlementItem> ITEM_MAPPER = (rs, n) -> new SettlementItem(
            rs.getString("merchant_no"),
            rs.getInt("txn_count"),
            rs.getBigDecimal("total_amount"),
            rs.getBigDecimal("total_fee"),
            rs.getBigDecimal("settled_amount"));

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String batchDate, SettlementItem item) {
        jdbc.update("INSERT INTO settlement_order (batch_date, merchant_no, txn_count, total_amount, "
                + "total_fee, settled_amount) VALUES (?,?,?,?,?,?)",
                batchDate, item.merchantNo(), item.txnCount(), item.totalAmount(),
                item.totalFee(), item.settledAmount());
    }

    public List<SettlementItem> findByBatch(String batchDate) {
        return jdbc.query("SELECT merchant_no, txn_count, total_amount, total_fee, settled_amount "
                + "FROM settlement_order WHERE batch_date = ? ORDER BY merchant_no",
                ITEM_MAPPER, batchDate);
    }

    public boolean existsByBatch(String batchDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM settlement_order WHERE batch_date = ?", Integer.class, batchDate);
        return count != null && count > 0;
    }
}
