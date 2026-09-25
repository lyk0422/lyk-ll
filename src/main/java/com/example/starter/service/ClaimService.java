package com.example.starter.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;

import com.example.starter.domain.Models.Claim;
import com.example.starter.domain.Models.ClaimStatus;
import com.example.starter.domain.Models.CloseResult;
import com.example.starter.domain.Models.EntryType;
import com.example.starter.domain.Models.EstimateAdjustResult;
import com.example.starter.domain.Models.Policy;
import com.example.starter.domain.Models.Treaty;
import com.example.starter.error.ApiException;
import com.example.starter.repo.AccountRepository;
import com.example.starter.repo.ClaimRepository;
import com.example.starter.repo.PolicyRepository;
import com.example.starter.repo.TreatyRepository;
import org.springframework.stereotype.Service;

/**
 * 赔案服务：未决按估损预提摊回，最终结案按实际赔付冲销差额（同事务），结案为终态。
 */
@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final PolicyRepository policyRepository;
    private final TreatyRepository treatyRepository;
    private final AccountRepository accountRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public ClaimService(ClaimRepository claimRepository, PolicyRepository policyRepository,
                        TreatyRepository treatyRepository, AccountRepository accountRepository,
                        IdempotencyService idempotencyService, Clock clock) {
        this.claimRepository = claimRepository;
        this.policyRepository = policyRepository;
        this.treatyRepository = treatyRepository;
        this.accountRepository = accountRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /** 立案结果。 */
    public record RegisterResult(String claimNo, BigDecimal estimateAmount,
                                 BigDecimal provisionAmount, BigDecimal balanceAfter) {
    }

    /** 登记赔案并按估损金额预提摊回（估损 × 比例）。 */
    public RegisterResult register(String requestId, String policyNo, String claimNo,
                                   BigDecimal estimateAmount) {
        String fp = IdempotencyService.fingerprint(requestId, policyNo, claimNo, estimateAmount);
        return idempotencyService.execute("CLAIM_REGISTER", requestId, fp, RegisterResult.class,
                () -> doRegister(requestId, policyNo, claimNo, estimateAmount));
    }

    private RegisterResult doRegister(String requestId, String policyNo, String claimNo,
                                      BigDecimal estimateAmount) {
        if (estimateAmount == null || estimateAmount.signum() < 0) {
            throw ApiException.badRequest("INVALID_ESTIMATE", "估损金额不得为负");
        }
        Policy policy = policyRepository.findByPolicyNo(policyNo)
                .orElseThrow(() -> ApiException.notFound("POLICY_NOT_FOUND", "保单不存在: " + policyNo));
        Treaty treaty = treatyRepository.findById(policy.treatyId()).orElseThrow();
        claimRepository.findByClaimNo(claimNo).ifPresent(c -> {
            throw ApiException.conflict("CLAIM_EXISTS", "赔案号已存在: " + claimNo);
        });
        OffsetDateTime now = OffsetDateTime.now(clock);
        Claim claim = claimRepository.insert(claimNo, policy.id(), Money.scale(estimateAmount), now);
        BigDecimal provision = Money.times(claim.estimateAmount(), treaty.cessionRate());
        BigDecimal balance = accountRepository.applyDelta(treaty.id(), provision, now);
        accountRepository.insertEntry(treaty.id(), claim.id(), EntryType.PROVISION, provision,
                balance, requestId, now);
        return new RegisterResult(claimNo, claim.estimateAmount(), provision, balance);
    }

    /**
     * 最终结案：按实际赔付冲销预提差额，结案状态变更与冲销分录在同一事务。
     * 结案为终态：重复结案返回首次结果（replayed=true）。
     */
    public CloseResult close(String requestId, String claimNo, BigDecimal finalPaidAmount) {
        String fp = IdempotencyService.fingerprint(requestId, claimNo, finalPaidAmount);
        return idempotencyService.execute("CLAIM_CLOSE", requestId, fp, CloseResult.class,
                () -> doClose(requestId, claimNo, finalPaidAmount));
    }

    private CloseResult doClose(String requestId, String claimNo, BigDecimal finalPaidAmount) {
        if (finalPaidAmount == null || finalPaidAmount.signum() < 0) {
            throw ApiException.badRequest("INVALID_PAID", "结案赔付金额不得为负");
        }
        Claim claim = claimRepository.findByClaimNo(claimNo)
                .orElseThrow(() -> ApiException.notFound("CLAIM_NOT_FOUND", "赔案不存在: " + claimNo));
        Policy policy = policyRepository.findById(claim.policyId()).orElseThrow();
        Treaty treaty = treatyRepository.findById(policy.treatyId()).orElseThrow();
        if (claim.status() == ClaimStatus.CLOSED) {
            return replayedClose(claim, treaty);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        int updated = claimRepository.closeIfOpen(claim.id(), Money.scale(finalPaidAmount), now);
        if (updated == 0) {
            // 并发结案：另一事务已结案，返回首次结果
            Claim closed = claimRepository.findByClaimNo(claimNo).orElseThrow();
            return replayedClose(closed, treaty);
        }
        BigDecimal provision = Money.times(claim.estimateAmount(), treaty.cessionRate());
        BigDecimal actual = Money.times(Money.scale(finalPaidAmount), treaty.cessionRate());
        BigDecimal reversal = actual.subtract(provision);
        BigDecimal balance = accountRepository.applyDelta(treaty.id(), reversal, now);
        accountRepository.insertEntry(treaty.id(), claim.id(), EntryType.REVERSAL, reversal,
                balance, requestId, now);
        return new CloseResult(claimNo, Money.scale(finalPaidAmount), provision, reversal,
                balance, now, false);
    }

    private CloseResult replayedClose(Claim closed, Treaty treaty) {
        BigDecimal provision = Money.times(closed.estimateAmount(), treaty.cessionRate());
        BigDecimal actual = Money.times(closed.paidAmount(), treaty.cessionRate());
        BigDecimal balance = accountRepository.findByTreatyId(treaty.id())
                .map(a -> a.balance()).orElse(BigDecimal.ZERO);
        return new CloseResult(closed.claimNo(), closed.paidAmount(), provision,
                actual.subtract(provision), balance, closed.closedAt(), true);
    }

    /**
     * 估损调整：未决时调整预提（同事务记调整分录）；结案后迟到调整只记事件、不改账务。
     */
    public EstimateAdjustResult adjustEstimate(String requestId, String claimNo,
                                               BigDecimal newEstimate) {
        String fp = IdempotencyService.fingerprint(requestId, claimNo, newEstimate);
        return idempotencyService.execute("CLAIM_ADJUST_ESTIMATE", requestId, fp,
                EstimateAdjustResult.class, () -> doAdjustEstimate(requestId, claimNo, newEstimate));
    }

    private EstimateAdjustResult doAdjustEstimate(String requestId, String claimNo,
                                                  BigDecimal newEstimate) {
        if (newEstimate == null || newEstimate.signum() < 0) {
            throw ApiException.badRequest("INVALID_ESTIMATE", "估损金额不得为负");
        }
        Claim claim = claimRepository.findByClaimNo(claimNo)
                .orElseThrow(() -> ApiException.notFound("CLAIM_NOT_FOUND", "赔案不存在: " + claimNo));
        Policy policy = policyRepository.findById(claim.policyId()).orElseThrow();
        Treaty treaty = treatyRepository.findById(policy.treatyId()).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (claim.status() == ClaimStatus.CLOSED) {
            claimRepository.insertEvent(claim.id(), "LATE_ESTIMATE_ADJUST", claim.estimateAmount(),
                    Money.scale(newEstimate), "结案后迟到估损调整，仅记录事件，不改账务", now);
            BigDecimal balance = accountRepository.findByTreatyId(treaty.id())
                    .map(a -> a.balance()).orElse(BigDecimal.ZERO);
            return new EstimateAdjustResult(claimNo, true, balance);
        }
        BigDecimal oldProvision = Money.times(claim.estimateAmount(), treaty.cessionRate());
        BigDecimal newProvision = Money.times(Money.scale(newEstimate), treaty.cessionRate());
        BigDecimal delta = newProvision.subtract(oldProvision);
        int updated = claimRepository.updateEstimateIfOpen(claim.id(), Money.scale(newEstimate));
        if (updated == 0) {
            // 并发结案：按迟到调整处理，只记事件
            claimRepository.insertEvent(claim.id(), "LATE_ESTIMATE_ADJUST", claim.estimateAmount(),
                    Money.scale(newEstimate), "结案后迟到估损调整，仅记录事件，不改账务", now);
            BigDecimal balance = accountRepository.findByTreatyId(treaty.id())
                    .map(a -> a.balance()).orElse(BigDecimal.ZERO);
            return new EstimateAdjustResult(claimNo, true, balance);
        }
        BigDecimal balance = accountRepository.applyDelta(treaty.id(), delta, now);
        accountRepository.insertEntry(treaty.id(), claim.id(), EntryType.PROVISION_ADJUST, delta,
                balance, requestId, now);
        return new EstimateAdjustResult(claimNo, false, balance);
    }
}
