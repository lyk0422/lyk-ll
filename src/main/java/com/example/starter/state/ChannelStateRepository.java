package com.example.starter.state;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 频道状态数据访问：通过 SELECT ... FOR UPDATE 在事务内串行化同一频道的并发操作。
 */
@Repository
public class ChannelStateRepository {

    private final JdbcTemplate jdbc;

    public ChannelStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 频道运行状态快照。
     *
     * @param currentVersionId 当前绑定播单版本主键；未发布为 null
     * @param watermarkMs      结算水位线（UTC 毫秒）
     * @param nextSeq          下一个 as-run 序号
     * @param lastTickMs       最近 tick 时间；未 tick 为 null
     */
    public record ChannelState(Long currentVersionId, long watermarkMs, long nextSeq, Long lastTickMs) {
    }

    /**
     * 确保频道状态行存在；并发插入冲突时忽略并重新读取。
     */
    public void ensureExists(String channelId) {
        try {
            jdbc.update("INSERT INTO channel_state (channel_id) VALUES (?)", channelId);
        } catch (Exception duplicate) {
            // 并发建行：唯一约束冲突，忽略即可
        }
    }

    /**
     * 读取并锁定频道状态行（须在事务内调用）。
     */
    public ChannelState lockByChannelId(String channelId) {
        ensureExists(channelId);
        return jdbc.queryForObject(
                "SELECT current_version_id, watermark_ms, next_seq, last_tick_ms "
                        + "FROM channel_state WHERE channel_id = ? FOR UPDATE",
                this::map, channelId);
    }

    public ChannelState find(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT current_version_id, watermark_ms, next_seq, last_tick_ms "
                            + "FROM channel_state WHERE channel_id = ?",
                    this::map, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public void updateBinding(String channelId, long versionId) {
        jdbc.update("UPDATE channel_state SET current_version_id = ? WHERE channel_id = ?",
                versionId, channelId);
    }

    public void advanceWatermark(String channelId, long watermarkMs) {
        jdbc.update("UPDATE channel_state SET watermark_ms = ? WHERE channel_id = ?",
                watermarkMs, channelId);
    }

    public void advanceTick(String channelId, long atMs, long nextSeq) {
        jdbc.update("UPDATE channel_state SET last_tick_ms = ?, next_seq = ? WHERE channel_id = ?",
                atMs, nextSeq, channelId);
    }

    public void advanceSeq(String channelId, long nextSeq) {
        jdbc.update("UPDATE channel_state SET next_seq = ? WHERE channel_id = ?", nextSeq, channelId);
    }

    private ChannelState map(ResultSet rs, int rowNum) throws SQLException {
        Long versionId = rs.getObject("current_version_id") != null ? rs.getLong("current_version_id") : null;
        Long lastTick = rs.getObject("last_tick_ms") != null ? rs.getLong("last_tick_ms") : null;
        return new ChannelState(versionId, rs.getLong("watermark_ms"), rs.getLong("next_seq"), lastTick);
    }
}
