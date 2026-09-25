package com.example.starter.reinsurance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * H2 数据库约束直测：余额非负不仅由服务层条件更新保证，也由数据库 CHECK 强制；
 * 季度账单唯一约束由唯一索引强制（不含 mock/Map，全部走真实 H2）。
 */
class DatabaseConstraintTest extends AbstractIntegrationTest {

    @Test
    void databaseRejectsNegativeAccountBalanceViaCheckConstraint() {
        treaty("T-D1", "0.30", null);
        policy("P-D1", "T-D1", "1000.00", LocalDate.parse("2026-08-01"));
        claim("CL-D1", "P-D1", "300.00", LocalDate.parse("2026-08-02"));
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT id FROM treaty WHERE contract_no = 'T-D1'", Long.class);
        assertEquals(new BigDecimal("90.00"), accountBalance(contractId));

        // 绕过服务层直接写负余额，数据库 CHECK 必须拒绝
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "UPDATE reinsurance_account SET balance = -0.01 WHERE contract_id = ?",
                        contractId));
        assertTrue(ex.getMessage() == null
                        || ex.getMessage().toUpperCase().contains("CHECK")
                        || ex.getMessage().contains("约束")
                        || ex.getMostSpecificCause().getMessage().toUpperCase().contains("CHECK"),
                "应为 CHECK 约束冲突，实际: " + ex.getMessage());

        // 余额保持原值
        assertEquals(new BigDecimal("90.00"), accountBalance(contractId));
    }

    @Test
    void databaseRejectsNegativeLedgerBalanceAfter() {
        treaty("T-D2", "0.30", null);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO account_ledger (contract_id, movement_type, gross_amount, cession_rate, "
                                + "amount, balance_after, event_date, created_at) "
                                + "VALUES (?, 'PROVISION', 100, 0.3, 30, -1, '2026-08-01', CURRENT_TIMESTAMP)",
                        jdbcTemplate.queryForObject(
                                "SELECT id FROM treaty WHERE contract_no = 'T-D2'", Long.class)));
    }

    @Test
    void quarterlyBillUniqueKeyIsEnforcedByDatabase() {
        treaty("T-D3", "0.30", null);
        billService.generate(new com.example.starter.reinsurance.api.GenerateBillRequest(
                "req-d3", "T-D3", "2026-Q3"));
        // 直接插入同合约同期同类型账单，唯一索引必须拒绝
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO bill (bill_no, contract_id, period, bill_type, status, cedant_confirmed, "
                        + "reinsurer_confirmed, premium_total, recovery_total, net_total, created_at) "
                        + "VALUES ('RB-FORCE', ?, '2026-Q3', 'QUARTERLY', 'PENDING', TRUE, FALSE, 0, 0, 0, "
                        + "CURRENT_TIMESTAMP)",
                jdbcTemplate.queryForObject(
                        "SELECT id FROM treaty WHERE contract_no = 'T-D3'", Long.class)));
    }
}
