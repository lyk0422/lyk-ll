package com.example.starter.repository;

import com.example.starter.entity.SettlementOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 商户清算单表数据访问。 */
@Repository
public class SettlementOrderRepository {

    private static final RowMapper<SettlementOrder> MAPPER = new OrderRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public SettlementOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(SettlementOrder order) {
        jdbcTemplate.update(
                "INSERT INTO settlement_order (batch_date, merchant_no, txn_count, total_amount, total_fee, "
                        + "settled_amount, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                order.getBatchDate(), order.getMerchantNo(), order.getTxnCount(), order.getTotalAmount(),
                order.getTotalFee(), order.getSettledAmount(), Timestamp.from(order.getCreatedAt()));
    }

    public List<SettlementOrder> findByBatch(LocalDate batchDate) {
        return jdbcTemplate.query(
                "SELECT id, batch_date, merchant_no, txn_count, total_amount, total_fee, settled_amount, created_at "
                        + "FROM settlement_order WHERE batch_date = ? ORDER BY merchant_no",
                MAPPER, batchDate);
    }

    private static class OrderRowMapper implements RowMapper<SettlementOrder> {
        @Override
        public SettlementOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            SettlementOrder o = new SettlementOrder();
            o.setId(rs.getLong("id"));
            o.setBatchDate(rs.getDate("batch_date").toLocalDate());
            o.setMerchantNo(rs.getString("merchant_no"));
            o.setTxnCount(rs.getInt("txn_count"));
            o.setTotalAmount(rs.getLong("total_amount"));
            o.setTotalFee(rs.getLong("total_fee"));
            o.setSettledAmount(rs.getLong("settled_amount"));
            o.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            return o;
        }
    }
}
