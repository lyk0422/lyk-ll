package com.example.starter.pension.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.pension.domain.AccountEntryType;
import com.example.starter.pension.domain.AttemptResult;
import com.example.starter.pension.domain.ContributionSource;
import com.example.starter.pension.domain.InstallmentStatus;
import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;

/**
 * 支付计划聚合数据访问：计划主表、缴费快照、分期期次、支付尝试、账户台账与幂等记录。
 */
@Repository
public class PlanRepository {

    /**
     * 支付计划持久化对象。
     */
    public record PlanPo(
            Long id,
            String planNo,
            String requestId,
            String memberNo,
            PayoutReason reason,
            InstallmentType installmentType,
            int installmentCount,
            PlanStatus status,
            long employeeAmountCents,
            long employerVestedCents,
            long employerForfeitCents,
            long totalAmountCents,
            int tenureYears,
            int vestingRateBp,
            LocalDate applicationDate,
            String originalPlanNo,
            Instant settledAtUtc,
            Instant createdAtUtc) {
    }

    /**
     * 分期期次持久化对象。
     */
    public record InstallmentPo(
            Long id,
            String planNo,
            int seqNo,
            LocalDate dueDate,
            long amountCents,
            InstallmentStatus status,
            Instant paidAtUtc,
            String paidRequestId) {
    }

    /**
     * 计划缴费快照明细持久化对象。
     */
    public record PlanContributionPo(
            String planNo,
            long contributionId,
            ContributionSource source,
            long amountCents,
            long vestedCents,
            long returnedCents) {
    }

    /**
     * 出款尝试持久化对象。
     */
    public record PaymentAttemptPo(
            Long id,
            String requestId,
            String planNo,
            int seqNo,
            long amountCents,
            AttemptResult result,
            String detail,
            Instant createdAtUtc) {
    }

    /**
     * 账户划转台账持久化对象。
     */
    public record AccountEntryPo(
            Long id,
            String planNo,
            AccountEntryType entryType,
            Integer installmentSeq,
            long amountCents,
            String requestId,
            Instant createdAtUtc) {
    }

    /**
     * 幂等记录持久化对象。
     */
    public record IdempotencyPo(
            String requestId,
            String operation,
            String fingerprint,
            String responseJson,
            Instant createdAtUtc) {
    }

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 支付计划 ----------------

