package com.example.starter.pension.service;

import com.example.starter.pension.api.ContributionRequest;
import com.example.starter.pension.api.InheritanceClaimRequest;
import com.example.starter.pension.api.InheritanceClaimResponse;
import com.example.starter.pension.api.InheritanceCompleteResponse;
import com.example.starter.pension.api.LateContributionEventView;
import com.example.starter.pension.api.MemberUpsertRequest;
import com.example.starter.pension.api.PaymentInstructionRequest;
import com.example.starter.pension.api.PaymentResultResponse;
import com.example.starter.pension.api.PayoutRequest;
import com.example.starter.pension.api.PlanResponse;
import com.example.starter.pension.api.SettleResponse;
import com.example.starter.pension.api.SupplementaryPayoutRequest;
import com.example.starter.pension.bank.BankGateway;
import com.example.starter.pension.domain.ClaimStatus;
import com.example.starter.pension.domain.InstallmentStatus;
import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.MemberStatus;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;
import com.example.starter.pension.po.ClaimRow;
import com.example.starter.pension.po.ContributionRow;
import com.example.starter.pension.po.InstallmentRow;
import com.example.starter.pension.po.InstructionRow;
import com.example.starter.pension.po.LateContributionEventRow;
import com.example.starter.pension.po.MemberRow;
import com.example.starter.pension.po.PlanRow;
import com.example.starter.pension.repo.ContributionRepository;
import com.example.starter.pension.repo.InheritanceClaimRepository;
import com.example.starter.pension.repo.InheritanceLedgerRepository;
import com.example.starter.pension.repo.InstallmentRepository;
import com.example.starter.pension.repo.InstallmentRepository.NewInstallment;
import com.example.starter.pension.repo.InstructionRepository;
import com.example.starter.pension.repo.LateContributionEventRepository;
import com.example.starter.pension.repo.LedgerRepository;
import com.example.starter.pension.repo.MemberRepository;
import com.example.starter.pension.repo.PlanRepository;
import com.example.starter.pension.support.TimeProvider;
import com.example.starter.pension.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 年金权益归属与分期支付应用服务实现。
 *
 * <p>关键事务边界：</p>
 * <ul>
 *     <li>申请受理：成员行锁 + requestId/进行中成员唯一索引，保证同成员并发唯一受理，
 *     缴费快照在同事务冻结；</li>
 *     <li>末期出款：成员出款入账、未归属企业缴费划回、计划结清同一事务提交或回滚；</li>
 *     <li>支付指令：requestId 唯一索引 + 期次行锁，每期最多出款一次，同键重放首次结果。</li>
 * </ul>
 */
@Service
public class PensionServiceImpl implements PensionService {

    private final MemberRepository memberRepository;
    private final ContributionRepository contributionRepository;
    private final PlanRepository planRepository;
    private final InstallmentRepository installmentRepository;
    private final InstructionRepository instructionRepository;
    private final LedgerRepository ledgerRepository;
    private final InheritanceClaimRepository claimRepository;
    private final InheritanceLedgerRepository inheritanceLedgerRepository;
    private final LateContributionEventRepository lateContributionEventRepository;
    private final BankGateway bankGateway;
    private final VestingCalculator vestingCalculator;
    private final InstallmentScheduler installmentScheduler;
    private final TimeProvider timeProvider;
    private final TransactionTemplate transactionTemplate;

