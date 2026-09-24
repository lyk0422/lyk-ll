package com.example.starter.payment.service;

import com.example.starter.payment.PayBatch;
import com.example.starter.payment.SettlementBill;
import com.example.starter.payment.repo.BatchRepository;
import com.example.starter.payment.repo.SettlementBillRepository;
import com.example.starter.payment.repo.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 封账服务：封账为终态，重复封账返回首次清算单且不重新计算。
 */
@Service
public class SettlementService {

    private final BatchRepository batchRepo;
    private final TransactionRepository txnRepo;
    private final SettlementBillRepository billRepo;

    public SettlementService(BatchRepository batchRepo, TransactionRepository txnRepo,
                             SettlementBillRepository billRepo) {
        this.batchRepo = batchRepo;
        this.txnRepo = txnRepo;
        this.billRepo = billRepo;
    }

    /**
     * 对指定批次日期封账并生成各商户清算单。
     *
     * @param batchDate 批次日期（UTC 日切归属日）
     * @return 该批次全部清算单（重复封账时返回首次生成的清算单）
     */
    @Transactional
    public List<SettlementBill> close(LocalDate batchDate) {
        // 锁定批次行，与入账串行化
        PayBatch batch = batchRepo.lockOrCreate(batchDate);
        if (batch.isClosed()) {
            // 幂等：返回首次清算结果，不重新计算
            return billRepo.findByBatchId(batch.id());
        }
        List<SettlementBill> bills = txnRepo.aggregateByBatch(batch.id()).stream()
                .map(agg -> billRepo.insert(batch.id(), agg.merchantNo(), agg.txnCount(),
                        agg.totalAmount(), agg.totalFee()))
                .toList();
        batchRepo.markClosed(batch.id());
        return bills;
    }

    @Transactional(readOnly = true)
    public List<SettlementBill> billsOf(LocalDate batchDate) {
        return batchRepo.findByDate(batchDate)
                .map(batch -> billRepo.findByBatchId(batch.id()))
                .orElse(List.of());
    }
}
