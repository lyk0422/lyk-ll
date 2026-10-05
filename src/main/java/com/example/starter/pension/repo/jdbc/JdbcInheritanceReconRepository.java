package com.example.starter.pension.repo.jdbc;

import com.example.starter.pension.po.InheritanceReconRow;
import com.example.starter.pension.repo.InheritanceReconRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 继承勾稽明细 JDBC 实现。
 */
@Repository
public class JdbcInheritanceReconRepository implements InheritanceReconRepository {

    private static final RowMapper<InheritanceReconRow> ROW_MAPPER = (rs, rowNum) -> new InheritanceReconRow(
            rs.getLong("id"),
            rs.getLong("claim_id"),
            rs.getString("component"),
            rs.getString("source_type"),
            rs.getLong("source_id"),
            rs.getLong("amount_cents"));

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public JdbcInheritanceReconRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Override
    public void insertBatch(long claimId, List<InheritanceReconRow> rows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO inheritance_recon (claim_id, component, source_type, source_id, amount_cents)"
                        + " VALUES (?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, item) -> {
                    ps.setLong(1, claimId);
                    ps.setString(2, item.component());
                    ps.setString(3, item.sourceType());
                    ps.setLong(4, item.sourceId());
                    ps.setLong(5, item.amountCents());
                });
    }

    @Override
    public List<InheritanceReconRow> findByClaimId(long claimId) {
        return jdbcClient.sql(
                        "SELECT id, claim_id, component, source_type, source_id, amount_cents"
                                + " FROM inheritance_recon WHERE claim_id = :claimId ORDER BY id")
                .param("claimId", claimId)
                .query(ROW_MAPPER)
                .list();
    }

    @Override
    public long sumByComponent(long claimId, String component) {
        Long sum = jdbcClient.sql("""
                        SELECT COALESCE(SUM(amount_cents), 0)
                          FROM inheritance_recon
                         WHERE claim_id = :claimId AND component = :component""")
                .param("claimId", claimId)
                .param("component", component)
                .query(Long.class)
                .single();
        return sum == null ? 0L : sum;
    }
}
