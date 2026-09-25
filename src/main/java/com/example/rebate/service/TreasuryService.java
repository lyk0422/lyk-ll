package com.example.rebate.service;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.domain.TreasuryWriteoff;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.repo.TreasuryWriteoffRepository;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.dto.WriteoffView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退库核销：按 批次＋国库流水号 唯一登记；到账与申报税额差额超 0.5% 登记差异并转人工，
 * 恰 0.5% 视为一致。
 */
@Service
public class TreasuryService {

    private final TreasuryWriteoffRepository writeoffRepo;
    private final BatchRepository batchRepo;
    private final TimeService timeService;

    public TreasuryService(TreasuryWriteoffRepository writeoffRepo,
                           BatchRepository batchRepo,
                           TimeService timeService) {
        this.writeoffRepo = writeoffRepo;
        this.batchRepo = batchRepo;
        this.timeService = timeService;
    }

    /**
     * 登记退库核销。
     */
    @Transactional
    public WriteoffView register(String batchNo, String treasurySerial, BigDecimal receivedAmount) {
        DeclarationBatch batch = batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        if (batch.status() == BatchStatus.VOID) {
            throw new ConflictException("批次已作废，不能退库核销");
        }
        if (batch.status() == BatchStatus.CLOSED) {
            throw new ConflictException("批次已结案，不能新增退库核销");
        }
        if (batch.status() != BatchStatus.APPROVED
                && batch.status() != BatchStatus.TREASURED
                && batch.status() != BatchStatus.DIFF_MANUAL) {
            throw new ConflictException("批次当前状态 " + batch.status() + " 不允许退库核销");
        }

        BigDecimal declaredTax = batch.totalDeclaredTax();
        if (declaredTax.signum() <= 0) {
            throw new ConflictException("批次申报税额非正，无法核销");
        }
        BigDecimal diffAmount = RebateCalculator.diffAmount(declaredTax, receivedAmount);
        BigDecimal diffRatio = RebateCalculator.diffRatio(declaredTax, receivedAmount);
        boolean consistent = RebateCalculator.diffConsistent(diffRatio);
        Instant now = timeService.now();

        TreasuryWriteoff writeoff = new TreasuryWriteoff(null, batch.id(), treasurySerial,
                declaredTax, receivedAmount, diffAmount, diffRatio, consistent, !consistent, now);
        try {
            writeoffRepo.insert(writeoff);
        } catch (DuplicateKeyException e) {
            TreasuryWriteoff existing =
                    writeoffRepo.find(batch.id(), treasurySerial).stream().findFirst().orElseThrow();
            return view(batch, existing);
        }

        BatchStatus nextStatus = consistent ? BatchStatus.TREASURED : BatchStatus.DIFF_MANUAL;
        batchRepo.updateStatus(batch.id(), nextStatus, declaredTax, now);

        TreasuryWriteoff saved =
                writeoffRepo.find(batch.id(), treasurySerial).stream().findFirst().orElseThrow();
        return view(batchRepo.findById(batch.id()).orElseThrow(), saved);
    }

    /**
     * 查询批次全部核销登记。
     */
    @Transactional(readOnly = true)
    public List<WriteoffView> list(String batchNo) {
        DeclarationBatch batch = batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        return writeoffRepo.findByBatchId(batch.id()).stream().map(w -> view(batch, w)).toList();
    }

    private WriteoffView view(DeclarationBatch batch, TreasuryWriteoff w) {
        return new WriteoffView(w.id(), batch.batchNo(), w.treasurySerial(), w.declaredTax(),
                w.receivedAmount(), w.diffAmount(), w.diffRatio(), w.consistent(),
                w.manualFlag(), batch.status().name(), w.registeredAt());
    }
}