    public PensionServiceImpl(MemberRepository memberRepository,
                              ContributionRepository contributionRepository,
                              PlanRepository planRepository,
                              InstallmentRepository installmentRepository,
                              InstructionRepository instructionRepository,
                              LedgerRepository ledgerRepository,
                              InheritanceClaimRepository claimRepository,
                              InheritanceLedgerRepository inheritanceLedgerRepository,
                              LateContributionEventRepository lateContributionEventRepository,
                              BankGateway bankGateway,
                              VestingCalculator vestingCalculator,
                              InstallmentScheduler installmentScheduler,
                              TimeProvider timeProvider,
                              PlatformTransactionManager transactionManager) {
        this.memberRepository = memberRepository;
        this.contributionRepository = contributionRepository;
        this.planRepository = planRepository;
        this.installmentRepository = installmentRepository;
        this.instructionRepository = instructionRepository;
        this.ledgerRepository = ledgerRepository;
        this.claimRepository = claimRepository;
        this.inheritanceLedgerRepository = inheritanceLedgerRepository;
        this.lateContributionEventRepository = lateContributionEventRepository;
        this.bankGateway = bankGateway;
        this.vestingCalculator = vestingCalculator;
        this.installmentScheduler = installmentScheduler;
        this.timeProvider = timeProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void upsertMember(MemberUpsertRequest request) {
        memberRepository.upsert(request.memberNo(), request.name(), request.hireDate(), request.status());
    }

    @Override
    public void addContribution(ContributionRequest request) {
        transactionTemplate.executeWithoutResult(status -> {
            MemberRow member = requireMember(request.memberNo());
            long contributionId = contributionRepository.insert(member.id(), request.contributionType(),
                    request.amountCents(), request.contributionDate(), request.period());
            // 继承支付完成（终态）后到账的缴费：不再进入任何快照，只记迟到事件并提示人工复核
            claimRepository.findByMember(member.id())
                    .filter(c -> c.status() == ClaimStatus.PAID)
                    .ifPresent(c -> lateContributionEventRepository.insert(member.id(), contributionId,
                            c.id(), request.amountCents(),
                            "继承支付完成后迟到的缴费，继承支付额不含该笔，请人工复核"));
        });
    }

    @Override
    public PlanResponse applyPayout(PayoutRequest request) {
        PlanRow replay = planRepository.findByRequestId(request.requestId()).orElse(null);
        if (replay != null) {
            return replayOrConflict(replay, request.memberNo(), request.reason(),
                    request.installmentType(), request.requestDate().toString(), null);
        }
        try {
            return transactionTemplate.execute(status -> doApply(request));
        } catch (DataIntegrityViolationException ex) {
            return handleApplyIntegrityViolation(request, null);
        }
    }

    @Override
    public PlanResponse applySupplementary(SupplementaryPayoutRequest request) {
        PlanRow replay = planRepository.findByRequestId(request.requestId()).orElse(null);
        if (replay != null) {
            return replayOrConflict(replay, request.memberNo(), request.reason(),
                    request.installmentType(), request.requestDate().toString(), request.originalPlanNo());
        }
        try {
            return transactionTemplate.execute(status -> doApplySupplementary(request));
        } catch (DataIntegrityViolationException ex) {
            return handleApplyIntegrityViolation(null, request);
        }
    }

    @Override
    public PlanResponse getPlan(String planNo) {
        PlanRow plan = requirePlan(planNo);
        return toPlanResponse(plan);
    }

    @Override
    public PaymentResultResponse payInstallment(PaymentInstructionRequest request) {
        InstructionRow replay = instructionRepository.findByRequestId(request.requestId()).orElse(null);
        if (replay != null) {
            PlanRow plan = planRepository.findById(replay.planId())
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "PLAN_MISSING", "指令关联计划缺失"));
            return paymentReplay(request, plan, replay);
        }
        try {
            return transactionTemplate.execute(status -> doPay(request));
        } catch (DataIntegrityViolationException ex) {
            InstructionRow winner = instructionRepository.findByRequestId(request.requestId()).orElse(null);
            if (winner == null) {
                throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_REJECTED",
                        "支付被唯一约束拒绝，请重试");
            }
            PlanRow plan = planRepository.findById(winner.planId()).orElseThrow();
            return paymentReplay(request, plan, winner);
        }
    }

    @Override
    public SettleResponse settle(String planNo) {
        return transactionTemplate.execute(status -> {
            PlanRow plan = planRepository.findByPlanNo(planNo)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                            "支付计划不存在: " + planNo));
            PlanRow locked = planRepository.lockById(plan.id()).orElseThrow();
            if (locked.status() == PlanStatus.SETTLED) {
                return buildSettleResponse(locked, true);
            }
            List<InstallmentRow> installments = installmentRepository.findByPlanId(locked.id());
            boolean allPaid = installments.stream().allMatch(i -> i.status() == InstallmentStatus.PAID);
            if (!allPaid) {
                throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_PAYABLE_COMPLETE",
                        "存在未完成期次，计划不能结清");
            }
            ensureEnterpriseReturned(locked);
            planRepository.markSettled(locked.id(), timeProvider.nowUtc());
            PlanRow refreshed = planRepository.findByPlanNo(planNo).orElseThrow();
            return buildSettleResponse(refreshed, false);
        });
    }

    // ------------------------------------------------------------------
    // 申请受理（原始 / 补发）
    // ------------------------------------------------------------------

    private PlanResponse doApply(PayoutRequest request) {
        MemberRow member = requireMember(request.memberNo());
        memberRepository.lockById(member.id());
        requireNoInheritanceClaim(member);
        PlanRow concurrent = planRepository.findByRequestId(request.requestId()).orElse(null);
        if (concurrent != null) {
            return replayOrConflict(concurrent, request.memberNo(), request.reason(),
                    request.installmentType(), request.requestDate().toString(), null);
        }
        if (planRepository.countActiveByMember(member.id()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_PLAN_EXISTS",
                    "该成员已存在进行中的支付计划");
        }
        if (planRepository.countByMember(member.id()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMBER_ALREADY_HAS_PLAN",
                    "该成员已存在历史支付计划，结清后补缴须走补发流程并引用原计划号");
        }
        validateReason(member, request.reason());
        List<ContributionRow> unfrozen = contributionRepository.findUnfrozen(member.id());
        PlanRow plan = createPlan(request.requestId(), member, request.reason(),
                request.installmentType(), request.requestDate(), unfrozen, null);
        return toPlanResponse(plan);
    }

    private PlanResponse doApplySupplementary(SupplementaryPayoutRequest request) {
        MemberRow member = requireMember(request.memberNo());
        PlanRow original = planRepository.findByPlanNo(request.originalPlanNo())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORIGINAL_PLAN_NOT_FOUND",
                        "原支付计划不存在: " + request.originalPlanNo()));
        if (original.memberId() != member.id()) {
            throw new ApiException(HttpStatus.CONFLICT, "ORIGINAL_PLAN_MEMBER_MISMATCH",
                    "原计划属于其他成员，不能作为补发引用");
        }
        if (original.status() != PlanStatus.SETTLED) {
            throw new ApiException(HttpStatus.CONFLICT, "ORIGINAL_PLAN_NOT_SETTLED",
                    "只有已结清（终态）计划才能发起补发，且补发不得改动原计划");
        }
        validateReason(member, request.reason());
        memberRepository.lockById(member.id());
        requireNoInheritanceClaim(member);
        PlanRow concurrent = planRepository.findByRequestId(request.requestId()).orElse(null);
        if (concurrent != null) {
            return replayOrConflict(concurrent, request.memberNo(), request.reason(),
                    request.installmentType(), request.requestDate().toString(), request.originalPlanNo());
        }
        if (planRepository.countActiveByMember(member.id()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_PLAN_EXISTS",
                    "该成员已存在进行中的支付计划");
        }
        List<ContributionRow> unfrozen = contributionRepository.findUnfrozen(member.id());
        if (unfrozen.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_SUPPLEMENTARY_CONTRIBUTIONS",
                    "没有结清后补缴且未冻结的历史缴费可用于补发");
        }
        PlanRow plan = createPlan(request.requestId(), member, request.reason(),
                request.installmentType(), request.requestDate(), unfrozen, original.planNo());
        return toPlanResponse(plan);
    }

    private PlanRow createPlan(String requestId, MemberRow member, PayoutReason reason,
                               InstallmentType installmentType, java.time.LocalDate requestDate,
                               List<ContributionRow> contributions, String originalPlanNo) {
        if (contributions.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_FREEZABLE_CONTRIBUTIONS",
                    "没有可冻结进权益快照的缴费，无法受理支付申请");
        }
        long personal = contributions.stream()
                .filter(c -> c.type() == com.example.starter.pension.domain.ContributionType.PERSONAL)
                .mapToLong(ContributionRow::amountCents)
                .sum();
        long enterprise = contributions.stream()
                .filter(c -> c.type() == com.example.starter.pension.domain.ContributionType.ENTERPRISE)
                .mapToLong(ContributionRow::amountCents)
                .sum();

        int hireYears = vestingCalculator.hireYears(member.hireDate(), requestDate);
        int ratioBp = vestingCalculator.vestedRatioBp(hireYears);
        long vestedEnterprise = vestingCalculator.vestedEnterprise(enterprise, ratioBp);
        long vestedTotal = Math.addExact(personal, vestedEnterprise);
        long unvested = enterprise - vestedEnterprise;

        String fingerprint = Fingerprints.sha256(requestId, member.memberNo(), reason.name(),
                installmentType.name(), requestDate.toString(),
                originalPlanNo == null ? "" : originalPlanNo);

        int count = installmentType.count();
        List<NewInstallment> schedule = installmentScheduler.schedule(
                vestedTotal, count, requestDate.withDayOfMonth(1));
        long scheduleSum = schedule.stream().mapToLong(NewInstallment::amountCents).sum();
        if (scheduleSum != vestedTotal) {
            throw new IllegalStateException("分期总额与归属权益快照不一致");
        }

        String planNo = newPlanNo();
        PlanRow plan = new PlanRow(0L, planNo, requestId, member.id(), reason, installmentType,
                fingerprint, requestDate, hireYears, ratioBp, personal, enterprise,
                vestedEnterprise, vestedTotal, unvested, count, PlanStatus.ACCEPTED,
                member.id(), false, originalPlanNo, originalPlanNo != null, null);
        long planId = planRepository.insert(plan);

        List<Long> contributionIds = contributions.stream().map(ContributionRow::id).toList();
        int frozen = contributionRepository.freeze(contributionIds, planId);
        if (frozen != contributions.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_SNAPSHOT_FREEZE",
                    "缴费快照被并发申请抢先冻结，唯一受理失败");
        }
        installmentRepository.insertBatch(planId, schedule);
        return planRepository.findById(planId)
                .orElseThrow(() -> new IllegalStateException("计划插入后无法回读"));
    }

    // ------------------------------------------------------------------
    // 单期出款
    // ------------------------------------------------------------------

    private PaymentResultResponse doPay(PaymentInstructionRequest request) {
        PlanRow plan = planRepository.findByPlanNo(request.planNo())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "支付计划不存在: " + request.planNo()));
        PlanRow lockedPlan = planRepository.lockById(plan.id()).orElseThrow();
        if (lockedPlan.status() == PlanStatus.TERMINATED) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_TERMINATED",
                    "计划已因继承申领终止，剩余分期不再出款");
        }
        InstructionRow lockedReplay = instructionRepository.findByRequestId(request.requestId())
                .orElse(null);
        if (lockedReplay != null) {
            return paymentReplay(request, lockedPlan, lockedReplay);
        }
        InstallmentRow installment = installmentRepository
                .lockByPlanAndSeq(lockedPlan.id(), request.seqNo())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INSTALLMENT_NOT_FOUND",
                        "期次不存在: " + request.seqNo()));
        if (installment.status() == InstallmentStatus.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, "INSTALLMENT_CANCELLED",
                    "该期次已因继承申领取消，不再出款");
        }
        if (installment.status() == InstallmentStatus.PAID) {
            throw new ApiException(HttpStatus.CONFLICT, "INSTALLMENT_ALREADY_PAID",
                    "该期次已出款，每期最多出款一次");
        }

        String fingerprint = Fingerprints.sha256(request.requestId(), lockedPlan.planNo(),
                Integer.toString(request.seqNo()), Long.toString(installment.amountCents()));
        int attempts = installment.attempts() + 1;
        boolean success = bankGateway.payout(lockedPlan.planNo(), request.seqNo(),
                installment.amountCents(), request.forceFail());

        if (success) {
            int paid = installmentRepository.markPaidIfNotYet(
                    installment.id(), attempts, timeProvider.nowUtc());
            if (paid == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "INSTALLMENT_ALREADY_PAID",
                        "该期次已出款，每期最多出款一次");
            }
            ledgerRepository.insertMemberPayout(lockedPlan.id(), installment.id(),
                    installment.amountCents());
            instructionRepository.insert(new InstructionRow(0L, request.requestId(), lockedPlan.id(),
                    installment.id(), request.seqNo(), installment.amountCents(), fingerprint,
                    true, timeProvider.nowUtc()));
            planRepository.markInProgress(lockedPlan.id());

            List<InstallmentRow> all = installmentRepository.findByPlanId(lockedPlan.id());
            boolean allPaid = all.stream().allMatch(i -> i.status() == InstallmentStatus.PAID);
            if (allPaid) {
                ensureEnterpriseReturned(lockedPlan);
                planRepository.markSettled(lockedPlan.id(), timeProvider.nowUtc());
            }
            InstallmentRow refreshed = installmentRepository.findByPlanAndSeq(lockedPlan.id(), request.seqNo()).orElseThrow();
            return new PaymentResultResponse(request.requestId(), lockedPlan.planNo(), request.seqNo(),
                    installment.amountCents(), true, false, refreshed.paidAt(), installment.dueDate());
        }

        installmentRepository.markFailed(installment.id(), attempts);
        instructionRepository.insert(new InstructionRow(0L, request.requestId(), lockedPlan.id(),
                installment.id(), request.seqNo(), installment.amountCents(), fingerprint,
                false, timeProvider.nowUtc()));
        return new PaymentResultResponse(request.requestId(), lockedPlan.planNo(), request.seqNo(),
                installment.amountCents(), false, false, null, installment.dueDate());
    }

    /**
     * 未归属企业缴费划回：以计划标志位条件更新为闸门，保证只划转一次；
     * 与末期成员出款处于同一事务。
     */
    private void ensureEnterpriseReturned(PlanRow lockedPlan) {
        if (lockedPlan.unvestedReturnCents() <= 0 || lockedPlan.enterpriseReturned()) {
            return;
        }
        int flagged = planRepository.markEnterpriseReturned(lockedPlan.id());
        if (flagged > 0) {
            ledgerRepository.insertEnterpriseReturn(lockedPlan.id(), lockedPlan.unvestedReturnCents());
        }
    }

    // ------------------------------------------------------------------
    // 幂等与冲突
    // ------------------------------------------------------------------

    private PaymentResultResponse paymentReplay(PaymentInstructionRequest request,
                                                PlanRow plan, InstructionRow first) {
        if (!plan.planNo().equals(request.planNo()) || first.seqNo() != request.seqNo()) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "支付指令 requestId 已存在但业务参数不一致");
        }
        InstallmentRow installment = installmentRepository
                .findByPlanAndSeq(first.planId(), first.seqNo())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "INSTALLMENT_MISSING", "指令关联期次缺失"));
        return new PaymentResultResponse(request.requestId(), plan.planNo(), first.seqNo(),
                first.amountCents(), first.success(), true, installment.paidAt(),
                installment.dueDate());
    }

    private PlanResponse replayOrConflict(PlanRow existing, String memberNo, PayoutReason reason,
                                          InstallmentType installmentType, String requestDate,
                                          String originalPlanNo) {
        MemberRow member = memberRepository.findById(existing.memberId()).orElseThrow();
        String expectedFingerprint = Fingerprints.sha256(existing.requestId(), member.memberNo(),
                reason.name(), installmentType.name(), requestDate,
                originalPlanNo == null ? "" : originalPlanNo);
        if (!member.memberNo().equals(memberNo) || !existing.fingerprint().equals(expectedFingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务字段不一致，拒绝产生新计划");
        }
        return toPlanResponse(existing);
    }

    private PlanResponse handleApplyIntegrityViolation(PayoutRequest normalRequest,
                                                       SupplementaryPayoutRequest supplementaryRequest) {
        String requestId = normalRequest != null ? normalRequest.requestId() : supplementaryRequest.requestId();
        PlanRow winner = planRepository.findByRequestId(requestId).orElse(null);
        if (winner == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_PLAN_EXISTS",
                    "该成员已存在进行中的支付计划，并发申请仅唯一受理");
        }
        if (normalRequest != null) {
            return replayOrConflict(winner, normalRequest.memberNo(), normalRequest.reason(),
                    normalRequest.installmentType(), normalRequest.requestDate().toString(), null);
        }
        return replayOrConflict(winner, supplementaryRequest.memberNo(), supplementaryRequest.reason(),
                supplementaryRequest.installmentType(), supplementaryRequest.requestDate().toString(),
                supplementaryRequest.originalPlanNo());
    }

    // ------------------------------------------------------------------
    // 视图与校验
    // ------------------------------------------------------------------

    private MemberRow requireMember(String memberNo) {
        return memberRepository.findByNo(memberNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND",
                        "成员不存在: " + memberNo));
    }

    private PlanRow requirePlan(String planNo) {
        return planRepository.findByPlanNo(planNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "支付计划不存在: " + planNo));
    }

    private void validateReason(MemberRow member, PayoutReason reason) {
        MemberStatus required = reason == PayoutReason.RESIGN
                ? MemberStatus.RESIGNED : MemberStatus.RETIRED;
        if (member.status() != required) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REASON_STATUS_MISMATCH",
                    "支付事由与成员状态不一致：" + reason + " 要求成员状态为 " + required);
        }
    }

    private PlanResponse toPlanResponse(PlanRow plan) {
        MemberRow member = memberRepository.findById(plan.memberId()).orElseThrow();
        List<PlanResponse.InstallmentView> views = installmentRepository.findByPlanId(plan.id())
                .stream()
                .map(i -> new PlanResponse.InstallmentView(i.seqNo(), i.dueDate(),
                        i.amountCents(), i.status().name(), i.attempts()))
                .toList();
        String ratioPercent = String.format(Locale.ROOT, "%.2f", plan.vestedRatioBp() / 100.0);
        return new PlanResponse(plan.planNo(), plan.originalPlanNo(), plan.supplementary(),
                plan.requestId(), member.memberNo(), plan.reason(), plan.installmentType(),
                plan.requestDate(), plan.hireYears(), ratioPercent, plan.personalAmountCents(),
                plan.enterpriseAmountCents(), plan.vestedEnterpriseCents(), plan.vestedTotalCents(),
                plan.unvestedReturnCents(), plan.status(), plan.enterpriseReturned(),
                plan.settledAt(), views);
    }

    private SettleResponse buildSettleResponse(PlanRow plan, boolean replayed) {
        long paid = ledgerRepository.sumMemberPayouts(plan.id());
        long returned = ledgerRepository.sumEnterpriseReturns(plan.id());
        return new SettleResponse(plan.planNo(), plan.status(), replayed,
                plan.vestedTotalCents(), paid, returned, plan.settledAt());
    }

    private static String newPlanNo() {
        return "PL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 继承申领（一次性继承支付）
    // ------------------------------------------------------------------

    /** 申领窗口：自成员身故日起 180 个自然日（UTC 日界），恰第 180 日受理，逾期拒绝。 */
    private static final long CLAIM_WINDOW_DAYS = 180L;

    @Override
    public InheritanceClaimResponse applyInheritanceClaim(InheritanceClaimRequest request) {
        ClaimRow replay = claimRepository.findByRequestId(request.requestId()).orElse(null);
        if (replay != null) {
            return claimReplayOrConflict(replay, request);
        }
        try {
            return transactionTemplate.execute(status -> doApplyClaim(request));
        } catch (DataIntegrityViolationException ex) {
            ClaimRow winner = claimRepository.findByRequestId(request.requestId()).orElse(null);
            if (winner != null) {
                return claimReplayOrConflict(winner, request);
            }
            throw new ApiException(HttpStatus.CONFLICT, "INHERITANCE_CLAIM_EXISTS",
                    "该成员已存在继承申领，多名继承人并发申请仅唯一受理");
        }
    }

    @Override
    public InheritanceClaimResponse getInheritanceClaim(String claimNo) {
        ClaimRow claim = claimRepository.findByClaimNo(claimNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CLAIM_NOT_FOUND",
                        "继承申领不存在: " + claimNo));
        return toClaimResponse(claim, false);
    }

    @Override
    public InheritanceCompleteResponse completeInheritancePayout(String claimNo, boolean forceFail) {
        return transactionTemplate.execute(status -> {
            ClaimRow claim = claimRepository.findByClaimNo(claimNo)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CLAIM_NOT_FOUND",
                            "继承申领不存在: " + claimNo));
            ClaimRow locked = claimRepository.lockById(claim.id()).orElseThrow();
            if (locked.status() == ClaimStatus.PAID) {
                // 终态：重复完成返回幂等成功
                return new InheritanceCompleteResponse(locked.claimNo(), locked.status(),
                        locked.claimAmountCents(), true, true, locked.paidAt());
            }
            boolean success = bankGateway.payout(locked.claimNo(), 0, locked.claimAmountCents(), forceFail);
            if (!success) {
                return new InheritanceCompleteResponse(locked.claimNo(), locked.status(),
                        locked.claimAmountCents(), false, false, null);
            }
            inheritanceLedgerRepository.insert(locked.id(),
                    InheritanceLedgerRepository.ENTRY_INHERITANCE_PAYOUT, 0L, locked.claimAmountCents());
            claimRepository.markPaid(locked.id(), timeProvider.nowUtc());
            ClaimRow refreshed = claimRepository.findByClaimNo(claimNo).orElseThrow();
            return new InheritanceCompleteResponse(refreshed.claimNo(), refreshed.status(),
                    refreshed.claimAmountCents(), true, false, refreshed.paidAt());
        });
    }

    @Override
    public List<LateContributionEventView> listLateContributionEvents(String memberNo) {
        MemberRow member = requireMember(memberNo);
        return lateContributionEventRepository.findByMember(member.id())
                .stream()
                .map(this::toLateEventView)
                .toList();
    }

    /**
     * 继承申领受理事务：成员行锁串行化并发申领；互斥判定（进行中计划）与计划终止、
     * 未付期次取消、申领及台账建账在同一事务内提交或回滚。
     */
    private InheritanceClaimResponse doApplyClaim(InheritanceClaimRequest request) {
        MemberRow member = requireMember(request.memberNo());
        memberRepository.lockById(member.id());
        ClaimRow concurrent = claimRepository.findByRequestId(request.requestId()).orElse(null);
        if (concurrent != null) {
            return claimReplayOrConflict(concurrent, request);
        }
        if (claimRepository.findByMember(member.id()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "INHERITANCE_CLAIM_EXISTS",
                    "该成员已存在继承申领，多名继承人申请仅唯一受理");
        }

        LocalDate applyDate = timeProvider.nowUtc().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        if (request.deathDate().isAfter(applyDate)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEATH_DATE_IN_FUTURE",
                    "身故日晚于受理日，申领无效");
        }
        if (applyDate.isAfter(request.deathDate().plusDays(CLAIM_WINDOW_DAYS))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CLAIM_WINDOW_EXPIRED",
                    "申领已逾期：须于成员身故日起 180 个自然日（UTC 日界）内申请");
        }

        // 账户余额=尚未被任何支付计划快照冻结的缴费合计
        long accountBalance = contributionRepository.findUnfrozen(member.id()).stream()
                .mapToLong(ContributionRow::amountCents)
                .sum();

        // 互斥：存在进行中分期计划时，受理继承即终止剩余分期（已支付部分不回溯）
        PlanRow activePlan = planRepository.findActiveByMember(member.id()).orElse(null);
        List<InstallmentRow> unpaid = List.of();
        if (activePlan != null) {
            planRepository.lockById(activePlan.id());
            unpaid = installmentRepository.findUnpaidByPlan(activePlan.id());
            installmentRepository.cancelUnpaidByPlan(activePlan.id());
            planRepository.markTerminated(activePlan.id(), timeProvider.nowUtc());
        }
        long unpaidPrincipal = unpaid.stream().mapToLong(InstallmentRow::amountCents).sum();

        // 勾稽：申报两项合计必须等于申报继承支付额，且与系统核算一致，不等拒绝
        long declaredSum = Math.addExact(request.declaredAccountBalanceCents(),
                request.declaredUnpaidInstallmentCents());
        if (declaredSum != request.declaredClaimAmountCents()
                || request.declaredAccountBalanceCents() != accountBalance
                || request.declaredUnpaidInstallmentCents() != unpaidPrincipal) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RECONCILE_MISMATCH",
                    "继承金额勾稽不一致：继承支付额必须等于账户余额加未支付分期本金之和"
                            + "（系统核算账户余额=" + accountBalance + "，未支付分期本金=" + unpaidPrincipal + "）");
        }

        String fingerprint = claimFingerprint(request);
        ClaimRow claim = new ClaimRow(0L, newClaimNo(), request.requestId(), member.id(),
                request.heirName(), request.deathDate(), applyDate, fingerprint,
                accountBalance, unpaidPrincipal, request.declaredClaimAmountCents(),
                activePlan == null ? null : activePlan.id(), ClaimStatus.ACCEPTED, null);
        long claimId = claimRepository.insert(claim);

        inheritanceLedgerRepository.insert(claimId,
                InheritanceLedgerRepository.ENTRY_ACCOUNT_BALANCE, 0L, accountBalance);
        for (InstallmentRow installment : unpaid) {
            inheritanceLedgerRepository.insert(claimId,
                    InheritanceLedgerRepository.ENTRY_UNPAID_INSTALLMENT,
                    installment.id(), installment.amountCents());
        }
        return toClaimResponse(claimRepository.findByClaimNo(claim.claimNo()).orElseThrow(), false);
    }

    private InheritanceClaimResponse claimReplayOrConflict(ClaimRow existing, InheritanceClaimRequest request) {
        MemberRow member = memberRepository.findById(existing.memberId()).orElseThrow();
        if (!member.memberNo().equals(request.memberNo())
                || !existing.fingerprint().equals(claimFingerprint(request))) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务字段不一致，拒绝产生新申领");
        }
        return toClaimResponse(existing, true);
    }

    private static String claimFingerprint(InheritanceClaimRequest request) {
        return Fingerprints.sha256(request.requestId(), request.memberNo(), request.heirName(),
                request.deathDate().toString(),
                Long.toString(request.declaredAccountBalanceCents()),
                Long.toString(request.declaredUnpaidInstallmentCents()),
                Long.toString(request.declaredClaimAmountCents()));
    }

    private void requireNoInheritanceClaim(MemberRow member) {
        if (claimRepository.findByMember(member.id()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "INHERITANCE_CLAIM_EXISTS",
                    "该成员已存在继承申领，继承与分期支付互斥，不得再受理支付申请");
        }
    }

    private InheritanceClaimResponse toClaimResponse(ClaimRow claim, boolean replayed) {
        MemberRow member = memberRepository.findById(claim.memberId()).orElseThrow();
        String terminatedPlanNo = null;
        if (claim.terminatedPlanId() != null) {
            terminatedPlanNo = planRepository.findById(claim.terminatedPlanId())
                    .map(PlanRow::planNo)
                    .orElse(null);
        }
        List<InheritanceClaimResponse.LedgerEntry> ledger = inheritanceLedgerRepository
                .findByClaimId(claim.id())
                .stream()
                .map(e -> new InheritanceClaimResponse.LedgerEntry(e.entryType(),
                        e.refId() == 0L ? null : e.refId(), e.amountCents()))
                .toList();
        return new InheritanceClaimResponse(claim.claimNo(), claim.requestId(), member.memberNo(),
                claim.heirName(), claim.deathDate(), claim.applyDate(), claim.accountBalanceCents(),
                claim.unpaidInstallmentCents(), claim.claimAmountCents(), claim.status(),
                terminatedPlanNo, replayed, claim.paidAt(), ledger);
    }

    private LateContributionEventView toLateEventView(LateContributionEventRow row) {
        String claimNo = claimRepository.findByMember(row.memberId())
                .map(ClaimRow::claimNo)
                .orElse(null);
        return new LateContributionEventView(row.id(), row.contributionId(), claimNo,
                row.amountCents(), row.note(), row.createdAt());
    }

    private static String newClaimNo() {
        return "IC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    }
}
