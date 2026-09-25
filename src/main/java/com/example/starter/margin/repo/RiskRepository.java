package com.example.starter.margin.repo;

import com.example.starter.margin.domain.DepositResult;
import com.example.starter.margin.domain.JobStatus;
import com.example.starter.margin.domain.LiquidationReason;
import com.example.starter.margin.domain.NoticeStatus;
import com.example.starter.margin.po.DepositPo;
import com.example.starter.margin.po.JobPo;
import com.example.starter.margin.po.LiquidationItemPo;
import com.example.starter.margin.po.NoticePo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 追保通知、保证金入账（幂等）、强平作业与指令的数据访问。
 * 数据库唯一索引是并发下的最终防线：
 * ux_notice_open 保证同一账户仅一条 OPEN 通知；ux_job_pending 保证仅一条 PENDING 作业；
 * margin_deposit 主键保证 requestId 唯一。
 */
@Repository
public class RiskRepository {

    private final JdbcTemplate jdbc;

    public RiskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<NoticePo> NOTICE_MAPPER = (rs, n) -> {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new NoticePo(
                rs.getLong("id"),
                rs.getString("account_no"),
                NoticeStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("trigger_ratio"),
                rs.getTimestamp("deadline").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant());
    };

    private static final RowMapper<DepositPo> DEPOSIT_MAPPER = (rs, n) -> new DepositPo(
            rs.getString("request_id"),
            rs.getString("account_no"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            DepositResult.valueOf(rs.getString("result")),
            rs.getBoolean("replayed"),
            rs.getBigDecimal("ratio_after"),
            rs.getBoolean("notice_resolved"),
            rs.getBoolean("job_cancelled"),
            rs.getTimestamp("deposited_at").toInstant());

    private static final RowMapper<JobPo> JOB_MAPPER = (rs, n) -> {
        Timestamp executedAt = rs.getTimestamp("executed_at");
        return new JobPo(
                rs.getLong("id"),
                rs.getString("account_no"),
                JobStatus.valueOf(rs.getString("status")),
                LiquidationReason.valueOf(rs.getString("reason")),
                rs.getBigDecimal("final_ratio"),
                rs.getTimestamp("created_at").toInstant(),
                executedAt == null ? null : executedAt.toInstant());
    };

    private static final RowMapper<LiquidationItemPo> ITEM_MAPPER = (rs, n) -> new LiquidationItemPo(
            rs.getLong("id"),
            rs.getLong("job_id"),
            rs.getString("account_no"),
            rs.getString("liability_no"),
            rs.getInt("seq_no"),
            rs.getBigDecimal("repaid_amount"),
            rs.getBigDecimal("assets_reduced"),
            rs.getBigDecimal("ratio_after_pct"),
            rs.getTimestamp("created_at").toInstant());

    /**
     * 插入 OPEN 通知；并发下唯一索引冲突返回 false，由上层合并（不新生成通知）。
     */
    public boolean tryInsertOpenNotice(String accountNo, BigDecimal triggerRatio,
                                       Instant deadline, Instant now) {
        try {
            jdbc.update("INSERT INTO margin_notice (account_no, status, trigger_ratio, deadline, created_at, "
                            + "resolved_at) VALUES (?, 'OPEN', ?, ?, ?, NULL)",
                    accountNo, triggerRatio, Timestamp.from(deadline), Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public NoticePo findOpenNotice(String accountNo) {
        List<NoticePo> list = jdbc.query("SELECT id, account_no, status, trigger_ratio, deadline, created_at, "
                + "resolved_at FROM margin_notice WHERE account_no = ? AND status = 'OPEN' ORDER BY id DESC",
                NOTICE_MAPPER, accountNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public NoticePo findNoticeById(long id) {
        List<NoticePo> list = jdbc.query("SELECT id, account_no, status, trigger_ratio, deadline, created_at, "
                + "resolved_at FROM margin_notice WHERE id = ?", NOTICE_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** CAS：OPEN → RESOLVED，仅当通知仍未结清。 */
    public boolean resolveNotice(long noticeId, Instant now) {
        return jdbc.update("UPDATE margin_notice SET status = 'RESOLVED', resolved_at = ? "
                + "WHERE id = ? AND status = 'OPEN'", Timestamp.from(now), noticeId) == 1;
    }

    /** CAS：OPEN → EXPIRED（逾期转强平时调用）。 */
    public boolean expireNotice(long noticeId, Instant now) {
        return jdbc.update("UPDATE margin_notice SET status = 'EXPIRED', resolved_at = ? "
                + "WHERE id = ? AND status = 'OPEN'", Timestamp.from(now), noticeId) == 1;
    }

    public DepositPo findDeposit(String requestId) {
        List<DepositPo> list = jdbc.query("SELECT request_id, account_no, amount, currency, result, replayed, "
                + "ratio_after, notice_resolved, job_cancelled, deposited_at FROM margin_deposit "
                + "WHERE request_id = ?", DEPOSIT_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 首次写入入账记录；requestId 冲突由上层先查后插并结合账户行锁串行化。
     */
    public void insertDeposit(DepositPo po) {
        jdbc.update("INSERT INTO margin_deposit (request_id, account_no, amount, currency, result, replayed, "
                        + "ratio_after, notice_resolved, job_cancelled, deposited_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                po.requestId(), po.accountNo(), po.amount(), po.currency(), po.result().name(),
                po.replayed(), po.ratioAfter(), po.noticeResolved(), po.jobCancelled(),
                Timestamp.from(po.depositedAt()));
    }

    /**
     * 插入 PENDING 作业；唯一索引冲突返回 null，由上层复用已有 PENDING。
     */
    public Long tryInsertPendingJob(String accountNo, LiquidationReason reason, Instant now) {
        try {
            jdbc.update("INSERT INTO liquidation_job (account_no, status, reason, final_ratio, created_at, "
                            + "executed_at) VALUES (?, 'PENDING', ?, NULL, ?, NULL)",
                    accountNo, reason.name(), Timestamp.from(now));
            JobPo job = findPendingJob(accountNo);
            return job == null ? null : job.id();
        } catch (DuplicateKeyException e) {
            JobPo existing = findPendingJob(accountNo);
            return existing == null ? null : existing.id();
        }
    }

    public JobPo findPendingJob(String accountNo) {
        List<JobPo> list = jdbc.query("SELECT id, account_no, status, reason, final_ratio, created_at, executed_at "
                + "FROM liquidation_job WHERE account_no = ? AND status = 'PENDING' ORDER BY id DESC",
                JOB_MAPPER, accountNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public JobPo findJobById(long jobId) {
        List<JobPo> list = jdbc.query("SELECT id, account_no, status, reason, final_ratio, created_at, executed_at "
                + "FROM liquidation_job WHERE id = ?", JOB_MAPPER, jobId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 账户是否已存在终态 EXECUTED 作业（强平已开始执行即不可撤销）。 */
    public boolean existsExecutedJob(String accountNo) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM liquidation_job WHERE account_no = ? AND status = 'EXECUTED'",
                Integer.class, accountNo);
        return cnt != null && cnt > 0;
    }

    /** 账户最新一条任意状态作业；无则 null。 */
    public JobPo findLatestJobAny(String accountNo) {
        List<JobPo> list = jdbc.query("SELECT id, account_no, status, reason, final_ratio, created_at, executed_at "
                + "FROM liquidation_job WHERE account_no = ? ORDER BY id DESC LIMIT 1",
                JOB_MAPPER, accountNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** CAS：PENDING → CANCELLED（待发期间入账达标）。 */
    public boolean cancelPendingJob(long jobId) {
        return jdbc.update("UPDATE liquidation_job SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'",
                jobId) == 1;
    }

    /** CAS：PENDING → EXECUTED，终态。成功返回 true 表示调用方赢得执行权。 */
    public boolean markExecuted(long jobId, BigDecimal finalRatio, Instant now) {
        return jdbc.update("UPDATE liquidation_job SET status = 'EXECUTED', final_ratio = ?, executed_at = ? "
                + "WHERE id = ? AND status = 'PENDING'", finalRatio, Timestamp.from(now), jobId) == 1;
    }

    public void insertItem(LiquidationItemPo po) {
        jdbc.update("INSERT INTO liquidation_item (job_id, account_no, liability_no, seq_no, repaid_amount, "
                        + "assets_reduced, ratio_after_pct, created_at) VALUES (?,?,?,?,?,?,?,?)",
                po.jobId(), po.accountNo(), po.liabilityNo(), po.seqNo(), po.repaidAmount(),
                po.assetsReduced(), po.ratioAfterPct(), Timestamp.from(po.createdAt()));
    }

    public List<LiquidationItemPo> findItemsByJob(long jobId) {
        return jdbc.query("SELECT id, job_id, account_no, liability_no, seq_no, repaid_amount, assets_reduced, "
                + "ratio_after_pct, created_at FROM liquidation_item WHERE job_id = ? ORDER BY seq_no",
                ITEM_MAPPER, jobId);
    }
}
