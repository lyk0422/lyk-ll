package com.example.starter.reinsurance.service;

import com.example.starter.reinsurance.api.BillItemResponse;
import com.example.starter.reinsurance.api.BillResponse;
import com.example.starter.reinsurance.api.ConfirmBillRequest;
import com.example.starter.reinsurance.api.CreateAdjustmentRequest;
import com.example.starter.reinsurance.api.DisputeBillRequest;
import com.example.starter.reinsurance.api.GenerateBillRequest;
import com.example.starter.reinsurance.common.ApiException;
import com.example.starter.reinsurance.common.ErrorCode;
import com.example.starter.reinsurance.common.Money;
import com.example.starter.reinsurance.common.Periods;
import com.example.starter.reinsurance.domain.AccountLedger;
import com.example.starter.reinsurance.domain.Bill;
import com.example.starter.reinsurance.domain.BillDispute;
import com.example.starter.reinsurance.domain.BillItem;
import com.example.starter.reinsurance.domain.BillItemType;
import com.example.starter.reinsurance.domain.BillStatus;
import com.example.starter.reinsurance.domain.BillType;
import com.example.starter.reinsurance.domain.Claim;
import com.example.starter.reinsurance.domain.Policy;
import com.example.starter.reinsurance.domain.Treaty;
import com.example.starter.reinsurance.repository.AccountLedgerRepository;
import com.example.starter.reinsurance.repository.BillDisputeRepository;
import com.example.starter.reinsurance.repository.BillItemRepository;
import com.example.starter.reinsurance.repository.BillRepository;
import com.example.starter.reinsurance.repository.ClaimRepository;
import com.example.starter.reinsurance.repository.PolicyRepository;
import com.example.starter.reinsurance.repository.TreatyRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账单服务：季度出账、再保人确认/异议、封存与下一期调整单。
 *
 * <p>并发语义：确认与异议均在事务内对账单行加悲观锁，并以状态条件更新收口，
 * 同一账单不可能既确认又异议。</p>
 */
@Service
public class BillService {

    private final BillRepository billRepository;
    private final BillItemRepository itemRepository;
    private final BillDisputeRepository disputeRepository;
    private final TreatyRepository treatyRepository;
    private final PolicyRepository policyRepository;
    private final ClaimRepository claimRepository;
    private final AccountLedgerRepository ledgerRepository;
    private final IdempotencyService idempotencyService;

