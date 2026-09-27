package com.example.rebate.repo;

import com.example.rebate.domain.ClawbackDetail;
import com.example.rebate.domain.ClawbackStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * 追缴明细数据访问。到账累加为单条条件更新：仅在追缴中且累计不超批准额时生效，
 * 恰收齐在同一更新内转 COMPLETED；完成后状态条件不再成立，天然只读。
 */
@Repository
public class ClawbackDetailRepository {

    private static final RowMapper<ClawbackDetail> MAPPER = new DetailRowMapper();

    private final JdbcTemplate jdbc;

    public ClawbackDetailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 取下一个追缴明细独立编号。 */
    public String nextClawbackNo() {
        long seq = jdbc.queryForObject("SELECT NEXT VALUE FOR seq_clawback_no", Long.class);
        return String.format("CLW-%08d", seq);
    }

    /** 批准事务内产生追缴明细（一单一条由 uk_clawback_order 保证），回填主键。 */
    public long insert(ClawbackDetail d) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO t_clawback_detail
                        (order_id, batch_id, clawback_no, approved_cents, collected_cents,
                         status, version_no, created_at, completed_at)
                    VALUES (?, ?, ?, ?, 0, ?, 1, ?, NULL)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, d.orderId());
            ps.setLong(2, d.batchId());
            ps.setString(3, d.clawbackNo());
            ps.setLong(4, d.approvedCents());
            ps.setString(5, d.status().name());
            ps.setTimestamp(6, Timestamp.from(d.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ClawbackDetail> findById(long id) {
        List<ClawbackDetail> list = jdbc.query(BASE_SELECT + " WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<ClawbackDetail> findByOrderId(long orderId) {
        List<ClawbackDetail> list = jdbc.query(BASE_SELECT + " WHERE order_id = ?", MAPPER, orderId);
        return list.stream().findFirst();
    }

    /**
     * 条件累加一笔到账。
     *
     * <p>仅当明细仍 COLLECTING、版本匹配且 {@code 已收 + 本笔 <= 批准} 时更新，并把版本加 1；
     * 恰收齐在同一语句内转 COMPLETED 并写收齐时刻。</p>
     *
     * @return 受影响行数；0 表示版本不匹配、已完成（只读）或会超收（由业务层重读区分）
     */
    public int tryAddReceived(long detailId, long amountCents, int expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE t_clawback_detail
                SET collected_cents = collected_cents + ?,
                    version_no = version_no + 1,
                    status = CASE WHEN collected_cents + ? = approved_cents
                                  THEN 'COMPLETED' ELSE status END,
                    completed_at = CASE WHEN collected_cents + ? = approved_cents
                                        THEN ? ELSE completed_at END
                WHERE id = ? AND status = 'COLLECTING' AND version_no = ?
                  AND collected_cents + ? <= approved_cents
                """,
                amountCents, amountCents, amountCents, Timestamp.from(now),
                detailId, expectedVersion, amountCents);
    }

    private static final String BASE_SELECT =
            "SELECT id, order_id, batch_id, clawback_no, approved_cents, collected_cents, status, "
                    + "version_no, created_at, completed_at FROM t_clawback_detail";

    private static final class DetailRowMapper implements RowMapper<ClawbackDetail> {
        @Override
        public ClawbackDetail mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp completed = rs.getTimestamp("completed_at");
            return new ClawbackDetail(
                    rs.getLong("id"),
                    rs.getLong("order_id"),
                    rs.getLong("batch_id"),
                    rs.getString("clawback_no"),
                    rs.getLong("approved_cents"),
                    rs.getLong("collected_cents"),
                    ClawbackStatus.valueOf(rs.getString("status")),
                    rs.getInt("version_no"),
                    rs.getTimestamp("created_at").toInstant(),
                    completed == null ? null : completed.toInstant());
        }
    }
}
