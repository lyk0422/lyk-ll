package com.example.starter.invoice.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 授信额度账户数据访问。额度变动均在业务事务内先锁定账户行再更新，保证并发串行校验。
 */
@Repository
public class CreditAccountRepository {

    private final JdbcClient jdbc;

    public CreditAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新建授信账户。
     */
    public void insert(String sellerTaxNo, BigDecimal totalLimit, LocalDateTime now) {
        jdbc.sql("INSERT INTO credit_account (seller_tax_no, total_limit, blue_unarchived_amount,"
                        + " red_confirmed_amount, version, created_at, updated_at)"
                        + " VALUES (?, ?, 0, 0, 0, ?, ?)")
                .params(sellerTaxNo, totalLimit, now, now)
                .update();
    }

    /**
     * 按销方税号查询账户（不加锁，用于查询场景）。
     */
    public Optional<CreditAccountRow> findBySellerTaxNo(String sellerTaxNo) {
        return jdbc.sql("SELECT * FROM credit_account WHERE seller_tax_no = ?")
                .param(sellerTaxNo)
                .query(CreditAccountRow.class)
                .optional();
    }

    /**
     * 按销方税号查询并锁定账户行（SELECT ... FOR UPDATE），并发开票/红冲确认时串行校验。
     */
    public Optional<CreditAccountRow> lockBySellerTaxNo(String sellerTaxNo) {
        return jdbc.sql("SELECT * FROM credit_account WHERE seller_tax_no = ? FOR UPDATE")
                .param(sellerTaxNo)
                .query(CreditAccountRow.class)
                .optional();
    }

    /**
     * 在账户行锁内更新额度占用，并递增版本号。
     */
    public void updateAmounts(long id, BigDecimal blueUnarchivedAmount, BigDecimal redConfirmedAmount,
                              LocalDateTime now) {
        jdbc.sql("UPDATE credit_account SET blue_unarchived_amount = ?, red_confirmed_amount = ?,"
                        + " version = version + 1, updated_at = ? WHERE id = ?")
                .params(blueUnarchivedAmount, redConfirmedAmount, now, id)
                .update();
    }
}
