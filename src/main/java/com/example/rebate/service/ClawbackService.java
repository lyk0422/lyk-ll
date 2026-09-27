package com.example.rebate.service;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.Clawback;
import com.example.rebate.domain.ClawbackQuota;
import com.example.rebate.domain.ClawbackReceipt;
import com.example.rebate.domain.ClawbackRecovery;
import com.example.rebate.domain.ClawbackStatus;
import com.example.rebate.domain.CheckDetail;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.domain.RecoveryStatus;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.repo.CheckDetailRepository;
import com.example.rebate.repo.ClawbackQuotaRepository;
import com.example.rebate.repo.ClawbackReceiptRepository;
import com.example.rebate.repo.ClawbackRecoveryRepository;
import com.example.rebate.repo.ClawbackRepository;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.UnprocessableException;
import com.example.rebate.web.dto.ClawbackBalanceView;
import com.example.rebate.web.dto.ClawbackDetailView;
import com.example.rebate.web.dto.ClawbackResultView;
import com.example.rebate.web.dto.ClawbackReviewRequest;
import com.example.rebate.web.dto.ClawbackSubmitRequest;
import com.example.rebate.web.dto.ClawbackView;
import com.example.rebate.web.dto.RecoveryView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结案后分批冲回与追缴。
 *
 * <p>只引用已结案批次内的一张报关单及其冻结退税额快照，不改原文库版本、核销流水与结案总额：
 * <ul>
 *   <li>提交冲回：金额为正整数分，外部更正编号同批次唯一，冲回单初为 PENDING（不占额）；</li>
 *   <li>复核：须由非提交人操作；批准在同一事务内对该单额度行 {@code FOR UPDATE} 加锁、
 *       占用剩余可冲额并产生独立追缴明细；驳回不占额；并发批准累计不得超冻结额，恰归零合法；</li>
 *   <li>追缴回执：独立流水号、多笔部分到账，累计不超批准额，恰收齐转 COMPLETED（只读）；
 *       同流水异金额拒绝，乱序到账不改变累计总额。</li>
 * </ul>
 * 冲回单带乐观版本号（从 1 开始，每次成功变更加 1）。</p>
 */
@Service
public class ClawbackService {

    private final BatchRepository batchRepo;
    private final CheckDetailRepository detailRepo;
    private final ClawbackRepository clawbackRepo;
    private final ClawbackQuotaRepository quotaRepo;
    private final ClawbackRecoveryRepository recoveryRepo;
    private final ClawbackReceiptRepository receiptRepo;
    private final TimeService timeService;

    public ClawbackService(BatchRepository batchRepo,
                          CheckDetailRepository detailRepo,
                          ClawbackRepository clawbackRepo,
                          ClawbackQuotaRepository quotaRepo,
                          ClawbackRecoveryRepository recoveryRepo,
                          ClawbackReceiptRepository receiptRepo,
                          TimeService timeService) {
        this.batchRepo = batchRepo;
        this.detailRepo = detailRepo;
        this.clawbackRepo = clawbackRepo;
        this.quotaRepo = quotaRepo;
        this.recoveryRepo = recoveryRepo;
        this.receiptRepo = receiptRepo;
        this.timeService = timeService;
    }

    /**
     * 提交冲回单（待复核，不占额）。
     */
    @Transactional
    public ClawbackResultView submit(String batchNo, ClawbackSubmitRequest req) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        CheckDetail detail = detailRepo.findByBatchId(batch.id()).stream()
                .filter(d -> d.declarationNo().equals(req.declarationNo()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "报关单不在该已结案批次内: " + req.declarationNo()));
        if (detail.taxBasis() == null || detail.taxBasis().signum() <= 0) {
            throw new UnprocessableException("该报关单无冻结退税额，不能冲回");
        }
        long frozenCents = toCents(detail.taxBasis());
        if (clawbackRepo.findByBatchAndCorrectionNo(batch.id(), req.externalCorrectionNo()).isPresent()) {
            throw new ConflictException(
                    "同批次外部更正编号已存在: " + req.externalCorrectionNo());
        }
        // 首次引用该单额度时落冻结额快照；并发首单以唯一约束兜底。
        quotaRepo.insertIfAbsent(batch.id(), req.declarationNo(), frozenCents, timeService.now());

