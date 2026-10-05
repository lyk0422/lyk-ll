package com.example.starter.pension.repo.jdbc;

import com.example.starter.pension.domain.InheritanceStatus;
import com.example.starter.pension.po.InheritanceClaimRow;
import com.example.starter.pension.repo.InheritanceClaimRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 继承申领 JDBC 实现。时间列以 UTC 解释。
 */
@Repository
public class JdbcInheritanceClaimRepository implements InheritanceClaimRepository {

    private static final String COLUMNS = """
            id, claim_no, request_id, member_id, heir_name, death_date, apply_date, fingerprint,
            account_balance_cents, unpaid_installment_cents, inheritance_amount_cents,
            terminated_plan_id, status, active_member_id, completed_at
            """;

    private static final RowMapper<InheritanceClaimRow> ROW_MAPPER = (rs, rowNum) -> new InheritanceClaimRow(
            rs.getLong("id"),
            rs.getString("claim_no"),
            rs.getString("request_id"),
            rs.getLong("member_id"),
            rs.getString("heir_name"),
            rs.getDate("death_date").toLocalDate(),
            rs.getDate("apply_date").toLocalDate(),
            rs.getString("fingerprint"),
            rs.getLong("account_balance_cents"),
            rs.getLong("unpaid_installment_cents"),
            rs.getLong("inheritance_amount_cents"),
            rs.getObject("terminated_plan_id", Long.class),
            InheritanceStatus.valueOf(rs.getString("status")),
            rs.getObject("active_member_id", Long.class),
            toOffsetDateTime(rs.getTimestamp("completed_at")));

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public JdbcInheritanceClaimRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Override
    public long insert(InheritanceClaimRow c) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO inheritance_claim ("
                            + "claim_no, request_id, member_id, heir_name, death_date, apply_date, "
                            + "fingerprint, account_balance_cents, unpaid_installment_cents, "
                            + "inheritance_amount_cents, terminated_plan_id, status, active_member_id, "
                            + "completed_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
                    new String[]{"id"});
            int i = 1;
            ps.setString(i++, c.claimNo());
            ps.setString(i++, c.requestId());
            ps.setLong(i++, c.memberId());
            ps.setString(i++, c.heirName());
            ps.setDate(i++, java.sql.Date.valueOf(c.deathDate()));
            ps.setDate(i++, java.sql.Date.valueOf(c.applyDate()));
            ps.setString(i++, c.fingerprint());
            ps.setLong(i++, c.accountBalanceCents());
            ps.setLong(i++, c.unpaidInstallmentCents());
            ps.setLong(i++, c.inheritanceAmountCents());
            ps.setObject(i++, c.terminatedPlanId());
            ps.setString(i++, c.status().name());
            ps.setObject(i, c.activeMemberId());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public Optional<InheritanceClaimRow> findByRequestId(String requestId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM inheritance_claim WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<InheritanceClaimRow> findByClaimNo(String claimNo) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM inheritance_claim WHERE claim_no = :claimNo")
                .param("claimNo", claimNo)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<InheritanceClaimRow> lockById(long claimId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM inheritance_claim WHERE id = :claimId FOR UPDATE")
                .param("claimId", claimId)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public long countByMember(long memberId) {
        Long count = jdbcClient.sql(
                        "SELECT COUNT(*) FROM inheritance_claim WHERE member_id = :memberId")
                .param("memberId", memberId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    @Override
    public Optional<InheritanceClaimRow> findCompletedByMember(long memberId) {
        return jdbcClient.sql("SELECT " + COLUMNS
                        + " FROM inheritance_claim WHERE member_id = :memberId AND status = 'COMPLETED'")
                .param("memberId", memberId)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public int markCompleted(long claimId, OffsetDateTime completedAt) {
        return jdbcClient.sql("""
                        UPDATE inheritance_claim
                           SET status = 'COMPLETED', completed_at = :completedAt
                         WHERE id = :claimId AND status = 'ACCEPTED'""")
                .param("completedAt", Timestamp.from(completedAt.toInstant()))
                .param("claimId", claimId)
                .update();
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
