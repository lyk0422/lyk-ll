package com.example.starter.compliance.repo;

import com.example.starter.compliance.support.JsonSupport;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 生产线过敏原清线凭证门禁数据访问层（H2 MODE=MySQL，全部参数化 SQL）。
 */
@Repository
public class LineGateRepository {

    private final JdbcTemplate jdbcTemplate;

    public LineGateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------- 过敏原目录 ----------------

    public long countAllergens() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM allergen", Long.class);
        return count == null ? 0L : count;
    }

    public void insertAllergen(String code, String name) {
        jdbcTemplate.update("INSERT INTO allergen(allergen_code, allergen_name) VALUES (?, ?)",
                code, name);
    }

    public boolean allergenExists(String allergenCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM allergen WHERE allergen_code = ?",
                Integer.class, allergenCode);
        return count != null && count > 0;
    }

    public Optional<AllergenRow> findAllergen(String allergenCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT allergen_code, allergen_name FROM allergen WHERE allergen_code = ?",
                    ALLERGEN_MAPPER, allergenCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ---------------- 配方过敏原登记 ----------------

    public void insertFormulaAllergen(long formulaId, int version, List<String> allergenCodes,
            String requestId, long updatedAt) {
        jdbcTemplate.update(
                "INSERT INTO formula_allergen(formula_id, version, allergen_codes, request_id, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?)",
                formulaId, version, JsonSupport.write(allergenCodes), requestId, updatedAt);
    }

    /** 条件更新登记：仅当当前版本等于 expectedVersion 时写入新版本并返回 1，否则返回 0。 */
    public int updateFormulaAllergen(long formulaId, int expectedVersion,
            List<String> allergenCodes, String requestId, long updatedAt) {
        return jdbcTemplate.update(
                "UPDATE formula_allergen SET version = version + 1, allergen_codes = ?, "
                        + "request_id = ?, updated_at = ? WHERE formula_id = ? AND version = ?",
                JsonSupport.write(allergenCodes), requestId, updatedAt, formulaId, expectedVersion);
    }

    public Optional<FormulaAllergenRow> findFormulaAllergen(long formulaId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT formula_id, version, allergen_codes, request_id, updated_at "
                            + "FROM formula_allergen WHERE formula_id = ?",
                    FORMULA_ALLERGEN_MAPPER, formulaId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ---------------- 生产线 ----------------

    public long insertProductionLine(String lineCode, String lineName, long now) {
        return jdbcTemplate.update(
                "INSERT INTO production_line(line_code, line_name, last_formula_id, "
                        + "occupancy_version, created_at, updated_at) VALUES (?, ?, NULL, 1, ?, ?)",
                lineCode, lineName, now, now);
    }

    public Optional<ProductionLineRow> findProductionLine(String lineCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT line_code, line_name, last_formula_id, occupancy_version, created_at, "
                            + "updated_at FROM production_line WHERE line_code = ?",
                    LINE_MAPPER, lineCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 事务内行锁：串行化同一线路的并发受控量产，配合条件更新保证占用版本唯一。 */
    public Optional<ProductionLineRow> lockProductionLine(String lineCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT line_code, line_name, last_formula_id, occupancy_version, created_at, "
                            + "updated_at FROM production_line WHERE line_code = ? FOR UPDATE",
                    LINE_MAPPER, lineCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ProductionLineRow> findAllProductionLines() {
        return jdbcTemplate.query(
                "SELECT line_code, line_name, last_formula_id, occupancy_version, created_at, "
                        + "updated_at FROM production_line ORDER BY line_code",
                LINE_MAPPER);
    }

    /**
     * 条件推进线路：仅当占用版本等于 expectedVersion 时把最近配方置为 newLastFormulaId、
     * 占用版本 +1；并发失败方影响行数为 0，整事务回滚。
     */
    public int advanceProductionLine(String lineCode, int expectedVersion, long newLastFormulaId,
            long updatedAt) {
        return jdbcTemplate.update(
                "UPDATE production_line SET last_formula_id = ?, occupancy_version = ? + 1, "
                        + "updated_at = ? WHERE line_code = ? AND occupancy_version = ?",
                newLastFormulaId, expectedVersion, updatedAt, lineCode, expectedVersion);
    }

    // ---------------- 清线凭证 ----------------

    public long insertCleaningCertificate(String lineCode, int expectedVersion,
            Long occupiedFormulaId, String operatorId, String reviewerId, long cleanedAt,
            List<String> allergenCodes, String requestId, long createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO cleaning_certificate(line_code, expected_version, "
                            + "occupied_formula_id, operator_id, reviewer_id, cleaned_at, "
                            + "allergen_codes, status, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'VALID', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lineCode);
            ps.setInt(2, expectedVersion);
            if (occupiedFormulaId == null) {
                ps.setObject(3, null);
            } else {
                ps.setLong(3, occupiedFormulaId);
            }
            ps.setString(4, operatorId);
            ps.setString(5, reviewerId);
            ps.setLong(6, cleanedAt);
            ps.setString(7, JsonSupport.write(allergenCodes));
            ps.setString(8, requestId);
            ps.setLong(9, createdAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("清线凭证主键生成失败");
        }
        return key.longValue();
    }

    public Optional<CleaningCertificateRow> findCertificateById(long certificateId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, line_code, expected_version, occupied_formula_id, operator_id, "
                            + "reviewer_id, cleaned_at, allergen_codes, status, request_id, "
                            + "consumed_at, created_at FROM cleaning_certificate WHERE id = ?",
                    CERTIFICATE_MAPPER, certificateId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 仅当凭证仍为 VALID 时核销为 CONSUMED，返回影响行数。 */
    public int consumeCertificate(long certificateId, long consumedAt) {
        return jdbcTemplate.update(
                "UPDATE cleaning_certificate SET status = 'CONSUMED', consumed_at = ? "
                        + "WHERE id = ? AND status = 'VALID'",
                consumedAt, certificateId);
    }

    /**
     * 线路推进后作废旧凭证：该线路上除本次核销凭证外的全部 VALID 凭证置 SUPERSEDED。
     * 任意后续生产先提交即生效，旧凭证即使仍在 2 小时窗口内也不可再用。
     */
    public int supersedeOtherValidCertificates(String lineCode, long consumedCertificateId,
            long consumedAt) {
        return jdbcTemplate.update(
                "UPDATE cleaning_certificate SET status = 'SUPERSEDED', consumed_at = ? "
                        + "WHERE line_code = ? AND status = 'VALID' AND id <> ?",
                consumedAt, lineCode, consumedCertificateId);
    }

    public List<CleaningCertificateRow> findCertificatesByLine(String lineCode) {
        return jdbcTemplate.query(
                "SELECT id, line_code, expected_version, occupied_formula_id, operator_id, "
                        + "reviewer_id, cleaned_at, allergen_codes, status, request_id, consumed_at, "
                        + "created_at FROM cleaning_certificate WHERE line_code = ? ORDER BY id",
                CERTIFICATE_MAPPER, lineCode);
    }

    // ---------------- 受控量产记录 ----------------

    public long insertLineProductionRecord(String lineCode, int occupancyVersion, long formulaId,
            long filingId, Long certificateId, String batchNo, BigDecimal quantityKg,
            long occurredAt, String requestId, long createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO line_production_record(line_code, occupancy_version, formula_id, "
                            + "filing_id, certificate_id, batch_no, quantity_kg, occurred_at, "
                            + "request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lineCode);
            ps.setInt(2, occupancyVersion);
            ps.setLong(3, formulaId);
            ps.setLong(4, filingId);
            if (certificateId == null) {
                ps.setObject(5, null);
            } else {
                ps.setLong(5, certificateId);
            }
            ps.setString(6, batchNo);
            ps.setBigDecimal(7, quantityKg);
            ps.setLong(8, occurredAt);
            ps.setString(9, requestId);
            ps.setLong(10, createdAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("受控量产记录主键生成失败");
        }
        return key.longValue();
    }

    public List<LineProductionRecordRow> findLineProductionRecords(String lineCode) {
        return jdbcTemplate.query(
                "SELECT id, line_code, occupancy_version, formula_id, filing_id, certificate_id, "
                        + "batch_no, quantity_kg, occurred_at, request_id, created_at "
                        + "FROM line_production_record WHERE line_code = ? ORDER BY id",
                LINE_PRODUCTION_MAPPER, lineCode);
    }

    /** 备案行锁：暂停/注销与受控量产并发时保证唯一提交顺序。 */
    public Optional<FilingRow> lockFiling(long filingId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, formula_id, product_code, version, status, request_id, snapshot, "
                            + "watch_start_day, suspend_reason, created_at, finalized_at "
                            + "FROM filing WHERE id = ? FOR UPDATE",
                    FILING_MAPPER, filingId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 场景间清理门禁业务数据（过敏原合成目录保留）。 */
    public void deleteGateBusinessData() {
        jdbcTemplate.update("DELETE FROM line_production_record");
        jdbcTemplate.update("DELETE FROM cleaning_certificate");
        jdbcTemplate.update("DELETE FROM production_line");
        jdbcTemplate.update("DELETE FROM formula_allergen");
    }

    private static final RowMapper<AllergenRow> ALLERGEN_MAPPER = (rs, n) ->
            new AllergenRow(rs.getString("allergen_code"), rs.getString("allergen_name"));

    private static final RowMapper<FormulaAllergenRow> FORMULA_ALLERGEN_MAPPER = (rs, n) ->
            new FormulaAllergenRow(rs.getLong("formula_id"), rs.getInt("version"),
                    rs.getString("allergen_codes"), rs.getString("request_id"),
                    rs.getLong("updated_at"));

    private static final RowMapper<ProductionLineRow> LINE_MAPPER = (rs, n) ->
            new ProductionLineRow(rs.getString("line_code"), rs.getString("line_name"),
                    rs.getObject("last_formula_id", Long.class), rs.getInt("occupancy_version"),
                    rs.getLong("created_at"), rs.getLong("updated_at"));

    private static final RowMapper<CleaningCertificateRow> CERTIFICATE_MAPPER = (rs, n) ->
            new CleaningCertificateRow(rs.getLong("id"), rs.getString("line_code"),
                    rs.getInt("expected_version"),
                    rs.getObject("occupied_formula_id", Long.class),
                    rs.getString("operator_id"), rs.getString("reviewer_id"),
                    rs.getLong("cleaned_at"), rs.getString("allergen_codes"),
                    rs.getString("status"), rs.getString("request_id"),
                    rs.getObject("consumed_at", Long.class), rs.getLong("created_at"));

    private static final RowMapper<LineProductionRecordRow> LINE_PRODUCTION_MAPPER = (rs, n) ->
            new LineProductionRecordRow(rs.getLong("id"), rs.getString("line_code"),
                    rs.getInt("occupancy_version"), rs.getLong("formula_id"),
                    rs.getLong("filing_id"), rs.getObject("certificate_id", Long.class),
                    rs.getString("batch_no"), rs.getBigDecimal("quantity_kg"),
                    rs.getLong("occurred_at"), rs.getString("request_id"),
                    rs.getLong("created_at"));

    private static final RowMapper<FilingRow> FILING_MAPPER = (rs, n) -> new FilingRow(
            rs.getLong("id"), rs.getLong("formula_id"), rs.getString("product_code"),
            rs.getInt("version"), rs.getString("status"), rs.getString("request_id"),
            rs.getString("snapshot"),
            rs.getObject("watch_start_day", Long.class),
            rs.getString("suspend_reason"),
            rs.getLong("created_at"),
            rs.getObject("finalized_at", Long.class));
}