    /**
     * 插入支付计划；plan_no、request_id 及“同成员唯一进行中”唯一约束冲突时由 Spring 转
     * {@link org.springframework.dao.DuplicateKeyException}。
     */
    public void insertPlan(PlanPo po) {
        jdbc.update("""
                INSERT INTO payout_plan (
                    plan_no, request_id, member_no, reason, installment_type, installment_count, status,
                    employee_amount_cents, employer_vested_cents, employer_forfeit_cents, total_amount_cents,
                    tenure_years, vesting_rate_bp, application_date, original_plan_no, settled_at_utc
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                po.planNo(), po.requestId(), po.memberNo(), po.reason().name(),
                po.installmentType().name(), po.installmentCount(), po.status().name(),
                po.employeeAmountCents(), po.employerVestedCents(), po.employerForfeitCents(),
                po.totalAmountCents(), po.tenureYears(), po.vestingRateBp(),
                po.applicationDate(), po.originalPlanNo(),
                po.settledAtUtc() == null ? null : Timestamp.from(po.settledAtUtc()));
    }

    /**
     * 按计划号查询。
     */
    public Optional<PlanPo> findPlanByNo(String planNo) {
        return jdbc.query(PLAN_SELECT + " WHERE plan_no = ?", PLAN_MAPPER, planNo)
                .stream().findFirst();
    }

    /**
     * 按支付申请 requestId 查询。
     */
    public Optional<PlanPo> findPlanByRequestId(String requestId) {
        return jdbc.query(PLAN_SELECT + " WHERE request_id = ?", PLAN_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 查询成员指定状态的计划（用于同成员唯一进行中校验）。
     */
    public Optional<PlanPo> findPlanByMemberAndStatus(String memberNo, PlanStatus status) {
        return jdbc.query(PLAN_SELECT + " WHERE member_no = ? AND status = ?",
                PLAN_MAPPER, memberNo, status.name()).stream().findFirst();
    }

    /**
     * 行级锁定计划，串行化期次出款与结清。
     */
    public PlanPo lockPlanByNo(String planNo) {
        return jdbc.queryForObject(PLAN_SELECT + " WHERE plan_no = ? FOR UPDATE", PLAN_MAPPER, planNo);
    }

    /**
     * 条件结清：仅当计划仍为进行中时置为结清。
     *
     * @return 受影响行数；0 表示已被结清（幂等重放）
     */
    public int markSettledIfActive(String planNo, Instant settledAtUtc) {
        return jdbc.update(
                "UPDATE payout_plan SET status = 'SETTLED', settled_at_utc = ? "
                        + "WHERE plan_no = ? AND status = 'ACTIVE'",
                Timestamp.from(settledAtUtc), planNo);
    }

    // ---------------- 缴费快照 ----------------

    /**
     * 批量写入计划受理时冻结的缴费快照。
     */
    public void insertPlanContributions(List<PlanContributionPo> snapshots) {
        jdbc.batchUpdate(
                "INSERT INTO plan_contribution "
                        + "(plan_no, contribution_id, source, amount_cents, vested_cents, returned_cents) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                snapshots, snapshots.size(), (ps, po) -> {
                    ps.setString(1, po.planNo());
                    ps.setLong(2, po.contributionId());
                    ps.setString(3, po.source().name());
                    ps.setLong(4, po.amountCents());
                    ps.setLong(5, po.vestedCents());
                    ps.setLong(6, po.returnedCents());
                });
    }

    /**
     * 查询计划的缴费快照明细。
     */
    public List<PlanContributionPo> findPlanContributions(String planNo) {
        return jdbc.query(
                "SELECT plan_no, contribution_id, source, amount_cents, vested_cents, returned_cents "
                        + "FROM plan_contribution WHERE plan_no = ? ORDER BY contribution_id",
                (rs, n) -> new PlanContributionPo(
                        rs.getString("plan_no"),
                        rs.getLong("contribution_id"),
                        ContributionSource.valueOf(rs.getString("source")),
                        rs.getLong("amount_cents"),
                        rs.getLong("vested_cents"),
                        rs.getLong("returned_cents")),
                planNo);
    }

    // ---------------- 分期期次 ----------------

    /**
     * 批量写入期次。
     */
    public void insertInstallments(List<InstallmentPo> installments) {
        jdbc.batchUpdate(
                "INSERT INTO plan_installment (plan_no, seq_no, due_date, amount_cents, status) "
                        + "VALUES (?, ?, ?, ?, 'PENDING')",
                installments, installments.size(), (ps, po) -> {
                    ps.setString(1, po.planNo());
                    ps.setInt(2, po.seqNo());
                    ps.setObject(3, po.dueDate());
                    ps.setLong(4, po.amountCents());
                });
    }

    /**
     * 查询计划全部期次（按期次序号）。
     */
    public List<InstallmentPo> findInstallments(String planNo) {
        return jdbc.query(
                INSTALLMENT_SELECT + " WHERE plan_no = ? ORDER BY seq_no",
                INSTALLMENT_MAPPER, planNo);
    }

    /**
     * 查询单期。
     */
    public Optional<InstallmentPo> findInstallment(String planNo, int seqNo) {
        return jdbc.query(
                INSTALLMENT_SELECT + " WHERE plan_no = ? AND seq_no = ?",
                INSTALLMENT_MAPPER, planNo, seqNo).stream().findFirst();
    }

    /**
     * 行级锁定单期，保证并发支付同期只有一个事务能出款。
     */
    public Optional<InstallmentPo> lockInstallment(String planNo, int seqNo) {
        return jdbc.query(
                INSTALLMENT_SELECT + " WHERE plan_no = ? AND seq_no = ? FOR UPDATE",
                INSTALLMENT_MAPPER, planNo, seqNo).stream().findFirst();
    }

    /**
     * 条件置为已出款：仅当期待支付时成功，保证每期最多出款一次。
     *
     * @return 受影响行数；0 表示已出款
     */
    public int markInstallmentPaid(String planNo, int seqNo, String requestId, Instant paidAtUtc) {
        return jdbc.update("""
                UPDATE plan_installment
                SET status = 'PAID', paid_at_utc = ?, paid_request_id = ?
                WHERE plan_no = ? AND seq_no = ? AND status = 'PENDING'
                """, Timestamp.from(paidAtUtc), requestId, planNo, seqNo);
    }

    /**
     * @return 计划待支付期次数量
     */
    public int countPendingInstallments(String planNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_installment WHERE plan_no = ? AND status = 'PENDING'",
                Integer.class, planNo);
        return count == null ? 0 : count;
    }

    // ---------------- 出款尝试 ----------------

    /**
     * 插入出款尝试；request_id 唯一约束冲突时抛 {@link org.springframework.dao.DuplicateKeyException}。
     */
    public long insertAttempt(PaymentAttemptPo po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO payment_attempt (request_id, plan_no, seq_no, amount_cents, result, detail) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setString(1, po.requestId());
            ps.setString(2, po.planNo());
            ps.setInt(3, po.seqNo());
            ps.setLong(4, po.amountCents());
            ps.setString(5, po.result().name());
            ps.setString(6, po.detail());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    /**
     * 按支付指令 requestId 查询尝试。
     */
    public Optional<PaymentAttemptPo> findAttemptByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, request_id, plan_no, seq_no, amount_cents, result, detail, created_at_utc
                FROM payment_attempt WHERE request_id = ?
                """, (rs, n) -> new PaymentAttemptPo(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("plan_no"),
                rs.getInt("seq_no"),
                rs.getLong("amount_cents"),
                AttemptResult.valueOf(rs.getString("result")),
                rs.getString("detail"),
                rs.getTimestamp("created_at_utc").toInstant()), requestId).stream().findFirst();
    }

