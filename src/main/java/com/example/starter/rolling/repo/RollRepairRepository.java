package com.example.starter.rolling.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 返修放行流程访问层：返修申请、两名检验员确认，以及成功后继裁决。
 */
@Repository
public class RollRepairRepository {

    private final JdbcTemplate jdbc;

    public RollRepairRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 返修申请行。status 为 PENDING/APPROVED/REJECTED；successorRollNo 为成功建立的下一代辊号。
     */
    public record RepairRequestRow(long id, long physicalId, String oldRollNo, String newRollNo,
                                   BigDecimal groundDiameterMm, BigDecimal newCycleLimitKm,
                                   String inspectionCert, int expectedVersion, String status,
                                   String successorRollNo, String requestId, String fingerprint,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 返修检验确认行。同一申请 (requestIdPk, inspectorId) 唯一。 */
    public record InspectionRow(long id, long requestIdPk, String inspectorId, boolean approved,
                                String certNo, LocalDateTime inspectedAt, LocalDateTime createdAt) {
    }

    private static final RowMapper<RepairRequestRow> REPAIR_MAPPER = (rs, n) ->
            new RepairRequestRow(
                    rs.getLong("id"), rs.getLong("physical_id"), rs.getString("old_roll_no"),
                    rs.getString("new_roll_no"), rs.getBigDecimal("ground_diameter_mm"),
                    rs.getBigDecimal("new_cycle_limit_km"), rs.getString("inspection_cert"),
                    rs.getInt("expected_version"), rs.getString("status"),
                    rs.getString("successor_roll_no"), rs.getString("request_id"),
                    rs.getString("fingerprint"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<InspectionRow> INSPECTION_MAPPER = (rs, n) -> new InspectionRow(
            rs.getLong("id"), rs.getLong("request_id_pk"), rs.getString("inspector_id"),
            rs.getBoolean("approved"), rs.getString("cert_no"),
            rs.getTimestamp("inspected_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    // ---------------- 返修申请 ----------------

    public void insertRepairRequest(RepairRequestRow row) {
        jdbc.update("""
                INSERT INTO roll_repair_request
                  (id, physical_id, old_roll_no, new_roll_no, ground_diameter_mm,
                   new_cycle_limit_km, inspection_cert, expected_version, status,
                   successor_roll_no, request_id, fingerprint, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                row.id(), row.physicalId(), row.oldRollNo(), row.newRollNo(),
                row.groundDiameterMm(), row.newCycleLimitKm(), row.inspectionCert(),
                row.expectedVersion(), row.status(), row.successorRollNo(), row.requestId(),
                row.fingerprint(), Timestamp.valueOf(row.createdAt()),
                Timestamp.valueOf(row.updatedAt()));
    }

    public RepairRequestRow findRepairRequest(long id) {
        List<RepairRequestRow> rows = jdbc.query(
                "SELECT * FROM roll_repair_request WHERE id = ?", REPAIR_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级加锁读取申请：同一申请上两名检验员的并发确认在此串行化。
     */
    public RepairRequestRow findRepairRequestForUpdate(long id) {
        List<RepairRequestRow> rows = jdbc.query(
                "SELECT * FROM roll_repair_request WHERE id = ? FOR UPDATE", REPAIR_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 旧代是否已有成功后继（每个旧代次最多一个）。
     */
    public RepairRequestRow findSuccessorRequest(String oldRollNo) {
        List<RepairRequestRow> rows = jdbc.query("""
                SELECT * FROM roll_repair_request
                WHERE old_roll_no = ? AND status = 'APPROVED'
                """, REPAIR_MAPPER, oldRollNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 申请终态流转：终止（REJECTED）或放行成功（APPROVED，回填下一代辊号）。
     */
    public void updateStatus(long id, String status, String successorRollNo, LocalDateTime now) {
        jdbc.update("""
                UPDATE roll_repair_request
                SET status = ?, successor_roll_no = ?, updated_at = ?
                WHERE id = ?
                """, status, successorRollNo, Timestamp.valueOf(now), id);
    }

    // ---------------- 检验确认 ----------------

    /**
     * @return true 插入成功；false 表示同一申请下该检验员已确认过（两人必须不同）
     */
    public boolean tryInsertInspection(InspectionRow row) {
        try {
            jdbc.update("""
                    INSERT INTO roll_repair_inspection
                      (id, request_id_pk, inspector_id, approved, cert_no, inspected_at, created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """,
                    row.id(), row.requestIdPk(), row.inspectorId(), row.approved(), row.certNo(),
                    Timestamp.valueOf(row.inspectedAt()), Timestamp.valueOf(row.createdAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<InspectionRow> findInspections(long requestIdPk) {
        return jdbc.query("""
                SELECT * FROM roll_repair_inspection
                WHERE request_id_pk = ? ORDER BY id
                """, INSPECTION_MAPPER, requestIdPk);
    }

    public int countApprovals(long requestIdPk) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM roll_repair_inspection
                WHERE request_id_pk = ? AND approved = 1
                """, Integer.class, requestIdPk);
        return count == null ? 0 : count;
    }
}
