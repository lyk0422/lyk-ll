package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.domain.Claim;
import com.example.starter.reinsurance.domain.ClaimStatus;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 赔案仓储。
 */
@Repository
public class ClaimRepository {

    private static final RowMapper<Claim> MAPPER = (rs, n) -> {
        Claim claim = new Claim();
        claim.setId(rs.getLong("id"));
        claim.setClaimNo(rs.getString("claim_no"));
        claim.setPolicyId(rs.getLong("policy_id"));
        claim.setEstimatedAmount(rs.getBigDecimal("estimated_amount"));
        claim.setPaidAmount(rs.getBigDecimal("paid_amount"));
        if (rs.wasNull()) {
            claim.setPaidAmount(null);
        }
        claim.setStatus(ClaimStatus.valueOf(rs.getString("status")));
        Date lossDate = rs.getDate("loss_date");
        if (lossDate != null) {
            claim.setLossDate(lossDate.toLocalDate());
        }
        Timestamp closedAt = rs.getTimestamp("closed_at");
        if (closedAt != null) {
            claim.setClosedAt(closedAt.toInstant());
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            claim.setCreatedAt(createdAt.toInstant());
        }
        return claim;
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ClaimRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public Claim insert(Claim claim) {
        java.time.Instant now = java.time.Instant.now(clock);
        claim.setCreatedAt(now);
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO claim (claim_no, policy_id, estimated_amount, paid_amount, status, "
                            + "loss_date, closed_at, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, claim.getClaimNo());
            ps.setLong(2, claim.getPolicyId());
            ps.setBigDecimal(3, claim.getEstimatedAmount());
            if (claim.getPaidAmount() == null) {
                ps.setNull(4, java.sql.Types.DECIMAL);
            } else {
                ps.setBigDecimal(4, claim.getPaidAmount());
            }
            ps.setString(5, claim.getStatus().name());
            ps.setDate(6, Date.valueOf(claim.getLossDate()));
            if (claim.getClosedAt() == null) {
                ps.setNull(7, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(7, Timestamp.from(claim.getClosedAt()));
            }
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        claim.setId(keyHolder.getKeyAs(Long.class));
        return claim;
    }

    public Optional<Claim> findByClaimNo(String claimNo) {
        List<Claim> list = jdbcTemplate.query("SELECT * FROM claim WHERE claim_no = ?", MAPPER, claimNo);
        return list.stream().findFirst();
    }

    public Claim getById(Long id) {
        List<Claim> list = jdbcTemplate.query("SELECT * FROM claim WHERE id = ?", MAPPER, id);
        return list.stream()
                .findFirst()
                .orElseThrow(() -> new com.example.starter.reinsurance.common.ApiException(
                        com.example.starter.reinsurance.common.ErrorCode.NOT_FOUND, "赔案不存在: id=" + id));
    }

    public java.util.Map<Long, Claim> findByIds(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Claim> list = jdbcTemplate.query(
                "SELECT * FROM claim WHERE id IN (" + placeholders + ")", MAPPER, ids.toArray());
        java.util.Map<Long, Claim> map = new java.util.HashMap<>();
        for (Claim claim : list) {
            map.put(claim.getId(), claim);
        }
        return map;
    }

    /** 行级悲观锁读取赔案（必须在事务内调用），保证结案/调整并发互斥。 */
    public Claim getByClaimNoForUpdate(String claimNo) {
        List<Claim> list = jdbcTemplate.query(
                "SELECT * FROM claim WHERE claim_no = ? FOR UPDATE", MAPPER, claimNo);
        return list.stream()
                .findFirst()
                .orElseThrow(() -> new com.example.starter.reinsurance.common.ApiException(
                        com.example.starter.reinsurance.common.ErrorCode.NOT_FOUND,
                        "赔案不存在: " + claimNo));
    }

    /** 更新估损（仅未决赔案）。 */
    public int updateEstimate(Long claimId, java.math.BigDecimal estimate) {
        return jdbcTemplate.update(
                "UPDATE claim SET estimated_amount = ? WHERE id = ? AND status = 'OPEN'",
                estimate,
                claimId);
    }

    /** 条件结案：仅当赔案仍为 OPEN 时置为 FINAL_CLOSED，保证“最终结案”只发生一次。 */
    public int finalClose(Long claimId, java.math.BigDecimal paidAmount, java.time.Instant closedAt) {
        return jdbcTemplate.update(
                "UPDATE claim SET status = 'FINAL_CLOSED', paid_amount = ?, closed_at = ? "
                        + "WHERE id = ? AND status = 'OPEN'",
                paidAmount,
                Timestamp.from(closedAt),
                claimId);
    }
}
