package com.example.starter.repository;

import com.example.starter.model.AlertRow;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 逾期升级告警数据访问：repair_order_id 唯一，每张维修单仅告警一次。
 */
@Repository
public class AlertRepository {

    private static final RowMapper<AlertRow> ROW_MAPPER = (rs, n) -> new AlertRow(
            rs.getLong("id"),
            rs.getLong("repair_order_id"),
            rs.getLong("blade_id"),
            rs.getLong("defect_id"),
            rs.getObject("first_detected_at", LocalDateTime.class),
            rs.getObject("alert_at", LocalDateTime.class),
            rs.getString("message"));

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录升级告警，保留首次缺陷登记时刻，返回自增主键。
     */
    public long insert(long repairOrderId, long bladeId, long defectId,
                       LocalDateTime firstDetectedAt, LocalDateTime alertAt,
                       String message, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO alert_record(repair_order_id, blade_id, defect_id,"
                            + " first_detected_at, alert_at, message, created_at)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, repairOrderId);
            ps.setLong(2, bladeId);
            ps.setLong(3, defectId);
            ps.setTimestamp(4, Timestamp.valueOf(firstDetectedAt));
            ps.setTimestamp(5, Timestamp.valueOf(alertAt));
            ps.setString(6, message);
            ps.setTimestamp(7, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按叶片列出告警，顺序稳定。
     */
    public List<AlertRow> listByBlade(long bladeId) {
        return jdbc.query(
                "SELECT id, repair_order_id, blade_id, defect_id, first_detected_at, alert_at, message"
                        + " FROM alert_record WHERE blade_id = ? ORDER BY id",
                ROW_MAPPER, bladeId);
    }
}
