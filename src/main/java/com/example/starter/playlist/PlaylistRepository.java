package com.example.starter.playlist;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * 播单版本与节目项数据访问。
 */
@Repository
public class PlaylistRepository {

    private static final RowMapper<PlaylistItem> ITEM_MAPPER = (rs, rowNum) -> new PlaylistItem(
            rs.getString("item_id"),
            rs.getString("media_digest"),
            rs.getLong("start_ms"),
            rs.getLong("end_ms"));

    private final JdbcTemplate jdbc;

    public PlaylistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入播单版本，返回主键；唯一约束冲突时抛出异常由上层转换。
     */
    public long insertVersion(String channelId, int versionNo, int itemCount, long publishedAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playlist_version (channel_id, version_no, item_count, published_at_ms) "
                            + "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setInt(2, versionNo);
            ps.setInt(3, itemCount);
            ps.setLong(4, publishedAtMs);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void insertItem(long versionId, String channelId, PlaylistItem item, int position) {
        jdbc.update("INSERT INTO playlist_item (version_id, channel_id, item_id, media_digest, start_ms, end_ms, position) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                versionId, channelId, item.itemId(), item.mediaDigest(), item.startMs(), item.endMs(), position);
    }

    /**
     * 查询频道已发布的最大版本号；无版本时返回 null。
     */
    public Integer maxVersionNo(String channelId) {
        return jdbc.query("SELECT MAX(version_no) AS v FROM playlist_version WHERE channel_id = ?",
                rs -> rs.next() && rs.getObject("v") != null ? rs.getInt("v") : null, channelId);
    }

    public boolean versionExists(String channelId, int versionNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playlist_version WHERE channel_id = ? AND version_no = ?",
                Integer.class, channelId, versionNo);
        return count != null && count > 0;
    }

    /**
     * 查询指定版本中覆盖时刻 atMs 的节目项（半开区间）；无则返回 null。
     */
    public PlaylistItem findItemAt(long versionId, long atMs) {
        List<PlaylistItem> items = jdbc.query(
                "SELECT item_id, media_digest, start_ms, end_ms FROM playlist_item "
                        + "WHERE version_id = ? AND start_ms <= ? AND end_ms > ? ORDER BY position LIMIT 1",
                ITEM_MAPPER, versionId, atMs, atMs);
        return items.isEmpty() ? null : items.get(0);
    }

    public List<PlaylistItem> itemsOf(long versionId) {
        return jdbc.query(
                "SELECT item_id, media_digest, start_ms, end_ms FROM playlist_item "
                        + "WHERE version_id = ? ORDER BY position",
                ITEM_MAPPER, versionId);
    }
}
