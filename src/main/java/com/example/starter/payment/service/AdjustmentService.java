package com.example.starter.payment.service;

import com.example.starter.payment.BizException;
import com.example.starter.payment.CutoffClock;
import com.example.starter.payment.PayBatch;
import com.example.starter.payment.PayTransaction;
import com.example.starter.payment.repo.BatchRepository;
import com.example.starter.payment.repo.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 调账服务：将挂账交易转入下一开放批次。调账与原交易在同一事务内完成，
 * 通过行锁与单条状态更新保证不出现两边同时计额。
 */
@Service
public class AdjustmentService {

    private final TransactionRepository txnRepo;
    private final BatchRepository batchRepo;

    public AdjustmentService(TransactionRepository txnRepo, BatchRepository batchRepo) {
        this.txnRepo = txnRepo;
        this.batchRepo = batchRepo;
    }

    /**
     * 将指定流水号的挂账交易调入下一开放批次。
     *
     * @param serialNo 渠道流水号
     * @return 调账后的交易（状态 POSTED，batchId 指向开放批次）
     */
    @Transactional
    public PayTransaction adjust(String serialNo) {
        PayTransaction txn = txnRepo.lockBySerialNo(serialNo)
                .orElseThrow(() -> BizException.notFound("交易不存在: " + serialNo));
        if (!PayTransaction.SUSPENDED.equals(txn.status())) {
            throw BizException.conflict("仅挂账交易可调账，当前状态: " + txn.status());
        }
        // 从交易按日切本应归属的批次起，找到第一个开放批次
        LocalDate targetDate = CutoffClock.batchDateOf(txn.txnTime());
        PayBatch target;
        while (true) {
            target = batchRepo.lockOrCreate(targetDate);
            if (!target.isClosed()) {
                break;
            }
            targetDate = targetDate.plusDays(1);
        }
        txnRepo.moveToBatch(txn.id(), target.id());
        return new PayTransaction(txn.id(), txn.requestId(), txn.fingerprint(), txn.serialNo(),
                txn.merchantNo(), txn.amount(), txn.fee(), txn.txnTime(), target.id(),
                PayTransaction.POSTED);
    }
}
