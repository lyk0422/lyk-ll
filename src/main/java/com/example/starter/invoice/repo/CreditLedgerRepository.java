package com.example.starter.invoice.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 授信额度台账数据访问：每次额度变动追加一条台账，可复算额度占用。
 */
@Repository
public class CreditLedgerRepository {

    private final JdbcClient jdbc;

    public CreditLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条额度台账。
     */
    public void append(String sellerTaxNo, String bizType, String bizNo,
                       BigDecimal deltaBlueUnarchived, BigDecimal deltaRedConfirmed,
                       BigDecimal blueUnarchivedAfter, BigDecimal redConfirmedAfter,
                       LocalDateTime now) {
        jdbc.sql("INSERT INTO credit_ledger (seller_tax_no, biz_type, biz_no, delta_blue_unarchived,"
                        + " delta_red_confirmed, blue_unarchived_after, red_confirmed_after, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(sellerTaxNo, bizType, bizNo, deltaBlueUnarchived, deltaRedConfirmed,
                        blueUnarchivedAfter, redConfirmedAfter, now)
                .update();
    }

    /**
     * 按销方税号查询台账，按主键升序，结果稳定可复算。
     */
    public List<CreditLedgerRow> findBySellerTaxNo(String sellerTaxNo) {
        return jdbc.sql("SELECT * FROM credit_ledger WHERE seller_tax_no = ? ORDER BY id")
                .param(sellerTaxNo)
                .query(CreditLedgerRow.class)
                .list();
    }

    /**
     * 额度台账行记录。
     */
    public record CreditLedgerRow(
            long id,
            String sellerTaxNo,
            String bizType,
            String bizNo,
            BigDecimal deltaBlueUnarchived,
            BigDecimal deltaRedConfirmed,
            BigDecimal blueUnarchivedAfter,
            BigDecimal redConfirmedAfter,
            LocalDateTime createdAt) {
    }
}