        long clawbackId;
        try {
            clawbackId = clawbackRepo.insert(batch.id(), batch.batchNo(), req.declarationNo(),
                    req.externalCorrectionNo(), req.reason(), req.amountCents(), frozenCents,
                    req.submittedBy(), timeService.now());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("同批次外部更正编号已存在: " + req.externalCorrectionNo());
        }
        Clawback saved = clawbackRepo.findById(clawbackId).orElseThrow();
        ClawbackQuota quota = quotaRepo.find(batch.id(), req.declarationNo()).orElseThrow();
        return new ClawbackResultView(view(saved), null,
                quota.frozenTaxCents(), quota.occupiedCents(), quota.remainingCents(),
                quota.versionNo());
    }

    /**
     * 复核冲回单：批准占用额度并产生追缴明细；驳回不占额。
     */
    @Transactional
    public ClawbackResultView review(String batchNo, long clawbackId, ClawbackReviewRequest req) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        Clawback clawback = loadClawbackInBatch(batch.id(), clawbackId);
        if (clawback.status() != ClawbackStatus.PENDING) {
            throw new UnprocessableException(
                    "冲回单当前状态 " + clawback.status() + "，不能再复核");
        }
        if (clawback.versionNo() != req.expectedVersion()) {
            throw new ConflictException("冲回单版本已变化：期望 " + req.expectedVersion()
                    + "，实际 " + clawback.versionNo());
        }
        if (clawback.submittedBy().equals(req.reviewer())) {
            throw new UnprocessableException("复核人必须不同于提交人");
        }

        Instant now = timeService.now();
        if (!req.approved()) {
            int rows = clawbackRepo.review(clawbackId, ClawbackStatus.REJECTED,
                    req.reviewer(), req.expectedVersion(), now);
            if (rows == 0) {
                throw new ConflictException("冲回单已被并发复核或版本已变化");
            }
            Clawback updated = clawbackRepo.findById(clawbackId).orElseThrow();
            ClawbackQuota quota =
                    quotaRepo.find(batch.id(), clawback.declarationNo()).orElseThrow();
            return new ClawbackResultView(view(updated), null,
                    quota.frozenTaxCents(), quota.occupiedCents(), quota.remainingCents(),
                    quota.versionNo());
        }

        // 批准：行锁串行化同单并发批准，再在同一事务占用额度、改状态、产生追缴明细。
        ClawbackQuota locked =
                quotaRepo.findForUpdate(batch.id(), clawback.declarationNo())
                        .orElseThrow(() -> new UnprocessableException("该报关单可冲额度尚未初始化"));
        if (clawback.amountCents() > locked.remainingCents()) {
            throw new UnprocessableException("累计冲回将超过冻结退税额：剩余可冲 "
                    + locked.remainingCents() + " 分，本次申请 " + clawback.amountCents() + " 分");
        }
        int rows = clawbackRepo.review(clawbackId, ClawbackStatus.APPROVED,
                req.reviewer(), req.expectedVersion(), now);
        if (rows == 0) {
            throw new ConflictException("冲回单已被并发复核或版本已变化");
        }
        quotaRepo.occupy(batch.id(), clawback.declarationNo(), clawback.amountCents(), now);
        long recoveryId = recoveryRepo.insert(clawbackId, clawback.amountCents(), now);

        Clawback updated = clawbackRepo.findById(clawbackId).orElseThrow();
        ClawbackQuota after = quotaRepo.find(batch.id(), clawback.declarationNo()).orElseThrow();
        return new ClawbackResultView(view(updated), recoveryId,
                after.frozenTaxCents(), after.occupiedCents(), after.remainingCents(),
                after.versionNo());
    }

    /**
     * 登记追缴回执：多笔部分到账，累计不超批准额，恰收齐转完成。
     */
    @Transactional
    public RecoveryView receive(String batchNo, long clawbackId,
                                com.example.rebate.web.dto.ClawbackReceiptRequest req) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        Clawback clawback = loadClawbackInBatch(batch.id(), clawbackId);
        switch (clawback.status()) {
            case PENDING -> throw new UnprocessableException("冲回单尚未批准，不能登记追缴回执");
            case REJECTED -> throw new UnprocessableException("冲回单已驳回，不能登记追缴回执");
            case COMPLETED -> throw new UnprocessableException("追缴已收齐完成，冲回单只读");
            default -> {
            }
        }
        if (clawback.versionNo() != req.expectedVersion()) {
            throw new ConflictException("冲回单版本已变化：期望 " + req.expectedVersion()
                    + "，实际 " + clawback.versionNo());
        }
        ClawbackRecovery recovery = recoveryRepo.findByClawbackId(clawbackId).orElseThrow();
        // 锁追缴明细行，串行化同单多笔回执，避免并发超收。
        ClawbackRecovery locked = recoveryRepo.findByIdForUpdate(recovery.id()).orElseThrow();

        Optional<ClawbackReceipt> sameSerial = receiptRepo.findBySerial(req.serialNo());
        if (sameSerial.isPresent()) {
            ClawbackReceipt prior = sameSerial.get();
            if (prior.receivedAmountCents() != req.amountCents()
                    || prior.clawbackId() != clawbackId) {
                throw new ConflictException("流水号 " + req.serialNo() + " 已用于不同金额/追缴单");
            }
            // 同流水同参：不重复累加，返回当前追缴状态（读取最新冲回单版本）。
            Clawback current = clawbackRepo.findById(clawbackId).orElseThrow();
            return recoveryView(clawbackId, current.versionNo());
        }

        long newReceived = locked.receivedAmountCents() + req.amountCents();
        if (newReceived > locked.approvedAmountCents()) {
            throw new UnprocessableException("累计到账将超过批准金额：已收 "
                    + locked.receivedAmountCents() + " 分，本笔 " + req.amountCents()
                    + " 分，批准 " + locked.approvedAmountCents() + " 分");
        }
        Instant now = timeService.now();
        try {
            receiptRepo.insert(locked.id(), clawbackId, req.serialNo(), req.amountCents(),
                    req.receivedAt(), now);
        } catch (DuplicateKeyException e) {
            // 并发同流水：异金额/异单拒绝，同金额按重复处理不累加。
            ClawbackReceipt prior = receiptRepo.findBySerial(req.serialNo()).orElseThrow();
            if (prior.receivedAmountCents() != req.amountCents()
                    || prior.clawbackId() != clawbackId) {
                throw new ConflictException("流水号 " + req.serialNo() + " 已用于不同金额/追缴单");
            }
            Clawback current = clawbackRepo.findById(clawbackId).orElseThrow();
            return recoveryView(clawbackId, current.versionNo());
        }
        boolean fullyReceived = newReceived == locked.approvedAmountCents();
        recoveryRepo.addReceived(locked.id(), req.amountCents(),
                fullyReceived ? RecoveryStatus.COMPLETED : RecoveryStatus.RECOVERING, now);
        // 每笔成功回执都使冲回单版本加 1；恰收齐同事务置 COMPLETED。乐观条件不命中即并发冲突，整体回滚。
        int changed = clawbackRepo.applyReceipt(
                clawbackId, req.expectedVersion(), fullyReceived, now);
        if (changed == 0) {
            throw new ConflictException("冲回单状态或版本已变化，回执登记失败");
        }
        return recoveryView(clawbackId, req.expectedVersion() + 1);
    }

    /**
     * 逐单余额查询：冻结额、累计占用、剩余可冲额及该单全部冲回单。
     */
    @Transactional(readOnly = true)
    public ClawbackBalanceView getBalance(String batchNo, String declarationNo) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        CheckDetail inBatch = detailRepo.findByBatchId(batch.id()).stream()
                .filter(d -> d.declarationNo().equals(declarationNo))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "报关单不在该已结案批次内: " + declarationNo));
        long frozenCents = toCents(inBatch.taxBasis());
        ClawbackQuota quota =
                quotaRepo.find(batch.id(), declarationNo)
                        .orElse(new ClawbackQuota(batch.id(), declarationNo, frozenCents,
                                0L, 1, timeService.now(), timeService.now()));
        List<ClawbackView> clawbacks =
                clawbackRepo.findByBatchAndDeclaration(batch.id(), declarationNo).stream()
                        .map(this::view).toList();
        return new ClawbackBalanceView(batch.batchNo(), batch.status().name(), declarationNo,
                quota.frozenTaxCents(), quota.occupiedCents(), quota.remainingCents(),
                quota.versionNo(), clawbacks);
    }

    /**
     * 冲回单详情（含追缴明细与回执）。
     */
    @Transactional(readOnly = true)
    public ClawbackDetailView getDetail(String batchNo, long clawbackId) {
        DeclarationBatch batch = loadClosedBatch(batchNo);
        Clawback clawback = loadClawbackInBatch(batch.id(), clawbackId);
        RecoveryView recovery = recoveryRepo.findByClawbackId(clawbackId)
                .map(r -> recoveryView(clawbackId, clawback.versionNo()))
                .orElse(null);
        return new ClawbackDetailView(view(clawback), recovery);
    }

    private DeclarationBatch loadClosedBatch(String batchNo) {
        DeclarationBatch batch = batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        if (batch.status() != BatchStatus.CLOSED) {
            throw new UnprocessableException(
                    "仅已结案批次允许冲回，当前状态 " + batch.status());
        }
        return batch;
    }

    private Clawback loadClawbackInBatch(long batchId, long clawbackId) {
        Clawback clawback = clawbackRepo.findById(clawbackId)
                .orElseThrow(() -> new NotFoundException("冲回单不存在: id=" + clawbackId));
        if (clawback.batchId() != batchId) {
            throw new NotFoundException("冲回单不属于该批次: id=" + clawbackId);
        }
        return clawback;
    }

    private RecoveryView recoveryView(long clawbackId, int clawbackVersion) {
        ClawbackRecovery recovery =
                recoveryRepo.findByClawbackId(clawbackId).orElseThrow();
        List<RecoveryView.ReceiptView> receipts =
                receiptRepo.findByRecoveryId(recovery.id()).stream()
                        .map(r -> new RecoveryView.ReceiptView(
                                r.id(), r.serialNo(), r.receivedAmountCents(), r.receivedAt()))
                        .toList();
        return new RecoveryView(recovery.id(), clawbackId, recovery.status().name(),
                recovery.approvedAmountCents(), recovery.receivedAmountCents(),
                recovery.approvedAmountCents() - recovery.receivedAmountCents(),
                clawbackVersion, receipts);
    }

    private ClawbackView view(Clawback c) {
        return new ClawbackView(c.id(), c.batchNo(), c.declarationNo(), c.externalCorrectionNo(),
                c.reason(), c.amountCents(), c.frozenTaxCents(), c.status().name(),
                c.submittedBy(), c.reviewer(), c.reviewedAt(), c.versionNo(),
                c.createdAt(), c.updatedAt());
    }

    /**
     * 冻结退税额（元，4 位小数）转分：×100 后四舍五入到整数分。
     */
    private static long toCents(BigDecimal yuan) {
        return yuan.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
