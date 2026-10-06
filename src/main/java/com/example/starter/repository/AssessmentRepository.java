package com.example.starter.repository;

import com.example.starter.model.AssessmentRow;
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
 * 根因评估数据访问：active_key 唯一索引防止同叶片同类缺陷重复评估。
 */
@Repository
public class AssessmentRepository {

    private static final String COLUMNS =
            "id, blade_id, defect_type, trigger_count, status, started_at, completed_at";

    private static final RowMapper<AssessmentRow> ROW_MAPPER = (rs, n) -> new AssessmentRow(
            rs.getLong("id"),
            rs.getLong("blade_id"),
            rs.getString("defect_type"),
            rs.getInt("trigger_count"),
            rs.getString("status"),
            rs.getObject("started_at", LocalDateTime.class),
            rs.getObject("completed_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public AssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建评估（状态 IN_PROGRESS），返回自增主键。
     */
    public long insert(long bladeId, String defectType, int triggerCount,
                       LocalDateTime windowStart, LocalDateTime windowEnd,
                       LocalDateTime startedAt, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO root_cause_assessment(blade_id, defect_type, trigger_count,"
                            + " window_start, window_end, status, started_at, active_key, created_at)"
                            + " VALUES (?,?,?,?,?,'IN_PROGRESS',?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, bladeId);
            ps.setString(2, defectType);
            ps.setInt(3, triggerCount);
            ps.setTimestamp(4, Timestamp.valueOf(windowStart));
            ps.setTimestamp(5, Timestamp.valueOf(windowEnd));
            ps.setTimestamp(6, Timestamp.valueOf(startedAt));
            ps.setString(7, bladeId + ":" + defectType);
            ps.setTimestamp(8, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询评估。
     */
    public Optional<AssessmentRow> findById(long id) {
        List<AssessmentRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM root_cause_assessment WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 查询同叶片同类缺陷的进行中的评估。
     */
    public Optional<AssessmentRow> findActiveByBladeAndType(long bladeId, String defectType) {
        List<AssessmentRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM root_cause_assessment"
                        + " WHERE blade_id = ? AND defect_type = ? AND status = 'IN_PROGRESS'",
                ROW_MAPPER, bladeId, defectType);
        return rows.stream().findFirst();
    }

    /**
     * 叶片是否存在进行中的评估。
     */
    public boolean existsActiveByBlade(long bladeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM root_cause_assessment"
                        + " WHERE blade_id = ? AND status = 'IN_PROGRESS'",
                Integer.class, bladeId);
        return count != null && count > 0;
    }

    /**
     * 完成评估：置完成时刻并释放占用键。
     */
    public void complete(long id, LocalDateTime completedAt) {
        jdbc.update(
                "UPDATE root_cause_assessment SET status = 'COMPLETED', completed_at = ?,"
                        + " active_key = NULL WHERE id = ?",
                Timestamp.valueOf(completedAt), id);
    }

    /**
     * 按叶片列出全部评估，顺序稳定。
     */
    public List<AssessmentRow> listByBlade(long bladeId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM root_cause_assessment WHERE blade_id = ? ORDER BY id",
                ROW_MAPPER, bladeId);
    }
}
