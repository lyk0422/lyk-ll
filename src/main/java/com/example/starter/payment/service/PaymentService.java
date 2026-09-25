package com.example.starter.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.payment.domain.BatchStatus;
import com.example.starter.payment.domain.TxnStatus;
import com.example.starter.payment.dto.AdjustRequest;
import com.example.starter.payment.dto.AdjustResponse;
import com.example.starter.payment.dto.PaymentRequest;
import com.example.starter.payment.dto.PaymentResponse;
import com.example.starter.payment.repo.AdjustmentRepository;
import com.example.starter.payment.repo.BatchRepository;
import com.example.starter.payment.repo.MerchantRepository;
import com.example.starter.payment.repo.TransactionRepository;
import com.example.starter.payment.repo.TransactionRepository.TxnRow;
import com.example.starter.payment.support.BusinessException;
import com.example.starter.payment.support.Cutoffs;
import com.example.starter.payment.support.Fingerprints;
import com.example.starter.payment.support.TimeProvider;

/**
 * 交易入账与挂账调账服务。
 *
 * <p>并发正确性：入账与封账都在事务内先对批次行加悲观写锁（{@code SELECT ... FOR UPDATE}），
 * 同批次的入账与封账因此被数据库串行化，一笔交易只能归属唯一批次：封账先拿到锁则交易看到
 * CLOSED 进入挂账；入账先拿到锁则交易计入该批次后才允许封账。</p>
 */
@Service
public class PaymentService {

    private final TransactionRepository txnRepo;
    private final BatchRepository batchRepo;
    private final MerchantRepository merchantRepo;
    private final AdjustmentRepository adjustmentRepo;
    private final TimeProvider timeProvider;

    public PaymentService(TransactionRepository txnRepo, BatchRepository batchRepo,
            MerchantRepository merchantRepo, AdjustmentRepository adjustmentRepo, TimeProvider timeProvider) {
        this.txnRepo = txnRepo;
        this.batchRepo = batchRepo;
        this.merchantRepo = merchantRepo;
        this.adjustmentRepo = adjustmentRepo;
        this.timeProvider = timeProvider;
    }

    /**
     * 渠道交易入账。以 requestId 幂等；批次已封账时交易进入挂账。
     */
    @Transactional
    public PaymentResponse post(PaymentRequest req) {
        Instant occurTime = req.occurTime() != null ? req.occurTime() : timeProvider.now();
        String fingerprint = Fingerprints.of(req.requestId(), req.channelSerialNo(), req.amount(),
                req.merchantNo());

        var existing = txnRepo.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        if (!merchantRepo.existsActive(req.merchantNo())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "商户不存在或未启用: " + req.merchantNo());
        }
        BigDecimal feeRate = merchantRepo.findFeeRate(req.merchantNo())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "商户未配置手续费费率: " + req.merchantNo()));
        BigDecimal fee = req.amount().multiply(feeRate).setScale(2, RoundingMode.HALF_UP);

        String batchDate = Cutoffs.batchDate(occurTime).toString();
        batchRepo.ensureBatch(batchDate);
        // 批次行锁：与封账互斥，保证交易归属唯一批次。
        var batch = batchRepo.lock(batchDate);

        TxnStatus status = BatchStatus.CLOSED.name().equals(batch.status())
                ? TxnStatus.SUSPENDED : TxnStatus.POSTED;

        TxnRow txn = new TxnRow(null, req.requestId(), fingerprint, req.channelSerialNo(), req.merchantNo(),
                req.amount(), feeRate, fee, occurTime, batchDate, status.name(), "CHANNEL");
        try {
            txnRepo.insert(txn);
        } catch (DuplicateKeyException e) {
            // 唯一键竞争（并发同 requestId 或渠道流水号），以库内首次结果为准。
            var first = txnRepo.findByRequestId(req.requestId());
            if (first.isPresent()) {
                return replayOrConflict(first.get(), fingerprint);
            }
            throw new BusinessException(HttpStatus.CONFLICT,
                    "渠道流水号已入账，不允许重复入账: " + req.channelSerialNo());
        }

        return toResponse(txn, false);
    }

    /**
     * 挂账调账：将挂账交易与调账指令在同一事务内转入下一开放批次。
     */
    @Transactional
    public AdjustResponse adjust(AdjustRequest req) {
        var suspendedOpt = txnRepo.lockSuspendedBySerialNo(req.channelSerialNo());
        if (suspendedOpt.isEmpty()) {
            var any = txnRepo.findByChannelSerialNo(req.channelSerialNo());
            if (any.isEmpty()) {
                throw new BusinessException(HttpStatus.NOT_FOUND,
                        "挂账交易不存在: " + req.channelSerialNo());
            }
            throw new BusinessException(HttpStatus.CONFLICT,
                    "交易不是挂账状态（可能已调账）: " + req.channelSerialNo());
        }
        TxnRow txn = suspendedOpt.get();

        String toBatchDate = LocalDate.parse(txn.batchDate()).plusDays(1).toString();
        batchRepo.ensureBatch(toBatchDate);
        var target = batchRepo.lock(toBatchDate);
        if (!"OPEN".equals(target.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "目标批次未开放，无法调账: " + toBatchDate);
        }

        try {
            // 先留痕再翻转，二者同一事务；uk_adjust_txn 保证一笔挂账只调账一次。
            adjustmentRepo.insert(txn.id(), txn.channelSerialNo(), txn.batchDate(), toBatchDate,
                    txn.amount(), req.operator());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "该挂账交易已调账，不允许重复调账: " + req.channelSerialNo());
        }
        int updated = txnRepo.markPostedToBatch(txn.id(), toBatchDate);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "调账入账失败: " + req.channelSerialNo());
        }

        return new AdjustResponse(txn.channelSerialNo(), txn.batchDate(), toBatchDate, txn.amount(),
                timeProvider.now());
    }

    private PaymentResponse replayOrConflict(TxnRow first, String incomingFingerprint) {
        if (!first.fingerprint().equals(incomingFingerprint)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "requestId 已存在但请求参数不一致（流水号/金额/商户号），拒绝重复入账: " + first.requestId());
        }
        return toResponse(first, true);
    }

    private PaymentResponse toResponse(TxnRow txn, boolean replayed) {
        return new PaymentResponse(txn.requestId(), txn.channelSerialNo(), txn.merchantNo(), txn.amount(),
                txn.occurTime(), txn.batchDate(), txn.status(), replayed);
    }
}
