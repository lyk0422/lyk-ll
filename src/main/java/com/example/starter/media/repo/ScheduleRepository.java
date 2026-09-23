package com.example.starter.media.repo;

import com.example.starter.media.domain.GrantStatus;
import com.example.starter.media.domain.LicenseMode;
import com.example.starter.media.domain.ScheduleItemStatus;
import com.example.starter.media.web.dto.EvidenceItemView;
import com.example.starter.media.web.dto.ScheduleItemView;
import com.example.starter.media.web.dto.ScheduleView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 播出排期与时段的数据访问。
 */
@Repository
public class ScheduleRepository {

    private static final RowMapper<ScheduleItemView> ITEM_MAPPER = (rs, n) -> {
        Long boundId = (Long) rs.getObject("bound_version_id");
        Integer boundVersionNo = (Integer) rs.getObject("bound_version_no");
        Timestamp played = rs.getTimestamp("played_at");
        return new ScheduleItemView(
                rs.getInt("item_no"),
                rs.getString("asset_id"),
                rs.getString("region"),
                rs.getString("platform"),
                rs.getTimestamp("slot_start").toInstant(),
                rs.getTimestamp("slot_end").toInstant(),
                boundId,
                boundVersionNo,
                ScheduleItemStatus.valueOf(rs.getString("status")),
                played == null ? null : played.toInstant()
        );
    };

    private static final RowMapper<EvidenceItemView> EVIDENCE_MAPPER = (rs, n) -> new EvidenceItemView(
            rs.getString("schedule_id"),
            (Integer) rs.getObject("schedule_version_no"),
            rs.getInt("item_no"),
            rs.getTimestamp("slot_start").toInstant(),
            rs.getTimestamp("slot_end").toInstant(),
            ScheduleItemStatus.valueOf(rs.getString("status")),
            (Timestamp) rs.getObject("played_at") == null ? null : rs.getTimestamp("played_at").toInstant(),
            (Long) rs.getObject("bound_version_id"),
            (Long) rs.getObject("grant_id"),
            (Integer) rs.getObject("bound_version_no"),
            rs.getTimestamp("grant_window_start") == null ? null : rs.getTimestamp("grant_window_start").toInstant(),
            rs.getTimestamp("grant_window_end") == null ? null : rs.getTimestamp("grant_window_end").toInstant(),
            rs.getString("grant_mode") == null ? null : LicenseMode.valueOf(rs.getString("grant_mode")),
            rs.getString("grant_status") == null ? null : GrantStatus.valueOf(rs.getString("grant_status"))
    );

    private final JdbcTemplate jdbc;

    public ScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSchedule(String scheduleId, int versionNo, String requestId, Instant now) {
        jdbc.update("INSERT INTO play_schedule (schedule_id, version_no, status, request_id, created_at, updated_at) VALUES (?, ?, 'COMMITTED', ?, ?, ?)",
                scheduleId, versionNo, requestId, Timestamp.from(now), Timestamp.from(now));
    }

    public void insertItem(String scheduleId, int itemNo, String assetId, String region, String platform,
                           Instant slotStart, Instant slotEnd, GrantVersionRow bound,
                           ScheduleItemStatus status, String requestId, Instant now) {
        jdbc.update("""
                        INSERT INTO play_schedule_item
                          (schedule_id, item_no, asset_id, region, platform, slot_start, slot_end,
                           bound_version_id, bound_version_no, bound_window_start, bound_window_end,
                           bound_mode, status, request_id, played_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)""",
                scheduleId, itemNo, assetId, region, platform,
                Timestamp.from(slotStart), Timestamp.from(slotEnd),
                bound.rowId(), bound.versionNo(),
                Timestamp.from(bound.windowStart()), Timestamp.from(bound.windowEnd()),
                bound.mode().name(), status.name(), requestId, Timestamp.from(now));
    }

