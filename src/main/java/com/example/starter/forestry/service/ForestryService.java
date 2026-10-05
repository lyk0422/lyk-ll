package com.example.starter.forestry.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.forestry.domain.ApplicationStatus;
import com.example.starter.forestry.domain.HarvestType;
import com.example.starter.forestry.domain.IdempotentOperation;
import com.example.starter.forestry.domain.LicenseStatus;
import com.example.starter.forestry.domain.RenewalStatus;
import com.example.starter.forestry.dto.AcceptanceRequest;
import com.example.starter.forestry.dto.ApplicationView;
import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.dto.ConfirmRequest;
import com.example.starter.forestry.dto.CorrectionRequest;
import com.example.starter.forestry.dto.IssueRequest;
import com.example.starter.forestry.dto.LicenseView;
import com.example.starter.forestry.dto.QuotaLedgerView;
import com.example.starter.forestry.dto.RenewalView;
import com.example.starter.forestry.dto.ReviewRequest;
import com.example.starter.forestry.po.ApplicationPO;
import com.example.starter.forestry.po.IdempotentRecordPO;
import com.example.starter.forestry.po.LicensePO;
import com.example.starter.forestry.po.QuotaHeldPO;
import com.example.starter.forestry.po.QuotaLedgerEntryPO;
import com.example.starter.forestry.po.QuotaTierPO;
import com.example.starter.forestry.po.RenewalPO;
import com.example.starter.forestry.po.SubplotPO;
import com.example.starter.forestry.repo.ApplicationRepository;
import com.example.starter.forestry.repo.IdempotencyRepository;
import com.example.starter.forestry.repo.LateEventRepository;
import com.example.starter.forestry.repo.LicenseRepository;
import com.example.starter.forestry.repo.QuotaRepository;
import com.example.starter.forestry.repo.RenewalRepository;
import com.example.starter.forestry.repo.RestrictionRepository;
import com.example.starter.forestry.repo.SubplotRepository;
import com.example.starter.forestry.repo.VolumeTableRepository;
import com.example.starter.forestry.support.BusinessClock;
import com.example.starter.forestry.support.Fingerprints;
import com.example.starter.forestry.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 林木采伐许可与迹地更新联动服务。
 *
 * <p>关键口径（全部日期取 UTC 日界）：
 * <ul>
 *   <li>申请须附小班蓄积设计值，系统按合成蓄积表复核（单株蓄积×株数）；
 *       不一致不予受理，允许一次补正，补正后仍不一致按撤回处理，不生成证照。</li>
 *   <li>限额按树种+年度分档，株数与蓄积双口径控制：任一口径累计达限判满，
 *       恰达合法，超出整笔拒绝；双口径判定与受理（发证落库）在同一事务内完成。</li>
 *   <li>生态公益林、禁伐区一律拒绝；主伐林龄须 ≥ 成熟龄，恰达合法。</li>
 *   <li>采伐期限 = 发证日 + 90 个自然日，第 90 日确认有效；逾期证件自动作废、限额当日释放。</li>
 *   <li>更新期限为采伐确认翌年 12 月 31 日；合格率 = 合格株数/总株数，≥ 0.85 合格，恰达合法。</li>
 *   <li>首次验收不合格给一次补植机会，补植期限 6 个月月末（含当日）；仍不合格列入限批名单 1 年；
 *       补植验收合格后补植株数不计回限额释放。</li>
 *   <li>申请/补正/发证/采伐确认/验收以 (operation, requestId) 幂等：指纹覆盖全部业务字段；
 *       同键同参返回首次结果，同键异参 409 且不产生任何业务副作用。</li>
 * </ul>
 */
@Service
public class ForestryService {

