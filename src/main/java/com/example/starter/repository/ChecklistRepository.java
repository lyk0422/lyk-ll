package com.example.starter.repository;

import com.example.starter.model.DocumentItemRow;
import com.example.starter.model.InspectionItemRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 资料核对表与检验链数据访问（document_item、inspection_item）。
 */
@Repository
public class ChecklistRepository {

    private final JdbcTemplate jdbc;

    public ChecklistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 初始化资料核对表：按合成目录全量建行，已提交项标记提交时间。
     */
    public void insertDocumentItems(long processId, String code, String name, boolean required,
                                    boolean submitted, Instant submittedAt) {
        jdbc.update("INSERT INTO document_item (process_id, item_code, item_name, required_flag, submitted_flag, submitted_at) "
                        + "VALUES (?,?,?,?,?,?)",
                processId, code, name, required ? 1 : 0, submitted ? 1 : 0,
                submittedAt == null ? null : Timestamp.from(submittedAt));
    }

    /**
     * 查询资料核对表（按目录项编码排序，可复算缺项）。
     */
    public List<DocumentItemRow> findDocumentItems(long processId) {
        return jdbc.query("SELECT item_code, item_name, required_flag, submitted_flag, submitted_at "
                        + "FROM document_item WHERE process_id = ? ORDER BY item_code",
                (rs, rowNum) -> new DocumentItemRow(
                        rs.getString("item_code"),
                        rs.getString("item_name"),
                        rs.getInt("required_flag") == 1,
                        rs.getInt("submitted_flag") == 1,
                        rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant()),
                processId);
    }

    /**
     * 补正提交：标记目录项已提交（仅未提交项生效）。
     */
    public void markDocumentSubmitted(long processId, String itemCode, Instant submittedAt) {
        jdbc.update("UPDATE document_item SET submitted_flag = 1, submitted_at = ? "
                        + "WHERE process_id = ? AND item_code = ? AND submitted_flag = 0",
                Timestamp.from(submittedAt), processId, itemCode);
    }

    /**
     * 写入检验项目，合格判定按“测定值小于等于限值”落库，可复算。
     */
    public void insertInspectionItem(long processId, String code, String name, boolean critical,
                                     BigDecimal limitValue, BigDecimal measuredValue, boolean qualified) {
        jdbc.update("INSERT INTO inspection_item (process_id, item_code, item_name, critical_flag, limit_value, measured_value, qualified_flag) "
                        + "VALUES (?,?,?,?,?,?,?)",
                processId, code, name, critical ? 1 : 0, limitValue, measuredValue, qualified ? 1 : 0);
    }

    /**
     * 查询检验链项目（按编码排序，合格判定可复算）。
     */
    public List<InspectionItemRow> findInspectionItems(long processId) {
        return jdbc.query("SELECT item_code, item_name, critical_flag, limit_value, measured_value, qualified_flag "
                        + "FROM inspection_item WHERE process_id = ? ORDER BY item_code",
                (rs, rowNum) -> new InspectionItemRow(
                        rs.getString("item_code"),
                        rs.getString("item_name"),
                        rs.getInt("critical_flag") == 1,
                        rs.getBigDecimal("limit_value"),
                        rs.getBigDecimal("measured_value"),
                        rs.getInt("qualified_flag") == 1),
                processId);
    }
}
