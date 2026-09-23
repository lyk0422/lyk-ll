package com.example.starter.service;

import com.example.starter.domain.CustodyEventType;
import com.example.starter.domain.Evidence;
import com.example.starter.domain.EvidenceStatus;
import com.example.starter.domain.HoldStatus;
import com.example.starter.domain.LegalHold;
import com.example.starter.repo.EvidenceRepository;
import com.example.starter.repo.HoldRepository;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.web.dto.CreateHoldRequest;
import com.example.starter.web.dto.ReleaseHoldRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 法律保全创建与解除服务。
 *
 * <p>所有创建/解除先锁定保全全局版本序列行，与清除最终确认互斥：
 * 保全先提交则清除救回证据，清除先提交则保全整笔失败。</p>
 */
@Service
public class HoldService {

    private static final String CREATE_KEY_PREFIX = "hold:create:";
    private static final String RELEASE_KEY_PREFIX = "hold:release:";

    private final HoldRepository holdRepository;
    private final EvidenceRepository evidenceRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TimeService timeService;

    public HoldService(HoldRepository holdRepository,
                       EvidenceRepository evidenceRepository,
                       IdempotencyRepository idempotencyRepository,
                       TimeService timeService) {
        this.holdRepository = holdRepository;
        this.evidenceRepository = evidenceRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.timeService = timeService;
    }

    @Transactional
    public LegalHold create(CreateHoldRequest request) {
        String requestKey = CREATE_KEY_PREFIX + request.requestId();
        Set<String> evidenceIds = new TreeSet<>(request.evidenceIds());
        String fingerprint = Fingerprints.sha256Hex(
                "CREATE|" + request.caseId() + "|" + String.join(",", evidenceIds) + "|"
                        + request.expectedVersion());

        // 快速重放路径：同键同参直接返回首次结果。
        LegalHold replay = replay(requestKey, fingerprint);
        if (replay != null) {
            return replay;
        }

        // 与其他保全写操作及清除最终确认串行化。
        holdRepository.lockVersionRow();

        // 双重检查：拿到序列锁后可能已有并发同键请求提交。
        replay = replay(requestKey, fingerprint);
        if (replay != null) {
            return replay;
        }

        long currentVersion = holdRepository.currentVersion();
        if (request.expectedVersion() != currentVersion) {
            throw ApiException.conflict("expected hold version " + request.expectedVersion()
                    + " but current version is " + currentVersion);
        }

        List<Evidence> locked = evidenceRepository.lockByIds(evidenceIds);
        if (locked.size() != evidenceIds.size()) {
            Set<String> found = new TreeSet<>(locked.stream().map(Evidence::evidenceId).toList());
            Set<String> missing = new TreeSet<>(evidenceIds);
            missing.removeAll(found);
            throw ApiException.notFound("evidence not found: " + String.join(",", missing));
        }
        for (Evidence evidence : locked) {
            if (evidence.status() == EvidenceStatus.PURGED) {
                // 清除先提交：保全整笔失败。
                throw ApiException.conflict(
                        "evidence already purged, hold cannot be created: " + evidence.evidenceId());
            }
        }

        Instant now = timeService.now();
        long version = holdRepository.lockAndNextVersion();
        long holdId;
        try {
            holdId = holdRepository.insertHold(request.caseId(), version, request.requestId(), now);
            for (String evidenceId : evidenceIds) {
                holdRepository.insertItem(holdId, evidenceId);
            }
            for (Evidence evidence : locked) {
                if (evidence.status() == EvidenceStatus.MARKED) {
                    // 保全在标记后、最终确认前生效：原子救回为可保留状态。
                    evidenceRepository.rescueIfMarked(evidence.evidenceId());
                    evidenceRepository.appendEvent(evidence.evidenceId(), CustodyEventType.RESCUED,
                            "rescued by hold " + holdId + " case " + request.caseId(), now);
                }
                evidenceRepository.appendEvent(evidence.evidenceId(), CustodyEventType.HELD,
                        "hold " + holdId + " case " + request.caseId(), now);
            }
            idempotencyRepository.insert(requestKey, fingerprint, String.valueOf(holdId), now);
        } catch (DuplicateKeyException ex) {
            // 唯一索引兜底：并发同键异参
            throw ApiException.conflict("requestId already used with different parameters");
        }
        return holdRepository.findById(holdId).orElseThrow();
    }

    @Transactional
    public LegalHold release(ReleaseHoldRequest request) {
        String requestKey = RELEASE_KEY_PREFIX + request.requestId();
        String fingerprint = Fingerprints.sha256Hex(
                "RELEASE|" + request.holdId() + "|" + request.expectedVersion());

        LegalHold replay = replay(requestKey, fingerprint);
        if (replay != null) {
            return replay;
        }

        holdRepository.lockVersionRow();
        replay = replay(requestKey, fingerprint);
        if (replay != null) {
            return replay;
        }

        LegalHold hold = holdRepository.findById(request.holdId())
                .orElseThrow(() -> ApiException.notFound("hold not found: " + request.holdId()));
        if (hold.status() == HoldStatus.RELEASED) {
            throw ApiException.conflict("hold already released: " + request.holdId());
        }
        if (hold.version() != request.expectedVersion()) {
            throw ApiException.conflict("expected hold version " + request.expectedVersion()
                    + " but current version is " + hold.version());
        }

        Instant now = timeService.now();
        holdRepository.lockAndNextVersion();
        int affected = holdRepository.release(request.holdId(), request.expectedVersion(),
                request.requestId(), now);
        if (affected == 0) {
            throw ApiException.conflict("hold release conflict, version changed concurrently");
        }
        for (String evidenceId : hold.evidenceIds()) {
            evidenceRepository.appendEvent(evidenceId, CustodyEventType.RELEASED,
                    "hold " + request.holdId() + " released", now);
        }
        try {
            idempotencyRepository.insert(requestKey, fingerprint, String.valueOf(request.holdId()), now);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("requestId already used with different parameters");
        }
        return holdRepository.findById(request.holdId()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public LegalHold get(long holdId) {
        return holdRepository.findById(holdId)
                .orElseThrow(() -> ApiException.notFound("hold not found: " + holdId));
    }

    /**
     * 同键同参返回首次结果引用；同键异参抛 409；无记录返回 null。
     */
    private LegalHold replay(String requestKey, String fingerprint) {
        return idempotencyRepository.find(requestKey).map(record -> {
            if (!record.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId already used with different parameters");
            }
            return holdRepository.findById(Long.parseLong(record.responseRef())).orElseThrow();
        }).orElse(null);
    }
}
