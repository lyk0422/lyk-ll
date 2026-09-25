package com.example.starter.pension.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.pension.domain.AccountEntryType;
import com.example.starter.pension.domain.AttemptResult;
import com.example.starter.pension.domain.ContributionSource;
import com.example.starter.pension.domain.InstallmentStatus;
import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;
import com.example.starter.pension.dto.InstallmentResponse;
import com.example.starter.pension.dto.PaymentRequest;
import com.example.starter.pension.dto.PaymentResponse;
import com.example.starter.pension.dto.PlanResponse;
import com.example.starter.pension.dto.PayoutRequest;
import com.example.starter.pension.dto.SettleResponse;
import com.example.starter.pension.dto.SupplementRequest;
import com.example.starter.pension.repo.ContributionRepository.ContributionPo;
import com.example.starter.pension.repo.MemberRepository.MemberPo;
import com.example.starter.pension.repo.PlanRepository.AccountEntryPo;
import com.example.starter.pension.repo.PlanRepository.IdempotencyPo;
import com.example.starter.pension.repo.PlanRepository.InstallmentPo;
import com.example.starter.pension.repo.PlanRepository.PaymentAttemptPo;
import com.example.starter.pension.repo.PlanRepository.PlanContributionPo;
import com.example.starter.pension.repo.PlanRepository.PlanPo;
import com.example.starter.pension.repo.ContributionRepository;
import com.example.starter.pension.repo.MemberRepository;
import com.example.starter.pension.repo.PlanRepository;
import com.example.starter.pension.support.FingerprintUtil;
import com.example.starter.pension.support.UtcClock;
import com.example.starter.pension.web.BadRequestException;
import com.example.starter.pension.web.ConflictException;
import com.example.starter.pension.web.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 年金支付核心服务：申请受理冻结权益快照、分期、逐期出款、结清划回与补发。
 *
 * <p>并发正确性依赖：成员行锁 {@code SELECT ... FOR UPDATE} 串行化同成员受理，
 * 数据库唯一索引兜底“同成员唯一进行中计划”，期次行锁 + 条件更新保证每期最多出款一次。
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private static final String OP_APPLY = "APPLY";
    private static final String OP_PAYMENT = "PAYMENT";

    private final MemberRepository memberRepository;
    private final ContributionRepository contributionRepository;
    private final PlanRepository planRepository;
    private final MemberService memberService;
    private final VestingCalculator calculator;
    private final UtcClock clock;
    private final ObjectMapper objectMapper;

    public PayoutService(MemberRepository memberRepository,
                         ContributionRepository contributionRepository,
                         PlanRepository planRepository,
                         MemberService memberService,
                         VestingCalculator calculator,
                         UtcClock clock,
                         ObjectMapper objectMapper) {
        this.memberRepository = memberRepository;
        this.contributionRepository = contributionRepository;
        this.planRepository = planRepository;
        this.memberService = memberService;
        this.calculator = calculator;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ---------------- 支付申请 ----------------

    /**
     * 受理支付申请：校验状态、冻结权益快照、生成分期计划。以 requestId 幂等。
     */
    @Transactional
    public PlanResponse apply(PayoutRequest request) {
        String fingerprint = FingerprintUtil.sha256(
                OP_APPLY, request.requestId(), request.memberNo(),
                request.reason().name(), request.installmentType().name());

        // 先非锁定校验成员存在，再锁定成员行串行化同成员并发受理。
        MemberPo member = memberRepository.findByNo(request.memberNo())
                .orElseThrow(() -> new NotFoundException("成员不存在: " + request.memberNo()));
        memberRepository.lockByNo(request.memberNo());

        PlanResponse replay = replayIfPresent(request.requestId(), fingerprint, PlanResponse.class);
        if (replay != null) {
            return replay;
        }
        assertNoActivePlan(request.memberNo(), null);
        if (planRepository.findPlanByMemberAndStatus(request.memberNo(), PlanStatus.SETTLED).isPresent()) {
            throw new BadRequestException("成员已有结清计划，结清后补缴的历史缴费须走补发流程 "
                    + "(/api/pension/supplements) 并引用原计划号");
        }
        memberService.requirePayableStatus(member, request.reason());

        return createPlan(request.requestId(), member, request.reason(), request.installmentType(), null,
                fingerprint, OP_APPLY);
    }

    /**
     * 补发申请：原计划必须已结清，生成引用原计划号的新计划，不改动原计划。
     */
    @Transactional
    public PlanResponse supplement(SupplementRequest request) {
        String fingerprint = FingerprintUtil.sha256(
                OP_APPLY, request.requestId(), request.originalPlanNo(),
                "SUPPLEMENT", request.installmentType().name());

        PlanPo original = planRepository.findPlanByNo(request.originalPlanNo())
                .orElseThrow(() -> new NotFoundException("原计划不存在: " + request.originalPlanNo()));
        if (original.status() != PlanStatus.SETTLED) {
            throw new BadRequestException("原计划尚未结清，结清后的补缴才能走补发流程: "
                    + request.originalPlanNo());
        }

        MemberPo member = memberRepository.lockByNo(original.memberNo());
        PlanResponse replay = replayIfPresent(request.requestId(), fingerprint, PlanResponse.class);
        if (replay != null) {
            return replay;
        }
        assertNoActivePlan(member.memberNo(), null);

        return createPlan(request.requestId(), member, original.reason(),
                request.installmentType(), original.planNo(), fingerprint, OP_APPLY);
    }

    /**
     * 创建计划的共享内部逻辑：锁定未受理缴费、计算快照、写计划/快照/期次/幂等记录。
     */
    private PlanResponse createPlan(String requestId, MemberPo member, PayoutReason reason,
                                    InstallmentType installmentType, String originalPlanNo,
                                    String fingerprint, String operation) {
        LocalDate applicationDate = clock.todayUtc();
        int tenureYears = calculator.tenureYears(member.hireDate(), applicationDate);
        int rateBp = calculator.employerVestingRateBp(tenureYears);

        // 锁定该成员尚未受理的缴费，保证并发受理下每笔缴费只进入一个计划。
        List<ContributionPo> unused = contributionRepository.lockUnusedByMember(member.memberNo());
        if (unused.isEmpty()) {
            throw new BadRequestException("没有可受理的缴费记录，无法创建支付计划");
        }

        long employeeAmount = 0;
        long employerVested = 0;
        long employerForfeit = 0;
        List<PlanContributionPo> snapshots = new ArrayList<>(unused.size());
        String planNo = newPlanNo();

        for (ContributionPo c : unused) {
            long vested;
            long returned;
            if (c.source() == ContributionSource.EMPLOYEE) {
                vested = c.amountCents();
                returned = 0;
                employeeAmount += c.amountCents();
            } else {
                vested = calculator.vestedAmount(c.amountCents(), rateBp);
                returned = c.amountCents() - vested;
                employerVested += vested;
                employerForfeit += returned;
            }
            snapshots.add(new PlanContributionPo(
                    planNo, c.id(), c.source(), c.amountCents(), vested, returned));
        }
        long totalAmount = employeeAmount + employerVested;

        Instant now = clock.nowInstant();
        int count = installmentType.count();
        PlanPo plan = new PlanPo(null, planNo, requestId, member.memberNo(), reason, installmentType, count,
                PlanStatus.ACTIVE, employeeAmount, employerVested, employerForfeit, totalAmount,
                tenureYears, rateBp, applicationDate, originalPlanNo, null, now);
        try {
            planRepository.insertPlan(plan);
        } catch (DuplicateKeyException e) {
            // 兜底：同成员进行中计划或 requestId 唯一约束冲突。
            throw new ConflictException("支付申请冲突：同一成员同时刻只允许一个进行中的支付计划，"
                    + "或 requestId 已被使用: " + requestId);
        }

        for (ContributionPo c : unused) {
            int marked = contributionRepository.markUsedIfFree(c.id(), planNo);
            if (marked != 1) {
                // 行锁保护下理论不可达；出现即说明并发抢占，整体回滚。
                throw new ConflictException("缴费已被其他计划受理: contributionId=" + c.id());
            }
        }
        planRepository.insertPlanContributions(snapshots);
        planRepository.insertInstallments(buildInstallments(planNo, applicationDate,
                installmentType, totalAmount));

        PlanResponse response = loadPlanResponse(planNo);
        saveIdempotency(requestId, operation, fingerprint, response, PlanResponse.class);
        return response;
    }

    // ---------------- 逐期支付 ----------------

    /**
     * 对单期下发出款指令。以 requestId 幂等；失败可换新 requestId 重试；每期最多出款一次；
     * 末期出款成功后同事务自动结清并划回未归属企业缴费。
     */
    @Transactional
    public PaymentResponse pay(PaymentRequest request) {
        String fingerprint = FingerprintUtil.sha256(
                OP_PAYMENT, request.requestId(), request.planNo(),
                String.valueOf(request.seqNo()), String.valueOf(request.forceFail()));

        // 先锁计划与期次，再查幂等：并发同键请求会在首个事务提交后重放首次结果，
        // 而不会因期次已出款误报 409。
        PlanPo plan = planRepository.findPlanByNo(request.planNo())
                .orElseThrow(() -> new NotFoundException("支付计划不存在: " + request.planNo()));
        planRepository.lockPlanByNo(request.planNo());
        InstallmentPo installment = planRepository.lockInstallment(request.planNo(), request.seqNo())
                .orElseThrow(() -> new NotFoundException("期次不存在: planNo=" + request.planNo()
                        + ", seqNo=" + request.seqNo()));

        PaymentResponse replay = replayIfPresent(request.requestId(), fingerprint, PaymentResponse.class);
        if (replay != null) {
            return replay;
        }

        if (installment.status() == InstallmentStatus.PAID) {
            throw new ConflictException("该期已出款，每期最多出款一次: seqNo=" + request.seqNo()
                    + ", 原出款 requestId=" + installment.paidRequestId());
        }

        Instant now = clock.nowInstant();
        if (Boolean.TRUE.equals(request.forceFail())) {
            PaymentAttemptPo attempt = new PaymentAttemptPo(null, request.requestId(), request.planNo(),
                    request.seqNo(), installment.amountCents(), AttemptResult.FAILED,
                    "模拟托管行出款失败", now);
            planRepository.insertAttempt(attempt);
            PaymentResponse response = new PaymentResponse(request.requestId(), request.planNo(),
                    request.seqNo(), installment.amountCents(), AttemptResult.FAILED,
                    "模拟托管行出款失败，期次保持待支付，可换新 requestId 重试",
                    PlanStatus.ACTIVE, false, null, now);
            saveIdempotency(request.requestId(), OP_PAYMENT, fingerprint, response, PaymentResponse.class);
            return response;
        }

        // 模拟托管行出款成功：条件更新保证每期最多出款一次。
        int paidRows = planRepository.markInstallmentPaid(
                request.planNo(), request.seqNo(), request.requestId(), now);
        if (paidRows != 1) {
            throw new ConflictException("该期已被并发请求出款: seqNo=" + request.seqNo());
        }
        planRepository.insertAttempt(new PaymentAttemptPo(null, request.requestId(), request.planNo(),
                request.seqNo(), installment.amountCents(), AttemptResult.SUCCESS, "出款成功", now));
        if (installment.amountCents() > 0) {
            planRepository.insertAccountEntry(new AccountEntryPo(null, request.planNo(),
                    AccountEntryType.MEMBER_PAYOUT, request.seqNo(), installment.amountCents(),
                    request.requestId(), now));
        }

        boolean settled = false;
        Instant settledAt = null;
        if (planRepository.countPendingInstallments(request.planNo()) == 0) {
            // 全部期次完成，同事务自动结清并把未归属企业缴费划回企业账户。
            settledAt = now;
            settleWithinTransaction(plan, settledAt, request.requestId());
            settled = true;
        }

        PaymentResponse response = new PaymentResponse(request.requestId(), request.planNo(),
                request.seqNo(), installment.amountCents(), AttemptResult.SUCCESS, "出款成功",
                settled ? PlanStatus.SETTLED : PlanStatus.ACTIVE, settled, settledAt, now);
        saveIdempotency(request.requestId(), OP_PAYMENT, fingerprint, response, PaymentResponse.class);
        return response;
    }

    // ---------------- 结清 ----------------

    /**
     * 结清计划：正常路径由末期出款自动触发；本接口用于查询/兜底，重复结清幂等成功。
     */
    @Transactional
    public SettleResponse settle(String planNo) {
        PlanPo plan = planRepository.findPlanByNo(planNo)
                .orElseThrow(() -> new NotFoundException("支付计划不存在: " + planNo));
        planRepository.lockPlanByNo(planNo);
        if (plan.status() == PlanStatus.SETTLED) {
            return new SettleResponse(planNo, PlanStatus.SETTLED, true,
                    planRepository.sumAccountEntries(planNo, AccountEntryType.COMPANY_RETURN),
                    plan.settledAtUtc());
        }
        if (planRepository.countPendingInstallments(planNo) > 0) {
            throw new BadRequestException("尚有未完成期次，不能结清，全部期次完成后将自动结清");
        }
        Instant settledAt = clock.nowInstant();
        settleWithinTransaction(plan, settledAt, "SETTLE:" + planNo);
        return new SettleResponse(planNo, PlanStatus.SETTLED, false, plan.employerForfeitCents(), settledAt);
    }

    /**
     * 在当前事务内把计划置为结清，并按快照逐笔登记未归属企业缴费划回企业台账。
     */
    private void settleWithinTransaction(PlanPo plan, Instant settledAt, String requestId) {
        int rows = planRepository.markSettledIfActive(plan.planNo(), settledAt);
        if (rows == 0) {
            // 已结清（幂等重放路径），不重复划转。
            return;
        }
        List<PlanContributionPo> snapshots = planRepository.findPlanContributions(plan.planNo());
        for (PlanContributionPo s : snapshots) {
            if (s.returnedCents() > 0) {
                planRepository.insertAccountEntry(new AccountEntryPo(null, plan.planNo(),
                        AccountEntryType.COMPANY_RETURN, null, s.returnedCents(), requestId, settledAt));
            }
        }
        long totalReturned = snapshots.stream().mapToLong(PlanContributionPo::returnedCents).sum();
        log.info("计划 {} 结清，未归属企业缴费 {} 分已同事务划回企业账户", plan.planNo(), totalReturned);
    }

    // ---------------- 查询 ----------------

    /**
     * 查询计划详情（含分期明细）。
     */
    public PlanResponse getPlan(String planNo) {
        return loadPlanResponse(planNo);
    }

    private PlanResponse loadPlanResponse(String planNo) {
        PlanPo plan = planRepository.findPlanByNo(planNo)
                .orElseThrow(() -> new NotFoundException("支付计划不存在: " + planNo));
        List<InstallmentResponse> installments = planRepository.findInstallments(planNo).stream()
                .map(i -> new InstallmentResponse(i.seqNo(), i.dueDate(), i.amountCents(),
                        i.status(), i.paidAtUtc(), i.paidRequestId()))
                .toList();
        return new PlanResponse(plan.planNo(), plan.requestId(), plan.memberNo(), plan.reason(),
                plan.installmentType(), plan.installmentCount(), plan.status(),
                plan.employeeAmountCents(), plan.employerVestedCents(), plan.employerForfeitCents(),
                plan.totalAmountCents(), plan.tenureYears(), plan.vestingRateBp(),
                plan.applicationDate(), plan.originalPlanNo(), plan.settledAtUtc(),
                plan.createdAtUtc(), installments);
    }

    // ---------------- 内部辅助 ----------------

    private void assertNoActivePlan(String memberNo, String excludePlanNo) {
        planRepository.findPlanByMemberAndStatus(memberNo, PlanStatus.ACTIVE)
                .filter(p -> !p.planNo().equals(excludePlanNo))
                .ifPresent(p -> {
                    throw new ConflictException(
                            "同一成员同时刻只允许一个进行中的支付计划: " + p.planNo());
                });
    }

    private List<InstallmentPo> buildInstallments(String planNo, LocalDate applicationDate,
                                                  InstallmentType type, long totalAmount) {
        int count = type.count();
        List<Long> amounts = calculator.splitInstallments(totalAmount, count);
        // 各期按月初（UTC）到期：首期为申请当月月初，其后逐期顺延一个月。
        LocalDate firstDueDate = YearMonth.from(applicationDate).atDay(1);
        List<InstallmentPo> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new InstallmentPo(null, planNo, i + 1, firstDueDate.plusMonths(i),
                    amounts.get(i), InstallmentStatus.PENDING, null, null));
        }
        return result;
    }

    private String newPlanNo() {
        return "PL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                .toUpperCase(Locale.ROOT);
    }

    /**
     * 命中幂等记录时：同指纹反序列化首次结果返回；异指纹抛 409，不产生新指令。
     *
     * @return 首次结果；无记录时返回 {@code null}
     */
    private <T> T replayIfPresent(String requestId, String fingerprint, Class<T> type) {
        var existing = planRepository.findIdempotency(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        IdempotencyPo po = existing.get();
        if (!po.fingerprint().equals(fingerprint)) {
            throw new ConflictException("requestId 已被使用但业务参数不一致（同键异参）: " + requestId);
        }
        try {
            return objectMapper.readValue(po.responseJson(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private void saveIdempotency(String requestId, String operation, String fingerprint,
                                 Object response, Class<?> type) {
        try {
            String json = objectMapper.writeValueAsString(response);
            planRepository.insertIdempotency(new IdempotencyPo(requestId, operation, fingerprint,
                    json, clock.nowInstant()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId：以已存记录为准做同参重放或异参冲突。
            IdempotencyPo po = planRepository.findIdempotency(requestId)
                    .orElseThrow(() -> new ConflictException("requestId 冲突: " + requestId));
            if (!po.fingerprint().equals(fingerprint)) {
                throw new ConflictException("requestId 已被使用但业务参数不一致（同键异参）: " + requestId);
            }
        }
    }
}
