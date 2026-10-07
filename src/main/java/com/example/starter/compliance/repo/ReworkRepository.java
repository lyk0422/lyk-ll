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
 * 返工料登记与消费数据访问层（H2 MODE=MySQL，全部参数化 SQL）。
 * 过敏原集合以有序 JSON 数组落库；消费流水只插入，不提供更新/删除。
 */
@Repository
public class ReworkRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReworkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------- 返工料登记 ----------------

    public long insertMaterial(String materialCode, long productionRecordId,
            String sourceProductCode, int sourceFormulaVersion, List<String> allergens,
            BigDecimal availableKg, String requestId, long registeredAt, long updatedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String allergensJson = JsonSupport.write(allergens);
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rework_material(material_code, production_record_id, "
                            + "source_product_code, source_formula_version, allergens, "
                            + "available_kg, consumed_kg, version, request_id, "
                            + "registered_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 0, 1, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, materialCode);
            ps.setLong(2, productionRecordId);
            ps.setString(3, sourceProductCode);
            ps.setInt(4, sourceFormulaVersion);
            ps.setString(5, allergensJson);
            ps.setBigDecimal(6, availableKg);
            ps.setString(7, requestId);
            ps.setLong(8, registeredAt);
            ps.setLong(9, updatedAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("返工料登记主键生成失败");
        }
        return key.longValue();
    }

    public Optional<ReworkMaterialRow> findMaterialByCode(String materialCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, material_code, production_record_id, source_product_code, "
                            + "source_formula_version, allergens, available_kg, consumed_kg, "
                            + "version, registered_at, updated_at FROM rework_material "
                            + "WHERE material_code = ?",
                    MATERIAL_MAPPER, materialCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 行级锁读取：受控量产扣减与并发修改先锁定料账行，串行化同料并发写。 */
    public Optional<ReworkMaterialRow> findMaterialByCodeForUpdate(String materialCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, material_code, production_record_id, source_product_code, "
                            + "source_formula_version, allergens, available_kg, consumed_kg, "
                            + "version, registered_at, updated_at FROM rework_material "
                            + "WHERE material_code = ? FOR UPDATE",
                    MATERIAL_MAPPER, materialCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ReworkMaterialRow> findMaterialById(long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, material_code, production_record_id, source_product_code, "
                            + "source_formula_version, allergens, available_kg, consumed_kg, "
                            + "version, registered_at, updated_at FROM rework_material "
                            + "WHERE id = ?",
                    MATERIAL_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ReworkMaterialRow> findMaterialByProductionRecordId(long productionRecordId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, material_code, production_record_id, source_product_code, "
                            + "source_formula_version, allergens, available_kg, consumed_kg, "
                            + "version, registered_at, updated_at FROM rework_material "
                            + "WHERE production_record_id = ?",
                    MATERIAL_MAPPER, productionRecordId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 乐观版本修正登记量：仅当当前料账版本匹配时成功，版本加 1。 */
    public boolean updateAvailableKg(long id, int expectedVersion, BigDecimal availableKg,
            long updatedAt) {
        return jdbcTemplate.update(
                "UPDATE rework_material SET available_kg = ?, version = version + 1, "
                        + "updated_at = ? WHERE id = ? AND version = ?",
                availableKg, updatedAt, id, expectedVersion) > 0;
    }

    /**
     * 原子扣减可用额度：仅当余额足够时累加消费并推进料账版本；
     * 与行锁配合保证两批争同料不得超用，返回是否扣减成功。
     */
    public boolean consumeMaterial(long id, BigDecimal weightKg, long updatedAt) {
        return jdbcTemplate.update(
                "UPDATE rework_material SET consumed_kg = consumed_kg + ?, "
                        + "version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND available_kg >= consumed_kg + ?",
                weightKg, updatedAt, id, weightKg) > 0;
    }

    // ---------------- 消费流水 ----------------

    public void insertConsumption(long materialId, long productionRecordId,
            BigDecimal weightKg, long consumedAt) {
        jdbcTemplate.update(
                "INSERT INTO rework_consumption(material_id, production_record_id, weight_kg, "
                        + "consumed_at) VALUES (?, ?, ?, ?)",
                materialId, productionRecordId, weightKg, consumedAt);
    }

    /** 某料账全部消费流水（按发生先后升序），用于勾稽原量、消费与余额。 */
    public List<ReworkConsumptionRow> findConsumptionsByMaterialId(long materialId) {
        return jdbcTemplate.query(
                "SELECT id, material_id, production_record_id, weight_kg, consumed_at "
                        + "FROM rework_consumption WHERE material_id = ? ORDER BY id",
                CONSUMPTION_MAPPER, materialId);
    }

    /** 某量产记录的返工料投用明细（按发生先后升序）。 */
    public List<ReworkConsumptionRow> findConsumptionsByProductionRecordId(
            long productionRecordId) {
        return jdbcTemplate.query(
                "SELECT id, material_id, production_record_id, weight_kg, consumed_at "
                        + "FROM rework_consumption WHERE production_record_id = ? ORDER BY id",
                CONSUMPTION_MAPPER, productionRecordId);
    }

    // ---------------- 行映射 ----------------

    private static final RowMapper<ReworkMaterialRow> MATERIAL_MAPPER = (rs, n) ->
            new ReworkMaterialRow(rs.getLong("id"), rs.getString("material_code"),
                    rs.getLong("production_record_id"), rs.getString("source_product_code"),
                    rs.getInt("source_formula_version"),
                    JsonSupport.readStringList(rs.getString("allergens")),
                    rs.getBigDecimal("available_kg"), rs.getBigDecimal("consumed_kg"),
                    rs.getInt("version"), rs.getLong("registered_at"), rs.getLong("updated_at"));

    private static final RowMapper<ReworkConsumptionRow> CONSUMPTION_MAPPER = (rs, n) ->
            new ReworkConsumptionRow(rs.getLong("id"), rs.getLong("material_id"),
                    rs.getLong("production_record_id"), rs.getBigDecimal("weight_kg"),
                    rs.getLong("consumed_at"));
}
