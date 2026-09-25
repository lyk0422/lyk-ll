package com.example.starter.reinsurance.service;

import com.example.starter.reinsurance.api.AdjustEstimateRequest;
import com.example.starter.reinsurance.api.ClaimResponse;
import com.example.starter.reinsurance.api.CloseClaimRequest;
import com.example.starter.reinsurance.api.CreateClaimRequest;
import com.example.starter.reinsurance.common.ApiException;
import com.example.starter.reinsurance.common.ErrorCode;
import com.example.starter.reinsurance.common.Money;
import com.example.starter.reinsurance.domain.AccountLedger;
import com.example.starter.reinsurance.domain.Claim;
import com.example.starter.reinsurance.domain.ClaimRecovery;
import com.example.starter.reinsurance.domain.ClaimStatus;
import com.example.starter.reinsurance.domain.Policy;
import com.example.starter.reinsurance.domain.ReinsuranceAccount;
import com.example.starter.reinsurance.domain.Treaty;
import com.example.starter.reinsurance.repository.AccountLedgerRepository;
import com.example.starter.reinsurance.repository.ClaimEventRepository;
import com.example.starter.reinsurance.repository.ClaimRecoveryRepository;
import com.example.starter.reinsurance.repository.ClaimRepository;
import com.example.starter.reinsurance.repository.PolicyRepository;
import com.example.starter.reinsurance.repository.ReinsuranceAccountRepository;
import com.example.starter.reinsurance.repository.TreatyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 赔案与摊回服务。
 *
 * <p>核心不变量：</p>
 * <ul>
 *   <li>未决按估损预提摊回，受保单责任限额约束（恰达限额按限额计入，超出留在分出公司）；</li>
 *   <li>结案按实际赔付计算最终实摊，预提与冲销差额在同一事务内完成；</li>
 *   <li>摊回账户余额任何时刻不得为负（条件更新 + 数据库 CHECK 双重保证）；</li>
 *   <li>最终结案只允许一次，结案为终态；重复结案返回首次结果；迟到估损调整只记事件。</li>
 * </ul>
 */
