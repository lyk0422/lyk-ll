package com.example.starter.flood.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 降雨预警/处置链仓储。
 */
@Repository
public class WarningRepository {

    private final JdbcTemplate jdbcTemplate;

    public WarningRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static final RowMapper<WarningPO> MAPPER = new RowMapper<>() {
        @Override
        public WarningPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            WarningPO po = new WarningPO();
            po.id = rs.getLong("id");
            po.warningNo = rs.getString("warning_no");
            po.pondId = rs.getLong("pond_id");
            po.title = rs.getString("title");
            po.rainSummary = rs.getString("rain_summary");
            po.status = rs.getString("status");
            po.warningLevel = rs.getString("warning_level");
            po.requestId = rs.getString("request_id");
            po.openedAt = toLdt(rs.getTimestamp("opened_at"));
            po.archivedAt = toLdt(rs.getTimestamp("archived_at"));
            return po;
        }
    };

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public void insert(WarningPO po) {
        jdbcTemplate.update(
                "INSERT INTO warning_chain(warning_no, pond_id, title, rain_summary, status, "
                        + "warning_level, request_id, opened_at, archived_at) VALUES (?,?,?,?,?,?,?,?,?)",
                po.warningNo, po.pondId, po.title, po.rainSummary, po.status,
                po.warningLevel, po.requestId, Timestamp.valueOf(po.openedAt),
                po.archivedAt == null ? null : Timestamp.valueOf(po.archivedAt));
    }

    public WarningPO findByNo(String warningNo) {
        List<WarningPO> list = jdbcTemplate.query(
                "SELECT * FROM warning_chain WHERE warning_no = ?", MAPPER, warningNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public WarningPO findById(Long id) {
        List<WarningPO> list = jdbcTemplate.query(
                "SELECT * FROM warning_chain WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public WarningPO findByRequestId(String requestId) {
        List<WarningPO> list = jdbcTemplate.query(
                "SELECT * FROM warning_chain WHERE request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询某尾矿库全部已归档处置链（用于迟到读数判定）。
     */
    public List<WarningPO> findArchivedByPond(Long pondId) {
        return jdbcTemplate.query(
                "SELECT * FROM warning_chain WHERE pond_id = ? AND status = 'ARCHIVED'",
                MAPPER, pondId);
    }

    /**
     * 统计某尾矿库生效中处置链数量。
     */
    public int countActiveByPond(Long pondId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM warning_chain WHERE pond_id = ? AND status = 'ACTIVE'",
                Integer.class, pondId);
        return count == null ? 0 : count;
    }

    /**
     * 终态归档；仅 ACTIVE 行可被更新，返回受影响行数。
     */
    public int markArchived(Long id, LocalDateTime archivedAt) {
        return jdbcTemplate.update(
                "UPDATE warning_chain SET status = 'ARCHIVED', archived_at = ? WHERE id = ? "
                        + "AND status = 'ACTIVE'",
                Timestamp.valueOf(archivedAt), id);
    }

    /**
     * 提升预警级别（与滚动演算、方案追加同一事务）；仅生效中处置链可变更。
     */
    public int updateLevel(Long id, String newLevel) {
        return jdbcTemplate.update(
                "UPDATE warning_chain SET warning_level = ? WHERE id = ? AND status = 'ACTIVE'",
                newLevel, id);
    }

    /**
     * 登记处置链事件（级别提升等留痕）。
     */
    public void addEvent(Long warningId, String eventType, LocalDateTime eventTime, String detail) {
        jdbcTemplate.update(
                "INSERT INTO warning_event(warning_id, event_type, event_time, detail) "
                        + "VALUES (?,?,?,?)",
                warningId, eventType, Timestamp.valueOf(eventTime), detail);
    }

    /**
     * 查询处置链事件流（按时刻升序）。
     */
    public List<Map<String, Object>> findEvents(Long warningId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type, event_time, detail FROM warning_event "
                        + "WHERE warning_id = ? ORDER BY event_time, id", warningId);
    }
}