    /** 采伐期限自然日数（含发证当日起第 90 日） */
    private static final int HARVEST_DAYS = 90;
    /** 验收合格线：qualified/total ≥ 0.85，以整数比较避免浮点误差 */
    private static final int PASS_RATE_PERCENT = 85;
    /** 补植仍不合格后的限批期限（年，含截止当日） */
    private static final int RESTRICTION_YEARS = 1;
    /** 并发同键请求等待首处理完成的最大毫秒数 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final ApplicationRepository applicationRepository;
    private final SubplotRepository subplotRepository;
    private final QuotaRepository quotaRepository;
    private final VolumeTableRepository volumeTableRepository;
    private final LicenseRepository licenseRepository;
    private final RenewalRepository renewalRepository;
    private final RestrictionRepository restrictionRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final LateEventRepository lateEventRepository;
    private final BusinessClock clock;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    public ForestryService(ApplicationRepository applicationRepository,
                           SubplotRepository subplotRepository,
                           QuotaRepository quotaRepository,
                           VolumeTableRepository volumeTableRepository,
                           LicenseRepository licenseRepository,
                           RenewalRepository renewalRepository,
                           RestrictionRepository restrictionRepository,
                           IdempotencyRepository idempotencyRepository,
                           LateEventRepository lateEventRepository,
                           BusinessClock clock,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.applicationRepository = applicationRepository;
        this.subplotRepository = subplotRepository;
        this.quotaRepository = quotaRepository;
        this.volumeTableRepository = volumeTableRepository;
        this.licenseRepository = licenseRepository;
        this.renewalRepository = renewalRepository;
        this.restrictionRepository = restrictionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.lateEventRepository = lateEventRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.transactionManager = transactionManager;
    }

    // ------------------------------------------------------------------
    // 公开业务操作
    // ------------------------------------------------------------------

    /**
     * 提交采伐申请。静态资格校验（限批/禁伐/公益林/成熟龄）在此完成，
     * 并按合成蓄积表复核蓄积设计值：一致则受理（SUBMITTED），不一致则
     * 不予受理并挂起为待补正（VOLUME_CORRECTION_PENDING）。限额余额在发证时串行校验。
     */
    public ApplicationView apply(ApplyRequest request) {
        HarvestType harvestType = parseHarvestType(request.harvestType());
        String fingerprint = Fingerprints.sha256(String.join("|",
                request.requestId(), request.operatorId(), request.subplotId(), request.speciesCode(),
                request.harvestType(), request.treeCount().toString(),
                request.designVolume().stripTrailingZeros().toPlainString()));

        return withIdempotency(IdempotentOperation.APPLY, request.requestId(), fingerprint,
                "APPLICATION", ApplicationView.class, () -> doApply(request, harvestType));
    }

    private ApplicationView doApply(ApplyRequest request, HarvestType harvestType) {
        LocalDate today = clock.today();

        if (restrictionRepository.isRestricted(request.operatorId(), today)) {
            throw ApiException.badRequest("OPERATOR_RESTRICTED", "经营主体处于限批期内，新采伐申请一律拒绝");
        }

        SubplotPO subplot = subplotRepository.findById(request.subplotId());
        if (subplot == null) {
            throw ApiException.notFound("SUBPLOT_NOT_FOUND", "小班不存在: " + request.subplotId());
        }
        if (!subplot.speciesCode().equals(request.speciesCode())) {
            throw ApiException.badRequest("SPECIES_MISMATCH",
                    "申请树种与小班树种不一致: " + subplot.speciesCode());
        }
        if (subplot.protectedZone()) {
            throw rejection("PROTECTED_ZONE_FORBIDDEN", "禁伐区小班禁止采伐: " + subplot.id());
        }
        if ("PUBLIC_WELFARE".equals(subplot.forestCategory())) {
            throw rejection("PUBLIC_WELFARE_FORBIDDEN", "生态公益林禁止商业性采伐: " + subplot.id());
        }
        if (harvestType == HarvestType.MAIN && subplot.standAge() < subplot.matureAge()) {
            throw rejection("STAND_AGE_NOT_MATURE",
                    "主伐林龄 " + subplot.standAge() + " 低于成熟龄 " + subplot.matureAge());
        }
        if (quotaRepository.findTier(subplot.speciesCode(), today.getYear()) == null) {
            throw rejection("QUOTA_TIER_NOT_FOUND",
                    "年度限额档位不存在: " + subplot.speciesCode() + " / " + today.getYear());
        }

        // 蓄积设计值复核：按合成蓄积表 单株蓄积 × 申请株数 计算复核口径
        BigDecimal volumePerTree = volumeTableRepository.findVolumePerTree(
                subplot.speciesCode(), subplot.standAge());
        if (volumePerTree == null) {
            throw rejection("VOLUME_TABLE_MISSING",
                    "合成蓄积表缺少档位，无法复核: " + subplot.speciesCode()
                            + " / 林龄 " + subplot.standAge());
        }
        BigDecimal checked = volumePerTree
                .multiply(BigDecimal.valueOf(request.treeCount()))
                .setScale(2, RoundingMode.HALF_UP);
        // 设计与复核口径不一致：拒绝受理，挂起待一次补正
        String status = request.designVolume().compareTo(checked) == 0
                ? ApplicationStatus.SUBMITTED.name()
                : ApplicationStatus.VOLUME_CORRECTION_PENDING.name();

        ApplicationPO po = new ApplicationPO(
                "APP-" + UUID.randomUUID(),
                request.requestId(),
                request.operatorId(),
                subplot.id(),
                subplot.speciesCode(),
                today.getYear(),
                harvestType.name(),
                request.treeCount(),
                request.designVolume(),
                checked,
                false,
                status,
                null, null, false, today, null, null);
        applicationRepository.insert(po);
        return toApplicationView(po);
    }