    public BillService(
            BillRepository billRepository,
            BillItemRepository itemRepository,
            BillDisputeRepository disputeRepository,
            TreatyRepository treatyRepository,
            PolicyRepository policyRepository,
            ClaimRepository claimRepository,
            AccountLedgerRepository ledgerRepository,
            IdempotencyService idempotencyService) {
        this.billRepository = billRepository;
        this.itemRepository = itemRepository;
        this.disputeRepository = disputeRepository;
        this.treatyRepository = treatyRepository;
        this.policyRepository = policyRepository;
        this.claimRepository = claimRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 生成季度账单（UTC 日界归集）。同合约同季度重复出账返回已存在的账单；
     * requestId 幂等：同键异参 409。
     */
    @Transactional
    public BillResponse generate(GenerateBillRequest request) {
        String period = Periods.requireValid(request.period());
        Map<String, Object> fingerprintPayload = new LinkedHashMap<>();
        fingerprintPayload.put("contractNo", request.contractNo());
        fingerprintPayload.put("period", period);

        IdempotencyService.IdempotentResult result = idempotencyService.execute(
                request.requestId(),
                "GENERATE_BILL",
                fingerprintPayload,
                () -> {
                    BillResponse response = doGenerate(request.contractNo(), period);
                    return IdempotencyService.Outcome.ok(response.billNo(), response);
                });
        return readBill(result.resultRef());
    }

    private BillResponse doGenerate(String contractNo, String period) {
        Treaty treaty = treatyRepository.getByContractNo(contractNo);
        // 锁合约行，串行化同合约季度出账。
        treatyRepository.lockById(treaty.getId());

        Optional<Bill> existing = billRepository.findQuarterly(treaty.getId(), period);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        LocalDate start = Periods.startDate(period);
        LocalDate end = Periods.endDateExclusive(period);

        List<Policy> policies =
                policyRepository.findUnbilledByContractAndPeriod(treaty.getId(), start, end);
        List<AccountLedger> ledgers =
                ledgerRepository.findUnbilledByContractAndPeriod(
                        treaty.getId(), start.toString(), end.toString());

        Bill bill = new Bill();
        bill.setBillNo("RB-" + treaty.getContractNo() + "-" + period);
        bill.setContractId(treaty.getId());
        bill.setPeriod(period);
        bill.setBillType(BillType.QUARTERLY);
        bill.setRefBillNo(null);
        bill.setStatus(BillStatus.PENDING);
        bill.setCedantConfirmed(true);
        bill.setReinsurerConfirmed(false);
        bill.setPremiumTotal(BigDecimal.ZERO.setScale(2));
        bill.setRecoveryTotal(BigDecimal.ZERO.setScale(2));
        bill.setNetTotal(BigDecimal.ZERO.setScale(2));
        try {
            billRepository.insert(bill);
        } catch (DuplicateKeyException e) {
            // 并发下另一事务已生成该季度账单。
            Bill raced = billRepository.findQuarterly(treaty.getId(), period).orElseThrow();
            return toResponse(raced);
        }

        BigDecimal premiumTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal recoveryTotal = BigDecimal.ZERO.setScale(2);
        int lineNo = 0;

        for (Policy policy : policies) {
            BigDecimal cededPremium = Money.applyRate(policy.getPremium(), treaty.getCessionRate());
            BillItem item = new BillItem();
            item.setBillId(bill.getId());
            item.setLineNo(++lineNo);
            item.setItemType(BillItemType.PREMIUM);
            item.setRefNo(policy.getPolicyNo());
            item.setGrossAmount(Money.amount(policy.getPremium()));
            item.setCessionRate(treaty.getCessionRate());
            item.setAmount(cededPremium);
            item.setRemark("分出保费 " + Money.format(policy.getPremium()) + " × 分保比例 "
                    + treaty.getCessionRate().toPlainString());
            itemRepository.insert(item);
            premiumTotal = Money.amount(premiumTotal.add(cededPremium));
            policyRepository.attachToBillIfFree(policy.getId(), bill.getId());
        }

        Set<Long> claimIds = new HashSet<>();
        for (AccountLedger ledger : ledgers) {
            if (ledger.getClaimId() != null) {
                claimIds.add(ledger.getClaimId());
            }
        }
        Map<Long, Claim> claims = claimRepository.findByIds(claimIds);

        for (AccountLedger ledger : ledgers) {
            BigDecimal signed = Money.amount(ledger.getAmount());
            BillItem item = new BillItem();
            item.setBillId(bill.getId());
            item.setLineNo(++lineNo);
            item.setItemType(BillItemType.CLAIM_RECOVERY);
            Claim claim = ledger.getClaimId() == null ? null : claims.get(ledger.getClaimId());
            item.setRefNo(claim == null ? ("ledger-" + ledger.getId()) : claim.getClaimNo());
            item.setGrossAmount(Money.amount(ledger.getGrossAmount()));
            item.setCessionRate(ledger.getCessionRate());
            item.setAmount(signed);
            item.setRemark(switch (ledger.getMovementType()) {
                case "PROVISION" -> "未决赔款预提摊回";
                case "ADJUST" -> "估损调整摊回差额";
                default -> "结案冲销摊回差额";
            });
            itemRepository.insert(item);
            recoveryTotal = Money.amount(recoveryTotal.add(signed));
        }
        ledgerRepository.attachToBill(
                ledgers.stream().map(AccountLedger::getId).toList(), bill.getId());

        billRepository.updateTotals(bill.getId(), premiumTotal, recoveryTotal);
        bill.setPremiumTotal(premiumTotal);
        bill.setRecoveryTotal(recoveryTotal);
        bill.setNetTotal(Money.subtract(premiumTotal, recoveryTotal));
        return toResponse(bill);
    }

    /**
     * 再保人确认：待确认账单封存为终态；重复确认（同键重放或对已封存账单）返回幂等成功；
     * 账单已被异议或在竞态中被异议方抢先时返回 409，绝不出现既确认又异议。
     */
    @Transactional
    public BillResponse confirm(String billNo, ConfirmBillRequest request) {
        Map<String, Object> fingerprintPayload = new LinkedHashMap<>();
        fingerprintPayload.put("billNo", billNo);
        fingerprintPayload.put("action", "CONFIRM");
        idempotencyService.execute(
                request.requestId(),
                "CONFIRM_BILL",
                fingerprintPayload,
                () -> {
                    BillResponse response = doConfirm(billNo);
                    return IdempotencyService.Outcome.ok(response.billNo(), response);
                });
        return readBill(billNo);
    }

    private BillResponse doConfirm(String billNo) {
        Bill bill = billRepository.getByBillNoForUpdate(billNo);
        if (bill.getStatus() == BillStatus.SEALED) {
            // 封存终态：重复确认幂等成功。
            return toResponse(bill);
        }
        if (bill.getStatus() == BillStatus.DISPUTED) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE, "账单已存在异议，不能确认；异议处理后再封存: " + billNo);
        }
        int sealed = billRepository.confirmAndSealIfPending(bill.getId(), java.time.Instant.now());
        if (sealed == 0) {
            // 并发竞态：账单状态已被异议方改变。
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                    "确认与异议并发，账单未保持待确认状态: " + billNo);
        }
        bill.setStatus(BillStatus.SEALED);
        bill.setReinsurerConfirmed(true);
        return toResponse(bill);
    }

    /**
     * 再保人逐项异议：封存账单一律拒绝；待确认账单置异议态；重复异议返回 409；
     * 与确认并发时只有一方成功。
     */
    @Transactional
    public BillResponse dispute(String billNo, DisputeBillRequest request) {
        Map<String, Object> fingerprintPayload = new LinkedHashMap<>();
        fingerprintPayload.put("billNo", billNo);
        fingerprintPayload.put("action", "DISPUTE");
        List<Map<String, Object>> itemPayloads = new ArrayList<>();
        for (DisputeBillRequest.DisputeItemRequest item : request.items()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("itemRef", item.itemRef());
            map.put("expectedAmount", Money.amount(item.expectedAmount()));
            map.put("reason", item.reason());
            itemPayloads.add(map);
        }
        fingerprintPayload.put("items", itemPayloads);

        idempotencyService.execute(
                request.requestId(),
                "DISPUTE_BILL",
                fingerprintPayload,
                () -> {
                    BillResponse response = doDispute(billNo, request);
                    return IdempotencyService.Outcome.ok(response.billNo(), response);
                });
        return readBill(billNo);
    }

    private BillResponse doDispute(String billNo, DisputeBillRequest request) {
        Bill bill = billRepository.getByBillNoForUpdate(billNo);
        if (bill.getStatus() == BillStatus.SEALED) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE,
                    "账单已双方确认封存，异议一律拒绝；差错请开下一期调整单并引用原账单号: " + billNo);
        }
        if (bill.getStatus() == BillStatus.DISPUTED) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE, "账单已处于异议状态，不能重复异议: " + billNo);
        }

        List<BillItem> existingItems = itemRepository.findByBillId(bill.getId());
        Set<String> allowedRefs = new HashSet<>();
        for (BillItem item : existingItems) {
            allowedRefs.add("L" + item.getLineNo());
            allowedRefs.add(item.getRefNo());
        }
        for (DisputeBillRequest.DisputeItemRequest item : request.items()) {
            Money.requireNonNegative(item.expectedAmount(), "expectedAmount");
            if (item.reason() == null || item.reason().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "异议必须逐项列明差异原因");
            }
            if (!allowedRefs.contains(item.itemRef())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "异议明细不存在: " + item.itemRef() + "（请使用行号 L1/L2… 或业务关联号）");
            }
        }

        int marked = billRepository.markDisputedIfPending(bill.getId());
        if (marked == 0) {
            // 与确认并发且确认方已封存成功。
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                    "确认与异议并发，账单已被确认封存，异议被拒绝: " + billNo);
        }
        for (DisputeBillRequest.DisputeItemRequest item : request.items()) {
            BillDispute dispute = new BillDispute();
            dispute.setBillId(bill.getId());
            dispute.setItemRef(item.itemRef());
            dispute.setExpectedAmount(Money.amount(item.expectedAmount()));
            dispute.setReason(item.reason());
            disputeRepository.insert(dispute);
        }
        bill.setStatus(BillStatus.DISPUTED);
        return toResponse(bill);
    }

    /**
     * 创建下一期调整单：原账单必须已封存，调整单期次必须晚于原账单，逐项引用原账单号。
     */
    @Transactional
    public BillResponse createAdjustment(CreateAdjustmentRequest request) {
        String period = Periods.requireValid(request.period());
        Map<String, Object> fingerprintPayload = new LinkedHashMap<>();
        fingerprintPayload.put("contractNo", request.contractNo());
        fingerprintPayload.put("period", period);
        fingerprintPayload.put("refBillNo", request.refBillNo());
        List<Map<String, Object>> itemPayloads = new ArrayList<>();
        for (CreateAdjustmentRequest.AdjustmentItemRequest item : request.items()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("itemType", item.itemType());
            map.put("refNo", item.refNo());
            map.put("grossAmount", item.grossAmount());
            map.put("reason", item.reason());
            itemPayloads.add(map);
        }
        fingerprintPayload.put("items", itemPayloads);

        IdempotencyService.IdempotentResult result = idempotencyService.execute(
                request.requestId(),
                "CREATE_ADJUSTMENT",
                fingerprintPayload,
                () -> {
                    BillResponse response = doCreateAdjustment(request, period);
                    return IdempotencyService.Outcome.ok(response.billNo(), response);
                });
        return readBill(result.resultRef());
    }

    private BillResponse doCreateAdjustment(CreateAdjustmentRequest request, String period) {
        Treaty treaty = treatyRepository.getByContractNo(request.contractNo());
        treatyRepository.lockById(treaty.getId());
        Bill original = billRepository.getByBillNo(request.refBillNo());
        if (!original.getContractId().equals(treaty.getId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "调整单合约与原账单不一致: " + request.refBillNo());
        }
        if (original.getStatus() != BillStatus.SEALED) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE,
                    "只有已封存账单的差错才能走下一期调整单: " + request.refBillNo());
        }
        if (!periodIsAfter(period, original.getPeriod())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "调整单必须开在下一期（晚于原账单期次 " + original.getPeriod() + "）: " + period);
        }
        // 调整单与同期季度账单按 bill_type 区分（唯一索引 contract+period+bill_type），允许共存。

        Bill bill = new Bill();
        bill.setBillNo("RA-" + treaty.getContractNo() + "-" + period);
        bill.setContractId(treaty.getId());
        bill.setPeriod(period);
        bill.setBillType(BillType.ADJUSTMENT);
        bill.setRefBillNo(original.getBillNo());
        bill.setStatus(BillStatus.PENDING);
        bill.setCedantConfirmed(true);
        bill.setReinsurerConfirmed(false);
        bill.setPremiumTotal(BigDecimal.ZERO.setScale(2));
        bill.setRecoveryTotal(BigDecimal.ZERO.setScale(2));
        bill.setNetTotal(BigDecimal.ZERO.setScale(2));
        try {
            billRepository.insert(bill);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE,
                    "该期调整单已存在，不能重复创建: " + bill.getBillNo());
        }

        BigDecimal premiumTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal recoveryTotal = BigDecimal.ZERO.setScale(2);
        int lineNo = 0;
        for (CreateAdjustmentRequest.AdjustmentItemRequest line : request.items()) {
            BillItemType itemType;
            try {
                itemType = BillItemType.valueOf(line.itemType());
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "调整项类型必须为 PREMIUM 或 CLAIM_RECOVERY: " + line.itemType());
            }
            if (itemType == BillItemType.ADJUSTMENT) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "调整项类型必须为 PREMIUM 或 CLAIM_RECOVERY: " + line.itemType());
            }
            BigDecimal gross = Money.amount(line.grossAmount());
            BigDecimal amount = Money.applyRate(gross, treaty.getCessionRate());
            BillItem item = new BillItem();
            item.setBillId(bill.getId());
            item.setLineNo(++lineNo);
            item.setItemType(itemType);
            item.setRefNo(line.refNo());
            item.setRefBillNo(original.getBillNo());
            item.setGrossAmount(gross);
            item.setCessionRate(treaty.getCessionRate());
            item.setAmount(amount);
            item.setRemark("下一期调整，引用原账单 " + original.getBillNo() + "：" + line.reason());
            itemRepository.insert(item);
            if (itemType == BillItemType.PREMIUM) {
                premiumTotal = Money.amount(premiumTotal.add(amount));
            } else {
                recoveryTotal = Money.amount(recoveryTotal.add(amount));
            }
        }
        billRepository.updateTotals(bill.getId(), premiumTotal, recoveryTotal);
        bill.setPremiumTotal(premiumTotal);
        bill.setRecoveryTotal(recoveryTotal);
        bill.setNetTotal(Money.subtract(premiumTotal, recoveryTotal));
        return toResponse(bill);
    }

    @Transactional(readOnly = true)
    public BillResponse getBill(String billNo) {
        return readBill(billNo);
    }

    private BillResponse readBill(String billNo) {
        Bill bill = billRepository.getByBillNo(billNo);
        return toResponse(bill);
    }

    private BillResponse toResponse(Bill bill) {
        Treaty treaty = treatyRepository.getById(bill.getContractId());
        List<BillItem> items = itemRepository.findByBillId(bill.getId());
        List<BillDispute> disputes = disputeRepository.findByBillId(bill.getId());

        List<BillItemResponse> itemResponses = new ArrayList<>();
        BigDecimal premiumSum = BigDecimal.ZERO.setScale(2);
        BigDecimal recoverySum = BigDecimal.ZERO.setScale(2);
        for (BillItem item : items) {
            itemResponses.add(new BillItemResponse(
                    item.getLineNo(),
                    item.getItemType().name(),
                    item.getRefNo(),
                    item.getRefBillNo(),
                    item.getGrossAmount(),
                    item.getCessionRate(),
                    item.getAmount(),
                    item.getRemark()));
            if (item.getItemType() == BillItemType.PREMIUM) {
                premiumSum = Money.amount(premiumSum.add(item.getAmount()));
            } else {
                recoverySum = Money.amount(recoverySum.add(item.getAmount()));
            }
        }
        // 明细合计必须等于汇总金额（持久化不变量校验，发现破坏即报错而非静默展示）。
        if (premiumSum.compareTo(Money.amount(bill.getPremiumTotal())) != 0
                || recoverySum.compareTo(Money.amount(bill.getRecoveryTotal())) != 0) {
            throw new IllegalStateException(
                    "账单明细合计与汇总不一致: " + bill.getBillNo()
                            + "，明细保费=" + premiumSum + " 汇总保费=" + bill.getPremiumTotal()
                            + "，明细摊回=" + recoverySum + " 汇总摊回=" + bill.getRecoveryTotal());
        }

        List<BillResponse.DisputeResponse> disputeResponses = disputes.stream()
                .map(d -> new BillResponse.DisputeResponse(
                        d.getItemRef(), d.getExpectedAmount(), d.getReason(), d.getCreatedAt()))
                .toList();
        return new BillResponse(
                bill.getBillNo(),
                treaty.getContractNo(),
                bill.getPeriod(),
                bill.getBillType().name(),
                bill.getRefBillNo(),
                bill.getStatus().name(),
                bill.isCedantConfirmed(),
                bill.isReinsurerConfirmed(),
                bill.getPremiumTotal(),
                bill.getRecoveryTotal(),
                bill.getNetTotal(),
                bill.getCreatedAt(),
                bill.getSealedAt(),
                itemResponses,
                disputeResponses);
    }

    /** 期次先后比较：{@code candidate} 是否严格晚于 {@code reference}。 */
    static boolean periodIsAfter(String candidate, String reference) {
        LocalDate candidateStart = Periods.startDate(candidate);
        LocalDate referenceStart = Periods.startDate(reference);
        return candidateStart.isAfter(referenceStart);
    }
}
