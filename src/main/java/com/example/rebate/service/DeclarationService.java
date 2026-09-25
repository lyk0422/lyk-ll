package com.example.rebate.service;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.domain.Doubt;
import com.example.rebate.domain.DoubtStatus;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.repo.CheckDetailRepository;
import com.example.rebate.repo.DoubtRepository;
import com.example.rebate.repo.LateEventRepository;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.UnprocessableException;
import com.example.rebate.web.dto.BatchView;
import com.example.rebate.web.dto.LateEventView;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 正式申报、审核、撤批、结案流转。
 */
@Service
public class DeclarationService {

    private final BatchRepository batchRepo;
    private final DoubtRepository doubtRepo;
    private final CheckDetailRepository detailRepo;
    private final LateEventRepository lateEventRepo;
    private final TimeService timeService;

    public DeclarationService(BatchRepository batchRepo,
                              DoubtRepository doubtRepo,
                              CheckDetailRepository detailRepo,
                              LateEventRepository lateEventRepo,
                              TimeService timeService) {
        this.batchRepo = batchRepo;
        this.doubtRepo = doubtRepo;
        this.detailRepo = detailRepo;
        this.lateEventRepo = lateEventRepo;
        this.timeService = timeService;
    }

    /**
     * 正式申报：批次冻结。
     *
     * <p>{@code reviewConfirmed=true} 时在本事务内把“已说明待复核”的疑点一并复核确认；
     * 若仍存在未解除疑点（含未提交说明者），整体回滚——批次不会被报出，疑点也不被部分改写。</p>
     */
    @Transactional
    public BatchView declare(String batchNo, boolean reviewConfirmed) {
        DeclarationBatch batch = loadBatch(batchNo);
        if (batch.status() != BatchStatus.CHECKED && batch.status() != BatchStatus.DOUBTFUL) {
            throw new ConflictException("批次当前状态 " + batch.status() + " 不允许正式申报");
        }
        List<Doubt> doubts = doubtRepo.findByBatchId(batch.id());
        if (reviewConfirmed) {
            for (Doubt d : doubts) {
                if (d.status() == DoubtStatus.EXPLAINED) {
                    doubtRepo.reviewResolve(d.id(), timeService.now());
                }
            }
        }
        List<Doubt> unresolved = doubtRepo.findUnresolvedByBatchId(batch.id());
        if (!unresolved.isEmpty()) {
            throw new UnprocessableException(
                    "存在 " + unresolved.size() + " 个未解除疑点，禁止正式申报");
        }
        batchRepo.freeze(batch.id(), timeService.now());
        return BatchViews.batch(batchRepo.findById(batch.id()).orElseThrow());
    }

    /**
     * 审核通过：DECLARED -> APPROVED。
     */
    @Transactional
    public BatchView approve(String batchNo) {
        DeclarationBatch batch = loadBatch(batchNo);
        if (batch.status() != BatchStatus.DECLARED) {
            throw new ConflictException("批次当前状态 " + batch.status() + " 不允许审核");
        }
        batchRepo.updateStatus(batch.id(), BatchStatus.APPROVED,
                batch.totalDeclaredTax(), timeService.now());
        return BatchViews.batch(batchRepo.findById(batch.id()).orElseThrow());
    }

    /**
     * 撤批：整体作废并释放全部关联单证及占用登记，不得部分撤出。仅允许在退库之前。
     */
    @Transactional
    public BatchView withdraw(String batchNo) {
        DeclarationBatch batch = loadBatch(batchNo);
        if (batch.status() == BatchStatus.VOID) {
            return BatchViews.batch(batch);
        }
        if (batch.status() == BatchStatus.CLOSED) {
            throw new ConflictException("批次已结案，不得撤批");
        }
        if (batch.status() == BatchStatus.APPROVED
                || batch.status() == BatchStatus.TREASURED
                || batch.status() == BatchStatus.DIFF_MANUAL) {
            throw new ConflictException("批次已进入退库环节(" + batch.status() + ")，不得撤批");
        }
        batchRepo.releaseAllDocuments(batch.id(), timeService.now());
        batchRepo.clearRegistrations(batch.id());
        batchRepo.markVoid(batch.id(), timeService.now());
        return BatchViews.batch(batchRepo.findById(batch.id()).orElseThrow());
    }

    /**
     * 结案终态。重复结案幂等成功。
     */
    @Transactional
    public BatchView close(String batchNo) {
        DeclarationBatch batch = loadBatch(batchNo);
        if (batch.status() == BatchStatus.CLOSED) {
            return BatchViews.batch(batch);
        }
        if (batch.status() != BatchStatus.TREASURED
                && batch.status() != BatchStatus.DIFF_MANUAL) {
            throw new ConflictException("批次当前状态 " + batch.status() + " 不允许结案");
        }
        batchRepo.markClosed(batch.id(), timeService.now());
        return BatchViews.batch(batchRepo.findById(batch.id()).orElseThrow());
    }

    /**
     * 结案后迟到单证：只登记迟到事件，不参与任何关联或校验。
     */
    @Transactional
    public LateEventView recordLateDocument(String batchNo, String declarationNo,
                                            String invoiceNo, String note) {
        DeclarationBatch batch = loadBatch(batchNo);
        if (batch.status() != BatchStatus.CLOSED) {
            throw new UnprocessableException("仅结案后的迟到单证走事件登记，当前状态 " + batch.status());
        }
        lateEventRepo.insert(batch.id(), declarationNo, invoiceNo, note, timeService.now());
        var events = lateEventRepo.findByBatchId(batch.id());
        var last = events.get(events.size() - 1);
        return new LateEventView(last.id(), batch.batchNo(), batch.status().name(),
                last.declarationNo(), last.invoiceNo(), last.note(), last.recordedAt());
    }

    /**
     * 查询批次迟到事件。
     */
    @Transactional(readOnly = true)
    public List<LateEventView> listLateEvents(String batchNo) {
        DeclarationBatch batch = loadBatch(batchNo);
        return lateEventRepo.findByBatchId(batch.id()).stream()
                .map(e -> new LateEventView(e.id(), batch.batchNo(), batch.status().name(),
                        e.declarationNo(), e.invoiceNo(), e.note(), e.recordedAt()))
                .toList();
    }

    private DeclarationBatch loadBatch(String batchNo) {
        return batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
    }
}
