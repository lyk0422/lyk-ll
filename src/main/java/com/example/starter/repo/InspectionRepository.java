package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.FieldInspectionRecord;
import com.example.starter.domain.InspectionVerdict;
import com.example.starter.domain.LabInspectionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 田间检验与室内检测记录仓储：
 * 首检与复检按 (lot_no, round) 唯一；复检生效时旧结论失效但记录保留可查。
 */
@Repository
public class InspectionRepository {

    private static final FieldRowMapper FIELD_MAPPER = new FieldRowMapper();
    private static final LabRowMapper LAB_MAPPER = new LabRowMapper();

    private final JdbcTemplate jdbc;

    public InspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增田间检验记录。 */
    public void insertField(FieldInspectionRecord record) {
        jdbc.update("""
                INSERT INTO field_inspection
                  (lot_no, request_id, purity, result, inspect_round, effective,
                   inspector, inspected_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.lotNo(), record.requestId(), record.purity(), record.verdict().name(),
                record.round(), record.effective(), record.inspector(),
                Timestamp.from(record.inspectedAt()), Timestamp.from(record.createdAt()));
    }

    /** 新增室内检测记录。 */
    public void insertLab(LabInspectionRecord record) {
        jdbc.update("""
                INSERT INTO lab_inspection
                  (lot_no, request_id, germination_rate, result, inspect_round, effective,
                   inspector, inspected_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.lotNo(), record.requestId(), record.germinationRate(), record.verdict().name(),
                record.round(), record.effective(), record.inspector(),
                Timestamp.from(record.inspectedAt()), Timestamp.from(record.createdAt()));
    }

    /** 将该批次既有田间检验结论全部置为失效（复检覆盖首检）。 */
    public int invalidateField(String lotNo) {
        return jdbc.update("UPDATE field_inspection SET effective = FALSE WHERE lot_no = ?", lotNo);
    }

    /** 将该批次既有室内检测结论全部置为失效（复检覆盖首检）。 */
    public int invalidateLab(String lotNo) {
        return jdbc.update("UPDATE lab_inspection SET effective = FALSE WHERE lot_no = ?", lotNo);
    }

    /** 查询当前有效田间检验结论。 */
    public Optional<FieldInspectionRecord> findEffectiveField(String lotNo) {
        return jdbc.query(
                "SELECT * FROM field_inspection WHERE lot_no = ? AND effective = TRUE",
                FIELD_MAPPER, lotNo).stream().findFirst();
    }

    /** 查询当前有效室内检测结论。 */
    public Optional<LabInspectionRecord> findEffectiveLab(String lotNo) {
        return jdbc.query(
                "SELECT * FROM lab_inspection WHERE lot_no = ? AND effective = TRUE",
                LAB_MAPPER, lotNo).stream().findFirst();
    }

    /** 按轮次查询田间检验记录（首检记录复检后仍保留可查）。 */
    public Optional<FieldInspectionRecord> findFieldByRound(String lotNo, int round) {
        return jdbc.query(
                "SELECT * FROM field_inspection WHERE lot_no = ? AND inspect_round = ?",
                FIELD_MAPPER, lotNo, round).stream().findFirst();
    }

    /** 按轮次查询室内检测记录（首检记录复检后仍保留可查）。 */
    public Optional<LabInspectionRecord> findLabByRound(String lotNo, int round) {
        return jdbc.query(
                "SELECT * FROM lab_inspection WHERE lot_no = ? AND inspect_round = ?",
                LAB_MAPPER, lotNo, round).stream().findFirst();
    }

    /** 查询批次全部田间检验记录，按轮次升序。 */
    public List<FieldInspectionRecord> findAllField(String lotNo) {
        return jdbc.query(
                "SELECT * FROM field_inspection WHERE lot_no = ? ORDER BY inspect_round",
                FIELD_MAPPER, lotNo);
    }

    /** 查询批次全部室内检测记录，按轮次升序。 */
    public List<LabInspectionRecord> findAllLab(String lotNo) {
        return jdbc.query(
                "SELECT * FROM lab_inspection WHERE lot_no = ? ORDER BY inspect_round",
                LAB_MAPPER, lotNo);
    }

    private static final class FieldRowMapper implements RowMapper<FieldInspectionRecord> {
        @Override
        public FieldInspectionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new FieldInspectionRecord(
                    rs.getLong("id"),
                    rs.getString("lot_no"),
                    rs.getString("request_id"),
                    rs.getBigDecimal("purity"),
                    InspectionVerdict.valueOf(rs.getString("result")),
                    rs.getInt("inspect_round"),
                    rs.getBoolean("effective"),
                    false,
                    rs.getString("inspector"),
                    rs.getTimestamp("inspected_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant());
        }
    }

    private static final class LabRowMapper implements RowMapper<LabInspectionRecord> {
        @Override
        public LabInspectionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LabInspectionRecord(
                    rs.getLong("id"),
                    rs.getString("lot_no"),
                    rs.getString("request_id"),
                    rs.getBigDecimal("germination_rate"),
                    InspectionVerdict.valueOf(rs.getString("result")),
                    rs.getInt("inspect_round"),
                    rs.getBoolean("effective"),
                    false,
                    rs.getString("inspector"),
                    rs.getTimestamp("inspected_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
