package com.example.starter.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.starter.domain.Models.Bill;
import com.example.starter.domain.Models.BillItem;
import com.example.starter.domain.Models.BillStatus;
import com.example.starter.domain.Models.BillType;
import com.example.starter.domain.Models.BillView;
import com.example.starter.domain.Models.Claim;
import com.example.starter.domain.Models.Party;
import com.example.starter.domain.Models.Policy;
import com.example.starter.domain.Models.Treaty;
import com.example.starter.error.ApiException;
import com.example.starter.repo.BillRepository;
import com.example.starter.repo.ClaimRepository;
import com.example.starter.repo.PolicyRepository;
import com.example.starter.repo.TreatyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分保账单服务：按季度（UTC 日界）出账、双方确认封存、逐项异议、差错调整单。
 * 确认与异议通过账单行锁串行化，并发下形成唯一结果。
 */
@Service
public class BillingService {

    private final BillRepository billRepository;
    private final TreatyRepository treatyRepository;
    private final PolicyRepository policyRepository;
    private final ClaimRepository claimRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public BillingService(BillRepository billRepository, TreatyRepository treatyRepository,
                          PolicyRepository policyRepository, ClaimRepository claimRepository,
                          IdempotencyService idempotencyService, Clock clock) {
        this.billRepository = billRepository;
        this.treatyRepository = treatyRepository;
        this.policyRepository = policyRepository;
        this.claimRepository = claimRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /** 异议明细项。 */
    public record DisputeItemInput(long billItemId, String reason) {
    }

    /** 异议结果。 */
    public record DisputeResult(String billNo, BillStatus status, int itemCount) {
    }

    /**
     * 生成季度账单（幂等）：保费分保＝分出保费×比例，赔款摊回＝当季已决赔款×比例；
     * 每张保单受责任限额约束，恰达限额按限额计入，超出部分留在分出公司。
     */
    public BillView generate(String requestId, String treatyNo, String quarterLabel) {
        String fp = IdempotencyService.fingerprint(requestId, treatyNo, quarterLabel);
        return idempotencyService.execute("BILL_GENERATE", requestId, fp, BillView.class,
                () -> doGenerate(treatyNo, quarterLabel));
    }

    private BillView doGenerate(String treatyNo, String quarterLabel) {
        Treaty treaty = treatyRepository.findByTreatyNo(treatyNo)
                .orElseThrow(() -> ApiException.notFound("TREATY_NOT_FOUND", "合约不存在: " + treatyNo));
        Quarter.Range range = Quarter.rangeOf(quarterLabel);
        String dedupeKey = treatyNo + "|" + quarterLabel;
        // 业务键幂等：同合约同季度只出一张常规账单
        var existing = billRepository.findByDedupeKey(dedupeKey);
        if (existing.isPresent()) {
            Bill bill = existing.get();
            return new BillView(bill, billRepository.findItems(bill.id()));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Policy> policies = policyRepository.findByTreatyId(treaty.id());
        List<BillItemDraft> drafts = new ArrayList<>();
        BigDecimal totalPremium = BigDecimal.ZERO;
        BigDecimal totalRecovery = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Policy policy : policies) {
            BigDecimal premiumCession = Money.times(policy.cededPremium(), treaty.cessionRate());
            BigDecimal claimRecovery = BigDecimal.ZERO;
            for (Claim claim : claimRepository.findClosedBetween(policy.id(), range.start(), range.end())) {
                claimRecovery = claimRecovery.add(Money.times(claim.paidAmount(), treaty.cessionRate()));
            }
            BigDecimal gross = premiumCession.add(claimRecovery);
            BigDecimal limit = treaty.limitPerPolicy();
            boolean limitApplied = gross.compareTo(limit) > 0;
            BigDecimal itemTotal = limitApplied ? limit : gross;
            BigDecimal retained = gross.subtract(itemTotal);
            drafts.add(new BillItemDraft(policy.id(), premiumCession, claimRecovery, itemTotal,
                    retained, limitApplied));
            totalPremium = totalPremium.add(premiumCession);
            totalRecovery = totalRecovery.add(claimRecovery);
            totalAmount = totalAmount.add(itemTotal);
        }
        Bill bill = billRepository.insertBill("TMP-" + UUID.randomUUID(), dedupeKey, treaty.id(),
                quarterLabel, BillType.REGULAR, null, BillStatus.PENDING, totalPremium,
                totalRecovery, totalAmount, null, now);
        billRepository.updateBillNo(bill.id(), String.format("B%08d", bill.id()));
        for (BillItemDraft draft : drafts) {
            billRepository.insertItem(bill.id(), draft.policyId(), draft.premiumCession(),
                    draft.claimRecovery(), draft.itemTotal(), draft.retainedAmount(),
                    draft.limitApplied());
        }
        Bill saved = billRepository.findByBillNo(String.format("B%08d", bill.id())).orElseThrow();
        return new BillView(saved, billRepository.findItems(saved.id()));
    }

    private record BillItemDraft(long policyId, BigDecimal premiumCession, BigDecimal claimRecovery,
                                 BigDecimal itemTotal, BigDecimal retainedAmount,
                                 boolean limitApplied) {
    }

    /**
     * 账单确认：分出公司与再保人双方确认后封存（终态）。
     * 已封存账单重复确认返回幂等成功；已异议账单拒绝确认。
     */
    public BillView confirm(String requestId, String billNo, Party party) {
        String fp = IdempotencyService.fingerprint(requestId, billNo, party);
        return idempotencyService.execute("BILL_CONFIRM", requestId, fp, BillView.class,
                () -> doConfirm(billNo, party));
    }

    private BillView doConfirm(String billNo, Party party) {
        Bill bill = billRepository.findByBillNoForUpdate(billNo)
                .orElseThrow(() -> ApiException.notFound("BILL_NOT_FOUND", "账单不存在: " + billNo));
        if (bill.status() == BillStatus.SEALED) {
            // 封存为终态：重复确认幂等成功
            return new BillView(bill, billRepository.findItems(bill.id()));
        }
        if (bill.status() == BillStatus.DISPUTED) {
            throw ApiException.conflict("BILL_DISPUTED", "账单已异议，确认被拒绝: " + billNo);
        }
        boolean cedant = bill.confirmedByCedant() || party == Party.CEDANT;
        boolean reinsurer = bill.confirmedByReinsurer() || party == Party.REINSURER;
        boolean seal = cedant && reinsurer;
        OffsetDateTime sealedAt = seal ? OffsetDateTime.now(clock) : null;
        billRepository.updateConfirmation(bill.id(), cedant, reinsurer,
                seal ? BillStatus.SEALED : BillStatus.PENDING, sealedAt);
        Bill saved = billRepository.findByBillNo(billNo).orElseThrow();
        return new BillView(saved, billRepository.findItems(saved.id()));
    }

    /**
     * 账单异议：须逐项列明差异原因。已封存账单一律拒绝；已有确认或已异议的账单拒绝，
     * 保证并发确认与异议形成唯一结果。
     */
    public DisputeResult dispute(String requestId, String billNo, List<DisputeItemInput> items) {
        String fp = IdempotencyService.fingerprint(requestId, billNo,
                items == null ? List.of() : items.stream()
                        .map(i -> i.billItemId() + ":" + i.reason()).sorted().toList());
        return idempotencyService.execute("BILL_DISPUTE", requestId, fp, DisputeResult.class,
                () -> doDispute(requestId, billNo, items));
    }

    private DisputeResult doDispute(String requestId, String billNo, List<DisputeItemInput> items) {
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("EMPTY_DISPUTE", "异议须逐项列明差异原因，明细不能为空");
        }
        Bill bill = billRepository.findByBillNoForUpdate(billNo)
                .orElseThrow(() -> ApiException.notFound("BILL_NOT_FOUND", "账单不存在: " + billNo));
        if (bill.status() == BillStatus.SEALED) {
            throw ApiException.conflict("BILL_SEALED",
                    "账单已封存，异议与修改一律拒绝，差错须走下一期调整单: " + billNo);
        }
        if (bill.status() == BillStatus.DISPUTED) {
            throw ApiException.conflict("ALREADY_DISPUTED", "账单已存在异议: " + billNo);
        }
        if (bill.confirmedByCedant() || bill.confirmedByReinsurer()) {
            throw ApiException.conflict("ALREADY_CONFIRMED", "账单已确认，不得再异议: " + billNo);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        long disputeId = billRepository.insertDispute(bill.id(), requestId, now);
        for (DisputeItemInput item : items) {
            if (item.reason() == null || item.reason().isBlank()) {
                throw ApiException.badRequest("EMPTY_REASON", "异议明细必须列明差异原因");
            }
            if (!billRepository.itemBelongsToBill(item.billItemId(), bill.id())) {
                throw ApiException.badRequest("ITEM_NOT_IN_BILL",
                        "异议明细不属于该账单: billItemId=" + item.billItemId());
            }
            billRepository.insertDisputeItem(disputeId, item.billItemId(), item.reason());
        }
        billRepository.updateStatus(bill.id(), BillStatus.DISPUTED);
        return new DisputeResult(billNo, BillStatus.DISPUTED, items.size());
    }

    /**
     * 差错调整单：仅允许针对已封存账单，生成下一期调整单并引用原账单号。
     */
    public BillView adjust(String requestId, String originalBillNo, String quarterLabel,
                           BigDecimal premiumAdjustment, BigDecimal claimRecoveryAdjustment,
                           String reason) {
        String fp = IdempotencyService.fingerprint(requestId, originalBillNo, quarterLabel,
                premiumAdjustment, claimRecoveryAdjustment, reason);
        return idempotencyService.execute("BILL_ADJUST", requestId, fp, BillView.class,
                () -> doAdjust(originalBillNo, quarterLabel, premiumAdjustment,
                        claimRecoveryAdjustment, reason));
    }

    private BillView doAdjust(String originalBillNo, String quarterLabel,
                              BigDecimal premiumAdjustment, BigDecimal claimRecoveryAdjustment,
                              String reason) {
        Bill original = billRepository.findByBillNo(originalBillNo)
                .orElseThrow(() -> ApiException.notFound("BILL_NOT_FOUND", "原账单不存在: " + originalBillNo));
        if (original.status() != BillStatus.SEALED) {
            throw ApiException.conflict("ONLY_SEALED_ADJUSTABLE",
                    "仅已封存账单的差错可开具调整单: " + originalBillNo);
        }
        Quarter.rangeOf(quarterLabel);
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("EMPTY_REASON", "调整单须说明差错原因");
        }
        BigDecimal premium = Money.scale(premiumAdjustment == null ? BigDecimal.ZERO : premiumAdjustment);
        BigDecimal recovery = Money.scale(claimRecoveryAdjustment == null ? BigDecimal.ZERO
                : claimRecoveryAdjustment);
        OffsetDateTime now = OffsetDateTime.now(clock);
        Bill bill = billRepository.insertBill("TMP-" + UUID.randomUUID(),
                "ADJ|" + UUID.randomUUID(), original.treatyId(), quarterLabel, BillType.ADJUSTMENT,
                originalBillNo, BillStatus.PENDING, premium, recovery, premium.add(recovery),
                reason, now);
        billRepository.updateBillNo(bill.id(), String.format("A%08d", bill.id()));
        Bill saved = billRepository.findByBillNo(String.format("A%08d", bill.id())).orElseThrow();
        return new BillView(saved, List.of());
    }

    /** 查询账单并复算校验：明细合计必须等于汇总金额。 */
    @Transactional(readOnly = true)
    public BillView getBill(String billNo) {
        Bill bill = billRepository.findByBillNo(billNo)
                .orElseThrow(() -> ApiException.notFound("BILL_NOT_FOUND", "账单不存在: " + billNo));
        List<BillItem> items = billRepository.findItems(bill.id());
        if (bill.billType() == BillType.REGULAR) {
            BigDecimal sum = items.stream().map(BillItem::itemTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(bill.totalAmount()) != 0) {
                throw new IllegalStateException("账单明细合计与汇总金额不一致: " + billNo);
            }
        }
        return new BillView(bill, items);
    }
}
