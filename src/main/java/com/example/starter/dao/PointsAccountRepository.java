package com.example.starter.dao;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 潜水员积分账访问（虚构积分模型）。
 *
 * <p>无行即首次积分 0；出水在事务内对两名组员的账行加行锁后更新，保证两人积分账原子一致。</p>
 */
@Repository
public class PointsAccountRepository {

    private static final RowMapper<PointsAccountRow> MAPPER = (rs, n) -> new PointsAccountRow(
            rs.getLong("id"),
            rs.getString("diver_code"),
            rs.getInt("points"),
            rs.getTimestamp("anchor_surfaced_at_utc") == null ? null
                    : rs.getTimestamp("anchor_surfaced_at_utc").toInstant(),
            rs.getString("last_dive_request_id"),
            rs.getTimestamp("updated_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public PointsAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PointsAccountRow> find(String diverCode) {
        return jdbc.query("SELECT * FROM diver_points_account WHERE diver_code = ?",
                MAPPER, diverCode).stream().findFirst();
    }

    /** 事务内对账行加行锁，串行化同一潜水员的出水积分更新。 */
    public Optional<PointsAccountRow> findForUpdate(String diverCode) {
        return jdbc.query("SELECT * FROM diver_points_account WHERE diver_code = ? FOR UPDATE",
                MAPPER, diverCode).stream().findFirst();
    }

    /**
     * 出水原子记账：账行存在则更新为本次残留积分与新出水锚点；不存在则插入。
     * 唯一冲突（并发首建）回退为更新，调用方应已持潜水员槽且在事务内。
     */
    public void upsertAtExit(String diverCode, int points, Instant anchorSurfacedAt,
                             String lastDiveRequestId, Instant now) {
        int updated = jdbc.update("UPDATE diver_points_account SET points = ?, "
                        + "anchor_surfaced_at_utc = ?, last_dive_request_id = ?, updated_at_utc = ? "
                        + "WHERE diver_code = ?",
                points, Timestamp.from(anchorSurfacedAt), lastDiveRequestId,
                Timestamp.from(now), diverCode);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO diver_points_account "
                                + "(diver_code, points, anchor_surfaced_at_utc, last_dive_request_id, "
                                + "updated_at_utc) VALUES (?, ?, ?, ?, ?)",
                        diverCode, points, Timestamp.from(anchorSurfacedAt), lastDiveRequestId,
                        Timestamp.from(now));
            } catch (DuplicateKeyException dup) {
                jdbc.update("UPDATE diver_points_account SET points = ?, "
                                + "anchor_surfaced_at_utc = ?, last_dive_request_id = ?, updated_at_utc = ? "
                                + "WHERE diver_code = ?",
                        points, Timestamp.from(anchorSurfacedAt), lastDiveRequestId,
                        Timestamp.from(now), diverCode);
            }
        }
    }
}
