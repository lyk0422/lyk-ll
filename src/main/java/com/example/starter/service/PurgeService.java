package com.example.starter.service;

import com.example.starter.domain.CustodyEventType;
import com.example.starter.domain.Evidence;
import com.example.starter.domain.EvidenceStatus;
import com.example.starter.domain.PurgeBatch;
import com.example.starter.domain.PurgeBatchItem;
import com.example.starter.domain.PurgeManifestEntry;
import com.example.starter.repo.EvidenceRepository;
import com.example.starter.repo.HoldRepository;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.repo.PurgeRepository;
import com.example.starter.web.dto.MarkPurgeRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 两阶段清除服务。
 *
 * <p>标记阶段：在单事务内锁定版本序列与候选证据行，仅选择已到期（expires_at &lt;= cutoff）
 * 且无活动保全的 RETAINED 证据，原子写 MARKED 批次与候选清单，证据置 MARKED。</p>
 *
 * <p>最终确认：行锁锁定批次后再锁定版本序列，等待标记后新生效的保全全部落盘，
 * 逐个候选重新检查活动保全与状态，被保全的原子救回为 RETAINED 并排除，
 * 其余写入不可变清除清单后置 PURGED。重复确认返回首次冻结清单。</p>
 */
@Service
public class PurgeService {

    private static final String MARK_KEY_PREFIX = "purge:mark:";

    private final PurgeRepository purgeRepository;
    private final EvidenceRepository evidenceRepository;
    private final HoldRepository holdRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TimeService timeService;

    public PurgeService(PurgeRepository purgeRepository,
                        EvidenceRepository evidenceRepository,
                        HoldRepository holdRepository,
                        IdempotencyRepository idempotencyRepository,
                        TimeService timeService) {
        this.purgeRepository = purgeRepository;
        this.evidenceRepository = evidenceRepository;
        this.holdRepository = holdRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.timeService = timeService;
    }

    @Transactional
    public PurgeBatch mark(MarkPurgeRequest request) {
        String requestKey = MARK_KEY_PREFIX + request.requestId();
        String fingerprint = Fingerprints.sha256Hex(
                "MARK|" + request.cutoffTime() + "|"
                        + (request.expectedVersion() == null ? "" : request.expectedVersion()));

        PurgeBatch replay = replayMark(requestKey, fingerprint);
        if (replay != null) {
            return replay;
        }

        // 与保全创建/解除及最终确认串行化，保证标记时刻的活动保全快照稳定。
        holdRepository.lockVersionRow();

        replay = replayMark(requestKey, fingerprint);
        if (replay != null) {
            return replay;
        }

        if (request.expectedVersion() != null
                && request.expectedVersion() != holdRepository.currentVersion()) {
            throw ApiException.conflict(
                    "expected hold version does not match current version at mark time");
        }

        // 行锁锁定到期候选；新保全/解除此刻因序列行锁无法提交，活动保全判定无并发窗口。
        List<Evidence> expired = evidenceRepository.lockExpiredRetained(request.cutoffTime());
        Set<String> activeHeld = evidenceRepository.filterActiveHeld(
                expired.stream().map(Evidence::evidenceId).toList());

        Instant now = timeService.now();
        List<String> candidateIds = new ArrayList<>();
        for (Evidence evidence : expired) {
            if (activeHeld.contains(evidence.evidenceId())) {
                // 活动保全阻止清除，不进入批次。
                continue;
            }
            int affected = evidenceRepository.markIfRetained(evidence.evidenceId());
            if (affected == 0) {
                // 并发下状态已变化则跳过（序列行锁下通常不可达，保守处理）。
                continue;
            }
            candidateIds.add(evidence.evidenceId());
            evidenceRepository.appendEvent(evidence.evidenceId(), CustodyEventType.MARKED,
                    "marked for purge at cutoff " + request.cutoffTime(), now);
        }

        String batchId = "B-" + UUID.randomUUID();
        purgeRepository.insertBatch(batchId, request.requestId(), request.cutoffTime(),
                request.expectedVersion(), now, candidateIds);
        idempotencyRepository.insert(requestKey, fingerprint, batchId, now);
        return loadBatch(batchId);
    }

