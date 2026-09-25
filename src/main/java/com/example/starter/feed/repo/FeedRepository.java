package com.example.starter.feed.repo;

import com.example.starter.feed.domain.AlertType;
import com.example.starter.feed.domain.BatchMedication;
import com.example.starter.feed.domain.BatchStatus;
import com.example.starter.feed.domain.ComplianceAlert;
import com.example.starter.feed.domain.FarmBatch;
import com.example.starter.feed.domain.FormulaItem;
import com.example.starter.feed.domain.FormulaVersion;
import com.example.starter.feed.domain.FormulaVersionStatus;
import com.example.starter.feed.domain.IdempotentRequest;
import com.example.starter.feed.domain.Ingredient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 饲料合规模块数据访问；所有 SQL 参数化，时间统一以 UTC Instant 与 TIMESTAMP 互换（epoch 毫秒不丢失）。
 */
@Repository
public class FeedRepository {

    private final JdbcTemplate jdbc;

    public FeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 目录 ----------------

    /** 查询单个原料/药物目录项；不存在返回 null。 */
    public Ingredient findIngredient(String code) {
        List<Ingredient> list = jdbc.query(
                "SELECT code, name, medicated, max_ratio, withdrawal_days FROM ingredient WHERE code = ?",
                (rs, n) -> new Ingredient(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getInt("medicated") == 1,
                        rs.getBigDecimal("max_ratio"),
                        (Integer) rs.getObject("withdrawal_days")),
                code);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 目录是否为空（初始化判定用）。 */
    public long countIngredients() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ingredient", Long.class);
        return count == null ? 0 : count;
    }

    /** 写入一条合成目录项。 */
    public void insertIngredient(Ingredient ingredient) {
        jdbc.update("INSERT INTO ingredient(code, name, medicated, max_ratio, withdrawal_days) VALUES (?, ?, ?, ?, ?)",
                ingredient.code(),
                ingredient.name(),
                ingredient.medicated() ? 1 : 0,
                ingredient.maxRatio(),
                ingredient.withdrawalDays());
    }

    /** 写入一条配伍禁忌（无向对，调用方保证 a<b 字典序）。 */
    public void insertContraindication(String a, String b) {
        jdbc.update("INSERT INTO contraindication(ingredient_a, ingredient_b) VALUES (?, ?)", a, b);
    }

