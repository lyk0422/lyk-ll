package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 追保通知数据访问。同一账户同一未结清周期仅一条 OPEN 通知：
 * 由账户行级悲观锁串行化保证，并以 (account_id, active_flag) 唯一约束兜底。
 */
@Repository
public class MarginCallRepository {

    /** 追保通知行。ratioBp 单位：基点。 */
    public record MarginCallRow(long id, long accountId, int ratioBp,
                                LocalDateTime triggerAt, LocalDateTime deadline,
                                String status, int mergeCount) {
    }

    private static final RowMapper<MarginCallRow> MAPPER = (rs, i) -> new MarginCallRow(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getInt("ratio_bp"),
            rs.getTimestamp("trigger_at").toLocalDateTime(),
            rs.getTimestamp("deadline").toLocalDateTime(),
            rs.getString("status"),
            rs.getInt("merge_count"));

    private final JdbcTemplate jdbc;

    public MarginCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建 OPEN 通知；调用方须持有账户行锁并确认当前无 OPEN 通知。 */
    public long insert(long accountId, int ratioBp, LocalDateTime triggerAt, LocalDateTime deadline) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO margin_call (account_id, ratio_bp, trigger_at, deadline, status, active_flag)"
                            + " VALUES (?, ?, ?, ?, 'OPEN', 1)",
                    new String[]{"id"});
            ps.setLong(1, accountId);
            ps.setInt(2, ratioBp);
            ps.setTimestamp(3, Timestamp.valueOf(triggerAt));
            ps.setTimestamp(4, Timestamp.valueOf(deadline));
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    public Optional<MarginCallRow> findById(long id) {
        return jdbc.query("SELECT * FROM margin_call WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<MarginCallRow> findOpenByAccount(long accountId) {
        return jdbc.query("SELECT * FROM margin_call WHERE account_id = ? AND status = 'OPEN'",
                MAPPER, accountId).stream().findFirst();
    }

    /** 同一未结清周期内重复触发：合并为已有通知，merge_count + 1。 */
    public void incrementMerge(long id) {
        jdbc.update("UPDATE margin_call SET merge_count = merge_count + 1 WHERE id = ? AND status = 'OPEN'", id);
    }

    /** 已逾期仍未结清的通知（deadline 早于 now）。 */
    public List<MarginCallRow> findOverdueOpen(LocalDateTime now) {
        return jdbc.query("SELECT * FROM margin_call WHERE status = 'OPEN' AND deadline < ?"
                + " ORDER BY id ASC", MAPPER, Timestamp.valueOf(now));
    }

    /** 补足结清：释放唯一标记，允许下一周期重新通知。 */
    public void resolve(long id, LocalDateTime resolvedAt) {
        jdbc.update("UPDATE margin_call SET status = 'RESOLVED', active_flag = NULL, resolved_at = ?"
                + " WHERE id = ? AND status = 'OPEN'", Timestamp.valueOf(resolvedAt), id);
    }

    /** 强平结清（逾期强平或低于110%直接强平）。 */
    public void markLiquidated(long id, LocalDateTime closedAt) {
        jdbc.update("UPDATE margin_call SET status = 'LIQUIDATED', active_flag = NULL, resolved_at = ?"
                + " WHERE id = ? AND status = 'OPEN'", Timestamp.valueOf(closedAt), id);
    }
}