    @Transactional
    public ConfirmResult confirm(String batchId) {
        PurgeRepository.BatchRow batchRow = purgeRepository.lockBatch(batchId)
                .orElseThrow(() -> ApiException.notFound("purge batch not found: " + batchId));

        if ("PURGED".equals(batchRow.status())) {
            // 重复最终确认：返回首次冻结的不可变清单。
            return new ConfirmResult(toBatch(batchRow), purgeRepository.listManifest(batchId));
        }

        // 等待标记之后、本次确认之前的全部保全创建/解除落盘，再做最终判定。
        holdRepository.lockVersionRow();

        List<PurgeBatchItem> items = purgeRepository.listItems(batchId);
        List<String> itemIds = items.stream().map(PurgeBatchItem::evidenceId).toList();
        Map<String, Evidence> lockedEvidence = new java.util.HashMap<>();
        for (Evidence e : evidenceRepository.lockByIds(itemIds)) {
            lockedEvidence.put(e.evidenceId(), e);
        }
        Set<String> activeHeld = evidenceRepository.filterActiveHeld(itemIds);

        Instant now = timeService.now();
        List<String> rescuedIds = new ArrayList<>();
        List<String> purgedIds = new ArrayList<>();
        for (PurgeBatchItem item : items) {
            Evidence evidence = lockedEvidence.get(item.evidenceId());
            boolean held = activeHeld.contains(item.evidenceId());
            boolean stillMarked = evidence != null && evidence.status() == EvidenceStatus.MARKED;
            if (held || !stillMarked) {
                // 标记后被活动保全救回，或状态已非 MARKED：原子保留并排除出最终结果。
                rescuedIds.add(item.evidenceId());
                if (stillMarked) {
                    evidenceRepository.rescueIfMarked(item.evidenceId());
                    evidenceRepository.appendEvent(item.evidenceId(), CustodyEventType.RESCUED,
                            "rescued at final confirmation of batch " + batchId, now);
                }
                continue;
            }
            purgedIds.add(item.evidenceId());
        }

        purgeRepository.markRescued(batchId, rescuedIds);

        // 先冻结不可变清除清单，再变更证据状态，最后原子翻转批次状态。
        for (String evidenceId : purgedIds) {
            Evidence evidence = lockedEvidence.get(evidenceId);
            purgeRepository.insertManifestEntry(batchId, evidenceId, evidence.sha256(), now);
        }
        for (String evidenceId : purgedIds) {
            int affected = evidenceRepository.purgeIfMarked(evidenceId);
            if (affected == 0) {
                // 与活动保全竞争失败则整体回滚，绝不清除被保全证据。
                throw ApiException.conflict(
                        "evidence became held during final confirmation: " + evidenceId);
            }
            evidenceRepository.appendEvent(evidenceId, CustodyEventType.PURGED,
                    "purged in batch " + batchId, now);
        }

        int finalized = purgeRepository.finalizeBatch(batchId, now);
        if (finalized == 0) {
            throw ApiException.conflict("purge batch already finalized: " + batchId);
        }

        return new ConfirmResult(loadBatch(batchId), purgeRepository.listManifest(batchId));
    }

    @Transactional(readOnly = true)
    public PurgeBatch getBatch(String batchId) {
        PurgeRepository.BatchRow row = purgeRepository.findBatch(batchId)
                .orElseThrow(() -> ApiException.notFound("purge batch not found: " + batchId));
        return toBatch(row);
    }

    @Transactional(readOnly = true)
    public List<PurgeManifestEntry> getManifest(String batchId) {
        purgeRepository.findBatch(batchId)
                .orElseThrow(() -> ApiException.notFound("purge batch not found: " + batchId));
        return purgeRepository.listManifest(batchId);
    }

    private PurgeBatch replayMark(String requestKey, String fingerprint) {
        return idempotencyRepository.find(requestKey).map(record -> {
            if (!record.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId already used with different parameters");
            }
            return loadBatch(record.responseRef());
        }).orElse(null);
    }

    private PurgeBatch loadBatch(String batchId) {
        PurgeRepository.BatchRow row = purgeRepository.findBatch(batchId).orElseThrow();
        return toBatch(row);
    }

    private PurgeBatch toBatch(PurgeRepository.BatchRow row) {
        return new PurgeBatch(row.batchId(), row.markRequestId(), row.cutoffTime(),
                row.expectedVersion(),
                "PURGED".equals(row.status())
                        ? com.example.starter.domain.BatchStatus.PURGED
                        : com.example.starter.domain.BatchStatus.MARKED,
                purgeRepository.listItems(row.batchId()),
                row.createdAt(), row.finalizedAt());
    }

    /**
     * 最终确认结果：冻结后的批次快照与不可变清单。
     */
    public record ConfirmResult(PurgeBatch batch, List<PurgeManifestEntry> manifest) {
    }
}
