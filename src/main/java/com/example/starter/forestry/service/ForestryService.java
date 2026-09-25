package com.example.starter.forestry.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.forestry.config.ForestryProperties;
import com.example.starter.forestry.repo.ForestryRepository;
import com.example.starter.forestry.repo.ForestryRepository.AcceptanceRow;
import com.example.starter.forestry.repo.ForestryRepository.ApplicationRow;
import com.example.starter.forestry.repo.ForestryRepository.BlacklistRow;
import com.example.starter.forestry.repo.ForestryRepository.LateEventRow;
import com.example.starter.forestry.repo.ForestryRepository.LicenseRow;
import com.example.starter.forestry.repo.ForestryRepository.PlotRow;
import com.example.starter.forestry.repo.ForestryRepository.QuotaRow;
import com.example.starter.forestry.repo.ForestryRepository.TaskRow;
import com.example.starter.forestry.support.Fingerprints;
import com.example.starter.forestry.support.ForestryException;
import com.example.starter.forestry.support.LicenseExpiredException;

/**
 * 林木采伐许可与迹地更新联动业务服务。
 *
 * <p>流转：申请（同步审核）→ 发证（占用限额）→ 采伐确认（生成迹地更新义务）→ 更新验收
 * （不合格进入一次补植，仍不合格列入限批名单）。归档为终态，归档后迟到数据只记迟到事件。
 *
 * <p>并发约定：发证对限额档位行加锁串行校验；小班行加锁保证同一小班同时刻仅一张有效证；
 * 许可证行加锁使作废与采伐确认形成唯一终态。所有日期均为 UTC 自然日。
 */
@Service
public class ForestryService {

    /** 林种：商品林。 */
    public static final String FOREST_TYPE_COMMERCIAL = "COMMERCIAL";
    /** 林种：生态公益林（一律拒绝）。 */
    public static final String FOREST_TYPE_ECOLOGICAL = "ECOLOGICAL";

    private final ForestryRepository repo;
    private final ForestryProperties props;
    private final Clock clock;

    public ForestryService(ForestryRepository repo, ForestryProperties props, Clock forestryClock) {
        this.repo = repo;
        this.props = props;
        this.clock = forestryClock;
    }

    // ---------- 结果对象 ----------

    /** 申请结果。status 为 APPROVED 或 REJECTED。 */
    public record ApplicationResult(long applicationId, String requestId, String status,
                                    String rejectReason) {
    }

    /** 发证结果。 */
    public record LicenseResult(long licenseId, String requestId, long applicationId, String status,
                                LocalDate issueDate, LocalDate expireDate, BigDecimal volume,
                                int quotaYear, String speciesTier) {
    }

    /** 采伐确认结果。late=true 表示证件已归档，仅记录迟到事件。 */
    public record ConfirmResult(long licenseId, String status, LocalDate confirmDate,
                                Long regenerationTaskId, LocalDate regenerationDeadline, boolean late) {
    }

    /** 验收结果。late=true 表示所属许可证已归档，仅记录迟到事件。 */
    public record AcceptanceResult(long taskId, int round, String result, String taskStatus,
                                   LocalDate replantDeadline, boolean blacklisted, boolean late) {
    }

    /** 归档结果。idempotent=true 表示重复归档的幂等成功。 */
    public record ArchiveResult(long licenseId, String status, boolean idempotent) {
    }

    // ---------- 申请 ----------

    /**
     * 提交采伐申请并同步审核。以 requestId 幂等：同键同参返回首次结果，同键异参返回 409。
     */
    @Transactional
    public ApplicationResult submitApplication(String requestId, String plotCode, String operatorId,
                                               int applyYear, BigDecimal volume) {
        String fingerprint = Fingerprints.of(requestId, plotCode, operatorId, applyYear, volume);
        var existing = repo.findApplicationByRequestId(requestId);
        if (existing.isPresent()) {
            ApplicationRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ForestryException.idempotencyConflict(requestId);
            }
            return new ApplicationResult(row.id(), row.requestId(), row.status(), row.rejectReason());
        }

        PlotRow plot = repo.findPlotByCode(plotCode)
                .orElseThrow(() -> ForestryException.notFound("小班 " + plotCode));

