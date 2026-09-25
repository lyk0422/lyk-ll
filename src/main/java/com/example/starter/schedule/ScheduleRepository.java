package com.example.starter.schedule;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 排产数据访问层：参数化 SQL，H2 MySQL 兼容模式。
 */
@Repository
public class ScheduleRepository {

    /** 排产单行记录。 */
    public record ScheduleRow(
            long id,
            String requestId,
            String fingerprint,
            long lineId,
            String unitNo,
            int version,
            ScheduleStatus status,
            boolean effective,
            boolean locked,
            boolean rollChangeRequired,
            BigDecimal actualMileageKm) {
    }

    private static final RowMapper<ScheduleRow> SCHEDULE_MAPPER = (rs, n) -> new ScheduleRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getLong("line_id"),
            rs.getString("unit_no"),
            rs.getInt("version"),
            ScheduleStatus.valueOf(rs.getString("status")),
            rs.getInt("effective") == 1,
            rs.getInt("locked") == 1,
            rs.getInt("roll_change_required") == 1,
            rs.getBigDecimal("actual_mileage_km"));

    private final JdbcTemplate jdbc;

    public ScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按产线编码查产线主键。 */
    public Optional<Long> findLineId(String lineCode) {
        try {
            Long id = jdbc.queryForObject(
                    "SELECT id FROM production_line WHERE line_code = ?", Long.class, lineCode);
            return Optional.ofNullable(id);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 按主键查产线编码。 */
    public String findLineCode(long lineId) {
        return jdbc.queryForObject(
                "SELECT line_code FROM production_line WHERE id = ?", String.class, lineId);
    }

    /** 按主键查排产单。 */
    public Optional<ScheduleRow> findSchedule(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM schedule WHERE id = ?", SCHEDULE_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 排产单是否存在指定产线单元（用于编制时判重）。 */
    public boolean unitExists(long lineId, String unitNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule WHERE line_id = ? AND unit_no = ?",
                Integer.class, lineId, unitNo);
        return count != null && count > 0;
    }

    /** 单元当前最大版本号，无记录返回 0。 */
    public int maxVersion(long lineId, String unitNo) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM schedule WHERE line_id = ? AND unit_no = ?",
                Integer.class, lineId, unitNo);
        return v == null ? 0 : v;
    }

    /** 插入排产单，返回自增主键。 */
    public long insertSchedule(String requestId, String fingerprint, long lineId, String unitNo,
                               int version, ScheduleStatus status) {
        KeyHolder keys = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schedule (request_id, fingerprint, line_id, unit_no, version, status,"
                            + " effective, locked, roll_change_required, actual_mileage_km, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, lineId);
            ps.setString(4, unitNo);
            ps.setInt(5, version);
            ps.setString(6, status.name());
            ps.setTimestamp(7, Timestamp.valueOf(now));
            ps.setTimestamp(8, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 批量插入轧制明细。 */
    public void insertItems(long scheduleId, List<ScheduleRequests.ItemSpec> items) {
        jdbc.batchUpdate(
                "INSERT INTO schedule_item (schedule_id, seq, contract_no, width_mm, mileage_km, warmup,"
                        + " planned_start, planned_end) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                items, items.size(), (ps, item) -> {
                    ps.setLong(1, scheduleId);
                    ps.setInt(2, item.seq());
                    ps.setString(3, item.contractNo());
                    ps.setInt(4, item.widthMm());
                    ps.setBigDecimal(5, item.mileageKm());
                    ps.setInt(6, item.warmup() ? 1 : 0);
                    ps.setTimestamp(7, Timestamp.valueOf(item.plannedStart()));
                    ps.setTimestamp(8, Timestamp.valueOf(item.plannedEnd()));
                });
    }

    /** 批量插入换辊窗口。 */
    public void insertWindows(long scheduleId, List<ScheduleRequests.RollWindowSpec> windows) {
        jdbc.batchUpdate(
                "INSERT INTO roll_change_window (schedule_id, window_start, window_end) VALUES (?, ?, ?)",
                windows, windows.size(), (ps, w) -> {
                    ps.setLong(1, scheduleId);
                    ps.setTimestamp(2, Timestamp.valueOf(w.start()));
                    ps.setTimestamp(3, Timestamp.valueOf(w.end()));
                });
    }

    /** 查询排产明细（按轧制顺序）。 */
    public List<ScheduleResponses.ItemView> findItems(long scheduleId) {
        return jdbc.query(
                "SELECT seq, contract_no, width_mm, mileage_km, warmup, planned_start, planned_end"
                        + " FROM schedule_item WHERE schedule_id = ? ORDER BY seq",
                (rs, n) -> new ScheduleResponses.ItemView(
                        rs.getInt("seq"),
                        rs.getString("contract_no"),
                        rs.getInt("width_mm"),
                        rs.getBigDecimal("mileage_km"),
                        rs.getInt("warmup") == 1,
                        rs.getTimestamp("planned_start").toLocalDateTime(),
                        rs.getTimestamp("planned_end").toLocalDateTime()),
                scheduleId);
    }

    /** 查询换辊窗口。 */
    public List<ScheduleResponses.WindowView> findWindows(long scheduleId) {
        return jdbc.query(
                "SELECT window_start, window_end FROM roll_change_window WHERE schedule_id = ?"
                        + " ORDER BY window_start",
                (rs, n) -> new ScheduleResponses.WindowView(
                        rs.getTimestamp("window_start").toLocalDateTime(),
                        rs.getTimestamp("window_end").toLocalDateTime()),
                scheduleId);
    }

    /** 更新排产状态。 */
    public void updateStatus(long scheduleId, ScheduleStatus status) {
        jdbc.update("UPDATE schedule SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.valueOf(LocalDateTime.now()), scheduleId);
    }

    /** 更新生效标记。 */
    public void updateEffective(long scheduleId, boolean effective) {
        jdbc.update("UPDATE schedule SET effective = ?, updated_at = ? WHERE id = ?",
                effective ? 1 : 0, Timestamp.valueOf(LocalDateTime.now()), scheduleId);
    }

    /** 锁定/解锁并设置换辊提示。 */
    public void updateLock(long scheduleId, boolean locked, boolean rollChangeRequired) {
        jdbc.update(
                "UPDATE schedule SET locked = ?, roll_change_required = ?, updated_at = ? WHERE id = ?",
                locked ? 1 : 0, rollChangeRequired ? 1 : 0,
                Timestamp.valueOf(LocalDateTime.now()), scheduleId);
    }

    /** 更新本周期实绩累计里程（公里）。 */
    public void updateActualMileage(long scheduleId, BigDecimal mileageKm) {
        jdbc.update("UPDATE schedule SET actual_mileage_km = ?, updated_at = ? WHERE id = ?",
                mileageKm, Timestamp.valueOf(LocalDateTime.now()), scheduleId);
    }

    /** 查询产线当前生效排产（行锁由调用方事务保证）。 */
    public Optional<ScheduleRow> findEffectiveSchedule(long lineId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT s.* FROM schedule s JOIN line_effective_schedule e ON e.schedule_id = s.id"
                            + " WHERE e.line_id = ?",
                    SCHEDULE_MAPPER, lineId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 写入产线生效排产（主键冲突即并发/违例，由调用方捕获）。 */
    public void insertEffectiveSchedule(long lineId, long scheduleId) {
        jdbc.update("INSERT INTO line_effective_schedule (line_id, schedule_id, updated_at) VALUES (?, ?, ?)",
                lineId, scheduleId, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 替换产线生效排产。 */
    public void replaceEffectiveSchedule(long lineId, long scheduleId) {
        jdbc.update("UPDATE line_effective_schedule SET schedule_id = ?, updated_at = ? WHERE line_id = ?",
                scheduleId, Timestamp.valueOf(LocalDateTime.now()), lineId);
    }

    /** 清除产线生效排产（日冻结时调用）。 */
    public void deleteEffectiveSchedule(long lineId) {
        jdbc.update("DELETE FROM line_effective_schedule WHERE line_id = ?", lineId);
    }

    /** 实绩是否已存在（合同号+道次去重）。 */
    public boolean actualExists(long scheduleId, String contractNo, int passNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM actual_record WHERE schedule_id = ? AND contract_no = ? AND pass_no = ?",
                Integer.class, scheduleId, contractNo, passNo);
        return count != null && count > 0;
    }

    /** 插入实绩记录。 */
    public void insertActual(long scheduleId, ScheduleRequests.ActualItem item) {
        jdbc.update(
                "INSERT INTO actual_record (schedule_id, contract_no, pass_no, mileage_km, occurred_at, recorded_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                scheduleId, item.contractNo(), item.passNo(), item.mileageKm(),
                Timestamp.valueOf(item.occurredAt()), Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 记录迟到实绩事件（冻结后）。 */
    public void insertLateEvent(long scheduleId, ScheduleRequests.ActualItem item) {
        jdbc.update(
                "INSERT INTO late_event (schedule_id, contract_no, pass_no, mileage_km, occurred_at, recorded_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                scheduleId, item.contractNo(), item.passNo(), item.mileageKm(),
                Timestamp.valueOf(item.occurredAt()), Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 统计迟到事件数（测试与排查用）。 */
    public int countLateEvents(long scheduleId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE schedule_id = ?", Integer.class, scheduleId);
        return count == null ? 0 : count;
    }

    /** 产线行排他锁（并发下达串行化），须在事务内调用。 */
    public void lockLine(long lineId) {
        jdbc.queryForObject("SELECT id FROM production_line WHERE id = ? FOR UPDATE",
                Long.class, lineId);
    }

    /** 排产单行排他锁（实绩回传/冻结串行化），须在事务内调用。 */
    public void lockSchedule(long scheduleId) {
        jdbc.queryForObject("SELECT id FROM schedule WHERE id = ? FOR UPDATE",
                Long.class, scheduleId);
    }
}
