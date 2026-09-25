package com.example.starter.payment.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.payment.dto.SettlementItem;
import com.example.starter.payment.dto.SettlementResponse;
import com.example.starter.payment.repo.BatchRepository;
import com.example.starter.payment.repo.SettlementRepository;
import com.example.starter.payment.repo.TransactionRepository;
import com.example.starter.payment.repo.TransactionRepository.TxnRow;
import com.example.starter.payment.support.BusinessException;

/**
 * 批次封账与清算服务。
 *
 * <p>封账在事务内持有批次行锁：先将批次 OPEN→CLOSED（终态），再聚合批次内已入账交易生成清算单。
 * 重复封账直接返回首次清算单，不重新计算。</p>
 */
@Service
public class SettlementService {

    private final BatchRepository batchRepo;
    private final TransactionRepository txnRepo;
    private final SettlementRepository settlementRepo;
    private final com.example.starter.payment.support.TimeProvider timeProvider;

    public SettlementService(BatchRepository batchRepo, TransactionRepository txnRepo,
            SettlementRepository settlementRepo,
            com.example.starter.payment.support.TimeProvider timeProvider) {
        this.batchRepo = batchRepo;
        this.txnRepo = txnRepo;
        this.settlementRepo = settlementRepo;
        this.timeProvider = timeProvider;
    }

    /**
     * 封账并生成清算单；已封账则幂等返回首次清算单。
     */
    @Transactional
    public SettlementResponse closeBatch(String batchDate) {
        batchRepo.ensureBatch(batchDate);
        // 批次行锁：与入账互斥；拿到锁后翻转终态，再聚合，杜绝封账后交易计入本批次。
        var batch = batchRepo.lock(batchDate);
        if ("CLOSED".equals(batch.status())) {
            return new SettlementResponse(batchDate, "CLOSED", true, settlementRepo.findByBatch(batchDate));
        }

        int closed = batchRepo.closeIfOpen(batchDate, timeProvider.now());
        if (closed == 0) {
            return new SettlementResponse(batchDate, "CLOSED", true, settlementRepo.findByBatch(batchDate));
        }

        // 封账后锁聚批次内交易，期间任何入账都因批次行锁阻塞并在随后看到 CLOSED 而挂账。
        List<TxnRow> txns = txnRepo.lockPostedByBatch(batchDate);
        Map<String, Agg> aggs = new LinkedHashMap<>();
        for (TxnRow txn : txns) {
            aggs.computeIfAbsent(txn.merchantNo(), k -> new Agg()).add(txn);
        }
        for (Map.Entry<String, Agg> entry : aggs.entrySet()) {
            settlementRepo.insert(batchDate, entry.getValue().toItem(entry.getKey()));
        }

        return new SettlementResponse(batchDate, "CLOSED", false, settlementRepo.findByBatch(batchDate));
    }

    /** 查询批次状态与清算单；批次不存在视为 404。 */
    @Transactional(readOnly = true)
    public SettlementResponse getSettlement(String batchDate) {
        var batch = batchRepo.find(batchDate)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "批次不存在: " + batchDate));
        List<SettlementItem> items = settlementRepo.findByBatch(batchDate);
        return new SettlementResponse(batchDate, batch.status(), "CLOSED".equals(batch.status()), items);
    }

    /** 单商户金额聚合器。 */
    private static final class Agg {
        private int count;
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal fee = BigDecimal.ZERO;

        private void add(TxnRow txn) {
            count++;
            amount = amount.add(txn.amount());
            fee = fee.add(txn.fee());
        }

        private SettlementItem toItem(String merchantNo) {
            return new SettlementItem(merchantNo, count, amount, fee, amount.subtract(fee));
        }
    }
}
