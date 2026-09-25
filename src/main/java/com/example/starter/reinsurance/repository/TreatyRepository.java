package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.domain.Treaty;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 再保合约仓储。
 */
@Repository
public class TreatyRepository {

    private static final RowMapper<Treaty> MAPPER = (rs, n) -> {
        Treaty treaty = new Treaty();
        treaty.setId(rs.getLong("id"));
        treaty.setContractNo(rs.getString("contract_no"));
        treaty.setCessionRate(rs.getBigDecimal("cession_rate"));
        treaty.setPerPolicyLimit(rs.getBigDecimal("per_policy_limit"));
        if (rs.wasNull()) {
            treaty.setPerPolicyLimit(null);
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            treaty.setCreatedAt(createdAt.toInstant());
        }
        return treaty;
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public TreatyRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public Treaty insert(Treaty treaty) {
        java.time.Instant now = java.time.Instant.now(clock);
        treaty.setCreatedAt(now);
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO treaty (contract_no, cession_rate, per_policy_limit, created_at) VALUES (?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, treaty.getContractNo());
            ps.setBigDecimal(2, treaty.getCessionRate());
            ps.setBigDecimal(3, treaty.getPerPolicyLimit());
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keyHolder);
        treaty.setId(keyHolder.getKeyAs(Long.class));
        return treaty;
    }

    public Optional<Treaty> findByContractNo(String contractNo) {
        List<Treaty> list =
                jdbcTemplate.query("SELECT * FROM treaty WHERE contract_no = ?", MAPPER, contractNo);
        return list.stream().findFirst();
    }

    public Treaty getByContractNo(String contractNo) {
        return findByContractNo(contractNo)
                .orElseThrow(() -> new com.example.starter.reinsurance.common.ApiException(
                        com.example.starter.reinsurance.common.ErrorCode.NOT_FOUND,
                        "合约不存在: " + contractNo));
    }

    public Treaty getById(Long id) {
        List<Treaty> list = jdbcTemplate.query("SELECT * FROM treaty WHERE id = ?", MAPPER, id);
        return list.stream()
                .findFirst()
                .orElseThrow(() -> new com.example.starter.reinsurance.common.ApiException(
                        com.example.starter.reinsurance.common.ErrorCode.NOT_FOUND, "合约不存在: id=" + id));
    }

    /** 行级悲观锁读取合约（必须在事务内），串行化同合约季度出账。 */
    public Treaty lockById(Long id) {
        List<Treaty> list = jdbcTemplate.query("SELECT * FROM treaty WHERE id = ? FOR UPDATE", MAPPER, id);
        return list.stream()
                .findFirst()
                .orElseThrow(() -> new com.example.starter.reinsurance.common.ApiException(
                        com.example.starter.reinsurance.common.ErrorCode.NOT_FOUND, "合约不存在: id=" + id));
    }
}