    /**
     * 蓄积设计值补正（仅一次机会）：补正值与复核口径一致则受理（SUBMITTED）；
     * 仍不一致按撤回处理（WITHDRAWN），不生成证照。
     */
    public ApplicationView correct(String applicationId, CorrectionRequest request) {
        String fingerprint = Fingerprints.sha256(String.join("|",
                request.requestId(), applicationId,
                request.designVolume().stripTrailingZeros().toPlainString()));
        return withIdempotency(IdempotentOperation.CORRECT, request.requestId(), fingerprint,
                "APPLICATION", ApplicationView.class, () -> doCorrect(applicationId, request));
    }

    private ApplicationView doCorrect(String applicationId, CorrectionRequest request) {
        ApplicationPO app = mustFindApplication(applicationId);
        if (app.archived()) {
            recordLateEvent(app.id(), app.licenseId(), "CORRECTION",
                    "designVolume=" + request.designVolume());
            throw ApiException.conflict("APPLICATION_ARCHIVED", "申请已归档，迟到补正仅记录事件");
        }
        if (!ApplicationStatus.VOLUME_CORRECTION_PENDING.name().equals(app.status())) {
            throw ApiException.conflict("INVALID_STATUS",
                    "当前状态 " + app.status() + " 不允许蓄积设计值补正");
        }
        // 补正判定沿用申请时固化的复核口径，保证可复算、结果稳定
        boolean consistent = request.designVolume().compareTo(app.checkedVolume()) == 0;
        String target = consistent
                ? ApplicationStatus.SUBMITTED.name()
                : ApplicationStatus.WITHDRAWN.name();
        applicationRepository.completeCorrection(app.id(), target, request.designVolume());
        return toApplicationView(applicationRepository.findById(app.id()));
    }

