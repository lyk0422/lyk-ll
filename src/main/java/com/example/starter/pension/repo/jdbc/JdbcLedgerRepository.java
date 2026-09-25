package com.example.starter.pension.repo.jdbc;

import com.example.starter.pension.repo.LedgerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 账务流水 JDBC 实现。成员出款与企业划回均通过唯一索引防止重复入账。
 */
@Repository
public class JdbcLedgerRepository implements LedgerRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public JdbcLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Override
    public int insertMemberPayout(long planId, long installmentId, long amountCents) {
        return jdbcClient.sql("""
                        INSERT INTO account_ledger
                            (plan_id, installment_id, account, movement_type, amount_cents)
                        VALUES
                            (:planId, :installmentId, 'MEMBER', 'MEMBER_PAYOUT', :amountCents)""")
                .param("planId", planId)
                .param("installmentId", installmentId)
                .param("amountCents", amountCents)
                .update();
    }

    @Override
    public int insertEnterpriseReturn(long planId, long amountCents) {
        if (amountCents <= 0) {
            return 0;
        }
        return jdbcClient.sql("""
                        INSERT INTO account_ledger
                            (plan_id, installment_id, account, movement_type, amount_cents)
                        VALUES
                            (:planId, NULL, 'ENTERPRISE', 'ENTERPRISE_RETURN', :amountCents)""")
                .param("planId", planId)
                .param("amountCents", amountCents)
                .update();
    }

    @Override
    public long sumMemberPayouts(long planId) {
        Long sum = jdbcClient.sql("""
                        SELECT COALESCE(SUM(amount_cents), 0)
                          FROM account_ledger
                         WHERE plan_id = :planId AND movement_type = 'MEMBER_PAYOUT'""")
                .param("planId", planId)
                .query(Long.class)
                .single();
        return sum == null ? 0L : sum;
    }

    @Override
    public long sumEnterpriseReturns(long planId) {
        Long sum = jdbcClient.sql("""
                        SELECT COALESCE(SUM(amount_cents), 0)
                          FROM account_ledger
                         WHERE plan_id = :planId AND movement_type = 'ENTERPRISE_RETURN'""")
                .param("planId", planId)
                .query(Long.class)
                .single();
        return sum == null ? 0L : sum;
    }
}
