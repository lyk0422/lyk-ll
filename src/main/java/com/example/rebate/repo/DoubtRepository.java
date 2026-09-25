package com.example.rebate.repo;

import com.example.rebate.domain.Doubt;
import com.example.rebate.domain.DoubtStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 疑点数据访问。
 */
@Repository
public class DoubtRepository {

    private static final RowMapper<Doubt> MAPPER = new DoubtRowMapper();

    private final JdbcTemplate jdbc;

    public DoubtRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 校验时登记疑点（同一校验明细+疑点类型唯一，重复校验不重复产生）。
     */
    public void insertIfAbsent(long batchId, long checkDetailId, String doubtType, Instant now) {
        jdbc.update("""
                INSERT INTO t_doubt (batch_id, check_detail_id, doubt_type, status)
                SELECT ?, ?, ?, ? FROM DUAL
                WHERE NOT EXISTS (
                    SELECT 1 FROM t_doubt WHERE check_detail_id = ? AND doubt_type = ?
                )
                """,
                batchId, checkDetailId, doubtType, DoubtStatus.OPEN.name(),
                checkDetailId, doubtType);
    }

    /**
     * 查询批次全部疑点。
     */
    public List<Doubt> findByBatchId(long batchId) {
        return jdbc.query(BASE_SELECT + " WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 查询批次未解除（OPEN/EXPLAINED）的疑点。
     */
    public List<Doubt> findUnresolvedByBatchId(long batchId) {
        return jdbc.query(
                BASE_SELECT + " WHERE batch_id = ? AND status <> ? ORDER BY id",
                MAPPER, batchId, DoubtStatus.RESOLVED.name());
    }

    /**
     * 按主键查询。
     */
    public List<Doubt> findById(long id) {
        return jdbc.query(BASE_SELECT + " WHERE id = ?", MAPPER, id);
    }

    /**
     * 提交补充说明：仅 OPEN -> EXPLAINED。
     *
     * @return 受影响行数；0 表示状态不允许（不存在或非 OPEN）
     */
    public int submitExplanation(long doubtId, String explanation, Instant now) {
        return jdbc.update("""
                UPDATE t_doubt SET status = ?, explanation = ?, explained_at = ?
                WHERE id = ? AND status = ?
                """,
                DoubtStatus.EXPLAINED.name(), explanation, Timestamp.from(now),
                doubtId, DoubtStatus.OPEN.name());
    }

    /**
     * 复核确认：仅 EXPLAINED -> RESOLVED。
     *
     * @return 受影响行数；0 表示尚未提交说明或已处理
     */
    public int reviewResolve(long doubtId, Instant now) {
        return jdbc.update("""
                UPDATE t_doubt SET status = ?, reviewed_at = ?
                WHERE id = ? AND status = ?
                """,
                DoubtStatus.RESOLVED.name(), Timestamp.from(now),
                doubtId, DoubtStatus.EXPLAINED.name());
    }

    private static final String BASE_SELECT =
            "SELECT id, batch_id, check_detail_id, doubt_type, status, explanation, "
                    + "explained_at, reviewed_at FROM t_doubt";

    private static final class DoubtRowMapper implements RowMapper<Doubt> {
        @Override
        public Doubt mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Doubt(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getLong("check_detail_id"),
                    rs.getString("doubt_type"),
                    DoubtStatus.valueOf(rs.getString("status")),
                    rs.getString("explanation"),
                    toInstant(rs.getTimestamp("explained_at")),
                    toInstant(rs.getTimestamp("reviewed_at")));
        }

        private static Instant toInstant(Timestamp ts) {
            return ts == null ? null : ts.toInstant();
        }
    }
}
