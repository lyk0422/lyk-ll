package com.example.rebate.service;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.Doubt;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.repo.CheckDetailRepository;
import com.example.rebate.repo.DoubtRepository;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.UnprocessableException;
import com.example.rebate.web.dto.CheckResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 疑点处理：提交补充说明后须复核确认方可解除。
 */
@Service
public class DoubtService {

    private final DoubtRepository doubtRepo;
    private final BatchRepository batchRepo;
    private final CheckDetailRepository detailRepo;
    private final TimeService timeService;

    public DoubtService(DoubtRepository doubtRepo, BatchRepository batchRepo,
                        CheckDetailRepository detailRepo, TimeService timeService) {
        this.doubtRepo = doubtRepo;
        this.batchRepo = batchRepo;
        this.detailRepo = detailRepo;
        this.timeService = timeService;
    }

    /**
     * 提交补充说明（OPEN -> EXPLAINED）。重复提交幂等返回当前状态，不改变说明内容。
     */
    @Transactional
    public CheckResponse explain(String batchNo, long doubtId, String explanation) {
        Doubt doubt = loadDoubt(batchNo, doubtId);
        switch (doubt.status()) {
            case OPEN -> doubtRepo.submitExplanation(doubtId, explanation, timeService.now());
            case EXPLAINED, RESOLVED -> {
                // 已提交/已复核：重复提交不覆盖，返回当前视图（同键重放也走存档）。
            }
        }
        return currentView(batchNo, doubt.batchId());
    }

    /**
     * 复核确认（EXPLAINED -> RESOLVED）。未提交说明不得复核。全部疑点解除后批次回到 CHECKED。
     */
    @Transactional
    public CheckResponse review(String batchNo, long doubtId) {
        Doubt doubt = loadDoubt(batchNo, doubtId);
        if (doubt.status() == com.example.rebate.domain.DoubtStatus.OPEN) {
            throw new UnprocessableException("疑点 " + doubtId + " 尚未提交补充说明，不能复核确认");
        }
        if (doubt.status() == com.example.rebate.domain.DoubtStatus.EXPLAINED) {
            doubtRepo.reviewResolve(doubtId, timeService.now());
        }
        // RESOLVED 重复复核幂等。
        refreshBatchStatus(doubt.batchId());
        return currentView(batchNo, doubt.batchId());
    }

    private Doubt loadDoubt(String batchNo, long doubtId) {
        var batch = batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        if (batch.status() == BatchStatus.VOID) {
            throw new ConflictException("批次已作废: " + batchNo);
        }
        List<Doubt> doubts = doubtRepo.findById(doubtId);
        Doubt doubt = doubts.stream().findFirst()
                .orElseThrow(() -> new NotFoundException("疑点不存在: " + doubtId));
        if (doubt.batchId() != batch.id()) {
            throw new NotFoundException("疑点 " + doubtId + " 不属于批次 " + batchNo);
        }
        return doubt;
    }

    /**
     * 若全部疑点均 RESOLVED，批次由 DOUBTFUL 回到 CHECKED；否则保持 DOUBTFUL。
     */
    private void refreshBatchStatus(long batchId) {
        boolean hasUnresolved = !doubtRepo.findUnresolvedByBatchId(batchId).isEmpty();
        if (!hasUnresolved) {
            var batch = batchRepo.findById(batchId).orElseThrow();
            if (batch.status() == BatchStatus.DOUBTFUL) {
                batchRepo.updateStatus(batchId, BatchStatus.CHECKED,
                        batch.totalDeclaredTax(), timeService.now());
            }
        }
    }

    private CheckResponse currentView(String batchNo, long batchId) {
        var batch = batchRepo.findById(batchId).orElseThrow();
        return BatchViews.check(batch,
                detailRepo.findByBatchId(batchId), doubtRepo.findByBatchId(batchId));
    }
}
