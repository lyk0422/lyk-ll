package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.domain.BillItem;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 账单明细仓储。
 */
@Repository
public class BillItemRepository {

    private static final RowMapper<BillItem> MAPPER = (rs, n) -> {
        BillItem item = new BillItem();
        item.setId(rs.getLong("id"));
        item.setBillId(rs.getLong("bill_id"));
        item.setLineNo(rs.getInt("line_no"));
        item.setItemType(com.example.starter.reinsurance.domain.BillItemType.valueOf(rs.getString("item_type")));
        item.setRefNo(rs.getString("ref_no"));
        item.setRefBillNo(rs.getString("ref_bill_no"));
        if (rs.wasNull()) {
            item.setRefBillNo(null);
        }
        item.setGrossAmount(rs.getBigDecimal("gross_amount"));
        item.setCessionRate(rs.getBigDecimal("cession_rate"));
        item.setAmount(rs.getBigDecimal("amount"));
        item.setRemark(rs.getString("remark"));
        return item;
    };

    private final JdbcTemplate jdbcTemplate;

    public BillItemRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(BillItem item) {
        jdbcTemplate.update(
                "INSERT INTO bill_item (bill_id, line_no, item_type, ref_no, ref_bill_no, gross_amount, "
                        + "cession_rate, amount, remark) VALUES (?,?,?,?,?,?,?,?,?)",
                item.getBillId(),
                item.getLineNo(),
                item.getItemType().name(),
                item.getRefNo(),
                item.getRefBillNo(),
                item.getGrossAmount(),
                item.getCessionRate(),
                item.getAmount(),
                item.getRemark());
    }

    public List<BillItem> findByBillId(Long billId) {
        return jdbcTemplate.query(
                "SELECT * FROM bill_item WHERE bill_id = ? ORDER BY line_no", MAPPER, billId);
    }
}
