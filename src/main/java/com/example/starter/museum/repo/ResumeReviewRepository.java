package com.example.starter.museum.repo;

import com.example.starter.museum.domain.po.ResumeReviewPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.Optional;

/** 挂起恢复复核仓储：(contract_id, alert_id) 唯一，双方复核齐备才完成。 */
@Repository
public class ResumeReviewRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ResumeReviewRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ResumeReviewPo getOrCreate(Long contractId, Long alertId) {
        Optional<ResumeReviewPo> existing = find(contractId, alertId);
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.update("""
                INSERT INTO resume_review
                  (contract_id, alert_id, lender_confirmed, borrower_confirmed,
                   completed_at, created_at, updated_at)
                VALUES (?, ?, FALSE, FALSE, NULL, ?, ?)
                """, contractId, alertId, Sql.ts(clock.instant()), Sql.ts(clock.instant()));
        return find(contractId, alertId).orElseThrow();
    }

    public Optional<ResumeReviewPo> find(Long contractId, Long alertId) {
        return jdbc.query(
                        "SELECT * FROM resume_review WHERE contract_id = ? AND alert_id = ?",
                        RowMappers.REVIEW, contractId, alertId)
                .stream().findFirst();
    }

    public int updateConfirmation(Long id, boolean lender, boolean borrower) {
        return jdbc.update("""
                UPDATE resume_review
                SET lender_confirmed = ?, borrower_confirmed = ?, updated_at = ?
                WHERE id = ?
                """, lender, borrower, Sql.ts(clock.instant()), id);
    }

    public int markCompleted(Long id) {
        return jdbc.update(
                "UPDATE resume_review SET completed_at = ?, updated_at = ? WHERE id = ? AND completed_at IS NULL",
                Sql.ts(clock.instant()), Sql.ts(clock.instant()), id);
    }
}
