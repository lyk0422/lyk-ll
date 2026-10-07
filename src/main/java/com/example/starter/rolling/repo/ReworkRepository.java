package com.example.starter.rolling.repo;

import com.example.starter.rolling.repo.RollLifeRepository.RollRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 返修放行域访问层：合成物理辊、代次链、返修申请，及各代寿命台账组合查询。
 *
 * <p>所有写入与检验、代次建立、幂等记录由服务层包裹在同一事务内。</p>
 */
@Repository
public class ReworkRepository {

    private final JdbcTemplate jdbc;

    public ReworkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 合成物理辊行。version 为返修聚合乐观版本，从 1 起。 */
    public record PhysicalRollRow(String physicalNo, String headRollNo, int headGenerationNo,
                                  int reworkCount, int version,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 物理辊代次登记行。diameterMm 首代为 null。 */
    public record RollGenerationRow(long id, String physicalNo, int generationNo, String rollNo,
                                    BigDecimal diameterMm, Long successorApplicationId,
                                    LocalDateTime createdAt) {
    }

    /** 返修放行申请行。status 为 INSPECTING/RELEASED/TERMINATED。 */
    public record ReworkApplicationRow(
            long id, String requestId, String physicalNo, String oldRollNo, int oldGenerationNo,
            String newRollNo, BigDecimal diameterAfterGrindMm, BigDecimal cycleLimitKm,
            String inspectionCertNo, LocalDateTime mountedAt, String status,
            String firstInspector, Boolean firstQualified, LocalDateTime firstConfirmedAt,
            String secondInspector, Boolean secondQualified, LocalDateTime secondConfirmedAt,
            LocalDateTime releasedAt, LocalDateTime terminatedAt, int version,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    private static final RowMapper<PhysicalRollRow> PHYSICAL_MAPPER = (rs, n) -> new PhysicalRollRow(
            rs.getString("physical_no"), rs.getString("head_roll_no"),
            rs.getInt("head_generation_no"), rs.getInt("rework_count"), rs.getInt("version"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<RollGenerationRow> GENERATION_MAPPER = (rs, n) ->
            new RollGenerationRow(
                    rs.getLong("id"), rs.getString("physical_no"), rs.getInt("generation_no"),
                    rs.getString("roll_no"), rs.getBigDecimal("diameter_mm"),
                    (Long) rs.getObject("successor_application_id"),
                    rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<ReworkApplicationRow> APPLICATION_MAPPER = (rs, n) ->
            new ReworkApplicationRow(
                    rs.getLong("id"), rs.getString("request_id"), rs.getString("physical_no"),
                    rs.getString("old_roll_no"), rs.getInt("old_generation_no"),
                    rs.getString("new_roll_no"), rs.getBigDecimal("diameter_after_grind_mm"),
                    rs.getBigDecimal("cycle_limit_km"), rs.getString("inspection_cert_no"),
                    rs.getTimestamp("mounted_at").toLocalDateTime(), rs.getString("status"),
                    rs.getString("first_inspector"),
                    rs.getObject("first_qualified", Boolean.class),
                    ts(rs, "first_confirmed_at"), rs.getString("second_inspector"),
                    rs.getObject("second_qualified", Boolean.class),
                    ts(rs, "second_confirmed_at"), ts(rs, "released_at"), ts(rs, "terminated_at"),
                    rs.getInt("version"), rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime());

    private static LocalDateTime ts(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static final RowMapper<RollRow> LEDGER_MAPPER = (rs, n) -> new RollRow(
            rs.getString("roll_no"), rs.getString("status"), rs.getBigDecimal("total_km"),
            rs.getBigDecimal("cycle_limit_km"), rs.getInt("coils_since_mount"),
            rs.getTimestamp("mounted_at").toLocalDateTime(),
            ts(rs, "frozen_at"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    // ---------------- 合成物理辊 ----------------

    public void insertPhysicalRoll(PhysicalRollRow row) {
        jdbc.update("""
                INSERT INTO physical_roll
                  (physical_no, head_roll_no, head_generation_no, rework_count, version,
                   created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                row.physicalNo(), row.headRollNo(), row.headGenerationNo(), row.reworkCount(),
                row.version(), Timestamp.valueOf(row.createdAt()), Timestamp.valueOf(row.updatedAt()));
    }

    public PhysicalRollRow findPhysicalRoll(String physicalNo) {
        List<PhysicalRollRow> rows = jdbc.query(
                "SELECT * FROM physical_roll WHERE physical_no = ?", PHYSICAL_MAPPER, physicalNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 乐观推进物理辊聚合头指针并累计返修次数：仅当 version 与期望一致时更新，成功后 version +1。
     *
     * @return 受影响行数；0 表示版本冲突（409）
     */
    public int advanceHead(String physicalNo, String newHeadRollNo, int newHeadGenerationNo,
                           int expectedVersion, LocalDateTime now) {
        return jdbc.update("""
                UPDATE physical_roll
                SET head_roll_no = ?, head_generation_no = ?,
                    rework_count = rework_count + 1, version = version + 1, updated_at = ?
                WHERE physical_no = ? AND version = ?
                """, newHeadRollNo, newHeadGenerationNo, Timestamp.valueOf(now),
                physicalNo, expectedVersion);
    }

    // ---------------- 代次链 ----------------

    public void insertGeneration(RollGenerationRow row) {
        jdbc.update("""
                INSERT INTO roll_generation
                  (id, physical_no, generation_no, roll_no, diameter_mm,
                   successor_application_id, created_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                row.id(), row.physicalNo(), row.generationNo(), row.rollNo(),
                row.diameterMm() == null ? null : row.diameterMm(),
                row.successorApplicationId(), Timestamp.valueOf(row.createdAt()));
    }

    public RollGenerationRow findGeneration(String physicalNo, int generationNo) {
        List<RollGenerationRow> rows = jdbc.query(
                "SELECT * FROM roll_generation WHERE physical_no = ? AND generation_no = ?",
                GENERATION_MAPPER, physicalNo, generationNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RollGenerationRow findGenerationByRollNo(String rollNo) {
        List<RollGenerationRow> rows = jdbc.query(
                "SELECT * FROM roll_generation WHERE roll_no = ?", GENERATION_MAPPER, rollNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<RollGenerationRow> findGenerations(String physicalNo) {
        return jdbc.query(
                "SELECT * FROM roll_generation WHERE physical_no = ? ORDER BY generation_no",
                GENERATION_MAPPER, physicalNo);
    }

    /**
     * 原子登记旧代次成功后继：仅当旧代次尚无成功后继时更新。
     *
     * @return 受影响行数；0 表示该旧代次已有成功后继（并发双建竞争裁决）
     */
    public int claimSuccessor(long generationId, long applicationId) {
        return jdbc.update("""
                UPDATE roll_generation SET successor_application_id = ?
                WHERE id = ? AND successor_application_id IS NULL
                """, applicationId, generationId);
    }

    // ---------------- 返修申请 ----------------

    public void insertApplication(ReworkApplicationRow row) {
        jdbc.update("""
                INSERT INTO rework_application
                  (id, request_id, physical_no, old_roll_no, old_generation_no, new_roll_no,
                   diameter_after_grind_mm, cycle_limit_km, inspection_cert_no, mounted_at,
                   status, first_inspector, first_qualified, first_confirmed_at,
                   second_inspector, second_qualified, second_confirmed_at,
                   released_at, terminated_at, version, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                ps -> {
                    ps.setLong(1, row.id());
                    ps.setString(2, row.requestId());
                    ps.setString(3, row.physicalNo());
                    ps.setString(4, row.oldRollNo());
                    ps.setInt(5, row.oldGenerationNo());
                    ps.setString(6, row.newRollNo());
                    ps.setBigDecimal(7, row.diameterAfterGrindMm());
                    ps.setBigDecimal(8, row.cycleLimitKm());
                    ps.setString(9, row.inspectionCertNo());
                    ps.setTimestamp(10, Timestamp.valueOf(row.mountedAt()));
                    ps.setString(11, row.status());
                    ps.setString(12, row.firstInspector());
                    setNullableBoolean(ps, 13, row.firstQualified());
                    setNullableTimestamp(ps, 14, row.firstConfirmedAt());
                    ps.setString(15, row.secondInspector());
                    setNullableBoolean(ps, 16, row.secondQualified());
                    setNullableTimestamp(ps, 17, row.secondConfirmedAt());
                    setNullableTimestamp(ps, 18, row.releasedAt());
                    setNullableTimestamp(ps, 19, row.terminatedAt());
                    ps.setInt(20, row.version());
                    ps.setTimestamp(21, Timestamp.valueOf(row.createdAt()));
                    ps.setTimestamp(22, Timestamp.valueOf(row.updatedAt()));
                });
    }

    private static void setNullableBoolean(java.sql.PreparedStatement ps, int index, Boolean value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.TINYINT);
        } else {
            ps.setBoolean(index, value);
        }
    }

    private static void setNullableTimestamp(java.sql.PreparedStatement ps, int index,
                                             LocalDateTime value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    public ReworkApplicationRow findApplicationById(long id) {
        List<ReworkApplicationRow> rows = jdbc.query(
                "SELECT * FROM rework_application WHERE id = ?", APPLICATION_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReworkApplicationRow findApplicationByRequestId(String requestId) {
        List<ReworkApplicationRow> rows = jdbc.query(
                "SELECT * FROM rework_application WHERE request_id = ?", APPLICATION_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 乐观更新第一次检验：仅当 status=INSPECTING、version 与期望一致、尚无第一名确认时生效。
     * 不合格时置 TERMINATED 并记录终止时刻；合格时保持 INSPECTING。
     *
     * @return 受影响行数；0 表示版本冲突或状态不再允许
     */
    public int updateFirstInspection(long id, String inspector, boolean qualified,
                                     LocalDateTime confirmedAt, LocalDateTime now,
                                     int expectedVersion) {
        String newStatus = qualified ? "INSPECTING" : "TERMINATED";
        return jdbc.update("""
                UPDATE rework_application
                SET first_inspector = ?, first_qualified = ?, first_confirmed_at = ?,
                    status = ?, terminated_at = COALESCE(terminated_at, ?),
                    version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'INSPECTING' AND first_inspector IS NULL AND version = ?
                """,
                inspector, qualified, Timestamp.valueOf(confirmedAt), newStatus,
                qualified ? null : Timestamp.valueOf(confirmedAt), Timestamp.valueOf(now),
                id, expectedVersion);
    }

    /**
     * 第二次检验不合格：乐观置 TERMINATED，仅当 status=INSPECTING、version 一致、
     * 第一名已确认且第二名尚空缺时生效。
     *
     * @return 受影响行数；0 表示版本冲突或状态不再允许
     */
    public int terminateOnSecondInspection(long id, String inspector, LocalDateTime confirmedAt,
                                           LocalDateTime now, int expectedVersion) {
        return jdbc.update("""
                UPDATE rework_application
                SET second_inspector = ?, second_qualified = FALSE, second_confirmed_at = ?,
                    status = 'TERMINATED', terminated_at = COALESCE(terminated_at, ?),
                    version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'INSPECTING' AND first_inspector IS NOT NULL
                  AND second_inspector IS NULL AND version = ?
                """,
                inspector, Timestamp.valueOf(confirmedAt), Timestamp.valueOf(confirmedAt),
                Timestamp.valueOf(now), id, expectedVersion);
    }

    /**
     * 第二次检验合格并放行：原子登记第二名、置 RELEASED 与放行时刻，
     * 仅当 status=INSPECTING、version 一致、第一名已确认且第二名尚空缺时生效。
     *
     * @return 受影响行数；0 表示版本冲突、并发双建竞争或状态不再允许
     */
    public int releaseWithSecondInspection(long id, String inspector, LocalDateTime confirmedAt,
                                           LocalDateTime releasedAt, LocalDateTime now,
                                           int expectedVersion) {
        return jdbc.update("""
                UPDATE rework_application
                SET second_inspector = ?, second_qualified = TRUE, second_confirmed_at = ?,
                    status = 'RELEASED', released_at = ?,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND status = 'INSPECTING' AND first_inspector IS NOT NULL
                  AND first_qualified = TRUE AND second_inspector IS NULL AND version = ?
                """,
                inspector, Timestamp.valueOf(confirmedAt), Timestamp.valueOf(releasedAt),
                Timestamp.valueOf(now), id, expectedVersion);
    }

    // ---------------- 各代独立台账 ----------------

    /**
     * 按给定辊号集合返回寿命台账行（顺序无关，由服务层按代次链对齐）。
     */
    public List<RollRow> findLedgersByRollNos(List<String> rollNos) {
        if (rollNos.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", rollNos.stream().map(r -> "?").toList());
        return jdbc.query(
                "SELECT * FROM roll_life WHERE roll_no IN (" + placeholders + ")",
                LEDGER_MAPPER, rollNos.toArray());
    }
}
