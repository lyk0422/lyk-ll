package com.example.starter.evidence.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.evidence.api.dto.ConfirmPurgeRequest;
import com.example.starter.evidence.api.dto.MarkPurgeRequest;
import com.example.starter.evidence.api.dto.PurgeBatchView;
import com.example.starter.evidence.api.dto.PurgeCandidateView;
import com.example.starter.evidence.domain.CustodyEventType;
import com.example.starter.evidence.domain.EvidenceStatus;
import com.example.starter.evidence.domain.PurgeBatchStatus;
import com.example.starter.evidence.domain.PurgeItemStatus;
import com.example.starter.evidence.error.ApiException;
import com.example.starter.evidence.repo.CustodyEventRepository;
import com.example.starter.evidence.repo.EvidenceRepository;
import com.example.starter.evidence.repo.EvidenceRow;
import com.example.starter.evidence.repo.HoldRepository;
import com.example.starter.evidence.repo.PurgeBatchRow;
import com.example.starter.evidence.repo.PurgeItemRow;
import com.example.starter.evidence.repo.PurgeRepository;
import com.example.starter.evidence.repo.RegistryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 两阶段清除服务：标记阶段原子写入批次与候选清单，确认阶段重新锁定复核并冻结不可变清单。
 */
@Service
public class PurgeService {

