package com.example.starter.forestry.repo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 采伐监管数据访问层：基于 JdbcTemplate 的参数化 SQL。
 * 并发控制依赖行级悲观锁（SELECT ... FOR UPDATE）与唯一约束。
 */
@Repository
public class ForestryRepository {

    private final JdbcTemplate jdbc;

    public ForestryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 行记录 ----------

    /** 小班档案行。 */
    public record PlotRow(long id, String code, String forestType, boolean inNoHarvestZone,
                          int standAge, int matureAge, String speciesTier, BigDecimal areaHa) {
    }

    /** 年度限额行。 */
    public record QuotaRow(long id, int quotaYear, String speciesTier,
                           BigDecimal quotaVolume, BigDecimal usedVolume) {
    }

    /** 采伐申请行。 */
    public record ApplicationRow(long id, String requestId, String fingerprint, long plotId,
                                 String operatorId, int applyYear, BigDecimal volume,
                                 String status, String rejectReason, LocalDateTime createdAt) {
    }

    /** 采伐许可证行。 */
    public record LicenseRow(long id, String requestId, String fingerprint, long applicationId,
                             long plotId, String operatorId, int quotaYear, String speciesTier,
                             BigDecimal volume, LocalDate issueDate, LocalDate expireDate,
                             String status, LocalDate confirmDate, LocalDate voidDate,
                             LocalDateTime archivedAt, int version) {
    }

    /** 迹地更新任务行。 */
    public record TaskRow(long id, long licenseId, long plotId, String operatorId,
                          LocalDate deadline, String status, int round,
                          LocalDate replantDeadline, BigDecimal finalRate, LocalDateTime createdAt) {
    }

    /** 验收记录行。 */
    public record AcceptanceRow(long id, long taskId, int round, String requestId, String fingerprint,
                                BigDecimal passRate, String result, LocalDateTime createdAt) {
    }

    /** 限批名单行。 */
    public record BlacklistRow(long id, String operatorId, LocalDate bannedUntil,
                               String reason, LocalDateTime createdAt) {
    }

    /** 迟到事件行。 */
    public record LateEventRow(long id, String entityType, long entityId, String eventType,
                               String payload, LocalDateTime createdAt) {
    }

    // ---------- 行映射 ----------

    private static final RowMapper<PlotRow> PLOT_MAPPER = (rs, n) -> new PlotRow(
            rs.getLong("id"), rs.getString("code"), rs.getString("forest_type"),
            rs.getBoolean("in_no_harvest_zone"), rs.getInt("stand_age"), rs.getInt("mature_age"),
            rs.getString("species_tier"), rs.getBigDecimal("area_ha"));

    private static final RowMapper<QuotaRow> QUOTA_MAPPER = (rs, n) -> new QuotaRow(
            rs.getLong("id"), rs.getInt("quota_year"), rs.getString("species_tier"),
            rs.getBigDecimal("quota_volume"), rs.getBigDecimal("used_volume"));

