package com.example.starter.compliance.repo;

import com.example.starter.compliance.domain.DirectAdditive;
import com.example.starter.compliance.domain.FormulaContent;
import com.example.starter.compliance.domain.IngredientUse;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 配方合规数据访问层（H2 MODE=MySQL，全部参数化 SQL）。
 */
@Repository
public class ComplianceRepository {

    private final JdbcTemplate jdbcTemplate;

    public ComplianceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    // ---------------- 基础目录 ----------------

    public long countCategories() {
        return count("food_category");
    }

    public void insertCategory(String code, String name) {
        jdbcTemplate.update("INSERT INTO food_category(category_code, category_name) VALUES (?, ?)",
                code, name);
    }

    public void insertAdditive(String code, String name, String functionCode) {
        jdbcTemplate.update(
                "INSERT INTO additive(additive_code, additive_name, function_code) VALUES (?, ?, ?)",
                code, name, functionCode);
    }

    public void insertIngredient(String code, String name) {
        jdbcTemplate.update(
                "INSERT INTO compound_ingredient(ingredient_code, ingredient_name) VALUES (?, ?)",
                code, name);
    }

    public void insertIngredientAdditive(String ingredientCode, String additiveCode,
            BigDecimal concentration) {
        jdbcTemplate.update(
                "INSERT INTO ingredient_additive(ingredient_code, additive_code, concentration) "
                        + "VALUES (?, ?, ?)",
                ingredientCode, additiveCode, concentration);
    }

    public void insertLimit(String additiveCode, String categoryCode, BigDecimal maxAmount,
            long updatedAt) {
        jdbcTemplate.update(
                "INSERT INTO additive_limit(additive_code, category_code, max_amount, updated_at) "
                        + "VALUES (?, ?, ?, ?)",
                additiveCode, categoryCode, maxAmount, updatedAt);
    }