        String rejectReason = evaluateApplication(plot, operatorId, applyYear, volume);
        String status = rejectReason == null ? "APPROVED" : "REJECTED";
        long id = repo.insertApplication(requestId, fingerprint, plot.id(), operatorId, applyYear,
                volume, status, rejectReason, now());
        return new ApplicationResult(id, requestId, status, rejectReason);
    }

    /** 审核规则：限批名单 → 生态公益林 → 禁伐区 → 成熟龄 → 限额预检（不占用）。 */
    private String evaluateApplication(PlotRow plot, String operatorId, int applyYear, BigDecimal volume) {
        LocalDate today = today();
        var blacklist = repo.findBlacklist(operatorId);
        if (blacklist.isPresent() && !today.isAfter(blacklist.get().bannedUntil())) {
            return "OPERATOR_BLACKLISTED";
        }
        if (FOREST_TYPE_ECOLOGICAL.equals(plot.forestType())) {
            return "ECOLOGICAL_FOREST";
        }
        if (plot.inNoHarvestZone()) {
            return "NO_HARVEST_ZONE";
        }
        if (plot.standAge() < plot.matureAge()) {
            return "STAND_NOT_MATURE";
        }
        var quota = repo.findQuota(applyYear, plot.speciesTier());
        if (quota.isEmpty()) {
            return "QUOTA_NOT_FOUND";
        }
        if (quota.get().usedVolume().add(volume).compareTo(quota.get().quotaVolume()) > 0) {
            return "QUOTA_EXCEEDED";
        }
        return null;
    }

    // ---------- 发证 ----------

    /**
     * 发证：串行占用年度限额（恰达限额合法、超出整笔拒绝），同一小班同时刻仅一张有效证。
     * 以 requestId 幂等；同键异参返回 409 且不占用限额。
     */
    @Transactional
    public LicenseResult issueLicense(String requestId, long applicationId) {
        String fingerprint = Fingerprints.of(requestId, applicationId);
        var existing = repo.findLicenseByRequestId(requestId);
        if (existing.isPresent()) {
            LicenseRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ForestryException.idempotencyConflict(requestId);
            }
            return toLicenseResult(row);
        }

        ApplicationRow application = repo.lockApplicationById(applicationId)
                .orElseThrow(() -> ForestryException.notFound("申请 " + applicationId));
        if (!"APPROVED".equals(application.status())) {
            throw ForestryException.conflict("APPLICATION_NOT_APPROVED",
                    "申请 " + applicationId + " 未审核通过，不能发证");
        }
        if (repo.findLicenseByApplicationId(applicationId).isPresent()) {
            throw ForestryException.conflict("LICENSE_ALREADY_ISSUED",
                    "申请 " + applicationId + " 已发证");
        }

        // 先作废已逾期的有效证并释放限额（限额当日释放的惰性实现）
        voidExpiredLicenses(today());

        // 小班行锁：同一小班同时刻只允许一张有效证
        repo.lockPlotById(application.plotId())
                .orElseThrow(() -> ForestryException.notFound("小班 " + application.plotId()));
        if (repo.findValidLicenseByPlot(application.plotId()).isPresent()) {
            throw ForestryException.conflict("PLOT_HAS_ACTIVE_LICENSE",
                    "小班 " + application.plotId() + " 已存在有效证");
        }

        PlotRow plot = repo.findPlotById(application.plotId()).orElseThrow();
        QuotaRow quota = repo.lockQuota(application.applyYear(), plot.speciesTier())
                .orElseThrow(() -> ForestryException.conflict("QUOTA_NOT_FOUND",
                        "年度 " + application.applyYear() + " 档位 " + plot.speciesTier() + " 无限额"));
        BigDecimal newUsed = quota.usedVolume().add(application.volume());
        if (newUsed.compareTo(quota.quotaVolume()) > 0) {
            throw ForestryException.conflict("QUOTA_EXCEEDED",
                    "档位 " + plot.speciesTier() + " 年度 " + application.applyYear() + " 限额不足");
        }
        repo.updateQuotaUsed(quota.id(), newUsed);

        LocalDate issueDate = today();
        LocalDate expireDate = issueDate.plusDays(props.harvestWindowDays() - 1L);
        long licenseId = repo.insertLicense(requestId, fingerprint, applicationId, application.plotId(),
                application.operatorId(), application.applyYear(), plot.speciesTier(),
                application.volume(), issueDate, expireDate, "VALID");
        return new LicenseResult(licenseId, requestId, applicationId, "VALID", issueDate, expireDate,
                application.volume(), application.applyYear(), plot.speciesTier());
    }

    /**
     * 作废已逾期的有效证并当日释放限额。证件行加锁，与采伐确认并发时形成唯一终态。
     */
    @Transactional
    public int voidExpiredLicenses(LocalDate today) {
        int count = 0;
        for (LicenseRow expired : repo.findExpiredValidLicenses(today)) {
            LicenseRow locked = repo.lockLicenseById(expired.id()).orElseThrow();
            if (!"VALID".equals(locked.status())) {
                continue;
            }
            repo.updateLicenseStatus(locked.id(), "VOID", locked.confirmDate(), today, null,
                    locked.version());
            QuotaRow quota = repo.lockQuota(locked.quotaYear(), locked.speciesTier()).orElseThrow();
            BigDecimal released = quota.usedVolume().subtract(locked.volume());
            repo.updateQuotaUsed(quota.id(), released.signum() < 0 ? BigDecimal.ZERO : released);
            count++;
        }
        return count;
    }

    // ---------- 采伐确认 ----------

    /**
     * 采伐确认：恰在第 90 个自然日（含）确认有效；逾期证件自动作废且限额当日释放。
     * 已归档证件只记录迟到事件。重复确认返回当前状态。
     * 逾期路径先提交作废与限额释放再抛出 {@link LicenseExpiredException}（不回滚）。
     */
    @Transactional(noRollbackFor = LicenseExpiredException.class)
    public ConfirmResult confirmHarvest(long licenseId) {
        LicenseRow license = repo.lockLicenseById(licenseId)
                .orElseThrow(() -> ForestryException.notFound("许可证 " + licenseId));
        LocalDate today = today();

        switch (license.status()) {
            case "ARCHIVED" -> {
                repo.insertLateEvent("LICENSE", license.id(), "CONFIRM",
                        "confirmDate=" + today, now());
                return new ConfirmResult(license.id(), license.status(), license.confirmDate(),
                        null, null, true);
            }
            case "CONFIRMED" -> {
                TaskRow task = repo.findTaskByLicenseId(license.id()).orElseThrow();
                return new ConfirmResult(license.id(), license.status(), license.confirmDate(),
                        task.id(), task.deadline(), false);
            }
            case "VOID" -> throw ForestryException.conflict("LICENSE_VOID",
                    "许可证 " + licenseId + " 已作废");
            default -> {
                // VALID：继续下方校验
            }
        }

        if (today.isAfter(license.expireDate())) {
            // 逾期：作废并当日释放限额
            repo.updateLicenseStatus(license.id(), "VOID", null, today, null, license.version());
            QuotaRow quota = repo.lockQuota(license.quotaYear(), license.speciesTier()).orElseThrow();
            BigDecimal released = quota.usedVolume().subtract(license.volume());
            repo.updateQuotaUsed(quota.id(), released.signum() < 0 ? BigDecimal.ZERO : released);
            throw new LicenseExpiredException(licenseId);
        }

        repo.updateLicenseStatus(license.id(), "CONFIRMED", today, null, null, license.version());
        // 更新期限：采伐确认翌年 12 月 31 日（UTC）
        LocalDate deadline = LocalDate.of(today.getYear() + 1, 12, 31);
        long taskId = repo.insertTask(license.id(), license.plotId(), license.operatorId(), deadline,
                "PENDING", now());
        return new ConfirmResult(license.id(), "CONFIRMED", today, taskId, deadline, false);
    }

    // ---------- 更新验收 ----------

    /**
     * 提交更新验收。首轮合格率不低于 85%（恰达合格）通过；不合格进入补植，
     * 补植期限为满 6 个月的当月月末；补植仍不合格或逾期未验收则列入限批名单。
     * 以 requestId 幂等；同键异参返回 409。
     */
    @Transactional
    public AcceptanceResult submitAcceptance(long taskId, String requestId, BigDecimal passRate) {
        String fingerprint = Fingerprints.of(requestId, taskId, passRate);
        var existing = repo.findAcceptanceByRequestId(requestId);
        if (existing.isPresent()) {
            AcceptanceRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ForestryException.idempotencyConflict(requestId);
            }
            TaskRow task = repo.findTaskById(row.taskId()).orElseThrow();
            return new AcceptanceResult(task.id(), row.round(), row.result(), task.status(),
                    task.replantDeadline(), isBlacklisted(task.operatorId()), false);
        }

        TaskRow task = repo.lockTaskById(taskId)
                .orElseThrow(() -> ForestryException.notFound("更新任务 " + taskId));
        LocalDate today = today();

        LicenseRow license = repo.findLicenseById(task.licenseId()).orElseThrow();
        if ("ARCHIVED".equals(license.status())) {
            repo.insertLateEvent("REGENERATION_TASK", task.id(), "ACCEPTANCE",
                    "passRate=" + passRate, now());
            return new AcceptanceResult(task.id(), task.round(), "LATE", task.status(),
                    task.replantDeadline(), false, true);
        }

        BigDecimal threshold = BigDecimal.valueOf(props.acceptancePassRate());
        boolean passed = passRate.compareTo(threshold) >= 0;

        switch (task.status()) {
            case "PENDING" -> {
                if (passed && !today.isAfter(task.deadline())) {
                    repo.updateTask(task.id(), "PASSED", 1, null, passRate);
                    repo.insertAcceptance(task.id(), 1, requestId, fingerprint, passRate, "PASSED", now());
                    return new AcceptanceResult(task.id(), 1, "PASSED", "PASSED", null, false, false);
                }
                // 不合格或逾期提交：进入补植，期限为首轮验收日满 6 个月的当月月末
                LocalDate replantDeadline = YearMonth.from(today.plusMonths(6))
                        .atEndOfMonth();
                repo.updateTask(task.id(), "REPLANT", 1, replantDeadline, null);
                repo.insertAcceptance(task.id(), 1, requestId, fingerprint, passRate, "FAILED", now());
                return new AcceptanceResult(task.id(), 1, "FAILED", "REPLANT", replantDeadline,
                        false, false);
            }
            case "REPLANT" -> {
                if (passed && !today.isAfter(task.replantDeadline())) {
                    repo.updateTask(task.id(), "PASSED", 2, task.replantDeadline(), passRate);
                    repo.insertAcceptance(task.id(), 2, requestId, fingerprint, passRate, "PASSED", now());
                    return new AcceptanceResult(task.id(), 2, "PASSED", "PASSED",
                            task.replantDeadline(), false, false);
                }
                // 补植仍不合格或逾期：列入限批名单
                repo.updateTask(task.id(), "FAILED", 2, task.replantDeadline(), passRate);
                repo.insertAcceptance(task.id(), 2, requestId, fingerprint, passRate, "FAILED", now());
                blacklist(task.operatorId(), today);
                return new AcceptanceResult(task.id(), 2, "FAILED", "FAILED",
                        task.replantDeadline(), true, false);
            }
            default -> throw ForestryException.conflict("TASK_CLOSED",
                    "更新任务 " + taskId + " 已终结（" + task.status() + "）");
        }
    }

    // ---------- 归档 ----------

    /**
     * 归档：终态。重复归档返回幂等成功；归档后迟到数据只记迟到事件。
     */
    @Transactional
    public ArchiveResult archiveLicense(long licenseId) {
        LicenseRow license = repo.lockLicenseById(licenseId)
                .orElseThrow(() -> ForestryException.notFound("许可证 " + licenseId));
        if ("ARCHIVED".equals(license.status())) {
            return new ArchiveResult(license.id(), "ARCHIVED", true);
        }
        repo.updateLicenseStatus(license.id(), "ARCHIVED", license.confirmDate(), license.voidDate(),
                now(), license.version());
        return new ArchiveResult(license.id(), "ARCHIVED", false);
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public ApplicationRow getApplication(long id) {
        return repo.findApplicationById(id)
                .orElseThrow(() -> ForestryException.notFound("申请 " + id));
    }

    @Transactional(readOnly = true)
    public LicenseRow getLicense(long id) {
        return repo.findLicenseById(id)
                .orElseThrow(() -> ForestryException.notFound("许可证 " + id));
    }

    @Transactional(readOnly = true)
    public TaskRow getTask(long id) {
        return repo.findTaskById(id)
                .orElseThrow(() -> ForestryException.notFound("更新任务 " + id));
    }

    @Transactional(readOnly = true)
    public QuotaRow getQuota(int year, String tier) {
        return repo.findQuota(year, tier)
                .orElseThrow(() -> ForestryException.notFound("限额 " + year + "/" + tier));
    }

    @Transactional(readOnly = true)
    public BlacklistRow getBlacklist(String operatorId) {
        return repo.findBlacklist(operatorId)
                .orElseThrow(() -> ForestryException.notFound("经营主体 " + operatorId + " 的限批记录"));
    }

    @Transactional(readOnly = true)
    public List<LateEventRow> listLateEvents() {
        return repo.findAllLateEvents();
    }

    // ---------- 内部 ----------

    private void blacklist(String operatorId, LocalDate today) {
        if (repo.findBlacklist(operatorId).isEmpty()) {
            repo.insertBlacklist(operatorId, today.plusDays(props.blacklistDays()),
                    "REGENERATION_FAILED", now());
        }
    }

    private boolean isBlacklisted(String operatorId) {
        return repo.findBlacklist(operatorId)
                .map(b -> !today().isAfter(b.bannedUntil()))
                .orElse(false);
    }

    private LicenseResult toLicenseResult(LicenseRow row) {
        return new LicenseResult(row.id(), row.requestId(), row.applicationId(), row.status(),
                row.issueDate(), row.expireDate(), row.volume(), row.quotaYear(), row.speciesTier());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
