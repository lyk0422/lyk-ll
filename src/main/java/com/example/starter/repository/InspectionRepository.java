package com.example.starter.repository;

import com.example.starter.domain.FieldInspection;
import com.example.starter.domain.LabInspection;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 田间检验（纯度）与室内检测（发芽率）记录数据访问。
 */
@Repository
public class InspectionRepository {

    private static final RowMapper<FieldInspection> FIELD_MAPPER = (rs, rowNum) -> new FieldInspection(
            rs.getLong("id"),
            rs.getLong("lot_id"),
            rs.getInt("round"),
            rs.getBigDecimal("purity"),
            rs.getBoolean("pass_original"),
            rs.getBoolean("pass_breeder"),
            rs.getBoolean("retestable"),
            rs.getString("inspector"),
            rs.getTimestamp("inspected_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<LabInspection> LAB_MAPPER = (rs, rowNum) -> new LabInspection(
            rs.getLong("id"),
            rs.getLong("lot_id"),
            rs.getInt("round"),
            rs.getBigDecimal("germination_rate"),
            rs.getBoolean("pass_original"),
            rs.getBoolean("pass_breeder"),
            rs.getBoolean("retestable"),
            rs.getString("inspector"),
            rs.getTimestamp("inspected_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public InspectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertField(FieldInspection inspection) {
        jdbcTemplate.update("""
                INSERT INTO field_inspection
                    (lot_id, round, purity, pass_original, pass_breeder, retestable,
                     inspector, inspected_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                inspection.lotId(), inspection.round(), inspection.purity(),
                inspection.passOriginal(), inspection.passBreeder(), inspection.retestable(),
                inspection.inspector(), Timestamp.from(inspection.inspectedAt()),
                Timestamp.from(inspection.createdAt()));
    }

    public List<FieldInspection> findFieldByLotId(long lotId) {
        return jdbcTemplate.query(
                "SELECT * FROM field_inspection WHERE lot_id = ? ORDER BY round",
                FIELD_MAPPER, lotId);
    }

    public FieldInspection findFieldByLotIdAndRound(long lotId, int round) {
        List<FieldInspection> list = jdbcTemplate.query(
                "SELECT * FROM field_inspection WHERE lot_id = ? AND round = ?",
                FIELD_MAPPER, lotId, round);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 加行锁读取指定轮次田间记录，用于复检与首检并发时确认唯一有效结论。 */
    public FieldInspection findFieldByLotIdAndRoundForUpdate(long lotId, int round) {
        List<FieldInspection> list = jdbcTemplate.query(
                "SELECT * FROM field_inspection WHERE lot_id = ? AND round = ? FOR UPDATE",
                FIELD_MAPPER, lotId, round);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insertLab(LabInspection inspection) {
        jdbcTemplate.update("""
                INSERT INTO lab_inspection
                    (lot_id, round, germination_rate, pass_original, pass_breeder, retestable,
                     inspector, inspected_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                inspection.lotId(), inspection.round(), inspection.germinationRate(),
                inspection.passOriginal(), inspection.passBreeder(), inspection.retestable(),
                inspection.inspector(), Timestamp.from(inspection.inspectedAt()),
                Timestamp.from(inspection.createdAt()));
    }

    public List<LabInspection> findLabByLotId(long lotId) {
        return jdbcTemplate.query(
                "SELECT * FROM lab_inspection WHERE lot_id = ? ORDER BY round",
                LAB_MAPPER, lotId);
    }

    public LabInspection findLabByLotIdAndRound(long lotId, int round) {
        List<LabInspection> list = jdbcTemplate.query(
                "SELECT * FROM lab_inspection WHERE lot_id = ? AND round = ?",
                LAB_MAPPER, lotId, round);
        return list.isEmpty() ? null : list.get(0);
    }

    public LabInspection findLabByLotIdAndRoundForUpdate(long lotId, int round) {
        List<LabInspection> list = jdbcTemplate.query(
                "SELECT * FROM lab_inspection WHERE lot_id = ? AND round = ? FOR UPDATE",
                LAB_MAPPER, lotId, round);
        return list.isEmpty() ? null : list.get(0);
    }
}
