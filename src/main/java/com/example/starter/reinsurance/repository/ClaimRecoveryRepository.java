package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.domain.ClaimRecovery;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 赔案摊回状态仓储（预提额/实摊额/限额自留额）。
 */
@Repository
public class ClaimRecoveryRepository {

    private static final RowMapper<ClaimRecovery> MAPPER = (rs, n) -> {
        ClaimRecovery recovery = new ClaimRecovery();
        recovery.setId(rs.getLong("id"));
        recovery.setClaimId(rs.getLong("claim_id"));
        recovery.setPolicyId(rs.getLong("policy_id"));
        recovery.setContractId(rs.getLong("contract_id"));
        recovery.setProvisionAmount(rs.getBigDecimal("provision_amount"));
        recovery.setSettledAmount(rs.getBigDecimal("settled_amount"));
        recovery.setCappedExcess(rs.getBigDecimal("capped_excess"));
        recovery.setRecoveryStatus(rs.getString("recovery_status"));
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) {
            recovery.setUpdatedAt(updatedAt.toInstant());
        }
        return recovery;
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ClaimRecoveryRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** 新建摊回记录（PROVISIONED，预提额为 0，随后更新）。 */
    public void create(Long claimId, Long policyId, Long contractId) {
        jdbcTemplate.update(
                "INSERT INTO claim_recovery (claim_id, policy_id, contract_id, provision_amount, "
                        + "settled_amount, capped_excess, recovery_status, updated_at) "
                        + "VALUES (?,?,?,0,0,0,'PROVISIONED',?)",
                claimId,
                policyId,
                contractId,
                Timestamp.from(java.time.Instant.now(clock)));
    }

    public ClaimRecovery getByClaimIdForUpdate(Long claimId) {
        List<ClaimRecovery> list = jdbcTemplate.query(
                "SELECT * FROM claim_recovery WHERE claim_id = ? FOR UPDATE", MAPPER, claimId);
        return list.stream().findFirst().orElse(null);
    }

    public ClaimRecovery getByClaimId(Long claimId) {
        List<ClaimRecovery> list = jdbcTemplate.query(
                "SELECT * FROM claim_recovery WHERE claim_id = ?", MAPPER, claimId);
        return list.stream().findFirst().orElse(null);
    }

    /** 更新未决预提额。 */
    public int updateProvision(Long claimId, java.math.BigDecimal provision) {
        return jdbcTemplate.update(
                "UPDATE claim_recovery SET provision_amount = ?, updated_at = ? "
                        + "WHERE claim_id = ? AND recovery_status = 'PROVISIONED'",
                provision,
                Timestamp.from(java.time.Instant.now(clock)),
                claimId);
    }

    /** 结案：预提归零，写实摊额、限额自留额，状态置 SETTLED（终态）。 */
    public int markSettled(
            Long claimId,
            java.math.BigDecimal settled,
            java.math.BigDecimal cappedExcess) {
        return jdbcTemplate.update(
                "UPDATE claim_recovery SET provision_amount = 0, settled_amount = ?, capped_excess = ?, "
                        + "recovery_status = 'SETTLED', updated_at = ? WHERE claim_id = ?",
                settled,
                cappedExcess,
                Timestamp.from(java.time.Instant.now(clock)),
                claimId);
    }

    /**
     * 保单下已占用的责任限额容量（元）= 未决预提 + 已决实摊之和。
     */
    public java.math.BigDecimal usedCapacityOfPolicy(Long policyId) {
        java.math.BigDecimal used = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(provision_amount + settled_amount), 0) FROM claim_recovery "
                        + "WHERE policy_id = ?",
                java.math.BigDecimal.class,
                policyId);
        return used == null ? java.math.BigDecimal.ZERO : used;
    }
}
