package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.domain.AccountLedger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 摊回账户流水仓储。
 */
@Repository
public class AccountLedgerRepository {

    private static final RowMapper<AccountLedger> MAPPER = (rs, n) -> {
        AccountLedger ledger = new AccountLedger();
        ledger.setId(rs.getLong("id"));
        ledger.setContractId(rs.getLong("contract_id"));
        long policyId = rs.getLong("policy_id");
        ledger.setPolicyId(rs.wasNull() ? null : policyId);
        long claimId = rs.getLong("claim_id");
        ledger.setClaimId(rs.wasNull() ? null : claimId);
        long billId = rs.getLong("bill_id");
        ledger.setBillId(rs.wasNull() ? null : billId);
        ledger.setMovementType(rs.getString("movement_type"));
        ledger.setGrossAmount(rs.getBigDecimal("gross_amount"));
        ledger.setCessionRate(rs.getBigDecimal("cession_rate"));
        ledger.setAmount(rs.getBigDecimal("amount"));
        ledger.setBalanceAfter(rs.getBigDecimal("balance_after"));
        Date eventDate = rs.getDate("event_date");
        if (eventDate != null) {
            ledger.setEventDate(eventDate.toLocalDate());
        }
        ledger.setRequestId(rs.getString("request_id"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            ledger.setCreatedAt(createdAt.toInstant());
        }
        return ledger;
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AccountLedgerRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public void insert(AccountLedger ledger) {
        java.time.Instant now = java.time.Instant.now(clock);
        ledger.setCreatedAt(now);
        jdbcTemplate.update(
                "INSERT INTO account_ledger (contract_id, policy_id, claim_id, bill_id, movement_type, "
                        + "gross_amount, cession_rate, amount, balance_after, event_date, request_id, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                ledger.getContractId(),
                ledger.getPolicyId(),
                ledger.getClaimId(),
                ledger.getBillId(),
                ledger.getMovementType(),
                ledger.getGrossAmount(),
                ledger.getCessionRate(),
                ledger.getAmount(),
                ledger.getBalanceAfter(),
                Date.valueOf(ledger.getEventDate()),
                ledger.getRequestId(),
                Timestamp.from(now));
    }

    /**
     * 某合约在指定季度内（UTC 日界）尚未出账的赔案摊回流水，按时间、ID 排序保证逐笔可复算。
     */
    public List<AccountLedger> findUnbilledByContractAndPeriod(
            Long contractId, String periodStart, String periodEnd) {
        return jdbcTemplate.query(
                "SELECT * FROM account_ledger WHERE contract_id = ? AND bill_id IS NULL "
                        + "AND event_date >= ? AND event_date < ? ORDER BY event_date, id",
                MAPPER,
                contractId,
                Date.valueOf(java.time.LocalDate.parse(periodStart)),
                Date.valueOf(java.time.LocalDate.parse(periodEnd)));
    }

    /** 出账后回填账单 ID（仅回填未出账流水，条件更新防重复出账）。 */
    public int attachToBill(List<Long> ledgerIds, Long billId) {
        if (ledgerIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ledgerIds.size(), "?"));
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(billId);
        args.addAll(ledgerIds);
        return jdbcTemplate.update(
                "UPDATE account_ledger SET bill_id = ? WHERE bill_id IS NULL AND id IN (" + placeholders + ")",
                args.toArray());
    }
}
