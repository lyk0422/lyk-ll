package com.example.starter.pension.repo.jdbc;

import com.example.starter.pension.domain.InstallmentStatus;
import com.example.starter.pension.po.InstallmentRow;
import com.example.starter.pension.repo.InstallmentRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 分期期次 JDBC 实现。
 */
@Repository
public class JdbcInstallmentRepository implements InstallmentRepository {

    private static final String COLUMNS =
            "id, plan_id, seq_no, due_date, amount_cents, status, attempts, paid_at";

    private static final RowMapper<InstallmentRow> ROW_MAPPER = (rs, rowNum) -> new InstallmentRow(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq_no"),
            rs.getDate("due_date").toLocalDate(),
            rs.getLong("amount_cents"),
            InstallmentStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            toOffsetDateTime(rs.getTimestamp("paid_at")));

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public JdbcInstallmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Override
    public void insertBatch(long planId, List<NewInstallment> installments) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO payout_installment (plan_id, seq_no, due_date, amount_cents, status)"
                        + " VALUES (?, ?, ?, ?, 'PENDING')",
                installments,
                installments.size(),
                (ps, item) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, item.seqNo());
                    ps.setDate(3, java.sql.Date.valueOf(item.dueDate()));
                    ps.setLong(4, item.amountCents());
                });
    }

    @Override
    public List<InstallmentRow> findByPlanId(long planId) {
        return jdbcClient.sql("SELECT " + COLUMNS
                        + " FROM payout_installment WHERE plan_id = :planId ORDER BY seq_no")
                .param("planId", planId)
                .query(ROW_MAPPER)
                .list();
    }

    @Override
    public List<InstallmentRow> findUnpaidByPlan(long planId) {
        return jdbcClient.sql("SELECT " + COLUMNS
                        + " FROM payout_installment WHERE plan_id = :planId"
                        + " AND status IN ('PENDING', 'FAILED') ORDER BY seq_no")
                .param("planId", planId)
                .query(ROW_MAPPER)
                .list();
    }

    @Override
    public int cancelUnpaidByPlan(long planId) {
        return jdbcClient.sql("""
                        UPDATE payout_installment
                           SET status = 'CANCELLED'
                         WHERE plan_id = :planId AND status IN ('PENDING', 'FAILED')""")
                .param("planId", planId)
                .update();
    }

    @Override
    public Optional<InstallmentRow> findByPlanAndSeq(long planId, int seqNo) {
        return jdbcClient.sql("SELECT " + COLUMNS
                        + " FROM payout_installment WHERE plan_id = :planId AND seq_no = :seqNo")
                .param("planId", planId)
                .param("seqNo", seqNo)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<InstallmentRow> lockByPlanAndSeq(long planId, int seqNo) {
        return jdbcClient.sql("SELECT " + COLUMNS
                        + " FROM payout_installment WHERE plan_id = :planId AND seq_no = :seqNo FOR UPDATE")
                .param("planId", planId)
                .param("seqNo", seqNo)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public int markFailed(long installmentId, int attempts) {
        return jdbcClient.sql("""
                        UPDATE payout_installment
                           SET status = 'FAILED', attempts = :attempts
                         WHERE id = :id AND status <> 'PAID'""")
                .param("attempts", attempts)
                .param("id", installmentId)
                .update();
    }

    @Override
    public int markPaidIfNotYet(long installmentId, int attempts, OffsetDateTime paidAt) {
        return jdbcClient.sql("""
                        UPDATE payout_installment
                           SET status = 'PAID', attempts = :attempts, paid_at = :paidAt
                         WHERE id = :id AND status <> 'PAID'""")
                .param("attempts", attempts)
                .param("paidAt", Timestamp.from(paidAt.toInstant()))
                .param("id", installmentId)
                .update();
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
