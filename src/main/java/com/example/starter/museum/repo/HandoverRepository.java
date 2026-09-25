package com.example.starter.museum.repo;

import com.example.starter.museum.domain.HandoverType;
import com.example.starter.museum.domain.po.HandoverPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.Optional;

/**
 * 点交单仓储。(contract_id, type) 唯一：每类点交只有同一张单，
 * 双方在该单上分别确认；单方确认时 completed_at 为 NULL。
 */
@Repository
public class HandoverRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public HandoverRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public HandoverPo getOrCreate(Long contractId, HandoverType type) {
        Optional<HandoverPo> existing = find(contractId, type);
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.update("""
                INSERT INTO handover
                  (contract_id, type, lender_confirmed, borrower_confirmed,
                   completed_at, created_at, updated_at)
                VALUES (?, ?, FALSE, FALSE, NULL, ?, ?)
                """, contractId, type.name(), Sql.ts(clock.instant()), Sql.ts(clock.instant()));
        return find(contractId, type).orElseThrow();
    }

    public Optional<HandoverPo> find(Long contractId, HandoverType type) {
        return jdbc.query(
                        "SELECT * FROM handover WHERE contract_id = ? AND type = ?",
                        RowMappers.HANDOVER, contractId, type.name())
                .stream().findFirst();
    }

    public int updateConfirmation(Long id, boolean lender, boolean borrower) {
        return jdbc.update("""
                UPDATE handover
                SET lender_confirmed = ?, borrower_confirmed = ?, updated_at = ?
                WHERE id = ?
                """, lender, borrower, Sql.ts(clock.instant()), id);
    }

    public int markCompleted(Long id) {
        return jdbc.update(
                "UPDATE handover SET completed_at = ?, updated_at = ? WHERE id = ? AND completed_at IS NULL",
                Sql.ts(clock.instant()), Sql.ts(clock.instant()), id);
    }
}