    /** 判断两个药物编码是否命中禁忌清单（无向）。 */
    public boolean isContraindicated(String codeA, String codeB) {
        String a = codeA.compareTo(codeB) <= 0 ? codeA : codeB;
        String b = codeA.compareTo(codeB) <= 0 ? codeB : codeA;
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contraindication WHERE ingredient_a = ? AND ingredient_b = ?",
                Integer.class, a, b);
        return cnt != null && cnt > 0;
    }

    /** 加载全部禁忌对键（{@code A|B} 字典序口径，供合规校验一次性使用）。 */
    public java.util.Set<String> findAllContraPairs() {
        return new java.util.HashSet<>(jdbc.query(
                "SELECT ingredient_a, ingredient_b FROM contraindication",
                (rs, n) -> rs.getString("ingredient_a") + "|" + rs.getString("ingredient_b")));
    }

    // ---------------- 养殖批次 ----------------

    /** 查询批次；不存在返回 null。 */
    public FarmBatch findBatch(String batchNo) {
        List<FarmBatch> list = jdbc.query(
                "SELECT batch_no, species, status, created_at, slaughtered_at FROM farm_batch WHERE batch_no = ?",
                (rs, n) -> mapBatch(rs),
                batchNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 事务内对批次行加锁查询（并发投产/出栏串行化用）；不存在返回 null。 */
    public FarmBatch findBatchForUpdate(String batchNo) {
        List<FarmBatch> list = jdbc.query(
                "SELECT batch_no, species, status, created_at, slaughtered_at FROM farm_batch WHERE batch_no = ? FOR UPDATE",
                (rs, n) -> mapBatch(rs),
                batchNo);
        return list.isEmpty() ? null : list.get(0);
    }

    private FarmBatch mapBatch(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp slaughteredAt = rs.getTimestamp("slaughtered_at");
        return new FarmBatch(
                rs.getString("batch_no"),
                rs.getString("species"),
                BatchStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                slaughteredAt == null ? null : slaughteredAt.toInstant());
    }

    /** 创建合成养殖批次（批次号冲突时忽略）。 */
    public void insertBatchIfAbsent(String batchNo, String species, Instant now) {
        jdbc.update("INSERT INTO farm_batch(batch_no, species, status, created_at) VALUES (?, ?, 'ACTIVE', ?)",
                batchNo, species, Timestamp.from(now));
    }

    /**
     * 原子出栏：仅 ACTIVE 行可更新。
     *
     * @return 受影响行数；1 表示本次成功出栏，0 表示已是终态（含并发竞争失败）。
     */
    public int markSlaughtered(String batchNo, Instant at) {
        return jdbc.update(
                "UPDATE farm_batch SET status = 'SLAUGHTERED', slaughtered_at = ? WHERE batch_no = ? AND status = 'ACTIVE'",
                Timestamp.from(at), batchNo);
    }

    // ---------------- 配方版本 ----------------

    /**
     * 在事务/行锁内分配下一版本号：配方不存在则建档（版本从 1 开始）。
     * 不同 requestId 并发提交同一配方时，靠主键冲突回退到“加锁读 + 递增”路径。
     */
    public int allocateVersionNo(String formulaCode, Instant now) {
        List<Integer> currents = jdbc.queryForList(
                "SELECT latest_version FROM formula WHERE code = ? FOR UPDATE", Integer.class, formulaCode);
        if (currents.isEmpty()) {
            try {
                jdbc.update("INSERT INTO formula(code, latest_version, created_at) VALUES (?, 1, ?)",
                        formulaCode, Timestamp.from(now));
                return 1;
            } catch (DuplicateKeyException concurrentInsert) {
                currents = jdbc.queryForList(
                        "SELECT latest_version FROM formula WHERE code = ? FOR UPDATE", Integer.class, formulaCode);
            }
        }
        int next = currents.get(0) + 1;
        jdbc.update("UPDATE formula SET latest_version = ? WHERE code = ?", next, formulaCode);
        return next;
    }

    /** 新建版本行（SUBMITTED 或 REJECTED）。 */
    public void insertVersion(FormulaVersion v) {
        jdbc.update("""
                        INSERT INTO formula_version
                            (formula_code, version_no, status, target_species, medicated,
                             fingerprint, reject_reasons, created_at, approved_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                v.formulaCode(),
                v.versionNo(),
                v.status().name(),
                v.targetSpecies(),
                v.medicated() ? 1 : 0,
                v.fingerprint(),
                v.rejectReasons().isEmpty() ? null : String.join("||", v.rejectReasons()),
                Timestamp.from(v.createdAt()),
                v.approvedAt() == null ? null : Timestamp.from(v.approvedAt()));
    }

    /** 批量写入版本组成项快照。 */
    public void insertItems(String formulaCode, int versionNo, List<FormulaItem> items) {
        for (FormulaItem item : items) {
            jdbc.update(
                    "INSERT INTO formula_item(formula_code, version_no, ingredient_code, ratio) VALUES (?, ?, ?, ?)",
                    formulaCode, versionNo, item.ingredientCode(), item.ratio());
        }
    }

    /** 查询指定版本；不存在返回 null。 */
    public FormulaVersion findVersion(String formulaCode, int versionNo) {
        List<FormulaVersion> list = jdbc.query(
                """
                        SELECT id, formula_code, version_no, status, target_species, medicated,
                               fingerprint, reject_reasons, created_at, approved_at
                        FROM formula_version WHERE formula_code = ? AND version_no = ?""",
                (rs, n) -> mapVersion(rs),
                formulaCode, versionNo);
        if (list.isEmpty()) {
            return null;
        }
        FormulaVersion v = list.get(0);
        return new FormulaVersion(v.formulaCode(), v.versionNo(), v.status(), v.targetSpecies(), v.medicated(),
                v.fingerprint(), v.rejectReasons(), v.createdAt(), v.approvedAt(), findItems(formulaCode, versionNo));
    }

    /** 查询配方最新已批准冻结版本；不存在返回 null。 */
    public FormulaVersion findLatestApproved(String formulaCode) {
        List<Integer> versions = jdbc.queryForList(
                "SELECT version_no FROM formula_version WHERE formula_code = ? AND status = 'APPROVED' "
                        + "ORDER BY version_no DESC LIMIT 1",
                Integer.class, formulaCode);
        return versions.isEmpty() ? null : findVersion(formulaCode, versions.get(0));
    }

    private List<FormulaItem> findItems(String formulaCode, int versionNo) {
        return jdbc.query(
                "SELECT ingredient_code, ratio FROM formula_item WHERE formula_code = ? AND version_no = ?",
                (rs, n) -> new FormulaItem(rs.getString("ingredient_code"), rs.getBigDecimal("ratio")),
                formulaCode, versionNo);
    }

    private FormulaVersion mapVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        String reasons = rs.getString("reject_reasons");
        return new FormulaVersion(
                rs.getString("formula_code"),
                rs.getInt("version_no"),
                FormulaVersionStatus.valueOf(rs.getString("status")),
                rs.getString("target_species"),
                rs.getInt("medicated") == 1,
                rs.getString("fingerprint"),
                reasons == null || reasons.isBlank() ? List.of() : List.of(reasons.split("\\|\\|")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant(),
                List.of());
    }

    /**
     * 批准版本（冻结）：仅 SUBMITTED 可被更新。
     *
     * @return 受影响行数；0 表示状态不允许（REJECTED 或已 APPROVED）。
     */
    public int approveVersion(String formulaCode, int versionNo, Instant now) {
        return jdbc.update(
                "UPDATE formula_version SET status = 'APPROVED', approved_at = ? "
                        + "WHERE formula_code = ? AND version_no = ? AND status = 'SUBMITTED'",
                Timestamp.from(now), formulaCode, versionNo);
    }

    // ---------------- 投产 / 休药期跟踪 ----------------

    /** 登记一条批次含药义务行（投产或迟到饲喂）。 */
    public void insertMedication(BatchMedication m) {
        jdbc.update("""
                        INSERT INTO batch_medication
                            (batch_no, formula_code, version_no, last_feeding_time,
                             max_withdrawal_days, created_at, late)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""",
                m.batchNo(),
                m.formulaCode(),
                m.versionNo(),
                Timestamp.from(m.lastFeedingTime()),
                m.maxWithdrawalDays(),
                Timestamp.from(m.createdAt()),
                m.late() ? 1 : 0);
    }

    /** 查询批次全部非迟到的含药义务行（休药期可据此复算）。 */
    public List<BatchMedication> findActiveMedications(String batchNo) {
        return jdbc.query(
                """
                        SELECT id, batch_no, formula_code, version_no, last_feeding_time,
                               max_withdrawal_days, created_at, late
                        FROM batch_medication WHERE batch_no = ? AND late = 0 ORDER BY id""",
                (rs, n) -> mapMedication(rs),
                batchNo);
    }

    /** 查询批次全部含药义务行（含出栏后迟到事件，供轨迹复算）。 */
    public List<BatchMedication> findAllMedications(String batchNo) {
        return jdbc.query(
                """
                        SELECT id, batch_no, formula_code, version_no, last_feeding_time,
                               max_withdrawal_days, created_at, late
                        FROM batch_medication WHERE batch_no = ? ORDER BY id""",
                (rs, n) -> mapMedication(rs),
                batchNo);
    }

    private BatchMedication mapMedication(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchMedication(
                rs.getLong("id"),
                rs.getString("batch_no"),
                rs.getString("formula_code"),
                rs.getInt("version_no"),
                rs.getTimestamp("last_feeding_time").toInstant(),
                rs.getInt("max_withdrawal_days"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("late") == 1);
    }

    // ---------------- 告警 ----------------

    /** 写入一条合规告警。 */
    public void insertAlert(String batchNo, AlertType type, String detail, Instant now) {
        jdbc.update("INSERT INTO compliance_alert(batch_no, alert_type, detail, created_at) VALUES (?, ?, ?, ?)",
                batchNo, type.name(), detail, Timestamp.from(now));
    }

    /** 查询批次全部告警。 */
    public List<ComplianceAlert> findAlerts(String batchNo) {
        return jdbc.query("SELECT id, batch_no, alert_type, detail, created_at FROM compliance_alert WHERE batch_no = ?",
                (rs, n) -> new ComplianceAlert(
                        rs.getLong("id"),
                        rs.getString("batch_no"),
                        AlertType.valueOf(rs.getString("alert_type")),
                        rs.getString("detail"),
                        rs.getTimestamp("created_at").toInstant()),
                batchNo);
    }

    // ---------------- 幂等台账 ----------------

    /** 读取幂等台账行；不存在返回 null。 */
    public IdempotentRequest findIdempotent(String requestId) {
        List<IdempotentRequest> list = jdbc.query(
                "SELECT request_id, operation, fingerprint, response_json, created_at FROM idempotent_request WHERE request_id = ?",
                (rs, n) -> mapIdempotent(rs),
                requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 事务内对幂等台账行加锁读取（同键并发请求串行化）；不存在返回 null。 */
    public IdempotentRequest lockIdempotent(String requestId) {
        List<IdempotentRequest> list = jdbc.query(
                "SELECT request_id, operation, fingerprint, response_json, created_at FROM idempotent_request WHERE request_id = ? FOR UPDATE",
                (rs, n) -> mapIdempotent(rs),
                requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    private IdempotentRequest mapIdempotent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IdempotentRequest(
                rs.getString("request_id"),
                rs.getString("operation"),
                rs.getString("fingerprint"),
                rs.getString("response_json"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 写入幂等台账；并发重放导致唯一键冲突时返回 false（调用方应重新读取）。
     */
    public boolean insertIdempotentIfAbsent(IdempotentRequest row) {
        try {
            jdbc.update(
                    "INSERT INTO idempotent_request(request_id, operation, fingerprint, response_json, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    row.requestId(), row.operation(), row.fingerprint(), row.responseJson(),
                    Timestamp.from(row.createdAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
