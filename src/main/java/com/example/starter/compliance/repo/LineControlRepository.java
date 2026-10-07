package com.example.starter.compliance.repo;

import com.example.starter.compliance.support.JsonSupport;
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
 * 过敏原登记、生产线与清线凭证数据访问层（H2 MODE=MySQL，全部参数化 SQL）。
 * 过敏原集合以有序去重 JSON 数组落库，保证指纹与查询结果稳定。
 */
@Repository
public class LineControlRepository {

    private final JdbcTemplate jdbcTemplate;

    public LineControlRepository(JdbcTemplate jdbcTemplate) {
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

    // ---------------- 配方版本过敏原登记 ----------------

    public Optional<FormulaAllergenRow> findFormulaAllergen(String productCode, int formulaVersion) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, product_code, formula_version, allergens, version, registered_at, "
                            + "updated_at FROM formula_allergen "
                            + "WHERE product_code = ? AND formula_version = ?",
                    FORMULA_ALLERGEN_MAPPER, productCode, formulaVersion));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public long insertFormulaAllergen(String productCode, int formulaVersion,
            List<String> allergens, long registeredAt, long updatedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String allergensJson = JsonSupport.write(allergens);
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO formula_allergen(product_code, formula_version, allergens, version, "
                            + "registered_at, updated_at) VALUES (?, ?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, productCode);
            ps.setInt(2, formulaVersion);
            ps.setString(3, allergensJson);
            ps.setLong(4, registeredAt);
            ps.setLong(5, updatedAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("过敏原登记主键生成失败");
        }
        return key.longValue();
    }

    /** 乐观版本更新：仅当当前版本匹配时成功，返回是否更新。 */
    public boolean updateFormulaAllergen(long id, int expectedVersion, List<String> allergens,
            long registeredAt, long updatedAt) {
        return jdbcTemplate.update(
                "UPDATE formula_allergen SET allergens = ?, version = version + 1, "
                        + "registered_at = ?, updated_at = ? WHERE id = ? AND version = ?",
                JsonSupport.write(allergens), registeredAt, updatedAt, id, expectedVersion) > 0;
    }

    // ---------------- 生产线 ----------------

    public void insertLine(String lineCode, String lineName, long updatedAt) {
        jdbcTemplate.update(
                "INSERT INTO production_line(line_code, line_name, occupied_version, updated_at) "
                        + "VALUES (?, ?, 1, ?)",
                lineCode, lineName, updatedAt);
    }

    /** 合成登记最近一次量产版本（占用版本保持初始 1）。 */
    public void updateLineLastProduction(String lineCode, String productCode,
            int formulaVersion) {
        jdbcTemplate.update(
                "UPDATE production_line SET last_product_code = ?, last_formula_version = ? "
                        + "WHERE line_code = ?",
                productCode, formulaVersion, lineCode);
    }

    public Optional<ProductionLineRow> findLine(String lineCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT line_code, line_name, last_product_code, last_formula_version, "
                            + "occupied_version, updated_at FROM production_line WHERE line_code = ?",
                    LINE_MAPPER, lineCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 行级锁读取：受控量产与凭证确认先锁定线路行，串行化同线路并发写。 */
    public Optional<ProductionLineRow> findLineForUpdate(String lineCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT line_code, line_name, last_product_code, last_formula_version, "
                            + "occupied_version, updated_at FROM production_line "
                            + "WHERE line_code = ? FOR UPDATE",
                    LINE_MAPPER, lineCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 乐观占用版本更新：仅当当前占用版本匹配时推进版本并写入最近量产配方。 */
    public boolean occupyLine(String lineCode, int expectedOccupiedVersion, String productCode,
            int formulaVersion, long updatedAt) {
        return jdbcTemplate.update(
                "UPDATE production_line SET last_product_code = ?, last_formula_version = ?, "
                        + "occupied_version = occupied_version + 1, updated_at = ? "
                        + "WHERE line_code = ? AND occupied_version = ?",
                productCode, formulaVersion, updatedAt, lineCode, expectedOccupiedVersion) > 0;
    }

    // ---------------- 清线凭证 ----------------

    public long insertCertificate(String certificateNo, String lineCode, List<String> allergens,
            String operatorCode, String reviewerCode, long clearedAt, int occupiedVersion,
            long confirmedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String allergensJson = JsonSupport.write(allergens);
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO clearance_certificate(certificate_no, line_code, cleared_allergens, "
                            + "operator_code, reviewer_code, cleared_at, occupied_version, status, "
                            + "confirmed_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, certificateNo);
            ps.setString(2, lineCode);
            ps.setString(3, allergensJson);
            ps.setString(4, operatorCode);
            ps.setString(5, reviewerCode);
            ps.setLong(6, clearedAt);
            ps.setInt(7, occupiedVersion);
            ps.setLong(8, confirmedAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("清线凭证主键生成失败");
        }
        return key.longValue();
    }

    public Optional<ClearanceCertificateRow> findCertificateById(long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, certificate_no, line_code, cleared_allergens, operator_code, "
                            + "reviewer_code, cleared_at, occupied_version, status, confirmed_at, "
                            + "consumed_at FROM clearance_certificate WHERE id = ?",
                    CERTIFICATE_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ClearanceCertificateRow> findCertificateByNo(String certificateNo) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, certificate_no, line_code, cleared_allergens, operator_code, "
                            + "reviewer_code, cleared_at, occupied_version, status, confirmed_at, "
                            + "consumed_at FROM clearance_certificate WHERE certificate_no = ?",
                    CERTIFICATE_MAPPER, certificateNo));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 核销凭证：仅 ACTIVE 状态可核销，返回是否成功。 */
    public boolean consumeCertificate(long id, long consumedAt) {
        return jdbcTemplate.update(
                "UPDATE clearance_certificate SET status = 'CONSUMED', consumed_at = ? "
                        + "WHERE id = ? AND status = 'ACTIVE'",
                consumedAt, id) > 0;
    }

    /** 线路随后任何量产提交成功后，作废旧占用版本上仍有效的凭证。 */
    public int supersedeActiveCertificates(String lineCode, long supersededAt) {
        return jdbcTemplate.update(
                "UPDATE clearance_certificate SET status = 'SUPERSEDED', consumed_at = ? "
                        + "WHERE line_code = ? AND status = 'ACTIVE'",
                supersededAt, lineCode);
    }

    // ---------------- 受控量产记录 ----------------

    // ---------------- 返工料账 ----------------

    public Optional<ReworkLotRow> findReworkLotByCode(String lotCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, lot_code, production_record_id, product_code, formula_version, "
                            + "allergens, available_kg, consumed_kg, version, registered_at, "
                            + "updated_at FROM rework_lot WHERE lot_code = ?",
                    REWORK_LOT_MAPPER, lotCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ReworkLotRow> findReworkLotById(long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, lot_code, production_record_id, product_code, formula_version, "
                            + "allergens, available_kg, consumed_kg, version, registered_at, "
                            + "updated_at FROM rework_lot WHERE id = ?",
                    REWORK_LOT_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ReworkLotRow> findReworkLotBySource(long productionRecordId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, lot_code, production_record_id, product_code, formula_version, "
                            + "allergens, available_kg, consumed_kg, version, registered_at, "
                            + "updated_at FROM rework_lot WHERE production_record_id = ?",
                    REWORK_LOT_MAPPER, productionRecordId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 行级锁读取：受控量产扣减前锁定料账行，串行化两批争同料的并发消费。 */
    public Optional<ReworkLotRow> findReworkLotByCodeForUpdate(String lotCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, lot_code, production_record_id, product_code, formula_version, "
                            + "allergens, available_kg, consumed_kg, version, registered_at, "
                            + "updated_at FROM rework_lot WHERE lot_code = ? FOR UPDATE",
                    REWORK_LOT_MAPPER, lotCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public long insertReworkLot(String lotCode, long productionRecordId, String productCode,
            int formulaVersion, List<String> allergens, java.math.BigDecimal availableKg,
            String requestId, long registeredAt, long updatedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rework_lot(lot_code, production_record_id, product_code, "
                            + "formula_version, allergens, available_kg, consumed_kg, version, "
                            + "request_id, registered_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 0, 1, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lotCode);
            ps.setLong(2, productionRecordId);
            ps.setString(3, productCode);
            ps.setInt(4, formulaVersion);
            ps.setString(5, JsonSupport.write(allergens));
            ps.setBigDecimal(6, availableKg);
            ps.setString(7, requestId);
            ps.setLong(8, registeredAt);
            ps.setLong(9, updatedAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("返工料账主键生成失败");
        }
        return key.longValue();
    }

    /**
     * 原子扣减返工额度：仅当扣后不超可用量时成功（消费加 quantity、料账版本加 1），
     * 与行锁配合保证两批争同料不超用；返回是否扣减成功。
     */
    public boolean deductReworkLot(long lotId, java.math.BigDecimal quantityKg, long updatedAt) {
        return jdbcTemplate.update(
                "UPDATE rework_lot SET consumed_kg = consumed_kg + ?, version = version + 1, "
                        + "updated_at = ? WHERE id = ? AND consumed_kg + ? <= available_kg",
                quantityKg, updatedAt, lotId, quantityKg) > 0;
    }

    public long insertReworkConsumption(long lotId, long productionRecordId,
            java.math.BigDecimal quantityKg, long consumedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rework_consumption(lot_id, production_record_id, quantity_kg, "
                            + "consumed_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lotId);
            ps.setLong(2, productionRecordId);
            ps.setBigDecimal(3, quantityKg);
            ps.setLong(4, consumedAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("返工料消费明细主键生成失败");
        }
        return key.longValue();
    }

    /** 某料号全部消费明细（按消费先后升序），用于勾稽原量、消费与余额。 */
    public List<ReworkConsumptionRow> findReworkConsumptionsByLot(long lotId) {
        return jdbcTemplate.query(
                "SELECT id, lot_id, production_record_id, quantity_kg, consumed_at "
                        + "FROM rework_consumption WHERE lot_id = ? ORDER BY id",
                REWORK_CONSUMPTION_MAPPER, lotId);
    }

    /** 某笔量产记录的返工料消费明细（按料号字典序，响应稳定）。 */
    public List<ReworkConsumptionRow> findReworkConsumptionsByRecord(long productionRecordId) {
        return jdbcTemplate.query(
                "SELECT c.id, c.lot_id, c.production_record_id, c.quantity_kg, c.consumed_at "
                        + "FROM rework_consumption c JOIN rework_lot l ON l.id = c.lot_id "
                        + "WHERE c.production_record_id = ? ORDER BY l.lot_code",
                REWORK_CONSUMPTION_MAPPER, productionRecordId);
    }

    // ---------------- 行映射 ----------------

    private static List<String> readAllergens(String json) {
        return JsonSupport.readStringList(json);
    }

    private static final RowMapper<FormulaAllergenRow> FORMULA_ALLERGEN_MAPPER = (rs, n) ->
            new FormulaAllergenRow(rs.getLong("id"), rs.getString("product_code"),
                    rs.getInt("formula_version"), readAllergens(rs.getString("allergens")),
                    rs.getInt("version"), rs.getLong("registered_at"), rs.getLong("updated_at"));

    private static final RowMapper<ProductionLineRow> LINE_MAPPER = (rs, n) ->
            new ProductionLineRow(rs.getString("line_code"), rs.getString("line_name"),
                    rs.getString("last_product_code"),
                    rs.getObject("last_formula_version", Integer.class),
                    rs.getInt("occupied_version"), rs.getLong("updated_at"));

    private static final RowMapper<ClearanceCertificateRow> CERTIFICATE_MAPPER = (rs, n) ->
            new ClearanceCertificateRow(rs.getLong("id"), rs.getString("certificate_no"),
                    rs.getString("line_code"), readAllergens(rs.getString("cleared_allergens")),
                    rs.getString("operator_code"), rs.getString("reviewer_code"),
                    rs.getLong("cleared_at"), rs.getInt("occupied_version"),
                    rs.getString("status"), rs.getLong("confirmed_at"),
                    rs.getObject("consumed_at", Long.class));

    private static final RowMapper<ReworkLotRow> REWORK_LOT_MAPPER = (rs, n) ->
            new ReworkLotRow(rs.getLong("id"), rs.getString("lot_code"),
                    rs.getLong("production_record_id"), rs.getString("product_code"),
                    rs.getInt("formula_version"), readAllergens(rs.getString("allergens")),
                    rs.getBigDecimal("available_kg"), rs.getBigDecimal("consumed_kg"),
                    rs.getInt("version"), rs.getLong("registered_at"), rs.getLong("updated_at"));

    private static final RowMapper<ReworkConsumptionRow> REWORK_CONSUMPTION_MAPPER = (rs, n) ->
            new ReworkConsumptionRow(rs.getLong("id"), rs.getLong("lot_id"),
                    rs.getLong("production_record_id"), rs.getBigDecimal("quantity_kg"),
                    rs.getLong("consumed_at"));
}
