package com.example.starter.pension.repo.jdbc;

import com.example.starter.pension.repo.InheritanceLedgerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 继承台账 JDBC 实现。继承出款通过 (claim_id, movement_type) 唯一索引防止重复入账。
 */
@Repository
public class JdbcInheritanceLedgerRepository implements InheritanceLedgerRepository {

    private final JdbcClient jdbcClient;

    public JdbcInheritanceLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Override
    public int insertInheritancePayout(long claimId, long amountCents) {
        return jdbcClient.sql("""
                        INSERT INTO inheritance_ledger (claim_id, movement_type, amount_cents)
                        VALUES (:claimId, 'INHERITANCE_PAYOUT', :amountCents)""")
                .param("claimId", claimId)
                .param("amountCents", amountCents)
                .update();
    }

    @Override
    public long sumInheritancePayouts(long claimId) {
        Long sum = jdbcClient.sql("""
                        SELECT COALESCE(SUM(amount_cents), 0)
                          FROM inheritance_ledger
                         WHERE claim_id = :claimId AND movement_type = 'INHERITANCE_PAYOUT'""")
                .param("claimId", claimId)
                .query(Long.class)
                .single();
        return sum == null ? 0L : sum;
    }
}