    public boolean scheduleExists(String scheduleId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM play_schedule WHERE schedule_id = ?",
                Integer.class, scheduleId);
        return count != null && count > 0;
    }

    public ScheduleView findSchedule(String scheduleId) {
        List<String> meta = jdbc.query("SELECT status FROM play_schedule WHERE schedule_id = ?",
                (rs, n) -> rs.getString("status"), scheduleId);
        if (meta.isEmpty()) {
            return null;
        }
        Integer versionNo = jdbc.queryForObject("SELECT version_no FROM play_schedule WHERE schedule_id = ?",
                Integer.class, scheduleId);
        List<ScheduleItemView> items = jdbc.query(itemSelect() + " WHERE i.schedule_id = ? ORDER BY i.item_no",
                ITEM_MAPPER, scheduleId);
        return new ScheduleView(scheduleId, versionNo, meta.get(0), items);
    }

    public ScheduleItemView findItemForUpdate(String scheduleId, int itemNo) {
        // 先用单表行锁串行化并发回执，再通过带 JOIN 的视图加载完整信息。
        Integer locked = jdbc.queryForObject(
                "SELECT item_no FROM play_schedule_item WHERE schedule_id = ? AND item_no = ? FOR UPDATE",
                Integer.class, scheduleId, itemNo);
        if (locked == null) {
            return null;
        }
        List<ScheduleItemView> items = jdbc.query(
                itemSelect() + " WHERE i.schedule_id = ? AND i.item_no = ?",
                ITEM_MAPPER, scheduleId, itemNo);
        return items.isEmpty() ? null : items.get(0);
    }

    public void markPlayed(String scheduleId, int itemNo, Instant playedAt, Instant now) {
        jdbc.update("UPDATE play_schedule_item SET status = 'PLAYED', played_at = ?, updated_at = ? WHERE schedule_id = ? AND item_no = ?",
                Timestamp.from(playedAt), Timestamp.from(now), scheduleId, itemNo);
        jdbc.update("UPDATE play_schedule SET updated_at = ? WHERE schedule_id = ?", Timestamp.from(now), scheduleId);
    }

    public void markBlockedByVersion(long versionRowId, Instant threshold, Instant now) {
        jdbc.update("""
                        UPDATE play_schedule_item
                        SET status = 'BLOCKED', updated_at = ?
                        WHERE bound_version_id = ? AND status = 'BOUND' AND slot_start > ?""",
                Timestamp.from(now), versionRowId, Timestamp.from(threshold));
    }

    public Integer findScheduleVersionNo(String scheduleId) {
        List<Integer> rows = jdbc.query("SELECT version_no FROM play_schedule WHERE schedule_id = ?",
                (rs, n) -> rs.getInt(1), scheduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查询某排期的全部时段及其绑定授权版本证据（已冻结的历史版本不会被改写）。
     */
    public List<EvidenceItemView> findEvidenceBySchedule(String scheduleId) {
        return jdbc.query(evidenceSelect() + " WHERE i.schedule_id = ? ORDER BY i.item_no",
                EVIDENCE_MAPPER, scheduleId);
    }

    /**
     * 查询某授权线所有版本支撑过的排期时段证据。
     */
    public List<EvidenceItemView> findEvidenceByGrant(long grantId) {
        return jdbc.query(evidenceSelect() + " WHERE v.grant_id = ? ORDER BY i.schedule_id, i.item_no",
                EVIDENCE_MAPPER, grantId);
    }

    private String evidenceSelect() {
        // 窗口与模式取时段行上的绑定快照（证据冻结）；仅 grant_id 与版本当前状态经 JOIN 展示。
        return """
                SELECT i.schedule_id, s.version_no AS schedule_version_no, i.item_no,
                       i.slot_start, i.slot_end, i.status, i.played_at,
                       i.bound_version_id, v.grant_id, i.bound_version_no,
                       i.bound_window_start AS grant_window_start, i.bound_window_end AS grant_window_end,
                       i.bound_mode AS grant_mode, v.status AS grant_status
                FROM play_schedule_item i
                INNER JOIN play_schedule s ON s.schedule_id = i.schedule_id
                LEFT JOIN rights_grant_version v ON v.id = i.bound_version_id
                """;
    }

    private String itemSelect() {
        return """
                SELECT i.item_no, i.asset_id, i.region, i.platform, i.slot_start, i.slot_end,
                       i.bound_version_id, i.bound_version_no, i.status, i.played_at
                FROM play_schedule_item i
                """;
    }
}
