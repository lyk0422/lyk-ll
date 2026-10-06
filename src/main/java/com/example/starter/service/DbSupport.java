package com.example.starter.service;

import java.util.Optional;

import com.example.starter.domain.CurtailmentReason;
import com.example.starter.domain.DefectLevel;
import com.example.starter.domain.DefectStatus;
import com.example.starter.domain.EvaluationStatus;
import com.example.starter.domain.RepairOrderStatus;
import com.example.starter.domain.Rows.CurtailmentRow;
import com.example.starter.domain.Rows.DefectRow;
import com.example.starter.domain.Rows.EvaluationRow;
import com.example.starter.domain.Rows.RepairOrderRow;
import com.example.starter.error.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 共享的数据库行映射与基础查询，供各业务服务复用。
 */
@Component
public class DbSupport {

    private final JdbcClient jdbc;

    public DbSupport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public void requireBlade(long bladeId) {
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM blade WHERE id = ?")
                .param(bladeId)
                .query(Long.class)
                .single() > 0;
        if (!exists) {
            throw new NotFoundException("叶片不存在: " + bladeId);
        }
    }

    public DefectRow requireDefect(long defectId) {
        return findDefect(defectId)
                .orElseThrow(() -> new NotFoundException("缺陷不存在: " + defectId));
    }

    public Optional<DefectRow> findDefect(long defectId) {
        return jdbc.sql("""
                        SELECT id, inspection_id, blade_id, position, defect_type, length_mm, depth_ratio,
                               level, status, registered_at, closed_at
                        FROM defect WHERE id = ?
                        """)
                .param(defectId)
                .query((rs, rowNum) -> new DefectRow(
                        rs.getLong("id"),
                        rs.getObject("inspection_id", Long.class),
                        rs.getLong("blade_id"),
                        rs.getString("position"),
                        rs.getString("defect_type"),
                        rs.getBigDecimal("length_mm"),
                        rs.getBigDecimal("depth_ratio"),
                        DefectLevel.valueOf(rs.getString("level")),
                        DefectStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("registered_at").toLocalDateTime(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toLocalDateTime()))
                .optional();
    }

    public RepairOrderRow requireOrder(long orderId) {
        return findOrder(orderId)
                .orElseThrow(() -> new NotFoundException("维修单不存在: " + orderId));
    }

    public Optional<RepairOrderRow> findOrder(long orderId) {
        return jdbc.sql("""
                        SELECT id, defect_id, blade_id, status, deadline_at, created_at, closed_at
                        FROM repair_order WHERE id = ?
                        """)
                .param(orderId)
                .query((rs, rowNum) -> new RepairOrderRow(
                        rs.getLong("id"),
                        rs.getLong("defect_id"),
                        rs.getLong("blade_id"),
                        RepairOrderStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("deadline_at").toLocalDateTime(),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toLocalDateTime()))
                .optional();
    }

    public EvaluationRow requireEvaluation(long evaluationId) {
        return jdbc.sql("""
                        SELECT id, blade_id, defect_type, trigger_defect_id, status, triggered_at, completed_at, conclusion
                        FROM root_cause_evaluation WHERE id = ?
                        """)
                .param(evaluationId)
                .query((rs, rowNum) -> new EvaluationRow(
                        rs.getLong("id"),
                        rs.getLong("blade_id"),
                        rs.getString("defect_type"),
                        rs.getLong("trigger_defect_id"),
                        EvaluationStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("triggered_at").toLocalDateTime(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime(),
                        rs.getString("conclusion")))
                .optional()
                .orElseThrow(() -> new NotFoundException("根因评估不存在: " + evaluationId));
    }

    public CurtailmentRow mapCurtailment(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CurtailmentRow(
                rs.getLong("id"),
                rs.getLong("blade_id"),
                CurtailmentReason.valueOf(rs.getString("reason")),
                rs.getObject("source_defect_id", Long.class),
                rs.getObject("source_evaluation_id", Long.class),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("lifted_at") == null ? null : rs.getTimestamp("lifted_at").toLocalDateTime());
    }
}