    private static final RowMapper<ApplicationRow> APPLICATION_MAPPER = (rs, n) -> new ApplicationRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getLong("plot_id"), rs.getString("operator_id"), rs.getInt("apply_year"),
            rs.getBigDecimal("volume"), rs.getString("status"), rs.getString("reject_reason"),
            rs.getObject("created_at", LocalDateTime.class));

    private static final RowMapper<LicenseRow> LICENSE_MAPPER = (rs, n) -> new LicenseRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getLong("application_id"), rs.getLong("plot_id"), rs.getString("operator_id"),
            rs.getInt("quota_year"), rs.getString("species_tier"), rs.getBigDecimal("volume"),
            rs.getObject("issue_date", LocalDate.class), rs.getObject("expire_date", LocalDate.class),
            rs.getString("status"), rs.getObject("confirm_date", LocalDate.class),
            rs.getObject("void_date", LocalDate.class),
            rs.getObject("archived_at", LocalDateTime.class), rs.getInt("version"));

    private static final RowMapper<TaskRow> TASK_MAPPER = (rs, n) -> new TaskRow(
            rs.getLong("id"), rs.getLong("license_id"), rs.getLong("plot_id"),
            rs.getString("operator_id"), rs.getObject("deadline", LocalDate.class),
            rs.getString("status"), rs.getInt("round"),
            rs.getObject("replant_deadline", LocalDate.class), rs.getBigDecimal("final_rate"),
            rs.getObject("created_at", LocalDateTime.class));

    private static final RowMapper<AcceptanceRow> ACCEPTANCE_MAPPER = (rs, n) -> new AcceptanceRow(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("round"),
            rs.getString("request_id"), rs.getString("fingerprint"), rs.getBigDecimal("pass_rate"),
            rs.getString("result"), rs.getObject("created_at", LocalDateTime.class));

    private static final RowMapper<BlacklistRow> BLACKLIST_MAPPER = (rs, n) -> new BlacklistRow(
            rs.getLong("id"), rs.getString("operator_id"),
            rs.getObject("banned_until", LocalDate.class), rs.getString("reason"),
            rs.getObject("created_at", LocalDateTime.class));

    private static final RowMapper<LateEventRow> LATE_EVENT_MAPPER = (rs, n) -> new LateEventRow(
            rs.getLong("id"), rs.getString("entity_type"), rs.getLong("entity_id"),
            rs.getString("event_type"), rs.getString("payload"),
            rs.getObject("created_at", LocalDateTime.class));

    // ---------- 小班 ----------

    public Optional<PlotRow> findPlotByCode(String code) {
        return jdbc.query("SELECT * FROM forest_plot WHERE code = ?", PLOT_MAPPER, code)
                .stream().findFirst();
    }

    public Optional<PlotRow> findPlotById(long id) {
        return jdbc.query("SELECT * FROM forest_plot WHERE id = ?", PLOT_MAPPER, id)
                .stream().findFirst();
    }

    /** 行锁读取小班，用于发证时串行化同一小班的有效证校验。 */
    public Optional<PlotRow> lockPlotById(long id) {
        return jdbc.query("SELECT * FROM forest_plot WHERE id = ? FOR UPDATE", PLOT_MAPPER, id)
                .stream().findFirst();
    }

    public long insertPlot(String code, String forestType, boolean inNoHarvestZone, int standAge,
                           int matureAge, String speciesTier, BigDecimal areaHa) {
        return insert("INSERT INTO forest_plot (code, forest_type, in_no_harvest_zone, stand_age,"
                        + " mature_age, species_tier, area_ha) VALUES (?,?,?,?,?,?,?)",
                code, forestType, inNoHarvestZone, standAge, matureAge, speciesTier, areaHa);
    }

    // ---------- 年度限额 ----------

    public Optional<QuotaRow> findQuota(int year, String tier) {
        return jdbc.query("SELECT * FROM annual_quota WHERE quota_year = ? AND species_tier = ?",
                QUOTA_MAPPER, year, tier).stream().findFirst();
    }

    /** 行锁读取限额档位，发证占用与作废释放均须先取锁，保证同一档位串行校验。 */
    public Optional<QuotaRow> lockQuota(int year, String tier) {
        return jdbc.query("SELECT * FROM annual_quota WHERE quota_year = ? AND species_tier = ? FOR UPDATE",
                QUOTA_MAPPER, year, tier).stream().findFirst();
    }

    public void updateQuotaUsed(long quotaId, BigDecimal usedVolume) {
        jdbc.update("UPDATE annual_quota SET used_volume = ? WHERE id = ?", usedVolume, quotaId);
    }

    public long insertQuota(int year, String tier, BigDecimal quotaVolume) {
        return insert("INSERT INTO annual_quota (quota_year, species_tier, quota_volume, used_volume)"
                + " VALUES (?,?,?,0)", year, tier, quotaVolume);
    }

    // ---------- 采伐申请 ----------

    public Optional<ApplicationRow> findApplicationByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM harvest_application WHERE request_id = ?",
                APPLICATION_MAPPER, requestId).stream().findFirst();
    }

    public Optional<ApplicationRow> findApplicationById(long id) {
        return jdbc.query("SELECT * FROM harvest_application WHERE id = ?",
                APPLICATION_MAPPER, id).stream().findFirst();
    }

    /** 行锁读取申请，发证时防止同一申请并发发证。 */
    public Optional<ApplicationRow> lockApplicationById(long id) {
        return jdbc.query("SELECT * FROM harvest_application WHERE id = ? FOR UPDATE",
                APPLICATION_MAPPER, id).stream().findFirst();
    }

    public long insertApplication(String requestId, String fingerprint, long plotId, String operatorId,
                                  int applyYear, BigDecimal volume, String status, String rejectReason,
                                  LocalDateTime createdAt) {
        return insert("INSERT INTO harvest_application (request_id, fingerprint, plot_id, operator_id,"
                        + " apply_year, volume, status, reject_reason, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                requestId, fingerprint, plotId, operatorId, applyYear, volume, status, rejectReason, createdAt);
    }

    // ---------- 采伐许可证 ----------

    public Optional<LicenseRow> findLicenseByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM harvest_license WHERE request_id = ?",
                LICENSE_MAPPER, requestId).stream().findFirst();
    }

    public Optional<LicenseRow> findLicenseById(long id) {
        return jdbc.query("SELECT * FROM harvest_license WHERE id = ?", LICENSE_MAPPER, id)
                .stream().findFirst();
    }

    /** 行锁读取许可证，采伐确认、作废与归档并发时形成唯一终态。 */
    public Optional<LicenseRow> lockLicenseById(long id) {
        return jdbc.query("SELECT * FROM harvest_license WHERE id = ? FOR UPDATE",
                LICENSE_MAPPER, id).stream().findFirst();
    }

    /** 查询小班当前有效证（VALID）。 */
    public Optional<LicenseRow> findValidLicenseByPlot(long plotId) {
        return jdbc.query("SELECT * FROM harvest_license WHERE plot_id = ? AND status = 'VALID'",
                LICENSE_MAPPER, plotId).stream().findFirst();
    }

    public Optional<LicenseRow> findLicenseByApplicationId(long applicationId) {
        return jdbc.query("SELECT * FROM harvest_license WHERE application_id = ?",
                LICENSE_MAPPER, applicationId).stream().findFirst();
    }

    /** 查询已逾期的有效证（最后有效日早于今日）。 */
    public List<LicenseRow> findExpiredValidLicenses(LocalDate today) {
        return jdbc.query("SELECT * FROM harvest_license WHERE status = 'VALID' AND expire_date < ?",
                LICENSE_MAPPER, today);
    }

    public long insertLicense(String requestId, String fingerprint, long applicationId, long plotId,
                              String operatorId, int quotaYear, String speciesTier, BigDecimal volume,
                              LocalDate issueDate, LocalDate expireDate, String status) {
        return insert("INSERT INTO harvest_license (request_id, fingerprint, application_id, plot_id,"
                        + " operator_id, quota_year, species_tier, volume, issue_date, expire_date, status, version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,0)",
                requestId, fingerprint, applicationId, plotId, operatorId, quotaYear, speciesTier,
                volume, issueDate, expireDate, status);
    }

    /** 状态迁移，携带版本号自增。 */
    public void updateLicenseStatus(long id, String status, LocalDate confirmDate, LocalDate voidDate,
                                    LocalDateTime archivedAt, int expectedVersion) {
        int updated = jdbc.update("UPDATE harvest_license SET status = ?, confirm_date = ?, void_date = ?,"
                        + " archived_at = ?, version = version + 1 WHERE id = ? AND version = ?",
                status, confirmDate, voidDate, archivedAt, id, expectedVersion);
        if (updated != 1) {
            throw ForestryConcurrencyException.licenseVersionConflict(id);
        }
    }

    // ---------- 迹地更新任务 ----------

    public Optional<TaskRow> findTaskById(long id) {
        return jdbc.query("SELECT * FROM regeneration_task WHERE id = ?", TASK_MAPPER, id)
                .stream().findFirst();
    }

    /** 行锁读取更新任务，验收提交串行化。 */
    public Optional<TaskRow> lockTaskById(long id) {
        return jdbc.query("SELECT * FROM regeneration_task WHERE id = ? FOR UPDATE", TASK_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<TaskRow> findTaskByLicenseId(long licenseId) {
        return jdbc.query("SELECT * FROM regeneration_task WHERE license_id = ?", TASK_MAPPER, licenseId)
                .stream().findFirst();
    }

    public long insertTask(long licenseId, long plotId, String operatorId, LocalDate deadline,
                           String status, LocalDateTime createdAt) {
        return insert("INSERT INTO regeneration_task (license_id, plot_id, operator_id, deadline, status,"
                        + " round, created_at) VALUES (?,?,?,?,?,0,?)",
                licenseId, plotId, operatorId, deadline, status, createdAt);
    }

    public void updateTask(long id, String status, int round, LocalDate replantDeadline, BigDecimal finalRate) {
        jdbc.update("UPDATE regeneration_task SET status = ?, round = ?, replant_deadline = ?,"
                + " final_rate = ? WHERE id = ?", status, round, replantDeadline, finalRate, id);
    }

    // ---------- 验收记录 ----------

    public Optional<AcceptanceRow> findAcceptanceByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM acceptance_record WHERE request_id = ?",
                ACCEPTANCE_MAPPER, requestId).stream().findFirst();
    }

    public long insertAcceptance(long taskId, int round, String requestId, String fingerprint,
                                 BigDecimal passRate, String result, LocalDateTime createdAt) {
        return insert("INSERT INTO acceptance_record (task_id, round, request_id, fingerprint, pass_rate,"
                        + " result, created_at) VALUES (?,?,?,?,?,?,?)",
                taskId, round, requestId, fingerprint, passRate, result, createdAt);
    }

    // ---------- 限批名单 ----------

    public Optional<BlacklistRow> findBlacklist(String operatorId) {
        return jdbc.query("SELECT * FROM operator_blacklist WHERE operator_id = ?",
                BLACKLIST_MAPPER, operatorId).stream().findFirst();
    }

    public long insertBlacklist(String operatorId, LocalDate bannedUntil, String reason,
                                LocalDateTime createdAt) {
        return insert("INSERT INTO operator_blacklist (operator_id, banned_until, reason, created_at)"
                + " VALUES (?,?,?,?)", operatorId, bannedUntil, reason, createdAt);
    }

    // ---------- 迟到事件 ----------

    public long insertLateEvent(String entityType, long entityId, String eventType, String payload,
                                LocalDateTime createdAt) {
        return insert("INSERT INTO late_event (entity_type, entity_id, event_type, payload, created_at)"
                + " VALUES (?,?,?,?,?)", entityType, entityId, eventType, payload, createdAt);
    }

    public List<LateEventRow> findAllLateEvents() {
        return jdbc.query("SELECT * FROM late_event ORDER BY id", LATE_EVENT_MAPPER);
    }

    // ---------- 内部工具 ----------

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未返回自增主键");
        }
        return key.longValue();
    }

    /** 乐观锁版本冲突，理论上行锁内不会发生，防御性处理。 */
    static final class ForestryConcurrencyException extends RuntimeException {
        private ForestryConcurrencyException(String message) {
            super(message);
        }

        static ForestryConcurrencyException licenseVersionConflict(long id) {
            return new ForestryConcurrencyException("许可证 " + id + " 版本冲突");
        }
    }
}
