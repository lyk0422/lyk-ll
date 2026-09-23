package com.example.starter.asrun;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * as-run 播出记录数据访问。序号在频道内单调递增、不复用。
 */
@Repository
public class AsRunRepository {

    private static final String COLUMNS = "channel_id, seq, kind, item_id, media_digest, break_request_id, "
            + "start_ms, end_ms, status, acked, acked_at_ms";

    private static final RowMapper<AsRunRecord> MAPPER = (rs, rowNum) -> new AsRunRecord(
            rs.getString("channel_id"),
            rs.getLong("seq"),
            rs.getString("kind"),
            rs.getString("item_id"),
            rs.getString("media_digest"),
            rs.getString("break_request_id"),
            rs.getLong("start_ms"),
            rs.getObject("end_ms") != null ? rs.getLong("end_ms") : null,
            rs.getString("status"),
            rs.getInt("acked") == 1,
            rs.getObject("acked_at_ms") != null ? rs.getLong("acked_at_ms") : null);

    private final JdbcTemplate jdbc;

    public AsRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AsRunRecord record) {
        jdbc.update("INSERT INTO as_run (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.channelId(), record.seq(), record.kind(), record.itemId(), record.mediaDigest(),
                record.breakRequestId(), record.startMs(), record.endMs(), record.status(),
                record.acked() ? 1 : 0, record.ackedAtMs());
    }

    /**
     * 当前在播（OPEN）记录；无则返回 null。
     */
    public AsRunRecord findOpen(String channelId) {
        List<AsRunRecord> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM as_run WHERE channel_id = ? AND status = 'OPEN' ORDER BY seq DESC LIMIT 1",
                MAPPER, channelId);
        return list.isEmpty() ? null : list.get(0);
    }

    public AsRunRecord findBySeq(String channelId, long seq) {
        List<AsRunRecord> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM as_run WHERE channel_id = ? AND seq = ?",
                MAPPER, channelId, seq);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 与 [fromMs, +∞) 相交且未被取代的记录（含在播），按序号升序。
     */
    public List<AsRunRecord> findOverlappingFrom(String channelId, long fromMs) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM as_run WHERE channel_id = ? AND status <> 'SUPERSEDED' "
                        + "AND (end_ms IS NULL OR end_ms > ?) ORDER BY seq",
                MAPPER, channelId, fromMs);
    }

    /**
     * 截断记录结束时间并标记为 CLOSED。
     */
    public void closeAt(String channelId, long seq, long endMs) {
        jdbc.update("UPDATE as_run SET end_ms = ?, status = 'CLOSED' WHERE channel_id = ? AND seq = ?",
                endMs, channelId, seq);
    }

    /**
     * 标记记录被后续记录取代（保留行供迟到 ACK 确认）。
     */
    public void supersede(String channelId, long seq) {
        jdbc.update("UPDATE as_run SET status = 'SUPERSEDED' WHERE channel_id = ? AND seq = ?",
                channelId, seq);
    }

    /**
     * 确认记录；返回更新行数。幂等：已确认时再次调用返回 0。
     */
    public int markAcked(String channelId, long seq, long ackedAtMs) {
        return jdbc.update("UPDATE as_run SET acked = 1, acked_at_ms = ? "
                + "WHERE channel_id = ? AND seq = ? AND acked = 0", ackedAtMs, channelId, seq);
    }

    /**
     * 按时间窗查询有效记录（不含被取代记录），半开区间相交判定。
     */
    public List<AsRunRecord> query(String channelId, Long fromMs, Long toMs) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM as_run WHERE channel_id = ? AND status <> 'SUPERSEDED' "
                        + "AND (? IS NULL OR end_ms IS NULL OR end_ms > ?) "
                        + "AND (? IS NULL OR start_ms < ?) ORDER BY seq",
                MAPPER, channelId, fromMs, fromMs, toMs, toMs);
    }
}
