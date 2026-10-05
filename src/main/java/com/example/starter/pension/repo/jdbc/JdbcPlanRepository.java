package com.example.starter.pension.repo.jdbc;

import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;
import com.example.starter.pension.po.PlanRow;
import com.example.starter.pension.repo.PlanRepository;
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
 * 支付计划 JDBC 实现。时间列以 UTC 解释。
 */
@Repository
public class JdbcPlanRepository implements PlanRepository {

    private static final String COLUMNS = """
            id, plan_no, request_id, member_id, reason, installment_type, fingerprint,
            request_date, hire_years, vested_ratio_bp, personal_amount_cents, enterprise_amount_cents,
            vested_enterprise_cents, vested_total_cents, unvested_return_cents, installments_count,
            status, active_member_id, enterprise_returned, original_plan_no, supplementary, settled_at
            """;

    private static final RowMapper<PlanRow> ROW_MAPPER = (rs, rowNum) -> new PlanRow(
            rs.getLong("id"),
            rs.getString("plan_no"),
            rs.getString("request_id"),
            rs.getLong("member_id"),
            PayoutReason.valueOf(rs.getString("reason")),
            InstallmentType.valueOf(rs.getString("installment_type")),
            rs.getString("fingerprint"),
            rs.getDate("request_date").toLocalDate(),
            rs.getInt("hire_years"),
            rs.getInt("vested_ratio_bp"),
            rs.getLong("personal_amount_cents"),
            rs.getLong("enterprise_amount_cents"),
            rs.getLong("vested_enterprise_cents"),
            rs.getLong("vested_total_cents"),
            rs.getLong("unvested_return_cents"),
            rs.getInt("installments_count"),
            PlanStatus.valueOf(rs.getString("status")),
            rs.getObject("active_member_id", Long.class),
            rs.getBoolean("enterprise_returned"),
            rs.getString("original_plan_no"),
            rs.getBoolean("supplementary"),
            toOffsetDateTime(rs.getTimestamp("settled_at")));

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public JdbcPlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Override
    public long insert(PlanRow p) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO payout_plan ("
                            + "plan_no, request_id, member_id, reason, installment_type, fingerprint, "
                            + "request_date, hire_years, vested_ratio_bp, personal_amount_cents, "
                            + "enterprise_amount_cents, vested_enterprise_cents, vested_total_cents, "
                            + "unvested_return_cents, installments_count, status, active_member_id, "
                            + "enterprise_returned, original_plan_no, supplementary, settled_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
                    new String[]{"id"});
            int i = 1;
            ps.setString(i++, p.planNo());
            ps.setString(i++, p.requestId());
            ps.setLong(i++, p.memberId());
            ps.setString(i++, p.reason().name());
            ps.setString(i++, p.installmentType().name());
            ps.setString(i++, p.fingerprint());
            ps.setDate(i++, java.sql.Date.valueOf(p.requestDate()));
            ps.setInt(i++, p.hireYears());
            ps.setInt(i++, p.vestedRatioBp());
            ps.setLong(i++, p.personalAmountCents());
            ps.setLong(i++, p.enterpriseAmountCents());
            ps.setLong(i++, p.vestedEnterpriseCents());
            ps.setLong(i++, p.vestedTotalCents());
            ps.setLong(i++, p.unvestedReturnCents());
            ps.setInt(i++, p.installmentsCount());
            ps.setString(i++, p.status().name());
            ps.setObject(i++, p.activeMemberId());
            ps.setBoolean(i++, p.enterpriseReturned());
            ps.setString(i++, p.originalPlanNo());
            ps.setBoolean(i, p.supplementary());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public Optional<PlanRow> findByRequestId(String requestId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM payout_plan WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<PlanRow> findById(long planId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM payout_plan WHERE id = :planId")
                .param("planId", planId)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<PlanRow> findByPlanNo(String planNo) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM payout_plan WHERE plan_no = :planNo")
                .param("planNo", planNo)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<PlanRow> lockById(long planId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM payout_plan WHERE id = :planId FOR UPDATE")
                .param("planId", planId)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public long countActiveByMember(long memberId) {
        Long count = jdbcClient.sql(
                        "SELECT COUNT(*) FROM payout_plan WHERE active_member_id = :memberId")
                .param("memberId", memberId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    @Override
    public Optional<PlanRow> findActiveByMember(long memberId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM payout_plan WHERE active_member_id = :memberId")
                .param("memberId", memberId)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public long countByMember(long memberId) {
        Long count = jdbcClient.sql(
                        "SELECT COUNT(*) FROM payout_plan WHERE member_id = :memberId")
                .param("memberId", memberId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    @Override
    public int markSettled(long planId, OffsetDateTime settledAt) {
        return jdbcClient.sql("""
                        UPDATE payout_plan
                           SET status = 'SETTLED', active_member_id = NULL, settled_at = :settledAt
                         WHERE id = :planId AND status <> 'SETTLED'""")
                .param("settledAt", Timestamp.from(settledAt.toInstant()))
                .param("planId", planId)
                .update();
    }

    @Override
    public int markEnterpriseReturned(long planId) {
        return jdbcClient.sql("""
                        UPDATE payout_plan
                           SET enterprise_returned = TRUE
                         WHERE id = :planId AND enterprise_returned = FALSE""")
                .param("planId", planId)
                .update();
    }

    @Override
    public int markInProgress(long planId) {
        return jdbcClient.sql("""
                        UPDATE payout_plan
                           SET status = 'IN_PROGRESS'
                         WHERE id = :planId AND status = 'ACCEPTED'""")
                .param("planId", planId)
                .update();
    }

    @Override
    public int markTerminated(long planId, OffsetDateTime terminatedAt) {
        return jdbcClient.sql("""
                        UPDATE payout_plan
                           SET status = 'TERMINATED', active_member_id = NULL, settled_at = :terminatedAt
                         WHERE id = :planId AND status IN ('ACCEPTED', 'IN_PROGRESS')""")
                .param("terminatedAt", Timestamp.from(terminatedAt.toInstant()))
                .param("planId", planId)
                .update();
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
