package com.example.starter.weight.repo;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 记分与罚款台账仓储：处罚入账、复检撤销退还，并支持余额复算。
 */
@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记分明细入账。
     *
     * @param delta 正数为记分，负数为退还
     */
    public void appendPoints(String plateNo, long caseId, long decisionId, int delta,
                             String reason, Instant createdAt) {
        jdbc.update("""
                INSERT INTO points_ledger (plate_no, case_id, decision_id, delta, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, plateNo, caseId, decisionId, delta, reason, Timestamp.from(createdAt));
    }

    /**
     * 罚款明细入账。
     *
     * @param amount 正数为处罚，负数为退还（元）
     */
    public void appendFine(String plateNo, long caseId, long decisionId,
                           java.math.BigDecimal amount, String reason, Instant createdAt) {
        jdbc.update("""
                INSERT INTO fine_ledger (plate_no, case_id, decision_id, amount, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, plateNo, caseId, decisionId, amount, reason, Timestamp.from(createdAt));
    }

    /**
     * 统计某案件记分明细净额（复算用）。
     */
    public int sumPointsByCase(long caseId) {
        Integer sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(delta), 0) FROM points_ledger WHERE case_id = ?", Integer.class, caseId);
        return sum == null ? 0 : sum;
    }

    /**
     * 统计某案件罚款明细净额（元，复算用）。
     */
    public java.math.BigDecimal sumFineByCase(long caseId) {
        java.math.BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM fine_ledger WHERE case_id = ?",
                java.math.BigDecimal.class, caseId);
        return sum == null ? java.math.BigDecimal.ZERO : sum;
    }

    /**
     * 统计某决定关联的记分明细条数（撤销原子性核查用）。
     */
    public int countPointsByDecision(long decisionId, String reason) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE decision_id = ? AND reason = ?",
                Integer.class, decisionId, reason);
        return cnt == null ? 0 : cnt;
    }
}
