package com.example.starter.food.repo;

import com.example.starter.food.domain.FilingStatus;
import com.example.starter.food.domain.RecipeStatus;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 食品合规模块数据访问；所有时间字段以 UTC 写入。
 */
@Repository
public class FoodRepository {

    private final JdbcTemplate jdbc;

    public FoodRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 标准版本 ----------------

    public boolean standardExists(String version) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(1) FROM standard_version WHERE version = ?", Integer.class, version);
        return c != null && c > 0;
    }

    public void insertStandardVersion(String version, Instant publishedAt) {
        jdbc.update("INSERT INTO standard_version (version, published_at) VALUES (?, ?)",
                version, Timestamp.from(publishedAt));
    }

    public Instant getStandardPublishedAt(String version) {
        List<Instant> r = jdbc.query(
                "SELECT published_at FROM standard_version WHERE version = ?",
                (rs, n) -> rs.getTimestamp(1).toInstant(), version);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 取 at 时刻（含）已发布的最新标准版本；无则返回 null。 */
    public String latestStandardVersion(Instant at) {
        List<String> r = jdbc.query(
                "SELECT version FROM standard_version WHERE published_at <= ? ORDER BY published_at DESC, version DESC LIMIT 1",
                (rs, n) -> rs.getString(1), Timestamp.from(at));
        return r.isEmpty() ? null : r.get(0);
    }

    // ---------------- 目录 / 限量 / 复合配料 ----------------

    public AdditiveInfo getAdditive(String code) {
        List<AdditiveInfo> r = jdbc.query(
                "SELECT additive_code, name_cn, function_type, unit FROM additive_catalog WHERE additive_code = ?",
                (rs, n) -> new AdditiveInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                code);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 指定标准版本下某添加剂在某食品分类的最大使用量 g/kg；未规定返回 null。 */
    public BigDecimal getLimit(String additiveCode, String categoryCode, String standardVersion) {
        List<BigDecimal> r = jdbc.query(
                "SELECT max_amount FROM additive_limit WHERE additive_code = ? AND category_code = ? AND standard_version = ?",
                (rs, n) -> rs.getBigDecimal(1), additiveCode, categoryCode, standardVersion);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 复合配料中各添加剂含量（以配料自身质量计，g/kg），保持目录顺序。 */
    public Map<String, BigDecimal> compoundAdditives(String compoundCode) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        jdbc.query("SELECT additive_code, amount FROM compound_ingredient_additive WHERE compound_code = ? ORDER BY id",
                rs -> {
                    map.put(rs.getString("additive_code"), rs.getBigDecimal("amount"));
                }, compoundCode);
        return map;
    }

    // ---------------- 配方 ----------------

    /** 插入配方主表，返回新主键。 */
    public long insertRecipe(String requestId, String productCode, String recipeVersion, String categoryCode,
                             String fingerprint, Instant now) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO recipe (request_id, product_code, recipe_version, category_code, status, fingerprint, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, productCode);
            ps.setString(3, recipeVersion);
            ps.setString(4, categoryCode);
            ps.setString(5, RecipeStatus.SUBMITTED.name());
            ps.setString(6, fingerprint);
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void insertRecipeAdditive(long recipeId, String additiveCode, BigDecimal amount, int seq) {
        jdbc.update("INSERT INTO recipe_additive (recipe_id, additive_code, amount, seq_no) VALUES (?, ?, ?, ?)",
                recipeId, additiveCode, amount, seq);
    }

    public void insertRecipeIngredient(long recipeId, String compoundCode, BigDecimal proportion, int seq) {
        jdbc.update("INSERT INTO recipe_ingredient (recipe_id, compound_code, proportion, seq_no) VALUES (?, ?, ?, ?)",
                recipeId, compoundCode, proportion, seq);
    }

    public RecipeRow findRecipeById(long id) {
        List<RecipeRow> r = jdbc.query(
                "SELECT * FROM recipe WHERE id = ?", (rs, n) -> RecipeRow.from(rs), id);
        return r.isEmpty() ? null : r.get(0);
    }

    public RecipeRow findRecipeByRequestId(String requestId) {
        List<RecipeRow> r = jdbc.query(
                "SELECT * FROM recipe WHERE request_id = ?", (rs, n) -> RecipeRow.from(rs), requestId);
        return r.isEmpty() ? null : r.get(0);
    }

    public List<Map<String, Object>> additivesOf(long recipeId) {
        return jdbc.queryForList(
                "SELECT additive_code AS additiveCode, amount FROM recipe_additive WHERE recipe_id = ? ORDER BY seq_no",
                recipeId);
    }

    public List<Map<String, Object>> ingredientsOf(long recipeId) {
        return jdbc.queryForList(
                "SELECT compound_code AS compoundCode, proportion FROM recipe_ingredient WHERE recipe_id = ? ORDER BY seq_no",
                recipeId);
    }

    /** 写入审查结论与逐项明细（COMPLIANT 或 REJECTED）。 */
    public void saveReviewResult(long recipeId, RecipeStatus status, String detailJson,
                                 String standardVersion, Instant reviewedAt) {
        jdbc.update("UPDATE recipe SET status = ?, review_detail = ?, reviewed_std = ?, reviewed_at = ? WHERE id = ?",
                status.name(), detailJson, standardVersion, Timestamp.from(reviewedAt), recipeId);
    }

    /** 批准：冻结版本快照。 */
    public void approveRecipe(long recipeId, String snapshotJson, String approvedStd, Instant approvedAt) {
        jdbc.update("UPDATE recipe SET status = ?, snapshot_json = ?, approved_std = ?, approved_at = ? WHERE id = ?",
                RecipeStatus.APPROVED.name(), snapshotJson, approvedStd, Timestamp.from(approvedAt), recipeId);
    }

    // ---------------- 备案 ----------------

    /** 插入备案；同一产品已有有效备案时由唯一约束 uk_filing_active 拒绝。 */
    public long insertFiling(String requestId, String productCode, long recipeId, String recipeVersion,
                             String standardVersion, FilingStatus status, String snapshotJson,
                             Instant filedAt, LocalDate reviewDueDate) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO filing (request_id, product_code, recipe_id, recipe_version, standard_version, "
                            + "status, snapshot_json, filed_at, review_due_date, active_token) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, productCode);
            ps.setLong(3, recipeId);
            ps.setString(4, recipeVersion);
            ps.setString(5, standardVersion);
            ps.setString(6, status.name());
            ps.setString(7, snapshotJson);
            ps.setTimestamp(8, Timestamp.from(filedAt));
            if (reviewDueDate == null) {
                ps.setNull(9, java.sql.Types.DATE);
            } else {
                ps.setDate(9, java.sql.Date.valueOf(reviewDueDate));
            }
            ps.setString(10, status == FilingStatus.ACTIVE || status == FilingStatus.REVIEW_PENDING ? "X" : null);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public FilingRow findFilingById(long id) {
        List<FilingRow> r = jdbc.query("SELECT * FROM filing WHERE id = ?", (rs, n) -> FilingRow.from(rs), id);
        return r.isEmpty() ? null : r.get(0);
    }

    public FilingRow findFilingByRequestId(String requestId) {
        List<FilingRow> r = jdbc.query("SELECT * FROM filing WHERE request_id = ?",
                (rs, n) -> FilingRow.from(rs), requestId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 查询产品当前唯一有效（ACTIVE 或 REVIEW_PENDING）备案。 */
    public FilingRow findEffectiveFiling(String productCode) {
        List<FilingRow> r = jdbc.query(
                "SELECT * FROM filing WHERE product_code = ? AND active_token = 'X'",
                (rs, n) -> FilingRow.from(rs), productCode);
        return r.isEmpty() ? null : r.get(0);
    }

    public List<FilingRow> listActiveFilings() {
        return jdbc.query("SELECT * FROM filing WHERE status = 'ACTIVE' ORDER BY id",
                (rs, n) -> FilingRow.from(rs));
    }

    public List<FilingRow> listReviewPendingFilings() {
        return jdbc.query("SELECT * FROM filing WHERE status = 'REVIEW_PENDING' ORDER BY id",
                (rs, n) -> FilingRow.from(rs));
    }

    public void markReviewPending(long filingId, LocalDate dueDate) {
        jdbc.update("UPDATE filing SET status = 'REVIEW_PENDING', review_due_date = ?, active_token = 'X' WHERE id = ?",
                java.sql.Date.valueOf(dueDate), filingId);
    }

    public void markReviewedActive(long filingId, String reviewedStd, Instant reviewedAt) {
        jdbc.update("UPDATE filing SET status = 'ACTIVE', reviewed_std = ?, reviewed_at = ?, "
                        + "review_due_date = NULL, active_token = 'X' WHERE id = ?",
                reviewedStd, Timestamp.from(reviewedAt), filingId);
    }

    public void suspendFiling(long filingId, String reason, Instant at) {
        jdbc.update("UPDATE filing SET status = 'SUSPENDED', suspended_at = ?, suspend_reason = ?, active_token = NULL WHERE id = ?",
                Timestamp.from(at), reason, filingId);
    }

    public void cancelFiling(long filingId, Instant at) {
        jdbc.update("UPDATE filing SET status = 'CANCELLED', cancelled_at = ?, active_token = NULL WHERE id = ?",
                Timestamp.from(at), filingId);
    }

    // ---------------- 量产记录 ----------------

    public long insertProductionReport(long filingId, String batchNo, BigDecimal quantityKg, Instant at) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO production_report (filing_id, batch_no, quantity_kg, reported_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, filingId);
            ps.setString(2, batchNo);
            ps.setBigDecimal(3, quantityKg);
            ps.setTimestamp(4, Timestamp.from(at));
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    // ---------------- 幂等记录 ----------------

    public StoredResponse findIdempotent(String requestId) {
        List<StoredResponse> r = jdbc.query(
                "SELECT fingerprint, status_code, response FROM idempotent_request WHERE request_id = ?",
                (rs, n) -> new StoredResponse(rs.getString(1), rs.getInt(2), rs.getString(3)),
                requestId);
        return r.isEmpty() ? null : r.get(0);
    }

    public void insertIdempotent(String requestId, String fingerprint, int statusCode, String responseJson) {
        jdbc.update("INSERT INTO idempotent_request (request_id, fingerprint, status_code, response, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                requestId, fingerprint, statusCode, responseJson, Timestamp.from(Instant.now()));
    }
}
