package com.example.starter.repository;

import com.example.starter.model.RepairOrderRow;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 维修单数据访问：active_blade_id 唯一索引保证同叶片同时刻仅一张进行中维修单。
 */
@Repository
public class RepairOrderRepository {

    private static final String COLUMNS =
            "id, defect_id, blade_id, status, deadline_at, closed_at, close_result, escalated";

    private static final RowMapper<RepairOrderRow> ROW_MAPPER = (rs, n) -> new RepairOrderRow(
            rs.getLong("id"),
            rs.getLong("defect_id"),
            rs.getLong("blade_id"),
            rs.getString("status"),
            rs.getObject("deadline_at", LocalDateTime.class),
            rs.getObject("closed_at", LocalDateTime.class),
            rs.getString("close_result"),
            rs.getBoolean("escalated"));

    private final JdbcTemplate jdbc;

    public RepairOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建进行中维修单；active_blade_id 唯一冲突时抛出 DuplicateKeyException。
     */
    public long insert(long defectId, long bladeId, LocalDateTime deadlineAt, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO repair_order(defect_id, blade_id, status, deadline_at, escalated, active_blade_id, created_at)"
                            + " VALUES (?,?,'IN_PROGRESS',?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, defectId);
            ps.setLong(2, bladeId);
            ps.setTimestamp(3, Timestamp.valueOf(deadlineAt));
            ps.setBoolean(4, false);
            ps.setLong(5, bladeId);
            ps.setTimestamp(6, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询维修单。
     */
    public Optional<RepairOrderRow> findById(long id) {
        List<RepairOrderRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM repair_order WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询并加行锁（复检闭环同事务内使用）。
     */
    public Optional<RepairOrderRow> findByIdForUpdate(long id) {
        List<RepairOrderRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM repair_order WHERE id = ? FOR UPDATE", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按缺陷查询维修单（一缺陷一单）。
     */
    public Optional<RepairOrderRow> findByDefectId(long defectId) {
        List<RepairOrderRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM repair_order WHERE defect_id = ?", ROW_MAPPER, defectId);
        return rows.stream().findFirst();
    }

    /**
     * 结案：置结案时刻与闭环结果，并释放进行中占用。
     */
    public void close(long id, LocalDateTime closedAt, String closeResult) {
        jdbc.update(
                "UPDATE repair_order SET status = 'CLOSED', closed_at = ?, close_result = ?,"
                        + " active_blade_id = NULL WHERE id = ?",
                Timestamp.valueOf(closedAt), closeResult, id);
    }

    /**
     * 查询逾期未升级告警的进行中维修单（now 严格大于期限才算逾期，恰第 7 日仍有效）。
     */
    public List<RepairOrderRow> findOverdueInProgress(LocalDateTime now) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM repair_order"
                        + " WHERE status = 'IN_PROGRESS' AND escalated = FALSE AND deadline_at < ?"
                        + " ORDER BY id",
                ROW_MAPPER, Timestamp.valueOf(now));
    }

    /**
     * 标记维修单已逾期升级。
     */
    public void markEscalated(long id) {
        jdbc.update("UPDATE repair_order SET escalated = TRUE WHERE id = ?", id);
    }

    /**
     * 维修台账：按叶片列出全部维修单，顺序稳定。
     */
    public List<RepairOrderRow> listByBlade(long bladeId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM repair_order WHERE blade_id = ? ORDER BY id",
                ROW_MAPPER, bladeId);
    }
}
