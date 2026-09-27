package com.example.rebate.service;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.CheckDetail;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.domain.Reversal;
import com.example.rebate.domain.ReversalQuota;
import com.example.rebate.domain.ReversalReceipt;
import com.example.rebate.domain.ReversalStatus;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.repo.CheckDetailRepository;
import com.example.rebate.repo.ReversalQuotaRepository;
import com.example.rebate.repo.ReversalReceiptRepository;
import com.example.rebate.repo.ReversalRepository;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.BadRequestException;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.UnprocessableException;
import com.example.rebate.web.dto.QuotaBalanceView;
import com.example.rebate.web.dto.ReceiptView;
import com.example.rebate.web.dto.ReversalView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 已结案批次分批冲回：提交 → 复核（批准占用/驳回不占额）→ 追缴回执分批到账 → 收齐只读。
 *
 * <p>所有写方法设计为运行在 {@link AtomicIdempotencyService} 的同一事务内（普通 REQUIRED
 * 语义加入该事务）：冲回单版本加 1、台账占用、回执写入与幂等记录原子提交。</p>
 *
 * <p>金额单位统一为正整数分；冻结额取自结案时该校验明细计税依据（元×100 四舍五入），
 * 台账一旦建立不再随后续冲回变化。</p>
 */
@Service
public class ReversalService {

    private final BatchRepository batchRepo;
    private final CheckDetailRepository detailRepo;
    private final ReversalRepository reversalRepo;
    private final ReversalQuotaRepository quotaRepo;
    private final ReversalReceiptRepository receiptRepo;
    private final TimeService timeService;

    public ReversalService(BatchRepository batchRepo,
                           CheckDetailRepository detailRepo,
                           ReversalRepository reversalRepo,
                           ReversalQuotaRepository quotaRepo,
                           ReversalReceiptRepository receiptRepo,
                           TimeService timeService) {
        this.batchRepo = batchRepo;
        this.detailRepo = detailRepo;
        this.reversalRepo = reversalRepo;
        this.quotaRepo = quotaRepo;
        this.receiptRepo = receiptRepo;
        this.timeService = timeService;
    }

    /**
     * 提交冲回单（初为待复核，版本 1），并确保逐单余额台账已建立。
     */
    @Transactional
    public ReversalView submit(String batchNo, String declarationNo, String correctionNo,
                               String reason, long amountCents, String submittedBy) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        long frozenCents = frozenTaxCents(batch.id(), declarationNo);
        if (reversalRepo.findByBatchAndCorrectionNo(batch.id(), correctionNo).isPresent()) {
            throw new ConflictException(
                    "批次 " + batchNo + " 已存在更正编号 " + correctionNo + " 的冲回单");
        }
        // 已批准占用为准的剩余可冲额；多张待复核单并发批准时由批准条件占用终裁。
        ReversalQuota existingQuota =
                quotaRepo.findByBatchAndDeclaration(batch.id(), declarationNo).orElse(null);
        long occupiedCents = existingQuota == null ? 0L : existingQuota.occupiedCents();
        if (amountCents > frozenCents - occupiedCents) {
            throw new UnprocessableException(
                    "申请冲回 " + amountCents + " 分超过剩余可冲额 "
                            + (frozenCents - occupiedCents) + " 分（冻结 "
                            + frozenCents + " 分，已占用 " + occupiedCents + " 分）");
        }

        // 台账行在首次提交时建立；并发首次提交由唯一约束兜底，重复建立不视为错误。
        quotaRepo.insertIfAbsent(batch.id(), declarationNo, frozenCents, timeService.now());