    /**
     * 审核：通过转 REVIEW_PASSED；不通过填拒绝原因转 REVIEW_REJECTED。
     */
    public ApplicationView review(String applicationId, ReviewRequest request) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ApplicationPO app = mustFindApplication(applicationId);
            if (app.archived()) {
                recordLateEvent(app.id(), app.licenseId(), "REVIEW",
                        "approved=" + request.approved());
                throw ApiException.conflict("APPLICATION_ARCHIVED", "申请已归档，迟到审核仅记录事件");
            }
            if (!ApplicationStatus.SUBMITTED.name().equals(app.status())) {
                throw ApiException.conflict("INVALID_STATUS",
                        "当前状态 " + app.status() + " 不允许审核");
            }
            LocalDate today = clock.today();
            if (Boolean.TRUE.equals(request.approved())) {
                applicationRepository.review(app.id(), ApplicationStatus.REVIEW_PASSED.name(), null, today);
            } else {
                if (request.comment() == null || request.comment().isBlank()) {
                    throw ApiException.badRequest("REJECT_REASON_REQUIRED", "审核拒绝必须填写拒绝原因");
                }
                applicationRepository.review(app.id(), ApplicationStatus.REVIEW_REJECTED.name(),
                        request.comment(), today);
            }
            return toApplicationView(applicationRepository.findById(app.id()));
        });
    }

    /**
     * 发证：同一限额档位串行校验（行锁），株数与蓄积双口径任一超限整笔拒绝；
     * 双口径判定与受理落库在同一事务内完成。同一小班同时刻只允许一张有效证。
     */
    public LicenseView issue(IssueRequest request) {
        String fingerprint = Fingerprints.sha256(request.requestId() + "|" + request.applicationId());
        return withIdempotency(IdempotentOperation.ISSUE, request.requestId(), fingerprint,
                "LICENSE", LicenseView.class, () -> doIssue(request));
    }

    private LicenseView doIssue(IssueRequest request) {
        LocalDate today = clock.today();
        ApplicationPO app = mustFindApplication(request.applicationId());
        if (app.archived()) {
            recordLateEvent(app.id(), null, "ISSUE", request.applicationId());
            throw ApiException.conflict("APPLICATION_ARCHIVED", "申请已归档，迟到发证仅记录事件");
        }
        if (!ApplicationStatus.REVIEW_PASSED.name().equals(app.status())) {
            throw ApiException.conflict("INVALID_STATUS",
                    "当前状态 " + app.status() + " 不允许发证");
        }
        if (restrictionRepository.isRestricted(app.operatorId(), today)) {
            throw ApiException.badRequest("OPERATOR_RESTRICTED", "经营主体处于限批期内，禁止发证");
        }

        // 锁定限额档位行：同树种同年度发证全局串行，杜绝并发合计超限（双口径同锁判定）
        QuotaTierPO tier = quotaRepository.findTierForUpdate(app.speciesCode(), app.quotaYear());
        if (tier == null) {
            throw ApiException.badRequest("QUOTA_TIER_NOT_FOUND",
                    "年度限额档位不存在: " + app.speciesCode() + " / " + app.quotaYear());
        }
        QuotaHeldPO held = quotaRepository.sumHeld(app.speciesCode(), app.quotaYear());
        // 株数口径：恰达合法，超出整笔拒绝
        if (held.trees() + app.treeCount() > tier.totalTrees()) {
            throw ApiException.conflict("QUOTA_EXCEEDED",
                    "超出年度采伐株数限额：已占 " + held.trees() + " + 本笔 " + app.treeCount()
                            + " > 限额 " + tier.totalTrees());
        }
        // 蓄积口径：恰达合法，超出整笔拒绝
        if (held.volume().add(app.checkedVolume()).compareTo(tier.totalVolume()) > 0) {
            throw ApiException.conflict("QUOTA_EXCEEDED",
                    "超出年度采伐蓄积限额：已占 " + held.volume() + " + 本笔 " + app.checkedVolume()
                            + " > 限额 " + tier.totalVolume());
        }

        // 锁定小班行：同一小班同时刻只允许一张有效证
        subplotRepository.lockForUpdate(app.subplotId());
        if (applicationRepository.countActiveLicensesOnSubplot(app.subplotId()) > 0) {
            throw ApiException.conflict("SUBPLOT_LICENSE_ACTIVE", "该小班存在有效采伐证，禁止重复发证");
        }

        LocalDate expireDate = today.plusDays(HARVEST_DAYS);
        LicensePO license = new LicensePO(
                "LIC-" + UUID.randomUUID(),
                app.id(), app.operatorId(), app.subplotId(), app.speciesCode(), app.quotaYear(),
                app.treeCount(), app.checkedVolume(),
                LicenseStatus.ISSUED.name(), today, expireDate, null, null);
        licenseRepository.insert(license);
        quotaRepository.appendLedger(app.speciesCode(), app.quotaYear(), app.id(), license.id(),
                app.treeCount(), app.checkedVolume(), "HOLD", today);
        applicationRepository.attachLicense(app.id(), license.id(), ApplicationStatus.LICENSED.name());
        return toLicenseView(license);
    }

    /**
     * 采伐确认：第 90 日（含）内有效；逾期首次确认触发证件作废并在独立事务中释放限额。
     */
    public LicenseView confirm(ConfirmRequest request) {
        String fingerprint = Fingerprints.sha256(request.requestId() + "|" + request.licenseId());
        return withIdempotency(IdempotentOperation.CONFIRM, request.requestId(), fingerprint,
                "LICENSE", LicenseView.class, () -> doConfirm(request));
    }

    private LicenseView doConfirm(ConfirmRequest request) {
        LocalDate today = clock.today();
        LicensePO license = mustFindLicense(request.licenseId());
        ApplicationPO app = mustFindApplication(license.applicationId());
        if (app.archived()) {
            recordLateEvent(app.id(), license.id(), "CONFIRM", request.licenseId());
            throw ApiException.conflict("APPLICATION_ARCHIVED", "申请已归档，迟到采伐确认仅记录事件");
        }

        if (LicenseStatus.HARVESTED.name().equals(license.status())) {
            return toLicenseView(license);
        }
        if (LicenseStatus.VOID.name().equals(license.status())) {
            throw ApiException.conflict("LICENSE_VOID", "采伐证已逾期作废，采伐确认无效");
        }

        if (today.isAfter(license.expireDate())) {
            // 逾期：条件作废 + 限额当日释放，独立事务先落库（随后主事务回滚不影响作废结果），
            // 再向调用方返回作废错误
            TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
            requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
            boolean voided = requiresNew.execute(status -> {
                LicensePO fresh = mustFindLicense(license.id());
                if (!LicenseStatus.ISSUED.name().equals(fresh.status())) {
                    return false;
                }
                int rows = licenseRepository.markVoidIfIssued(license.id(), today);
                if (rows == 0) {
                    return false;
                }
                applicationRepository.updateStatus(app.id(), ApplicationStatus.LICENSE_VOID.name());
                quotaRepository.appendLedger(license.speciesCode(), license.quotaYear(),
                        app.id(), license.id(), -license.treeCount(), license.volume().negate(),
                        "RELEASE", today);
                return true;
            });
            if (Boolean.TRUE.equals(voided)) {
                throw ApiException.conflict("LICENSE_EXPIRED",
                        "采伐证已逾 " + HARVEST_DAYS + " 日期限（截止 " + license.expireDate() + "），证件作废且限额已释放");
            }
            LicensePO reloaded = mustFindLicense(license.id());
            if (LicenseStatus.HARVESTED.name().equals(reloaded.status())) {
                return toLicenseView(reloaded);
            }
            throw ApiException.conflict("LICENSE_VOID", "采伐证已逾期作废，采伐确认无效");
        }

        // 期限内：CAS 置 HARVESTED，与并发作废竞争形成唯一终态
        int rows = licenseRepository.markHarvestedIfIssued(license.id(), today);
        if (rows == 0) {
            LicensePO reloaded = mustFindLicense(license.id());
            if (LicenseStatus.HARVESTED.name().equals(reloaded.status())) {
                return toLicenseView(reloaded);
            }
            throw ApiException.conflict("LICENSE_VOID", "采伐证已作废，采伐确认无效");
        }
        applicationRepository.updateStatus(app.id(), ApplicationStatus.HARVESTED.name());
        RenewalPO renewal = new RenewalPO(
                "REN-" + UUID.randomUUID(),
                app.id(), app.operatorId(), RenewalStatus.PENDING.name(),
                LocalDate.of(today.getYear() + 1, 12, 31),
                null, null, null, null, today);
        renewalRepository.insert(renewal);
        return toLicenseView(mustFindLicense(license.id()));
    }

    /**
     * 更新验收提交：首次不合格进入补植（提交本身作为首次结果提交并缓存），
     * 补植验收仍不合格则列入限批名单。补植验收合格后补植株数不计回限额释放，
     * 台账仅由发证占用与逾期作废释放驱动。
     */
    public RenewalView acceptance(AcceptanceRequest request) {
        if (request.qualifiedCount() > request.totalCount()) {
            throw ApiException.badRequest("INVALID_COUNTS", "合格株数不得大于总株数");
        }
        String fingerprint = Fingerprints.sha256(String.join("|",
                request.requestId(), request.applicationId(),
                request.qualifiedCount().toString(), request.totalCount().toString()));
        return withIdempotency(IdempotentOperation.ACCEPTANCE, request.requestId(), fingerprint,
                "RENEWAL", RenewalView.class, () -> doAcceptance(request));
    }

    private RenewalView doAcceptance(AcceptanceRequest request) {
        LocalDate today = clock.today();
        ApplicationPO app = mustFindApplication(request.applicationId());
        if (app.archived()) {
            recordLateEvent(app.id(), app.licenseId(), "ACCEPTANCE",
                    request.qualifiedCount() + "/" + request.totalCount());
            throw ApiException.conflict("APPLICATION_ARCHIVED", "申请已归档，迟到验收仅记录事件");
        }
        RenewalPO renewal = renewalRepository.findByApplicationId(app.id());
        if (renewal == null) {
            throw ApiException.conflict("NO_RENEWAL_OBLIGATION", "该申请尚未采伐确认，无更新义务");
        }

        // 合格判定：qualified/total ≥ 85%（长整数比较，恰达合法）
        boolean qualified = request.qualifiedCount().longValue() * 100
                >= request.totalCount().longValue() * PASS_RATE_PERCENT;
        BigDecimal rate = BigDecimal.valueOf(request.qualifiedCount())
                .divide(BigDecimal.valueOf(request.totalCount()), 4, RoundingMode.HALF_UP);

        switch (RenewalStatus.valueOf(renewal.status())) {
            case PENDING -> {
                if (today.isAfter(renewal.deadline())) {
                    throw ApiException.badRequest("RENEWAL_DEADLINE_PASSED",
                            "已超过首次更新期限 " + renewal.deadline());
                }
                if (qualified) {
                    int rows = renewalRepository.markPassedFromPending(app.id(), rate, today);
                    if (rows == 0) {
                        throw ApiException.conflict("RENEWAL_STATE_CHANGED", "更新状态已被并发改变，请重试");
                    }
                    applicationRepository.updateStatus(app.id(), ApplicationStatus.RENEWAL_DONE.name());
                } else {
                    // 首次不合格：给予一次补植机会，期限 6 个月月末（含当日）
                    LocalDate plusSix = today.plusMonths(6);
                    LocalDate replantDeadline = YearMonth.from(plusSix).atEndOfMonth();
                    renewalRepository.markReplanting(app.id(), rate, today, replantDeadline);
                }
                return toRenewalView(renewalRepository.findByApplicationId(app.id()));
            }
            case REPLANTING -> {
                if (renewal.replantDeadline() != null && today.isAfter(renewal.replantDeadline())) {
                    throw ApiException.badRequest("REPLANT_DEADLINE_PASSED",
                            "已超过补植期限 " + renewal.replantDeadline());
                }
                String target = qualified ? RenewalStatus.PASSED.name() : RenewalStatus.FAILED.name();
                int rows = renewalRepository.completeReplant(app.id(), target, rate, today);
                if (rows == 0) {
                    throw ApiException.conflict("RENEWAL_STATE_CHANGED", "更新状态已被并发改变，请重试");
                }
                if (qualified) {
                    // 补植验收合格：补植株数不计回限额释放（不写 RELEASE 台账）
                    applicationRepository.updateStatus(app.id(), ApplicationStatus.RENEWAL_DONE.name());
                } else {
                    // 补植仍不合格：经营主体列入限批名单，期限 1 年（含截止当日）
                    applicationRepository.updateStatus(app.id(), ApplicationStatus.RENEWAL_FAILED.name());
                    restrictionRepository.add(app.operatorId(),
                            "迹地更新补植验收仍不合格，合格率 " + rate,
                            today, today.plusYears(RESTRICTION_YEARS));
                }
                return toRenewalView(renewalRepository.findByApplicationId(app.id()));
            }
            case PASSED, FAILED -> throw ApiException.conflict("RENEWAL_TERMINAL",
                    "更新义务已终态（" + renewal.status() + "），不能重复验收");
        }
        throw ApiException.badRequest("INVALID_RENEWAL_STATE", "未知更新状态: " + renewal.status());
    }

    /**
     * 归档（终态）：重复归档幂等成功；归档后迟到数据只记录迟到事件。
     */
    public ApplicationView archive(String applicationId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ApplicationPO app = mustFindApplication(applicationId);
            if (app.archived()) {
                return toApplicationView(app);
            }
            applicationRepository.archive(app.id(), clock.today());
            return toApplicationView(applicationRepository.findById(app.id()));
        });
    }

    /**
     * 扫描并处理所有已逾期（expire_date &lt; 今天 UTC）仍有效的证件：
     * 条件作废 + 限额当日释放。与 confirm 的 CAS 共同保证唯一终态。
     *
     * @return 本次新作废证件数量
     */
    public int processExpiredLicenses() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            LocalDate today = clock.today();
            List<LicensePO> expired = licenseRepository.findIssuedExpiredForUpdate(today);
            int count = 0;
            for (LicensePO license : expired) {
                int rows = licenseRepository.markVoidIfIssued(license.id(), today);
                if (rows == 0) {
                    continue;
                }
                applicationRepository.updateStatus(license.applicationId(),
                        ApplicationStatus.LICENSE_VOID.name());
                quotaRepository.appendLedger(license.speciesCode(), license.quotaYear(),
                        license.applicationId(), license.id(),
                        -license.treeCount(), license.volume().negate(), "RELEASE", today);
                count++;
            }
            return count;
        });
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public ApplicationView getApplication(String applicationId) {
        return toApplicationView(mustFindApplication(applicationId));
    }

    public LicenseView getLicense(String licenseId) {
        return toLicenseView(mustFindLicense(licenseId));
    }

    public RenewalView getRenewal(String applicationId) {
        RenewalPO renewal = renewalRepository.findByApplicationId(applicationId);
        if (renewal == null) {
            throw ApiException.notFound("RENEWAL_NOT_FOUND", "更新义务不存在: " + applicationId);
        }
        return toRenewalView(renewal);
    }

    /**
     * 双口径限额台账（按树种+年度档位）：持有量由台账明细逐笔复算，
     * 明细按记账顺序稳定排序，重复查询结果一致。
     */
    public QuotaLedgerView quotaLedger(String speciesCode, int year) {
        QuotaTierPO tier = quotaRepository.findTier(speciesCode, year);
        if (tier == null) {
            throw ApiException.notFound("QUOTA_TIER_NOT_FOUND",
                    "年度限额档位不存在: " + speciesCode + " / " + year);
        }
        List<QuotaLedgerEntryPO> rows = quotaRepository.listLedgerEntries(speciesCode, year);
        long heldTrees = 0;
        BigDecimal heldVolume = BigDecimal.ZERO;
        List<QuotaLedgerView.Entry> entries = new java.util.ArrayList<>(rows.size());
        for (QuotaLedgerEntryPO row : rows) {
            heldTrees += row.trees();
            heldVolume = heldVolume.add(row.volume());
            entries.add(new QuotaLedgerView.Entry(
                    row.applicationId(), row.licenseId(), row.trees(), row.volume(),
                    row.action(), row.createdDate()));
        }
        return new QuotaLedgerView(speciesCode, year, tier.totalTrees(), tier.totalVolume(),
                heldTrees, heldVolume, List.copyOf(entries));
    }

    // ------------------------------------------------------------------
    // 幂等封装
    // ------------------------------------------------------------------

    /**
     * 业务操作的幂等包装。
     * <p>首处理者插入 PENDING 占位并在同一事务内执行业务；同键同参的并发请求等待 DONE
     * 后重放首次结果（成功返回首次响应，业务拒绝重放首次拒绝）；同键异参立即 409，
     * 不触碰任何业务数据、不占用限额。业务异常（如 422/409 规则拒绝）同样固化为首次
     * 结果并提交；非业务异常回滚，不缓存。
     */
    private <T> T withIdempotency(IdempotentOperation operation, String requestId, String fingerprint,
                                  String responseType, Class<T> responseClass, SupplierTx<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<ApiException> pendingError =
                new java.util.concurrent.atomic.AtomicReference<>();
        boolean[] acquired = {false};
        T result = template.execute(status -> {
            boolean inserted = idempotencyRepository.tryInsertPending(operation, requestId,
                    fingerprint, clock.today());
            if (!inserted) {
                return null;
            }
            acquired[0] = true;
            try {
                T value = action.get();
                idempotencyRepository.markDone(operation, requestId, responseType, null,
                        extractRefId(value), writeJson(value));
                return value;
            } catch (ApiException e) {
                // 业务拒绝也是确定性“首次结果”：固化后随事务提交，随后在事务外重放
                idempotencyRepository.markDone(operation, requestId, "ERROR",
                        e.getStatus().value(), null,
                        writeJson(new com.example.starter.forestry.web.ErrorResponse(
                                e.getErrorCode(), e.getMessage())));
                pendingError.set(e);
                return null;
            }
        });
        if (acquired[0]) {
            if (pendingError.get() != null) {
                throw pendingError.get();
            }
            return result;
        }

        // 同键已存在：指纹不符立即 409（无论对方处理中还是已完成，均不产生副作用）
        IdempotentRecordPO existing = idempotencyRepository.find(operation, requestId);
        if (existing == null) {
            throw ApiException.conflict("IDEMPOTENCY_RACE", "幂等记录状态异常，请重试");
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "requestId=" + requestId + " 已用于不同业务参数的请求");
        }

        // 同键同参：等待首处理者完成后重放首次结果
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        IdempotentRecordPO done = existing;
        while ("PENDING".equals(done.status()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("IDEMPOTENCY_WAIT_INTERRUPTED", "等待首次处理完成时被中断");
            }
            done = idempotencyRepository.find(operation, requestId);
            if (done == null) {
                throw ApiException.conflict("IDEMPOTENCY_RACE", "幂等记录状态异常，请重试");
            }
        }
        if ("PENDING".equals(done.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_PENDING",
                    "同一 requestId 的首次请求仍在处理中");
        }
        if ("ERROR".equals(done.responseType())) {
            com.example.starter.forestry.web.ErrorResponse error =
                    readJson(done.responseJson(), com.example.starter.forestry.web.ErrorResponse.class);
            throw new ApiException(HttpStatus.valueOf(done.responseStatus()), error.errorCode(), error.message());
        }
        return readJson(done.responseJson(), responseClass);
    }

    private String extractRefId(Object view) {
        if (view instanceof ApplicationView a) {
            return a.id();
        }
        if (view instanceof LicenseView l) {
            return l.id();
        }
        if (view instanceof RenewalView r) {
            return r.id();
        }
        return null;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化首次响应失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("解析首次响应快照失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private HarvestType parseHarvestType(String raw) {
        try {
            return HarvestType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_HARVEST_TYPE",
                    "未知采伐类型: " + raw + "（支持 MAIN/TENDING/REFORMATION）");
        }
    }

    private ApiException rejection(String code, String message) {
        // 资格类拒绝返回 422：请求格式正确但业务规则拒绝
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /**
     * 记录归档后迟到事件：使用独立事务提交，确保外层业务事务随后回滚时事件仍保留。
     */
    private void recordLateEvent(String applicationId, String licenseId, String eventType, String payload) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        requiresNew.execute(status -> {
            lateEventRepository.record(applicationId, licenseId, eventType, payload, clock.today());
            return null;
        });
    }

    private ApplicationPO mustFindApplication(String id) {
        ApplicationPO app = applicationRepository.findById(id);
        if (app == null) {
            throw ApiException.notFound("APPLICATION_NOT_FOUND", "采伐申请不存在: " + id);
        }
        return app;
    }

    private LicensePO mustFindLicense(String id) {
        LicensePO license = licenseRepository.findById(id);
        if (license == null) {
            throw ApiException.notFound("LICENSE_NOT_FOUND", "采伐许可证不存在: " + id);
        }
        return license;
    }

    private ApplicationView toApplicationView(ApplicationPO po) {
        return new ApplicationView(
                po.id(), po.status(), po.operatorId(), po.subplotId(), po.speciesCode(),
                po.quotaYear(), po.harvestType(), po.treeCount(), po.designVolume(), po.checkedVolume(),
                po.licenseId(), po.rejectReason(), po.createdDate(), po.archivedDate());
    }

    private LicenseView toLicenseView(LicensePO po) {
        return new LicenseView(
                po.id(), po.applicationId(), po.operatorId(), po.subplotId(), po.speciesCode(),
                po.quotaYear(), po.treeCount(), po.volume(), po.status(),
                po.issueDate(), po.expireDate(), po.harvestDate());
    }

    private RenewalView toRenewalView(RenewalPO po) {
        return new RenewalView(
                po.id(), po.applicationId(), po.operatorId(), po.status(), po.deadline(),
                po.replantDeadline(), po.qualifiedRate(), po.acceptedDate(), po.replantAcceptedDate());
    }

    /** 可在事务模板内抛受检包装的业务供应商。 */
    @FunctionalInterface
    private interface SupplierTx<T> {
        T get();
    }
}