@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final PolicyRepository policyRepository;
    private final TreatyRepository treatyRepository;
    private final ClaimRecoveryRepository recoveryRepository;
    private final ReinsuranceAccountRepository accountRepository;
    private final AccountLedgerRepository ledgerRepository;
    private final ClaimEventRepository eventRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public ClaimService(
            ClaimRepository claimRepository,
            PolicyRepository policyRepository,
            TreatyRepository treatyRepository,
            ClaimRecoveryRepository recoveryRepository,
            ReinsuranceAccountRepository accountRepository,
            AccountLedgerRepository ledgerRepository,
            ClaimEventRepository eventRepository,
            IdempotencyService idempotencyService,
            Clock clock) {
        this.claimRepository = claimRepository;
        this.policyRepository = policyRepository;
        this.treatyRepository = treatyRepository;
        this.recoveryRepository = recoveryRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.eventRepository = eventRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /** 报案并按估损金额预提摊回（同事务）。 */
    @Transactional
    public ClaimResponse createClaim(CreateClaimRequest request) {
        Policy policy = policyRepository.getByPolicyNo(request.policyNo());
        Treaty treaty = treatyRepository.getById(policy.getContractId());
        BigDecimal estimate = Money.requireNonNegative(request.estimatedAmount(), "estimatedAmount");

        // 先锁账户行，串行化同合约全部预提/冲销；锁顺序固定为 账户 → 赔案 → 摊回记录。
        accountRepository.lockByContract(treaty.getId());

        Claim claim = new Claim();
        claim.setClaimNo(request.claimNo());
        claim.setPolicyId(policy.getId());
        claim.setEstimatedAmount(estimate);
        claim.setPaidAmount(null);
        claim.setStatus(ClaimStatus.OPEN);
        claim.setLossDate(request.lossDate());
        try {
            claimRepository.insert(claim);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE, "赔案号已存在: " + request.claimNo());
        }
        eventRepository.record(claim.getId(), "CREATED", null, estimate, "赔案创建");
        recoveryRepository.create(claim.getId(), policy.getId(), treaty.getId());

        ClaimRecovery recovery = recoveryRepository.getByClaimIdForUpdate(claim.getId());
        BigDecimal provision = postProvision(treaty, policy, claim, recovery, estimate, "PROVISION", null);
        recoveryRepository.updateProvision(claim.getId(), provision);

        return toResponse(claim, recoveryRepository.getByClaimId(claim.getId()));
    }

    /**
     * 估损调整。未决：同事务调整预提；已最终结案：迟到调整只记事件，不改任何金额与余额。
     * 统一加锁顺序 账户 → 赔案 → 摊回记录，避免与报案/结案互相死锁。
     */
    @Transactional
    public ClaimResponse adjustEstimate(String claimNo, AdjustEstimateRequest request) {
        BigDecimal newEstimate =
                Money.requireNonNegative(request.newEstimatedAmount(), "newEstimatedAmount");
        Claim existing = claimRepository.findByClaimNo(claimNo)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "赔案不存在: " + claimNo));
        Policy policy = policyRepository.getById(existing.getPolicyId());
        Treaty treaty = treatyRepository.getById(policy.getContractId());

        accountRepository.lockByContract(treaty.getId());
        Claim claim = claimRepository.getByClaimNoForUpdate(claimNo);
        ClaimRecovery recovery = recoveryRepository.getByClaimIdForUpdate(claim.getId());

        if (claim.getStatus() == ClaimStatus.FINAL_CLOSED) {
            // 终态：迟到估损调整只记事件。
            eventRepository.record(
                    claim.getId(),
                    "LATE_ESTIMATE_RECORDED",
                    claim.getEstimatedAmount(),
                    newEstimate,
                    "赔案已最终结案，迟到估损调整仅记录事件，不改动摊回与账户");
            return toResponse(claim, recovery);
        }

        BigDecimal oldEstimate = claim.getEstimatedAmount();
        BigDecimal currentProvision = recovery.getProvisionAmount();

        // 以新估损、按“其他赔案已占容量”重算本赔案目标预提（含限额截断），
        // 再与当前预提求差额，保证上调/下调在限额场景都不会突破保单责任限额。
        BigDecimal usedByOthers =
                Money.amount(recoveryRepository.usedCapacityOfPolicy(policy.getId())
                        .subtract(currentProvision));
        BigDecimal capacity = treaty.getPerPolicyLimit() == null
                ? null
                : Money.amount(treaty.getPerPolicyLimit().subtract(usedByOthers));
        BigDecimal targetProvision = Money.cap(
                Money.applyRate(newEstimate, treaty.getCessionRate()), capacity);
        BigDecimal delta = Money.amount(targetProvision.subtract(currentProvision));

        if (delta.signum() == 0 && newEstimate.compareTo(oldEstimate) != 0) {
            // 金额有变化但摊回无变化（典型：已在限额之上继续上调），仍记录估损调整事件。
            claimRepository.updateEstimate(claim.getId(), newEstimate);
            eventRepository.record(
                    claim.getId(), "ESTIMATE_ADJUSTED", oldEstimate, newEstimate,
                    "估损调整，受责任限额约束摊回无变化");
            Claim refreshed = claimRepository.getByClaimNoForUpdate(claimNo);
            return toResponse(refreshed, recoveryRepository.getByClaimId(claim.getId()));
        }
        if (delta.signum() != 0) {
            String movementType = "ADJUST";
            String remark = delta.signum() > 0
                    ? "估损调整，补提 " + Money.format(delta)
                    : "估损调整，释放预提 " + Money.format(delta.negate());
            postSignedMovement(
                    treaty,
                    policy,
                    claim,
                    Money.amount(newEstimate.subtract(oldEstimate)),
                    delta,
                    movementType,
                    null,
                    null);
            recoveryRepository.updateProvision(claim.getId(), targetProvision);
        }
        claimRepository.updateEstimate(claim.getId(), newEstimate);
        eventRepository.record(claim.getId(), "ESTIMATE_ADJUSTED", oldEstimate, newEstimate,
                delta.signum() == 0 ? "估损金额无变化" : "估损调整，预提差额 " + Money.format(delta));

        Claim refreshed = claimRepository.getByClaimNoForUpdate(claimNo);
        ClaimRecovery refreshedRecovery = recoveryRepository.getByClaimId(claim.getId());
        return toResponse(refreshed, refreshedRecovery);
    }

    /**
     * 最终结案：按实际赔付计算实摊，与未决预提的差额同事务冲销/补提。
     * 重复结案（含不同 requestId）一律返回首次结果。
     */
    @Transactional
    public ClaimResponse closeClaim(String claimNo, CloseClaimRequest request) {
        BigDecimal paid = Money.requireNonNegative(request.paidAmount(), "paidAmount");
        Claim found = claimRepository.findByClaimNo(claimNo)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "赔案不存在: " + claimNo));
        Policy policy = policyRepository.getById(found.getPolicyId());
        Treaty treaty = treatyRepository.getById(policy.getContractId());

        // 统一加锁顺序：账户 → 赔案 → 摊回记录。
        accountRepository.lockByContract(treaty.getId());
        Claim claim = claimRepository.getByClaimNoForUpdate(claimNo);

        // 终态幂等：持锁后复查，重复结案返回首次结果，不产生任何动作。
        if (claim.getStatus() == ClaimStatus.FINAL_CLOSED) {
            ClaimRecovery settled = recoveryRepository.getByClaimId(claim.getId());
            return toResponse(claim, settled);
        }
        ClaimRecovery recovery = recoveryRepository.getByClaimIdForUpdate(claim.getId());

        // 该赔案最终占用容量：仅排除本赔案当前预提后，再按实赔计算限额截断。
        BigDecimal usedByOthers =
                Money.amount(recoveryRepository.usedCapacityOfPolicy(policy.getId())
                        .subtract(recovery.getProvisionAmount()));
        BigDecimal capacity = treaty.getPerPolicyLimit() == null
                ? null
                : Money.amount(treaty.getPerPolicyLimit().subtract(usedByOthers));
        BigDecimal uncapped = Money.applyRate(paid, treaty.getCessionRate());
        BigDecimal settled = Money.cap(uncapped, capacity);
        BigDecimal cappedExcess = Money.amount(uncapped.subtract(settled));

        BigDecimal currentProvision = recovery.getProvisionAmount();
        BigDecimal delta = Money.amount(settled.subtract(currentProvision));
        if (delta.signum() != 0) {
            String remark;
            if (delta.signum() < 0) {
                remark = "结案冲销预提差额 " + Money.format(delta);
            } else {
                remark = "结案补提差额 " + Money.format(delta)
                        + (cappedExcess.signum() > 0 ? "；超限额自留 " + Money.format(cappedExcess) : "");
            }
            postSignedMovement(
                    treaty,
                    policy,
                    claim,
                    Money.amount(paid.subtract(claim.getEstimatedAmount())),
                    delta,
                    "SETTLE",
                    request.requestId(),
                    remark);
        }

        Instant closedAt = Instant.now(clock);
        int closed = claimRepository.finalClose(claim.getId(), paid, closedAt);
        if (closed == 0) {
            // 并发下已被另一事务结案：抛冲突由事务回滚；调用方可重新读取获得首次结果。
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "赔案结案并发冲突，请重试: " + claimNo);
        }
        recoveryRepository.markSettled(claim.getId(), settled, cappedExcess);
        eventRepository.record(
                claim.getId(),
                "SETTLED",
                claim.getEstimatedAmount(),
                paid,
                "最终结案，实摊 " + Money.format(settled)
                        + (cappedExcess.signum() > 0 ? "，限额自留 " + Money.format(cappedExcess) : ""));

        claim.setStatus(ClaimStatus.FINAL_CLOSED);
        claim.setPaidAmount(paid);
        claim.setClosedAt(closedAt);
        ClaimRecovery refreshed = recoveryRepository.getByClaimId(claim.getId());
        return toResponse(claim, refreshed);
    }

    /** 查询赔案视图。 */
    @Transactional(readOnly = true)
    public ClaimResponse getClaim(String claimNo) {
        Claim claim = claimRepository.findByClaimNo(claimNo)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "赔案不存在: " + claimNo));
        ClaimRecovery recovery = recoveryRepository.getByClaimId(claim.getId());
        return toResponse(claim, recovery);
    }

    /**
     * 预提/上调一笔：按比例计算并按保单剩余限额容量截断，入账户、记流水。
     * 返回本次实际新增预提。
     */
    private BigDecimal postProvision(
            Treaty treaty,
            Policy policy,
            Claim claim,
            ClaimRecovery recovery,
            BigDecimal grossBase,
            String movementType,
            String requestId) {
        BigDecimal uncapped = Money.applyRate(grossBase, treaty.getCessionRate());
        BigDecimal capacity = treaty.getPerPolicyLimit() == null
                ? null
                : Money.amount(treaty.getPerPolicyLimit()
                        .subtract(recoveryRepository.usedCapacityOfPolicy(policy.getId())));
        BigDecimal provision = Money.cap(uncapped, capacity);
        String remark = null;
        if (capacity != null && uncapped.compareTo(provision) > 0) {
            remark = "受保单责任限额截断，按剩余容量 "
                    + Money.format(provision) + " 计入，自留 "
                    + Money.format(Money.amount(uncapped.subtract(provision)));
        }
        postSignedMovement(treaty, policy, claim, grossBase, provision, movementType, requestId, remark);
        return provision;
    }

    /**
     * 落一笔带符号摊回变动：条件更新账户（借记时校验余额非负），写流水。
     * 预提与冲销必须与调用方处于同一事务。
     */
    private void postSignedMovement(
            Treaty treaty,
            Policy policy,
            Claim claim,
            BigDecimal signedGross,
            BigDecimal signedAmount,
            String movementType,
            String requestId,
            String remark) {
        BigDecimal amount = Money.amount(signedAmount);
        boolean applied = accountRepository.applyDelta(treaty.getId(), amount);
        if (!applied) {
            throw new ApiException(
                    ErrorCode.INSUFFICIENT_BALANCE,
                    "结案冲销将使摊回账户为负，事务回滚: claimNo=" + claim.getClaimNo());
        }
        ReinsuranceAccount account = accountRepository.getByContract(treaty.getId());
        AccountLedger ledger = new AccountLedger();
        ledger.setContractId(treaty.getId());
        ledger.setPolicyId(policy.getId());
        ledger.setClaimId(claim.getId());
        ledger.setMovementType(movementType);
        ledger.setGrossAmount(Money.amount(signedGross));
        ledger.setCessionRate(treaty.getCessionRate());
        ledger.setAmount(amount);
        ledger.setBalanceAfter(account.getBalance());
        // 按 UTC 记账日落季度：预提在发生当季出账，跨季结案的冲销差额落入结案当季账单。
        ledger.setEventDate(java.time.LocalDate.now(clock));
        ledger.setRequestId(requestId);
        ledgerRepository.insert(ledger);
        if (remark != null) {
            // remark 通过流水行承载说明；此处无独立字段，使用 ledger 表之外的事件不必要，
            // 将截断说明同时写入赔案事件便于审计。
            eventRepository.record(
                    claim.getId(), movementType + "_DETAIL", signedGross, amount, remark);
        }
    }

    private ClaimResponse toResponse(Claim claim, ClaimRecovery recovery) {
        Policy policy = policyRepository.getById(claim.getPolicyId());
        Treaty treaty = treatyRepository.getById(policy.getContractId());
        ReinsuranceAccount account = accountRepository.getByContract(treaty.getId());
        return new ClaimResponse(
                claim.getClaimNo(),
                policy.getPolicyNo(),
                claim.getStatus().name(),
                claim.getEstimatedAmount(),
                claim.getPaidAmount(),
                claim.getLossDate(),
                recovery == null ? BigDecimal.ZERO.setScale(2) : recovery.getProvisionAmount(),
                recovery == null ? BigDecimal.ZERO.setScale(2) : recovery.getSettledAmount(),
                recovery == null ? BigDecimal.ZERO.setScale(2) : recovery.getCappedExcess(),
                account.getBalance());
    }
}
