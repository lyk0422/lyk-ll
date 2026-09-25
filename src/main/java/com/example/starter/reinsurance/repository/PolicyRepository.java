package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.domain.Policy;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 分出保单仓储。
 */
@Repository
public class PolicyRepository {

    private static final RowMapper<Policy> MAPPER = (rs, n) -> {
        Policy policy = new Policy();
        policy.setId(rs.getLong("id"));
        policy.setPolicyNo(rs.getString("policy_no"));
        policy.setContractId(rs.getLong("contract_id"));
        policy.setPremium(rs.getBigDecimal("premium"));
        Date effective = rs.getDate("effective_date");
        if (effective != null) {
            policy.setEffectiveDate(effective.toLocalDate());
        }
        long billId = rs.getLong("bill_id");
        policy.setBillId(rs.wasNull() ? null : billId);
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            policy.setCreatedAt(createdAt.toInstant());
        }
        return policy;
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PolicyRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public Policy insert(Policy policy) {
        java.time.Instant now = java.time.Instant.now(clock);
        policy.setCreatedAt(now);
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO policy (policy_no, contract_id, premium, effective_date, bill_id, created_at) "
                            + "VALUES (?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, policy.getPolicyNo());
            ps.setLong(2, policy.getContractId());
            ps.setBigDecimal(3, policy.getPremium());
            ps.setDate(4, Date.valueOf(policy.getEffectiveDate()));
            if (policy.getBillId() == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, policy.getBillId());
            }
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        policy.setId(keyHolder.getKeyAs(Long.class));
        return policy;
    }

    public Optional<Policy> findByPolicyNo(String policyNo) {
        List<Policy> list =
                jdbcTemplate.query("SELECT * FROM policy WHERE policy_no = ?", MAPPER, policyNo);
        return list.stream().findFirst();
    }

    public Policy getByPolicyNo(String policyNo) {
        return findByPolicyNo(policyNo)
                .orElseThrow(() -> new com.example.starter.reinsurance.common.ApiException(
                        com.example.starter.reinsurance.common.ErrorCode.NOT_FOUND,
                        "保单不存在: " + policyNo));
    }

    public Policy getById(Long id) {
        List<Policy> list = jdbcTemplate.query("SELECT * FROM policy WHERE id = ?", MAPPER, id);
        return list.stream()
                .findFirst()
                .orElseThrow(() -> new com.example.starter.reinsurance.common.ApiException(
                        com.example.starter.reinsurance.common.ErrorCode.NOT_FOUND, "保单不存在: id=" + id));
    }

    /**
     * 仅当保单尚未出账时将其挂到账单上（条件更新，防重复出账）。
     *
     * @return 受影响行数；0 表示保单已出账
     */
    public int attachToBillIfFree(Long policyId, Long billId) {
        return jdbcTemplate.update(
                "UPDATE policy SET bill_id = ? WHERE id = ? AND bill_id IS NULL",
                billId,
                policyId);
    }

    /**
     * 某合约在指定季度内（按生效日，UTC 日界）尚未出账的保单。
     */
    public java.util.List<Policy> findUnbilledByContractAndPeriod(
            Long contractId, java.time.LocalDate periodStart, java.time.LocalDate periodEnd) {
        return jdbcTemplate.query(
                "SELECT * FROM policy WHERE contract_id = ? AND bill_id IS NULL "
                        + "AND effective_date >= ? AND effective_date < ? ORDER BY effective_date, id",
                MAPPER,
                contractId,
                Date.valueOf(periodStart),
                Date.valueOf(periodEnd));
    }
}