    private final PurgeRepository purgeRepository;
    private final EvidenceRepository evidenceRepository;
    private final HoldRepository holdRepository;
    private final CustodyEventRepository custodyEventRepository;
    private final RegistryRepository registryRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PurgeService(PurgeRepository purgeRepository,
            EvidenceRepository evidenceRepository,
            HoldRepository holdRepository,
            CustodyEventRepository custodyEventRepository,
            RegistryRepository registryRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.purgeRepository = purgeRepository;
        this.evidenceRepository = evidenceRepository;
        this.holdRepository = holdRepository;
        this.custodyEventRepository = custodyEventRepository;
        this.registryRepository = registryRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 标记阶段：仅允许已到期且无生效保全的证据，原子写入 MARKED 批次与候选清单。
     */
    @Transactional
    public PurgeBatchView mark(MarkPurgeRequest request) {
        String fingerprint = markFingerprint(request);
        var evidenceIds = new TreeSet<>(request.evidenceIds());
        // 先按主键序加行锁：同键并发标记在此串行化，后到事务的重放检查可见先提交的结果。
        var locked = evidenceRepository.findAllByIdForUpdate(evidenceIds);
        if (locked.size() != evidenceIds.size()) {
            var found = new TreeSet<String>();
            locked.forEach(row -> found.add(row.evidenceId()));
            var missing = new TreeSet<>(evidenceIds);
            missing.removeAll(found);
            throw new ApiException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND",
                    "证据不存在: " + String.join(",", missing));
        }

        var existing = purgeRepository.findBatchByRequestId(request.requestId());
        if (existing.isPresent()) {
            PurgeBatchRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "REQUEST_FINGERPRINT_CONFLICT",
                        "请求键 " + request.requestId() + " 已存在且参数指纹不一致");
            }
            return toView(row, true);
        }
        if (request.expectedVersion() != null
                && request.expectedVersion() != registryRepository.currentVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "REGISTRY_VERSION_CONFLICT",
                    "预期领域版本 " + request.expectedVersion() + " 与当前版本不一致");
        }

        Set<String> underHold = holdRepository.findEvidenceIdsWithActiveHold(evidenceIds);
        List<String> ineligible = new ArrayList<>();
        for (EvidenceRow row : locked) {
            if (row.status() != EvidenceStatus.ACTIVE) {
                ineligible.add(row.evidenceId() + "(状态" + row.status() + ")");
            } else if (row.expiresAt().isAfter(request.cutoff())) {
                ineligible.add(row.evidenceId() + "(未到期)");
            } else if (underHold.contains(row.evidenceId())) {
                ineligible.add(row.evidenceId() + "(存在生效保全)");
            }
        }
        if (!ineligible.isEmpty()) {
            // 任一候选不满足条件则整批回滚，保证批次与候选清单原子写入。
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_NOT_ELIGIBLE",
                    "以下证据不满足清除条件: " + String.join(",", ineligible));
        }

        Instant now = Instant.now(clock);
        String batchId = UUID.randomUUID().toString();
        try {
            purgeRepository.insertBatch(batchId, request.requestId(), fingerprint, request.cutoff(),
                    request.expectedVersion(), now);
        } catch (DuplicateKeyException ex) {
            // 证据集合不相交的同键并发标记：以唯一约束兜底，按已提交记录判定重放或冲突。
            return resolveConcurrentDuplicate(request.requestId(), fingerprint);
        }
        for (EvidenceRow row : locked) {
            purgeRepository.insertItem(batchId, row.evidenceId());
            evidenceRepository.updateStatus(row.evidenceId(), EvidenceStatus.MARKED, now);
            custodyEventRepository.append(row.evidenceId(), CustodyEventType.MARKED,
                    "清除批次 " + batchId + " 标记", now);
        }
        registryRepository.bump();
        return toView(purgeRepository.findBatchById(batchId).orElseThrow(), false);
    }

    /**
     * 最终确认：重新锁定并复核每个候选，冻结不可变清除清单；重复确认返回首次冻结的清单。
     */
    @Transactional
    public PurgeBatchView confirm(String batchId, ConfirmPurgeRequest request) {
        PurgeBatchRow batch = purgeRepository.findBatchByIdForUpdate(batchId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PURGE_BATCH_NOT_FOUND",
                        "清除批次 " + batchId + " 不存在"));
        String fingerprint = confirmFingerprint(batchId, request);
        if (batch.status() == PurgeBatchStatus.CONFIRMED) {
            if (request.requestId().equals(batch.confirmRequestId())
                    && fingerprint.equals(batch.confirmFingerprint())) {
                return toView(batch, true);
            }
            throw new ApiException(HttpStatus.CONFLICT, "PURGE_BATCH_ALREADY_CONFIRMED",
                    "清除批次 " + batchId + " 已确认，清单不可变");
        }
        if (request.expectedVersion() != batch.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "PURGE_BATCH_VERSION_CONFLICT",
                    "预期批次版本 " + request.expectedVersion() + " 与当前版本 " + batch.version() + " 不一致");
        }

        List<PurgeItemRow> items = purgeRepository.findItems(batchId);
        var pendingIds = new TreeSet<String>();
        for (PurgeItemRow item : items) {
            if (item.finalStatus() == null) {
                pendingIds.add(item.evidenceId());
            }
        }
        var locked = evidenceRepository.findAllByIdForUpdate(pendingIds);
        Map<String, EvidenceRow> lockedById = new LinkedHashMap<>();
        locked.forEach(row -> lockedById.put(row.evidenceId(), row));
        Set<String> underHold = holdRepository.findEvidenceIdsWithActiveHold(pendingIds);

        Instant now = Instant.now(clock);
        List<Map<String, Object>> manifestItems = new ArrayList<>();
        for (PurgeItemRow item : items) {
            if (item.finalStatus() == PurgeItemStatus.RESCUED) {
                manifestItems.add(manifestItem(item.evidenceId(), PurgeItemStatus.RESCUED, item.reason()));
                continue;
            }
            EvidenceRow evidence = lockedById.get(item.evidenceId());
            if (evidence != null && evidence.status() == EvidenceStatus.MARKED
                    && !underHold.contains(item.evidenceId())) {
                evidenceRepository.updateStatus(item.evidenceId(), EvidenceStatus.PURGED, now);
                purgeRepository.updateItemFinal(batchId, item.evidenceId(), PurgeItemStatus.PURGED, "确认清除");
                custodyEventRepository.append(item.evidenceId(), CustodyEventType.PURGED,
                        "清除批次 " + batchId + " 最终确认", now);
                manifestItems.add(manifestItem(item.evidenceId(), PurgeItemStatus.PURGED, "确认清除"));
            } else {
                // 确认前出现生效保全或状态已变化：原子救回为可保留状态并从清除结果中排除。
                String reason = evidence != null && underHold.contains(item.evidenceId())
                        ? "确认复核发现生效保全，救回"
                        : "确认复核状态已变化，救回";
                if (evidence != null && evidence.status() == EvidenceStatus.MARKED) {
                    evidenceRepository.updateStatus(item.evidenceId(), EvidenceStatus.ACTIVE, now);
                    custodyEventRepository.append(item.evidenceId(), CustodyEventType.RESCUED, reason, now);
                }
                purgeRepository.updateItemFinal(batchId, item.evidenceId(), PurgeItemStatus.RESCUED, reason);
                manifestItems.add(manifestItem(item.evidenceId(), PurgeItemStatus.RESCUED, reason));
            }
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("batchId", batchId);
        manifest.put("confirmedAt", now.toString());
        manifest.put("items", manifestItems);
        String manifestJson;
        try {
            manifestJson = objectMapper.writeValueAsString(manifest);
        } catch (Exception ex) {
            throw new IllegalStateException("清除清单序列化失败", ex);
        }
        purgeRepository.confirmBatch(batchId, manifestJson, request.requestId(), fingerprint, now);
        registryRepository.bump();
        return toView(purgeRepository.findBatchById(batchId).orElseThrow(), false);
    }

    /**
     * 查询清除批次及候选清单，已确认批次附带冻结清单。
     */
    @Transactional(readOnly = true)
    public PurgeBatchView get(String batchId) {
        PurgeBatchRow batch = purgeRepository.findBatchById(batchId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PURGE_BATCH_NOT_FOUND",
                        "清除批次 " + batchId + " 不存在"));
        return toView(batch, false);
    }

    private String markFingerprint(MarkPurgeRequest request) {
        var sorted = new TreeSet<>(request.evidenceIds());
        return Fingerprints.sha256("MARK|" + String.join(",", sorted) + "|" + request.cutoff()
                + "|" + request.expectedVersion());
    }

    private String confirmFingerprint(String batchId, ConfirmPurgeRequest request) {
        return Fingerprints.sha256("CONFIRM|" + batchId + "|" + request.expectedVersion());
    }

    private PurgeBatchView resolveConcurrentDuplicate(String requestId, String fingerprint) {
        PurgeBatchRow committed = purgeRepository.findBatchByRequestId(requestId)
                .orElseThrow(() -> new IllegalStateException("请求键冲突但未找到已提交记录: " + requestId));
        if (!committed.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_FINGERPRINT_CONFLICT",
                    "请求键 " + requestId + " 已存在且参数指纹不一致");
        }
        return toView(committed, true);
    }

    private Map<String, Object> manifestItem(String evidenceId, PurgeItemStatus result, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evidenceId", evidenceId);
        item.put("result", result.name());
        item.put("reason", reason);
        return item;
    }

    private PurgeBatchView toView(PurgeBatchRow batch, boolean replayed) {
        List<PurgeCandidateView> candidates = purgeRepository.findItems(batch.batchId()).stream()
                .map(item -> new PurgeCandidateView(item.evidenceId(),
                        item.finalStatus() == null ? null : item.finalStatus().name(), item.reason()))
                .toList();
        Map<String, Object> manifest = null;
        if (batch.manifestJson() != null) {
            try {
                manifest = objectMapper.readValue(batch.manifestJson(), new TypeReference<>() {
                });
            } catch (Exception ex) {
                throw new IllegalStateException("清除清单反序列化失败", ex);
            }
        }
        return new PurgeBatchView(batch.batchId(), batch.requestId(), batch.status().name(), batch.version(),
                batch.cutoff(), candidates, manifest, replayed);
    }
}
