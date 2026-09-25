package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.BerthWindow;
import com.example.starter.domain.WindowStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 靠泊窗口数据访问：全部使用参数化 SQL；时间以 UTC epoch 秒存取。
 */
@Repository
public class WindowRepository {

    private static final RowMapper<BerthWindow> WINDOW_MAPPER = new RowMapper<>() {
        @Override
        public BerthWindow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new BerthWindow(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("fingerprint"),
                    rs.getString("vessel_name"),
                    rs.getLong("berth_id"),
                    Instant.ofEpochSecond(rs.getLong("start_epoch")),
                    Instant.ofEpochSecond(rs.getLong("end_epoch")),
                    rs.getDouble("draft_m"),
                    rs.getInt("pilots_required"),
                    WindowStatus.valueOf(rs.getString("status")),
                    List.of(),
                    Instant.ofEpochSecond(rs.getLong("created_epoch")),
                    Instant.ofEpochSecond(rs.getLong("updated_epoch")));
        }
    };

    private final JdbcTemplate jdbc;

    public WindowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询窗口（不含引航员分配）。
     */
    public Optional<BerthWindow> findById(long id) {
        List<BerthWindow> rows = jdbc.query(
                "SELECT * FROM berth_window WHERE id = ?", WINDOW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按幂等键查询窗口。
     */
    public Optional<BerthWindow> findByRequestId(String requestId) {
        List<BerthWindow> rows = jdbc.query(
                "SELECT * FROM berth_window WHERE request_id = ?", WINDOW_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 新增待审批窗口，返回生成主键。
     */
    public long insert(BerthWindow w) {
        jdbc.update(
                "INSERT INTO berth_window (request_id, fingerprint, vessel_name, berth_id,"
                        + " start_epoch, end_epoch, draft_m, pilots_required, status,"
                        + " created_epoch, updated_epoch)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                w.requestId(), w.fingerprint(), w.vesselName(), w.berthId(),
                w.startAt().getEpochSecond(), w.endAt().getEpochSecond(),
                w.draftM(), w.pilotsRequired(), w.status().name(),
                w.createdAt().getEpochSecond(), w.updatedAt().getEpochSecond());
        Long id = jdbc.queryForObject("SELECT id FROM berth_window WHERE request_id = ?",
                Long.class, w.requestId());
        return id == null ? -1L : id;
    }

    /**
     * 更新窗口状态与变更时间。
     */
    public void updateStatus(long id, WindowStatus status, Instant updatedAt) {
        jdbc.update("UPDATE berth_window SET status = ?, updated_epoch = ? WHERE id = ?",
                status.name(), updatedAt.getEpochSecond(), id);
    }

    /**
     * 改期：更新起止时刻、引航员需求不变，记录变更时间。
     */
    public void updateSchedule(long id, Instant startAt, Instant endAt, Instant updatedAt) {
        jdbc.update(
                "UPDATE berth_window SET start_epoch = ?, end_epoch = ?, updated_epoch = ? WHERE id = ?",
                startAt.getEpochSecond(), endAt.getEpochSecond(), updatedAt.getEpochSecond(), id);
    }

    /**
     * 查询泊位上与指定半开区间存在内部重叠、且状态为已批准/已生效的窗口。
     * 半开区间重叠判定：a.start < b.end 且 b.start < a.end；端点相接不算重叠。
     *
     * @param excludeId 需要排除的窗口（改期时排除自身），传 0 表示不排除
     */
    public List<BerthWindow> findActiveOverlaps(long berthId, Instant start, Instant end, long excludeId) {
        return jdbc.query(
                "SELECT * FROM berth_window"
                        + " WHERE berth_id = ? AND status IN ('APPROVED', 'ACTIVE')"
                        + " AND start_epoch < ? AND end_epoch > ? AND id <> ?",
                WINDOW_MAPPER,
                berthId, end.getEpochSecond(), start.getEpochSecond(), excludeId);
    }

    /**
     * 对泊位行加写锁（SELECT ... FOR UPDATE），用于串行化同一泊位的冲突校验。
     */
    public void lockBerth(long berthId) {
        jdbc.queryForObject("SELECT id FROM berth WHERE id = ? FOR UPDATE", Long.class, berthId);
    }

    /**
     * 查询泊位是否存在及其限深（米）。
     */
    public Optional<Double> findBerthMaxDraft(long berthId) {
        List<Double> rows = jdbc.queryForList(
                "SELECT max_draft_m FROM berth WHERE id = ?", Double.class, berthId);
        return rows.stream().findFirst();
    }

    /**
     * 判断是否存在完整覆盖 [start, end] 的允许靠泊时段（端点恰等于边界合法）。
     */
    public boolean isWithinAllowedWindow(Instant start, Instant end) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allowed_window WHERE start_epoch <= ? AND end_epoch >= ?",
                Integer.class, start.getEpochSecond(), end.getEpochSecond());
        return count != null && count > 0;
    }

    /**
     * 对全部引航员行加写锁，用于串行化引航员分配。
     */
    public void lockPilots() {
        jdbc.queryForList("SELECT id FROM pilot ORDER BY id FOR UPDATE", Long.class);
    }

    /**
     * 查询指定时段内空闲的引航员主键（未被其他已批准/已生效窗口占用），按主键升序。
     *
     * @param excludeWindowId 需要排除的窗口（改期时排除自身），传 0 表示不排除
     */
    public List<Long> findAvailablePilotIds(Instant start, Instant end, long excludeWindowId) {
        return jdbc.queryForList(
                "SELECT p.id FROM pilot p WHERE NOT EXISTS ("
                        + " SELECT 1 FROM window_pilot wp"
                        + " JOIN berth_window w ON w.id = wp.window_id"
                        + " WHERE wp.pilot_id = p.id"
                        + " AND w.status IN ('APPROVED', 'ACTIVE')"
                        + " AND w.start_epoch < ? AND w.end_epoch > ?"
                        + " AND w.id <> ?)"
                        + " ORDER BY p.id",
                Long.class, end.getEpochSecond(), start.getEpochSecond(), excludeWindowId);
    }

    /**
     * 为窗口分配引航员（先清空旧分配）。
     */
    public void replacePilotAssignments(long windowId, List<Long> pilotIds) {
        jdbc.update("DELETE FROM window_pilot WHERE window_id = ?", windowId);
        for (Long pilotId : pilotIds) {
            jdbc.update("INSERT INTO window_pilot (window_id, pilot_id) VALUES (?, ?)",
                    windowId, pilotId);
        }
    }

    /**
     * 释放窗口的全部引航员分配。
     */
    public void clearPilotAssignments(long windowId) {
        jdbc.update("DELETE FROM window_pilot WHERE window_id = ?", windowId);
    }

    /**
     * 查询窗口已分配的引航员主键。
     */
    public List<Long> findAssignedPilotIds(long windowId) {
        return jdbc.queryForList(
                "SELECT pilot_id FROM window_pilot WHERE window_id = ? ORDER BY pilot_id",
                Long.class, windowId);
    }
}
