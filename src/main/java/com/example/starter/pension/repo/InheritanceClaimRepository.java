package com.example.starter.pension.repo;

import com.example.starter.pension.domain.ClaimStatus;
import com.example.starter.pension.po.ClaimRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 继承申领表数据访问。同一成员并发唯一受理由成员行锁与 member_id 唯一索引兜底。
 */
@Repository
public class InheritanceClaimRepository {

    private static final String COLUMNS = """
            id, claim_no, request_id, member_id, heir_name, death_date, apply_date, fingerprint,
            account_balance_cents, unpaid_installment_cents, claim_amount_cents,
            terminated_plan_id, status, paid_at
            """;

    private static final RowMapper<ClaimRow> ROW_MAPPER = (rs, rowNum) -> new ClaimRow(
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
            rs.getLong("claim_amount_cents"),
            rs.getObject("terminated_plan_id", Long.class),
            ClaimStatus.valueOf(rs.getString("status")),
            toOffsetDateTime(rs.getTimestamp("paid_at")));

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public InheritanceClaimRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 插入继承申领；requestId 冲突或成员已有申领时由唯一索引拒绝。
     *
     * @return 新申领主键
     */
    public long insert(ClaimRow c) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO inheritance_claim ("
                            + "claim_no, request_id, member_id, heir_name, death_date, apply_date, "
                            + "fingerprint, account_balance_cents, unpaid_installment_cents, "
                            + "claim_amount_cents, terminated_plan_id, status, paid_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
                    new String[]{"id"});
            int i = 1;
            ps.setString(i++, c.claimNo());
            ps.setString(i++, c.requestId());
            ps.setLong(i++, c.memberId());
            ps.setString(i++, c.heirName());
            ps.setDate(i++, Date.valueOf(c.deathDate()));
            ps.setDate(i++, Date.valueOf(c.applyDate()));
            ps.setString(i++, c.fingerprint());
            ps.setLong(i++, c.accountBalanceCents());
            ps.setLong(i++, c.unpaidInstallmentCents());
            ps.setLong(i++, c.claimAmountCents());
            ps.setObject(i++, c.terminatedPlanId());
            ps.setString(i, c.status().name());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按申领幂等键查询。 */
    public Optional<ClaimRow> findByRequestId(String requestId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM inheritance_claim WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(ROW_MAPPER)
                .optional();
    }

    /** 按申领号查询。 */
    public Optional<ClaimRow> findByClaimNo(String claimNo) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM inheritance_claim WHERE claim_no = :claimNo")
                .param("claimNo", claimNo)
                .query(ROW_MAPPER)
                .optional();
    }

    /** 按成员查询唯一申领（member_id 唯一索引保证至多一条）；无为空。 */
    public Optional<ClaimRow> findByMember(long memberId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM inheritance_claim WHERE member_id = :memberId")
                .param("memberId", memberId)
                .query(ROW_MAPPER)
                .optional();
    }

    /** 按主键查询并对申领行加排他锁，串行化继承支付完成。 */
    public Optional<ClaimRow> lockById(long claimId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM inheritance_claim WHERE id = :claimId FOR UPDATE")
                .param("claimId", claimId)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * 继承支付完成（终态）：仅当申领仍为 ACCEPTED 时置 PAID 并记录完成时间。
     *
     * @return 1 表示本次完成；0 表示已完成（幂等场景）
     */
    public int markPaid(long claimId, OffsetDateTime paidAt) {
        return jdbcClient.sql("""
                        UPDATE inheritance_claim
                           SET status = 'PAID', paid_at = :paidAt
                         WHERE id = :claimId AND status = 'ACCEPTED'""")
                .param("paidAt", Timestamp.from(paidAt.toInstant()))
                .param("claimId", claimId)
                .update();
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