    // ---------------- 账户台账 ----------------

    /**
     * 记录一笔账户划转（成员出款或未归属划回企业）；时间戳由服务层给定，
     * 同一事务内的成员出款与企业划回共享同一结清时刻。
     */
    public void insertAccountEntry(AccountEntryPo po) {
        jdbc.update("""
                INSERT INTO account_entry (plan_no, entry_type, installment_seq, amount_cents, request_id, created_at_utc)
                VALUES (?, ?, ?, ?, ?, ?)
                """, po.planNo(), po.entryType().name(), po.installmentSeq(),
                po.amountCents(), po.requestId(),
                po.createdAtUtc() == null ? null : Timestamp.from(po.createdAtUtc()));
    }

    /**
     * 汇总计划某类划转金额，用于原子性断言。
     */
    public long sumAccountEntries(String planNo, AccountEntryType type) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM account_entry "
                        + "WHERE plan_no = ? AND entry_type = ?",
                Long.class, planNo, type.name());
        return sum == null ? 0L : sum;
    }

    // ---------------- 幂等记录 ----------------

    /**
     * 写入幂等记录；request_id 冲突时抛 {@link org.springframework.dao.DuplicateKeyException}。
     */
    public void insertIdempotency(IdempotencyPo po) {
        jdbc.update(
                "INSERT INTO idempotency_record (request_id, operation, fingerprint, response_json) "
                        + "VALUES (?, ?, ?, ?)",
                po.requestId(), po.operation(), po.fingerprint(), po.responseJson());
    }

    /**
     * 按 requestId 查询幂等记录。
     */
    public Optional<IdempotencyPo> findIdempotency(String requestId) {
        return jdbc.query("""
                SELECT request_id, operation, fingerprint, response_json, created_at_utc
                FROM idempotency_record WHERE request_id = ?
                """, (rs, n) -> new IdempotencyPo(
                rs.getString("request_id"),
                rs.getString("operation"),
                rs.getString("fingerprint"),
                rs.getString("response_json"),
                rs.getTimestamp("created_at_utc").toInstant()), requestId).stream().findFirst();
    }

    private static final String PLAN_SELECT = """
            SELECT id, plan_no, request_id, member_no, reason, installment_type, installment_count, status,
                   employee_amount_cents, employer_vested_cents, employer_forfeit_cents, total_amount_cents,
                   tenure_years, vesting_rate_bp, application_date, original_plan_no, settled_at_utc, created_at_utc
            FROM payout_plan
            """;

    private static final RowMapper<PlanPo> PLAN_MAPPER = (rs, n) -> new PlanPo(
            rs.getLong("id"),
            rs.getString("plan_no"),
            rs.getString("request_id"),
            rs.getString("member_no"),
            PayoutReason.valueOf(rs.getString("reason")),
            InstallmentType.valueOf(rs.getString("installment_type")),
            rs.getInt("installment_count"),
            PlanStatus.valueOf(rs.getString("status")),
            rs.getLong("employee_amount_cents"),
            rs.getLong("employer_vested_cents"),
            rs.getLong("employer_forfeit_cents"),
            rs.getLong("total_amount_cents"),
            rs.getInt("tenure_years"),
            rs.getInt("vesting_rate_bp"),
            rs.getObject("application_date", LocalDate.class),
            rs.getString("original_plan_no"),
            rs.getTimestamp("settled_at_utc") == null ? null : rs.getTimestamp("settled_at_utc").toInstant(),
            rs.getTimestamp("created_at_utc").toInstant());

    private static final String INSTALLMENT_SELECT = """
            SELECT id, plan_no, seq_no, due_date, amount_cents, status, paid_at_utc, paid_request_id
            FROM plan_installment
            """;

    private static final RowMapper<InstallmentPo> INSTALLMENT_MAPPER = (rs, n) -> new InstallmentPo(
            rs.getLong("id"),
            rs.getString("plan_no"),
            rs.getInt("seq_no"),
            rs.getObject("due_date", LocalDate.class),
            rs.getLong("amount_cents"),
            InstallmentStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("paid_at_utc") == null ? null : rs.getTimestamp("paid_at_utc").toInstant(),
            rs.getString("paid_request_id"));
}