    public boolean categoryExists(String categoryCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM food_category WHERE category_code = ?",
                Integer.class, categoryCode);
        return count != null && count > 0;
    }

    public boolean ingredientExists(String ingredientCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM compound_ingredient WHERE ingredient_code = ?",
                Integer.class, ingredientCode);
        return count != null && count > 0;
    }

    public Optional<AdditiveRow> findAdditive(String additiveCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT additive_code, additive_name, function_code FROM additive "
                            + "WHERE additive_code = ?",
                    ADDITIVE_MAPPER, additiveCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<LimitRow> findLimit(String additiveCode, String categoryCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT additive_code, category_code, max_amount, updated_at "
                            + "FROM additive_limit WHERE additive_code = ? AND category_code = ?",
                    LIMIT_MAPPER, additiveCode, categoryCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 返回某食品分类下全部限量，键为添加剂编码。 */
    public List<LimitRow> findLimitsByCategory(String categoryCode) {
        return jdbcTemplate.query(
                "SELECT additive_code, category_code, max_amount, updated_at FROM additive_limit "
                        + "WHERE category_code = ?",
                LIMIT_MAPPER, categoryCode);
    }

    public int updateLimit(String additiveCode, String categoryCode, BigDecimal maxAmount,
            long updatedAt) {
        return jdbcTemplate.update(
                "UPDATE additive_limit SET max_amount = ?, updated_at = ? "
                        + "WHERE additive_code = ? AND category_code = ?",
                maxAmount, updatedAt, additiveCode, categoryCode);
    }

    public List<IngredientAdditiveRow> findIngredientAdditives(Collection<String> ingredientCodes) {
        if (ingredientCodes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ingredientCodes.stream().map(c -> "?").toList());
        return jdbcTemplate.query(
                "SELECT ingredient_code, additive_code, concentration FROM ingredient_additive "
                        + "WHERE ingredient_code IN (" + placeholders + ")",
                INGREDIENT_ADDITIVE_MAPPER, ingredientCodes.toArray());
    }

    // ---------------- 配方 ----------------

    public long insertFormula(FormulaContent content, String state, long createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO formula(product_code, product_name, category_code, version, state, "
                            + "created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, content.productCode());
            ps.setString(2, content.productName());
            ps.setString(3, content.categoryCode());
            ps.setInt(4, content.version());
            ps.setString(5, state);
            ps.setLong(6, createdAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("配方主键生成失败");
        }
        return key.longValue();
    }

    public void insertDirectAdditive(long formulaId, String additiveCode, BigDecimal amount) {
        jdbcTemplate.update(
                "INSERT INTO formula_direct_additive(formula_id, additive_code, amount) "
                        + "VALUES (?, ?, ?)",
                formulaId, additiveCode, amount);
    }

    public void insertIngredientUse(long formulaId, String ingredientCode, BigDecimal ratio) {
        jdbcTemplate.update(
                "INSERT INTO formula_ingredient(formula_id, ingredient_code, ratio) VALUES (?, ?, ?)",
                formulaId, ingredientCode, ratio);
    }

    public Optional<FormulaRow> findFormulaById(long formulaId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, product_code, product_name, category_code, version, state, request_id, "
                            + "review_passed, review_json, reviewed_at, approved_snapshot, approved_at, "
                            + "created_at FROM formula WHERE id = ?",
                    FORMULA_MAPPER, formulaId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<FormulaRow> findFormula(String productCode, int version) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, product_code, product_name, category_code, version, state, request_id, "
                            + "review_passed, review_json, reviewed_at, approved_snapshot, approved_at, "
                            + "created_at FROM formula WHERE product_code = ? AND version = ?",
                    FORMULA_MAPPER, productCode, version));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<DirectAdditive> findDirectAdditives(long formulaId) {        return jdbcTemplate.query(
                "SELECT additive_code, amount FROM formula_direct_additive WHERE formula_id = ? "
                        + "ORDER BY additive_code",
                (rs, n) -> new DirectAdditive(rs.getString("additive_code"),
                        rs.getBigDecimal("amount")),
                formulaId);
    }

    public List<IngredientUse> findIngredientUses(long formulaId) {
        return jdbcTemplate.query(
                "SELECT ingredient_code, ratio FROM formula_ingredient WHERE formula_id = ? "
                        + "ORDER BY ingredient_code",
                (rs, n) -> new IngredientUse(rs.getString("ingredient_code"),
                        rs.getBigDecimal("ratio")),
                formulaId);
    }

    /** 审查完成：记录逐项判定报告，通过则进入 REVIEWED，违例保持 SUBMITTED（整单拒绝，无部分记录）。 */
    public void markReviewed(long formulaId, boolean passed, String reviewJson, long reviewedAt) {
        jdbcTemplate.update(
                "UPDATE formula SET review_passed = ?, review_json = ?, reviewed_at = ?, state = ? "
                        + "WHERE id = ?",
                passed, reviewJson, reviewedAt,
                passed ? "REVIEWED" : "SUBMITTED", formulaId);
    }

    /** 批准：冻结批准版本快照。 */
    public void markApproved(long formulaId, String snapshotJson, long approvedAt) {
        jdbcTemplate.update(
                "UPDATE formula SET state = 'APPROVED', approved_snapshot = ?, approved_at = ? "
                        + "WHERE id = ?",
                snapshotJson, approvedAt, formulaId);
    }

    // ---------------- 备案 ----------------

    public long insertFiling(long formulaId, String productCode, int version, String status,
            String requestId, String snapshotJson, long createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO filing(formula_id, product_code, version, status, request_id, snapshot, "
                            + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, formulaId);
            ps.setString(2, productCode);
            ps.setInt(3, version);
            ps.setString(4, status);
            ps.setString(5, requestId);
            ps.setString(6, snapshotJson);
            ps.setLong(7, createdAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("备案主键生成失败");
        }
        return key.longValue();
    }

    public Optional<FilingRow> findFilingById(long filingId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, formula_id, product_code, version, status, request_id, snapshot, "
                            + "watch_start_day, suspend_reason, created_at, finalized_at "
                            + "FROM filing WHERE id = ?",
                    FILING_MAPPER, filingId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<FilingRow> findFilingByFormulaId(long formulaId) {        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, formula_id, product_code, version, status, request_id, snapshot, "
                            + "watch_start_day, suspend_reason, created_at, finalized_at "
                            + "FROM filing WHERE formula_id = ?",
                    FILING_MAPPER, formulaId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 加锁读取备案（SELECT ... FOR UPDATE），受控量产与暂停/注销竞态时建立统一提交顺序。 */
    public Optional<FilingRow> findFilingByIdForUpdate(long filingId) {
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

    /** 进入复审观察；已在观察期内的备案保留最早起始日，防止反复更新标准延长 90 日期限。 */
    public void markReviewWatch(long filingId, Long watchStartDay) {
        jdbcTemplate.update(
                "UPDATE filing SET status = 'REVIEW_WATCH', watch_start_day = COALESCE(watch_start_day, ?), "
                        + "suspend_reason = NULL WHERE id = ?",
                watchStartDay, filingId);
    }

    public void markActive(long filingId) {
        jdbcTemplate.update(
                "UPDATE filing SET status = 'ACTIVE', watch_start_day = NULL, suspend_reason = NULL "
                        + "WHERE id = ?",
                filingId);
    }

    public void markSuspended(long filingId, String reason, long finalizedAt) {
        jdbcTemplate.update(
                "UPDATE filing SET status = 'SUSPENDED', suspend_reason = ?, finalized_at = ? "
                        + "WHERE id = ?",
                reason, finalizedAt, filingId);
    }

    public void markCancelled(long filingId, long finalizedAt) {
        jdbcTemplate.update(
                "UPDATE filing SET status = 'CANCELLED', finalized_at = ? WHERE id = ?",
                finalizedAt, filingId);
    }

    /** 同一产品当前有效备案（ACTIVE/REVIEW_WATCH 均占用唯一有效位）。 */
    public List<FilingRow> findEffectiveFilingsByProduct(String productCode) {
        return jdbcTemplate.query(
                "SELECT id, formula_id, product_code, version, status, request_id, snapshot, "
                        + "watch_start_day, suspend_reason, created_at, finalized_at FROM filing "
                        + "WHERE product_code = ? AND status IN ('ACTIVE', 'REVIEW_WATCH')",
                FILING_MAPPER, productCode);
    }

    /** 产品全部配方版本轨迹（按版本升序）。 */
    public List<FormulaRow> findFormulaVersionsByProduct(String productCode) {
        return jdbcTemplate.query(
                "SELECT id, product_code, product_name, category_code, version, state, request_id, "
                        + "review_passed, review_json, reviewed_at, approved_snapshot, approved_at, "
                        + "created_at FROM formula WHERE product_code = ? ORDER BY version",
                FORMULA_MAPPER, productCode);
    }

    /** 产品全部备案轨迹（按创建先后升序）。 */
    public List<FilingRow> findFilingsByProduct(String productCode) {
        return jdbcTemplate.query(
                "SELECT id, formula_id, product_code, version, status, request_id, snapshot, "
                        + "watch_start_day, suspend_reason, created_at, finalized_at FROM filing "
                        + "WHERE product_code = ? ORDER BY id",
                FILING_MAPPER, productCode);
    }

    public List<FilingRow> findReviewWatchFilings() {
        return jdbcTemplate.query(
                "SELECT id, formula_id, product_code, version, status, request_id, snapshot, "
                        + "watch_start_day, suspend_reason, created_at, finalized_at FROM filing "
                        + "WHERE status = 'REVIEW_WATCH'",
                FILING_MAPPER);
    }

    /**
     * 查找受某条限量标准更新影响的有效备案：配方食品分类匹配，且直接或经复合配料带入使用了该添加剂。
     */
    public List<FilingRow> findFilingsAffectedByStandard(String categoryCode, String additiveCode) {
        String sql =
                "SELECT DISTINCT f.id, f.formula_id, f.product_code, f.version, f.status, f.request_id, "
                        + "f.snapshot, f.watch_start_day, f.suspend_reason, f.created_at, f.finalized_at "
                        + "FROM filing f JOIN formula fm ON fm.id = f.formula_id "
                        + "WHERE f.status IN ('ACTIVE', 'REVIEW_WATCH') AND fm.category_code = ? "
                        + "AND (EXISTS (SELECT 1 FROM formula_direct_additive d "
                        + "             WHERE d.formula_id = fm.id AND d.additive_code = ?) "
                        + "      OR EXISTS (SELECT 1 FROM formula_ingredient fi "
                        + "                 JOIN ingredient_additive ia "
                        + "                 ON ia.ingredient_code = fi.ingredient_code "
                        + "                 WHERE fi.formula_id = fm.id AND ia.additive_code = ?))";
        return jdbcTemplate.query(sql, FILING_MAPPER, categoryCode, additiveCode, additiveCode);
    }

    /** 占用产品唯一有效备案位；唯一冲突由调用方转成并发备案失败。 */
    public boolean tryOccupyProduct(String productCode, long filingId) {
        return jdbcTemplate.update(
                "INSERT INTO active_filing(product_code, filing_id) VALUES (?, ?)",
                productCode, filingId) > 0;
    }

    public void releaseProduct(String productCode) {
        jdbcTemplate.update("DELETE FROM active_filing WHERE product_code = ?", productCode);
    }

    public void insertFilingReview(long filingId, String requestId, boolean passed,
            String reportJson, long createdAt) {
        jdbcTemplate.update(
                "INSERT INTO filing_review(filing_id, request_id, passed, report_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                filingId, requestId, passed, reportJson, createdAt);
    }

    public void insertProductionRecord(long filingId, String batchNo, BigDecimal quantityKg,
            long reportedAt) {
        jdbcTemplate.update(
                "INSERT INTO production_record(filing_id, batch_no, quantity_kg, reported_at) "
                        + "VALUES (?, ?, ?, ?)",
                filingId, batchNo, quantityKg, reportedAt);
    }

    public long countProductionRecords(long filingId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM production_record WHERE filing_id = ?",
                Long.class, filingId);
        return count == null ? 0L : count;
    }

    // ---------------- requestId 幂等 ----------------

    public Optional<IdempotentRow> findIdempotent(String requestId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT request_id, fingerprint, status_code, response_json, created_at "
                            + "FROM idempotent_request WHERE request_id = ?",
                    IDEMPOTENT_MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void insertIdempotent(String requestId, String fingerprint, int statusCode,
            String responseJson, long createdAt) {
        jdbcTemplate.update(
                "INSERT INTO idempotent_request(request_id, fingerprint, status_code, response_json, "
                        + "created_at) VALUES (?, ?, ?, ?, ?)",
                requestId, fingerprint, statusCode, responseJson, createdAt);
    }

    /** 业务动作成功后回填首次响应（占位行 status_code=0、response_json 为 NULL）。 */
    public void completeIdempotent(String requestId, int statusCode, String responseJson) {
        jdbcTemplate.update(
                "UPDATE idempotent_request SET status_code = ?, response_json = ? WHERE request_id = ?",
                statusCode, responseJson, requestId);
    }

    // ---------------- 过敏原换配方清线（合成规则） ----------------

    public long countAllergens() {
        return count("allergen");
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

    public List<AllergenRow> findAllAllergens() {
        return jdbcTemplate.query(
                "SELECT allergen_code, allergen_name FROM allergen ORDER BY allergen_code",
                ALLERGEN_MAPPER);
    }

    /** 登记配方过敏原（version=1）；同一配方重复登记由唯一约束（主键）拒绝。 */
    public void insertFormulaAllergenReg(long formulaId, String requestId, long now) {
        jdbcTemplate.update(
                "INSERT INTO formula_allergen_reg(formula_id, version, request_id, created_at, updated_at) "
                        + "VALUES (?, 1, ?, ?, ?)",
                formulaId, requestId, now, now);
    }

    public void insertFormulaAllergenItem(long formulaId, String allergenCode) {
        jdbcTemplate.update(
                "INSERT INTO formula_allergen_item(formula_id, allergen_code) VALUES (?, ?)",
                formulaId, allergenCode);
    }

    public List<String> findFormulaAllergenItems(long formulaId) {
        return jdbcTemplate.queryForList(
                "SELECT allergen_code FROM formula_allergen_item WHERE formula_id = ? "
                        + "ORDER BY allergen_code",
                String.class, formulaId);
    }

    public Optional<FormulaAllergenRegRow> findFormulaAllergenReg(long formulaId) {
        try {
            FormulaAllergenRegRow row = jdbcTemplate.queryForObject(
                    "SELECT formula_id, version, request_id, created_at, updated_at "
                            + "FROM formula_allergen_reg WHERE formula_id = ?",
                    FORMULA_ALLERGEN_REG_MAPPER, formulaId);
            return Optional.of(row).map(r -> new FormulaAllergenRegRow(r.formulaId(), r.version(),
                    r.requestId(), r.createdAt(), r.updatedAt(), findFormulaAllergenItems(formulaId)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void deleteFormulaAllergenItems(long formulaId) {
        jdbcTemplate.update("DELETE FROM formula_allergen_item WHERE formula_id = ?", formulaId);
    }

    /**
     * 乐观版本递增：仅当当前 version 等于 expectedVersion 时 +1 并更新 requestId/时间，
     * 返回受影响行数（0 表示版本已被并发修改）。
     */
    public int bumpFormulaAllergenRegVersion(long formulaId, int expectedVersion, String requestId,
            long now) {
        return jdbcTemplate.update(
                "UPDATE formula_allergen_reg SET version = version + 1, request_id = ?, updated_at = ? "
                        + "WHERE formula_id = ? AND version = ?",
                requestId, now, formulaId, expectedVersion);
    }

    /** 登记生产线（occupied_version=1，version=1，最近量产版本为空）。 */
    public void insertProductionLine(String lineCode, String lineName, long now) {
        jdbcTemplate.update(
                "INSERT INTO production_line(line_code, line_name, last_formula_id, occupied_version, "
                        + "version, created_at, updated_at) VALUES (?, ?, NULL, 1, 1, ?, ?)",
                lineCode, lineName, now, now);
    }

    public Optional<ProductionLineRow> findProductionLine(String lineCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT line_code, line_name, last_formula_id, occupied_version, version, "
                            + "created_at, updated_at FROM production_line WHERE line_code = ?",
                    PRODUCTION_LINE_MAPPER, lineCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 加锁读取生产线（SELECT ... FOR UPDATE）：受控量产事务内串行化同一线路的并发提交，
     * 配合占用版本条件更新，保证同一占用版本最多一笔量产成功。
     */
    public Optional<ProductionLineRow> findProductionLineForUpdate(String lineCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT line_code, line_name, last_formula_id, occupied_version, version, "
                            + "created_at, updated_at FROM production_line WHERE line_code = ? FOR UPDATE",
                    PRODUCTION_LINE_MAPPER, lineCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 受控量产受理成功后的占用版本条件推进：仅当 occupied_version 仍等于声明显式携带的
     * expectedOccupiedVersion 时，写入最近量产配方并把占用版本 +1；返回 0 表示并发版本冲突。
     */
    public int advanceProductionLineForProduction(String lineCode, long formulaId,
            int expectedOccupiedVersion, long now) {
        return jdbcTemplate.update(
                "UPDATE production_line SET last_formula_id = ?, occupied_version = occupied_version + 1, "
                        + "version = version + 1, updated_at = ? WHERE line_code = ? AND occupied_version = ?",
                formulaId, now, lineCode, expectedOccupiedVersion);
    }

    public long insertClearanceCertificate(String lineCode, String operatorId, String reviewerId,
            long cleanedAt, long confirmedAt, int occupiedVersion, String coveredAllergensJson,
            String requestId, long now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO line_clearance_certificate(line_code, operator_id, reviewer_id, "
                            + "cleaned_at, confirmed_at, occupied_version, covered_allergens, status, "
                            + "request_id, consumed_at, controlled_production_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'VALID', ?, NULL, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lineCode);
            ps.setString(2, operatorId);
            ps.setString(3, reviewerId);
            ps.setLong(4, cleanedAt);
            ps.setLong(5, confirmedAt);
            ps.setInt(6, occupiedVersion);
            ps.setString(7, coveredAllergensJson);
            ps.setString(8, requestId);
            ps.setLong(9, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("清线凭证主键生成失败");
        }
        return key.longValue();
    }

    public Optional<ClearanceCertificateRow> findClearanceCertificate(long certificateId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, line_code, operator_id, reviewer_id, cleaned_at, confirmed_at, "
                            + "occupied_version, covered_allergens, status, request_id, consumed_at, "
                            + "controlled_production_id, created_at FROM line_clearance_certificate "
                            + "WHERE id = ?",
                    CERTIFICATE_MAPPER, certificateId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 加锁读取凭证，防止并发量产同时核销同一凭证。 */
    public Optional<ClearanceCertificateRow> findClearanceCertificateForUpdate(long certificateId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, line_code, operator_id, reviewer_id, cleaned_at, confirmed_at, "
                            + "occupied_version, covered_allergens, status, request_id, consumed_at, "
                            + "controlled_production_id, created_at FROM line_clearance_certificate "
                            + "WHERE id = ? FOR UPDATE",
                    CERTIFICATE_MAPPER, certificateId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 核销凭证：仅 VALID 凭证可核销，返回 0 表示已被前序量产核销。 */
    public int consumeClearanceCertificate(long certificateId, long controlledProductionId, long now) {
        return jdbcTemplate.update(
                "UPDATE line_clearance_certificate SET status = 'CONSUMED', consumed_at = ?, "
                        + "controlled_production_id = ? WHERE id = ? AND status = 'VALID'",
                now, controlledProductionId, certificateId);
    }

    public long insertControlledProduction(String lineCode, long filingId, long formulaId,
            String batchNo, BigDecimal quantityKg, int occupiedVersion, Long certificateId,
            long occurredAt, String requestId, long now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO controlled_production_record(line_code, filing_id, formula_id, batch_no, "
                            + "quantity_kg, occupied_version, certificate_id, occurred_at, request_id, "
                            + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lineCode);
            ps.setLong(2, filingId);
            ps.setLong(3, formulaId);
            ps.setString(4, batchNo);
            ps.setBigDecimal(5, quantityKg);
            ps.setInt(6, occupiedVersion);
            if (certificateId == null) {
                ps.setNull(7, java.sql.Types.BIGINT);
            } else {
                ps.setLong(7, certificateId);
            }
            ps.setLong(8, occurredAt);
            ps.setString(9, requestId);
            ps.setLong(10, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("受控量产记录主键生成失败");
        }
        return key.longValue();
    }

    public Optional<ControlledProductionRow> findControlledProduction(long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, line_code, filing_id, formula_id, batch_no, quantity_kg, "
                            + "occupied_version, certificate_id, occurred_at, request_id, created_at "
                            + "FROM controlled_production_record WHERE id = ?",
                    CONTROLLED_PRODUCTION_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ControlledProductionRow> findControlledProductionsByLine(String lineCode) {
        return jdbcTemplate.query(
                "SELECT id, line_code, filing_id, formula_id, batch_no, quantity_kg, "
                        + "occupied_version, certificate_id, occurred_at, request_id, created_at "
                        + "FROM controlled_production_record WHERE line_code = ? ORDER BY id",
                CONTROLLED_PRODUCTION_MAPPER, lineCode);
    }

    // ---------------- 测试清理 ----------------

    public void deleteBusinessData() {
        jdbcTemplate.update("DELETE FROM production_record");
        jdbcTemplate.update("DELETE FROM controlled_production_record");
        jdbcTemplate.update("DELETE FROM line_clearance_certificate");
        jdbcTemplate.update("DELETE FROM production_line");
        jdbcTemplate.update("DELETE FROM formula_allergen_item");
        jdbcTemplate.update("DELETE FROM formula_allergen_reg");
        jdbcTemplate.update("DELETE FROM filing_review");
        jdbcTemplate.update("DELETE FROM active_filing");
        jdbcTemplate.update("DELETE FROM filing");
        jdbcTemplate.update("DELETE FROM formula_direct_additive");
        jdbcTemplate.update("DELETE FROM formula_ingredient");
        jdbcTemplate.update("DELETE FROM formula");
        jdbcTemplate.update("DELETE FROM idempotent_request");
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private static final RowMapper<AdditiveRow> ADDITIVE_MAPPER = (rs, n) -> new AdditiveRow(
            rs.getString("additive_code"), rs.getString("additive_name"),
            rs.getString("function_code"));

    private static final RowMapper<LimitRow> LIMIT_MAPPER = (rs, n) -> new LimitRow(
            rs.getString("additive_code"), rs.getString("category_code"),
            rs.getBigDecimal("max_amount"), rs.getLong("updated_at"));

    private static final RowMapper<IngredientAdditiveRow> INGREDIENT_ADDITIVE_MAPPER = (rs, n) ->
            new IngredientAdditiveRow(rs.getString("ingredient_code"),
                    rs.getString("additive_code"), rs.getBigDecimal("concentration"));

    private static final RowMapper<FormulaRow> FORMULA_MAPPER = (rs, n) -> new FormulaRow(
            rs.getLong("id"), rs.getString("product_code"), rs.getString("product_name"),
            rs.getString("category_code"), rs.getInt("version"), rs.getString("state"),
            rs.getString("request_id"), (Boolean) rs.getObject("review_passed"),
            rs.getString("review_json"),
            rs.getObject("reviewed_at", Long.class),
            rs.getString("approved_snapshot"),
            rs.getObject("approved_at", Long.class),
            rs.getLong("created_at"));

    private static final RowMapper<FilingRow> FILING_MAPPER = (rs, n) -> new FilingRow(
            rs.getLong("id"), rs.getLong("formula_id"), rs.getString("product_code"),
            rs.getInt("version"), rs.getString("status"), rs.getString("request_id"),
            rs.getString("snapshot"),
            rs.getObject("watch_start_day", Long.class),
            rs.getString("suspend_reason"),
            rs.getLong("created_at"),
            rs.getObject("finalized_at", Long.class));

    private static final RowMapper<IdempotentRow> IDEMPOTENT_MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"), rs.getString("fingerprint"), rs.getInt("status_code"),
            rs.getString("response_json"), rs.getLong("created_at"));

    private static final RowMapper<AllergenRow> ALLERGEN_MAPPER = (rs, n) -> new AllergenRow(
            rs.getString("allergen_code"), rs.getString("allergen_name"));

    private static final RowMapper<FormulaAllergenRegRow> FORMULA_ALLERGEN_REG_MAPPER = (rs, n) ->
            new FormulaAllergenRegRow(rs.getLong("formula_id"), rs.getInt("version"),
                    rs.getString("request_id"), rs.getLong("created_at"),
                    rs.getLong("updated_at"), java.util.List.of());

    private static final RowMapper<ProductionLineRow> PRODUCTION_LINE_MAPPER = (rs, n) ->
            new ProductionLineRow(rs.getString("line_code"), rs.getString("line_name"),
                    rs.getObject("last_formula_id", Long.class), rs.getInt("occupied_version"),
                    rs.getInt("version"), rs.getLong("created_at"), rs.getLong("updated_at"));

    private static final RowMapper<ClearanceCertificateRow> CERTIFICATE_MAPPER = (rs, n) ->
            new ClearanceCertificateRow(rs.getLong("id"), rs.getString("line_code"),
                    rs.getString("operator_id"), rs.getString("reviewer_id"),
                    rs.getLong("cleaned_at"), rs.getLong("confirmed_at"),
                    rs.getInt("occupied_version"),
                    parseStringList(rs.getString("covered_allergens")),
                    rs.getString("status"), rs.getString("request_id"),
                    rs.getObject("consumed_at", Long.class),
                    rs.getObject("controlled_production_id", Long.class),
                    rs.getLong("created_at"));

    private static final RowMapper<ControlledProductionRow> CONTROLLED_PRODUCTION_MAPPER = (rs, n) ->
            new ControlledProductionRow(rs.getLong("id"), rs.getString("line_code"),
                    rs.getLong("filing_id"), rs.getLong("formula_id"), rs.getString("batch_no"),
                    rs.getBigDecimal("quantity_kg"), rs.getInt("occupied_version"),
                    rs.getObject("certificate_id", Long.class), rs.getLong("occurred_at"),
                    rs.getString("request_id"), rs.getLong("created_at"));

    /** 解析覆盖过敏原 JSON 数组为升序字符串列表。 */
    static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
            return values.stream().sorted().toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
