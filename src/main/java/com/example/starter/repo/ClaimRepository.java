package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.Models.Claim;
import com.example.starter.domain.Models.ClaimStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 赔案及赔案事件数据访问。
 */
@Repository
public class ClaimRepository {

    private static final RowMapper<Claim> MAPPER = (rs, n) -> map(rs);

    private final JdbcTemplate jdbc;

    public ClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Claim map(ResultSet rs) throws SQLException {
        return new Claim(rs.getLong("id"), rs.getString("claim_no"), rs.getLong("policy_id"),
                rs.getBigDecimal("estimate_amount"), rs.getBigDecimal("paid_amount"),
                ClaimStatus.valueOf(rs.getString("status")),
                rs.getObject("closed_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    public Claim insert(String claimNo, long policyId, BigDecimal estimateAmount, OffsetDateTime now) {
        jdbc.update("INSERT INTO claim (claim_no, policy_id, estimate_amount, status, created_at)"
                        + " VALUES (?, ?, ?, 'OPEN', ?)",
                claimNo, policyId, estimateAmount, now);
        return findByClaimNo(claimNo).orElseThrow();
    }

    public Optional<Claim> findByClaimNo(String claimNo) {
        return jdbc.query("SELECT * FROM claim WHERE claim_no = ?", MAPPER, claimNo)
                .stream().findFirst();
    }

    /** 更新未决赔案估损；返回受影响行数，0 表示赔案已结案。 */
    public int updateEstimateIfOpen(long claimId, BigDecimal newEstimate) {
        return jdbc.update("UPDATE claim SET estimate_amount = ? WHERE id = ? AND status = 'OPEN'",
                newEstimate, claimId);
    }

    /** 最终结案：仅 OPEN 状态可结案，返回受影响行数，0 表示已结案（终态不可重复）。 */
    public int closeIfOpen(long claimId, BigDecimal paidAmount, OffsetDateTime closedAt) {
        return jdbc.update("UPDATE claim SET status = 'CLOSED', paid_amount = ?, closed_at = ?"
                        + " WHERE id = ? AND status = 'OPEN'",
                paidAmount, closedAt, claimId);
    }

    /** 查询某保单在 [start, end)（UTC 日界）内结案的赔案，用于季度账单赔款摊回。 */
    public List<Claim> findClosedBetween(long policyId, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.query("SELECT * FROM claim WHERE policy_id = ? AND status = 'CLOSED'"
                        + " AND closed_at >= ? AND closed_at < ? ORDER BY id",
                MAPPER, policyId, start, end);
    }

    public void insertEvent(long claimId, String eventType, BigDecimal oldEstimate,
                            BigDecimal newEstimate, String note, OffsetDateTime now) {
        jdbc.update("INSERT INTO claim_event (claim_id, event_type, old_estimate, new_estimate, note, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                claimId, eventType, oldEstimate, newEstimate, note, now);
    }

    public long countEvents(long claimId, String eventType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claim_event WHERE claim_id = ? AND event_type = ?",
                Long.class, claimId, eventType);
        return count == null ? 0 : count;
    }
}
