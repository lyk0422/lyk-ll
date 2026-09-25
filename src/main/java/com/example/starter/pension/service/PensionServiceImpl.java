package com.example.starter.pension.service;

import com.example.starter.pension.api.ContributionRequest;
import com.example.starter.pension.api.MemberUpsertRequest;
import com.example.starter.pension.api.PaymentInstructionRequest;
import com.example.starter.pension.api.PaymentResultResponse;
import com.example.starter.pension.api.PayoutRequest;
import com.example.starter.pension.api.PlanResponse;
import com.example.starter.pension.api.SettleResponse;
import com.example.starter.pension.api.SupplementaryPayoutRequest;
import com.example.starter.pension.bank.BankGateway;
import com.example.starter.pension.domain.InstallmentStatus;
import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.MemberStatus;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;
import com.example.starter.pension.po.ContributionRow;
import com.example.starter.pension.po.InstallmentRow;
import com.example.starter.pension.po.InstructionRow;
import com.example.starter.pension.po.MemberRow;
import com.example.starter.pension.po.PlanRow;
import com.example.starter.pension.repo.ContributionRepository;
import com.example.starter.pension.repo.InstallmentRepository;
import com.example.starter.pension.repo.InstallmentRepository.NewInstallment;
import com.example.starter.pension.repo.InstructionRepository;
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
        MemberRow member = requireMember(request.memberNo());
        contributionRepository.insert(member.id(), request.contributionType(), request.amountCents(),
                request.contributionDate(), request.period());
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
        InstructionRow lockedReplay = instructionRepository.findByRequestId(request.requestId())
                .orElse(null);
        if (lockedReplay != null) {
            return paymentReplay(request, lockedPlan, lockedReplay);
        }
        InstallmentRow installment = installmentRepository
                .lockByPlanAndSeq(lockedPlan.id(), request.seqNo())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INSTALLMENT_NOT_FOUND",
                        "期次不存在: " + request.seqNo()));
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
}
