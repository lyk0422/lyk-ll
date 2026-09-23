package com.example.starter.evidence.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.evidence.api.dto.CreateHoldRequest;
import com.example.starter.evidence.api.dto.HoldView;
import com.example.starter.evidence.api.dto.ReleaseHoldRequest;
import com.example.starter.evidence.domain.CustodyEventType;
import com.example.starter.evidence.domain.EvidenceStatus;
import com.example.starter.evidence.domain.HoldStatus;
import com.example.starter.evidence.error.ApiException;
import com.example.starter.evidence.repo.CustodyEventRepository;
import com.example.starter.evidence.repo.EvidenceRepository;
import com.example.starter.evidence.repo.EvidenceRow;
import com.example.starter.evidence.repo.HoldRepository;
import com.example.starter.evidence.repo.HoldRow;
import com.example.starter.evidence.repo.PurgeRepository;
import com.example.starter.evidence.repo.RegistryRepository;

/**
 * 法律保全服务：创建、解除与查询。创建时对已标记未确认的证据执行原子救回。
 */
@Service
public class HoldService {

    private final HoldRepository holdRepository;
    private final EvidenceRepository evidenceRepository;
    private final PurgeRepository purgeRepository;
    private final CustodyEventRepository custodyEventRepository;
    private final RegistryRepository registryRepository;
    private final Clock clock;

    public HoldService(HoldRepository holdRepository,
            EvidenceRepository evidenceRepository,
            PurgeRepository purgeRepository,
            CustodyEventRepository custodyEventRepository,
            RegistryRepository registryRepository,
            Clock clock) {
        this.holdRepository = holdRepository;
        this.evidenceRepository = evidenceRepository;
        this.purgeRepository = purgeRepository;
        this.custodyEventRepository = custodyEventRepository;
        this.registryRepository = registryRepository;
        this.clock = clock;
    }

    /**
     * 创建保全单。覆盖已标记证据时将其原子救回为可保留状态；任一证据已清除则整笔失败。
     */
    @Transactional
    public HoldView create(CreateHoldRequest request) {
        String fingerprint = fingerprint(request);
        var evidenceIds = new TreeSet<>(request.evidenceIds());
        // 先按主键序加行锁：同键并发创建在此串行化，后到事务的重放检查可见先提交的结果。
        var locked = evidenceRepository.findAllByIdForUpdate(evidenceIds);
        if (locked.size() != evidenceIds.size()) {
            var found = new TreeSet<String>();
            locked.forEach(row -> found.add(row.evidenceId()));
            var missing = new TreeSet<>(evidenceIds);
            missing.removeAll(found);
            throw new ApiException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND",
                    "证据不存在: " + String.join(",", missing));
        }

        var existing = holdRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            HoldRow row = existing.get();
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

        Instant now = Instant.now(clock);
        for (EvidenceRow row : locked) {
            if (row.status() == EvidenceStatus.PURGED) {
                throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_ALREADY_PURGED",
                        "证据 " + row.evidenceId() + " 已清除，保全整笔失败");
            }
            if (row.status() == EvidenceStatus.MARKED) {
                // 标记后、最终确认前生效的保全：原子救回为可保留状态，并从批次最终结果中排除。
                evidenceRepository.updateStatus(row.evidenceId(), EvidenceStatus.ACTIVE, now);
                purgeRepository.rescuePendingItems(row.evidenceId(), "保全生效救回");
                custodyEventRepository.append(row.evidenceId(), CustodyEventType.RESCUED, "保全生效救回", now);
            }
        }

        String holdId = UUID.randomUUID().toString();
        try {
            holdRepository.insert(holdId, request.requestId(), fingerprint, request.caseId(), now);
        } catch (DuplicateKeyException ex) {
            // 证据集合不相交的同键并发创建：以唯一约束兜底，按已提交记录判定重放或冲突。
            return resolveConcurrentDuplicate(request.requestId(), fingerprint);
        }
        for (String evidenceId : evidenceIds) {
            holdRepository.insertItem(holdId, evidenceId);
            custodyEventRepository.append(evidenceId, CustodyEventType.HOLD_CREATED,
                    "保全单 " + holdId + " 生效，案件 " + request.caseId(), now);
        }
        registryRepository.bump();
        return toView(holdRepository.findById(holdId).orElseThrow(), false);
    }

    /**
     * 解除保全单，需携带匹配的预期版本；同幂等键重放返回当前状态。
     */
    @Transactional
    public HoldView release(String holdId, ReleaseHoldRequest request) {
        HoldRow hold = holdRepository.findByIdForUpdate(holdId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND",
                        "保全单 " + holdId + " 不存在"));
        if (hold.status() == HoldStatus.RELEASED) {
            if (request.requestId().equals(hold.releaseRequestId())) {
                return toView(hold, true);
            }
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_ALREADY_RELEASED",
                    "保全单 " + holdId + " 已解除");
        }
        if (request.expectedVersion() != hold.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_VERSION_CONFLICT",
                    "预期保全单版本 " + request.expectedVersion() + " 与当前版本 " + hold.version() + " 不一致");
        }
        Instant now = Instant.now(clock);
        holdRepository.release(holdId, request.requestId(), now);
        for (String evidenceId : holdRepository.findEvidenceIds(holdId)) {
            custodyEventRepository.append(evidenceId, CustodyEventType.HOLD_RELEASED,
                    "保全单 " + holdId + " 解除", now);
        }
        registryRepository.bump();
        return toView(holdRepository.findById(holdId).orElseThrow(), false);
    }

    /**
     * 查询保全单。
     */
    @Transactional(readOnly = true)
    public HoldView get(String holdId) {
        HoldRow hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND",
                        "保全单 " + holdId + " 不存在"));
        return toView(hold, false);
    }

    private String fingerprint(CreateHoldRequest request) {
        var sorted = new TreeSet<>(request.evidenceIds());
        return Fingerprints.sha256("HOLD|" + request.caseId() + "|" + String.join(",", sorted)
                + "|" + request.expectedVersion());
    }

    private HoldView resolveConcurrentDuplicate(String requestId, String fingerprint) {
        HoldRow committed = holdRepository.findByRequestId(requestId)
                .orElseThrow(() -> new IllegalStateException("请求键冲突但未找到已提交记录: " + requestId));
        if (!committed.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_FINGERPRINT_CONFLICT",
                    "请求键 " + requestId + " 已存在且参数指纹不一致");
        }
        return toView(committed, true);
    }

    private HoldView toView(HoldRow row, boolean replayed) {
        return new HoldView(row.holdId(), row.caseId(), row.status().name(), row.version(),
                holdRepository.findEvidenceIds(row.holdId()), row.createdAt(), row.releasedAt(), replayed);
    }
}
