package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.Models.Policy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 分出保单数据访问。
 */
@Repository
public class PolicyRepository {

    private static final RowMapper<Policy> MAPPER = (rs, n) -> map(rs);

    private final JdbcTemplate jdbc;

    public PolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Policy map(ResultSet rs) throws SQLException {
        return new Policy(rs.getLong("id"), rs.getString("policy_no"), rs.getLong("treaty_id"),
                rs.getBigDecimal("ceded_premium"), rs.getObject("created_at", OffsetDateTime.class));
    }

    public Policy insert(String policyNo, long treatyId, java.math.BigDecimal cededPremium,
                         OffsetDateTime now) {
        jdbc.update("INSERT INTO policy (policy_no, treaty_id, ceded_premium, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                policyNo, treatyId, cededPremium, now);
        return findByPolicyNo(policyNo).orElseThrow();
    }

    public Optional<Policy> findByPolicyNo(String policyNo) {
        return jdbc.query("SELECT * FROM policy WHERE policy_no = ?", MAPPER, policyNo)
                .stream().findFirst();
    }

    public Optional<Policy> findById(long id) {
        return jdbc.query("SELECT * FROM policy WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<Policy> findByTreatyId(long treatyId) {
        return jdbc.query("SELECT * FROM policy WHERE treaty_id = ? ORDER BY id", MAPPER, treatyId);
    }
}