        long id;
        try {
            id = reversalRepo.insert(batch.id(), declarationNo, correctionNo, reason,
                    amountCents, submittedBy, timeService.now());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发下另一事务以相同更正编号抢先提交：唯一约束为最终保障。
            throw new ConflictException(
                    "批次 " + batchNo + " 已存在更正编号 " + correctionNo + " 的冲回单");
        }
        return view(reversalRepo.findById(id).orElseThrow());
    }

    /**
     * 复核：批准在同一事务内条件占用剩余可冲额；驳回不占额。
     */
    @Transactional
    public ReversalView review(String batchNo, String correctionNo, int expectedVersion,
                               String reviewedBy, boolean approved, String rejectReason) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        Reversal reversal = loadReversal(batch.id(), batchNo, correctionNo);
        if (reversal.versionNo() != expectedVersion) {
            throw new ConflictException("expectedVersion=" + expectedVersion
                    + " 与冲回单当前版本 " + reversal.versionNo() + " 不一致");
        }
        if (reversal.status() != ReversalStatus.PENDING) {
            throw new ConflictException("冲回单当前状态 " + reversal.status() + "，不可再复核");
        }
        if (reviewedBy.equals(reversal.submittedBy())) {
            throw new UnprocessableException("复核人不得与提交人为同一人: " + reviewedBy);
        }

        if (approved) {
            ReversalQuota quota = quotaRepo
                    .findByBatchAndDeclaration(batch.id(), reversal.declarationNo())
                    .orElseThrow(() -> new UnprocessableException("可冲余额台账缺失，不能批准"));
            // 条件占用是并发终裁：超额更新 0 行，整事务回滚。
            int occupied = quotaRepo.occupyIfWithinFrozen(quota.id(),
                    reversal.amountCents(), timeService.now());
            if (occupied == 0) {
                throw new UnprocessableException("剩余可冲额不足，批准将超过该报关单冻结退税额");
            }
            int rows = reversalRepo.approve(reversal.id(), expectedVersion, reviewedBy,
                    reversal.amountCents(), timeService.now());
            if (rows == 0) {
                // 并发下状态/版本已变：占用随异常一并回滚。
                throw new ConflictException("冲回单状态已并发改变，请刷新版本后重试");
            }
        } else {
            if (rejectReason == null || rejectReason.isBlank()) {
                throw new BadRequestException("驳回时必须填写 rejectReason");
            }
            int rows = reversalRepo.reject(reversal.id(), expectedVersion, reviewedBy,
                    rejectReason, timeService.now());
            if (rows == 0) {
                throw new ConflictException("冲回单状态已并发改变，请刷新版本后重试");
            }
        }
        return view(reversalRepo.findById(reversal.id()).orElseThrow());
    }

    /**
     * 登记追缴回执：仅已批准单可登记，允许多笔部分到账，累计不超过批准额，恰收齐转完成。
     *
     * <p>同流水同金额幂等受理且不改变总额、不加版本；同流水异金额拒绝（422）。</p>
     */
    @Transactional
    public ReversalView registerReceipt(String batchNo, String correctionNo, int expectedVersion,
                                        String receiptSerial, long amountCents,
                                        java.time.Instant occurredAt) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        Reversal reversal = loadReversal(batch.id(), batchNo, correctionNo);

        // 同流水重复登记：以既有回执金额为准，不改变总额。
        ReversalReceipt existing =
                receiptRepo.findBySerial(reversal.id(), receiptSerial).orElse(null);
        if (existing != null) {
            if (existing.amountCents() != amountCents) {
                throw new UnprocessableException(
                        "流水号 " + receiptSerial + " 已登记金额 " + existing.amountCents()
                                + " 分，与本次 " + amountCents + " 分不一致");
            }
            return view(reversal);
        }

        switch (reversal.status()) {
            case PENDING, REJECTED -> throw new UnprocessableException("冲回单未批准，不能登记追缴回执");
            case COMPLETED -> throw new ConflictException("冲回单已追缴完成，只读不可再登记");
            default -> { /* APPROVED 可继续登记 */ }
        }
        if (reversal.versionNo() != expectedVersion) {
            throw new ConflictException("expectedVersion=" + expectedVersion
                    + " 与冲回单当前版本 " + reversal.versionNo() + " 不一致");
        }
        if (reversal.collectedCents() + amountCents > reversal.approvedCents()) {
            throw new UnprocessableException(
                    "本次到账后累计 " + (reversal.collectedCents() + amountCents)
                            + " 分将超过批准金额 " + reversal.approvedCents() + " 分");
        }

        int rows = reversalRepo.addReceiptAmount(reversal.id(), expectedVersion,
                amountCents, timeService.now());
        if (rows == 0) {
            // 预检后仍更新失败只可能是并发版本/状态变化。
            throw new ConflictException("冲回单状态已并发改变，请刷新版本后重试");
        }
        try {
            receiptRepo.insert(reversal.id(), receiptSerial, amountCents,
                    occurredAt, timeService.now());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发下另一事务已用同流水登记：唯一约束终裁，本事务回滚，累计不被重复计入。
            ReversalReceipt racer =
                    receiptRepo.findBySerial(reversal.id(), receiptSerial).orElseThrow();
            if (racer.amountCents() != amountCents) {
                throw new UnprocessableException(
                        "流水号 " + receiptSerial + " 已登记不同金额，拒绝重复登记");
            }
            throw new ConflictException("流水号 " + receiptSerial + " 正在并发登记，请稍后重放");
        }
        return view(reversalRepo.findById(reversal.id()).orElseThrow());
    }

    /**
     * 逐单可冲余额查询：无冲回记录时也可按冻结额查询，占用为 0。
     */
    @Transactional(readOnly = true)
    public QuotaBalanceView balance(String batchNo, String declarationNo) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        long frozenCents = frozenTaxCents(batch.id(), declarationNo);
        ReversalQuota quota =
                quotaRepo.findByBatchAndDeclaration(batch.id(), declarationNo).orElse(null);
        long occupied = quota == null ? 0L : quota.occupiedCents();
        long pending = reversalRepo.findByBatchId(batch.id()).stream()
                .filter(r -> r.declarationNo().equals(declarationNo))
                .filter(r -> r.status() == ReversalStatus.PENDING)
                .mapToLong(Reversal::amountCents)
                .sum();
        return new QuotaBalanceView(batch.batchNo(), declarationNo, frozenCents, occupied,
                frozenCents - occupied, pending);
    }

    /**
     * 查询冲回单详情（含台账余额与追缴回执）。
     */
    @Transactional(readOnly = true)
    public ReversalView get(String batchNo, String correctionNo) {
        DeclarationBatch batch = loadBatch(batchNo);
        return view(loadReversal(batch.id(), batchNo, correctionNo));
    }

    private DeclarationBatch loadBatch(String batchNo) {
        return batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
    }

    private DeclarationBatch loadClosedBatch(String batchNo) {
        DeclarationBatch batch = loadBatch(batchNo);
        if (batch.status() != BatchStatus.CLOSED) {
            throw new UnprocessableException(
                    "冲回仅支持已结案(CLOSED)批次，批次 " + batchNo + " 当前状态 " + batch.status());
        }
        return batch;
    }

    private Reversal loadReversal(long batchId, String batchNo, String correctionNo) {
        return reversalRepo.findByBatchAndCorrectionNo(batchId, correctionNo)
                .orElseThrow(() -> new NotFoundException(
                        "批次 " + batchNo + " 下更正编号 " + correctionNo + " 的冲回单不存在"));
    }

    /**
     * 取该报关单在批次校验明细中的冻结退税额（整数分）；不存在或无计税依据均拒绝冲回。
     */
    private long frozenTaxCents(long batchId, String declarationNo) {
        List<CheckDetail> details = detailRepo.findByBatchId(batchId).stream()
                .filter(d -> d.declarationNo().equals(declarationNo))
                .toList();
        if (details.isEmpty()) {
            throw new NotFoundException(
                    "报关单 " + declarationNo + " 不属于该结案批次，无冻结退税额可引用");
        }
        BigDecimal taxBasis = details.get(0).taxBasis();
        if (taxBasis == null || taxBasis.signum() <= 0) {
            throw new UnprocessableException(
                    "报关单 " + declarationNo + " 无有效冻结退税额，不能冲回");
        }
        return taxBasis.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private ReversalView view(Reversal r) {
        DeclarationBatch batch = batchRepo.findById(r.batchId()).orElseThrow();
        ReversalQuota quota =
                quotaRepo.findByBatchAndDeclaration(r.batchId(), r.declarationNo()).orElse(null);
        long frozen = quota == null ? 0L : quota.frozenTaxCents();
        long occupied = quota == null ? 0L : quota.occupiedCents();
        List<ReceiptView> receipts = receiptRepo.findByReversalId(r.id()).stream()
                .map(x -> new ReceiptView(x.id(), x.receiptSerial(), x.amountCents(),
                        x.occurredAt(), x.registeredAt()))
                .toList();
        return new ReversalView(
                r.id(), batch.batchNo(), r.declarationNo(), r.correctionNo(), r.reason(),
                r.amountCents(), r.status().name(), r.submittedBy(), r.submittedAt(),
                r.reviewedBy(), r.reviewedAt(), r.rejectReason(),
                r.approvedCents(), r.collectedCents(), r.completedAt(), r.versionNo(),
                frozen, occupied, frozen - occupied, receipts);
    }
}
