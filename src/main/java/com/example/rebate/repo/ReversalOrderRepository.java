package com.example.rebate.repo;

import com.example.rebate.domain.ReversalOrder;
import com.example.rebate.domain.ReversalStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 分批冲回单数据访问。状态流转全部使用带 {@code status/version_no} 条件的 UPDATE，
 * 配合 expectedVersion 实现乐观并发控制。
 */
@Repository
public class ReversalOrderRepository {

    private static final RowMapper<ReversalOrder> MAPPER = new OrderRowMapper();

    private static final String BASE_SELECT =
            "SELECT id, batch_id, declaration_no, correction_no, reason, amount_cents, status, "
                    + "submitter, reviewer, received_cents, version_no, submitted_at, reviewed_at, "
                    + "created_at, updated_at FROM t_reversal_order";

    private final JdbcTemplate jdbc;

    public ReversalOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 提交冲回单（状态 PENDING，版本从 1 开始），回填自增主键。
     * 同批次同更正编号唯一约束冲突时由调用方转 422。
     */
    public long insert(long batchId, String declarationNo, String correctionNo, String reason,
                       long amountCents, String submitter, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO t_reversal_order
                        (batch_id, declaration_no, correction_no, reason, amount_cents, status,
                         submitter, reviewer, received_cents, version_no,
                         submitted_at, reviewed_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', ?, NULL, 0, 1, ?, NULL, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, declarationNo);
            ps.setString(3, correctionNo);
            ps.setString(4, reason);
            ps.setLong(5, amountCents);
            ps.setString(6, submitter);
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            ps.setTimestamp(9, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReversalOrder> findById(long id) {
        return jdbc.query(BASE_SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<ReversalOrder> findByBatchAndDeclaration(long batchId, String declarationNo) {
        return jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND declaration_no = ? ORDER BY id",
                MAPPER, batchId, declarationNo);
    }

    public Optional<ReversalOrder> findByBatchAndCorrectionNo(long batchId, String correctionNo) {
        return jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND correction_no = ?",
                MAPPER, batchId, correctionNo).stream().findFirst();
    }

    /**
     * 行锁读取（须在事务内），串行化同单复核/到账并发。
     */
    public Optional<ReversalOrder> lockById(long id) {
        return jdbc.query(BASE_SELECT + " WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 批准：仅当仍为 PENDING 且版本匹配时生效，版本 +1。
     *
     * @return 更新行数（0 表示状态/版本已变化，调用方回滚并转 409）
     */
    public int markApproved(long id, String reviewer, int expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE t_reversal_order
                SET status = 'APPROVED', reviewer = ?, reviewed_at = ?,
                    version_no = version_no + 1, updated_at = ?
                WHERE id = ? AND status = 'PENDING' AND version_no = ?
                """,
                reviewer, Timestamp.from(now), Timestamp.from(now), id, expectedVersion);
    }

    /**
     * 驳回：仅当仍为 PENDING 且版本匹配时生效，版本 +1。
     */
    public int markRejected(long id, String reviewer, int expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE t_reversal_order
                SET status = 'REJECTED', reviewer = ?, reviewed_at = ?,
                    version_no = version_no + 1, updated_at = ?
                WHERE id = ? AND status = 'PENDING' AND version_no = ?
                """,
                reviewer, Timestamp.from(now), Timestamp.from(now), id, expectedVersion);
    }

    /**
     * 登记到账并推进状态：仅当版本匹配且累计不超批准金额时生效，版本 +1；
     * 恰收齐时转 COMPLETED，否则保持 APPROVED。
     *
     * @return 更新行数（0 表示版本冲突或超收）
     */
    public int addReceived(long id, long amountCents, ReversalStatus nextStatus,
                           int expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE t_reversal_order
                SET received_cents = received_cents + ?, status = ?,
                    version_no = version_no + 1, updated_at = ?
                WHERE id = ? AND version_no = ? AND received_cents + ? <= amount_cents
                """,
                amountCents, nextStatus.name(), Timestamp.from(now), id,
                expectedVersion, amountCents);
    }

    /**
     * 统计某报关单上仍占用（或等待批准）额度的冲回金额（整数分）：PENDING + APPROVED + COMPLETED。
     * REJECTED 不占额；已批准部分以台账 occupied_cents 为准，PENDING 仅用于提交前预检。
     */
    public long sumPendingAmount(long batchId, String declarationNo) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_cents), 0) FROM t_reversal_order
                WHERE batch_id = ? AND declaration_no = ? AND status = 'PENDING'
                """,
                Long.class, batchId, declarationNo);
        return sum == null ? 0L : sum;
    }

    private static final class OrderRowMapper implements RowMapper<ReversalOrder> {
        @Override
        public ReversalOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReversalOrder(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getString("declaration_no"),
                    rs.getString("correction_no"),
                    rs.getString("reason"),
                    rs.getLong("amount_cents"),
                    ReversalStatus.valueOf(rs.getString("status")),
                    rs.getString("submitter"),
                    rs.getString("reviewer"),
                    rs.getLong("received_cents"),
                    rs.getInt("version_no"),
                    toInstant(rs.getTimestamp("submitted_at")),
                    toInstant(rs.getTimestamp("reviewed_at")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }

        private static Instant toInstant(Timestamp ts) {
            return ts == null ? null : ts.toInstant();
        }
    }
}
