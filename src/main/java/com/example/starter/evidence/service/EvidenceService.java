package com.example.starter.evidence.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.evidence.api.dto.CustodyEventView;
import com.example.starter.evidence.api.dto.EvidenceView;
import com.example.starter.evidence.api.dto.RegisterEvidenceRequest;
import com.example.starter.evidence.domain.CustodyEventType;
import com.example.starter.evidence.error.ApiException;
import com.example.starter.evidence.repo.CustodyEventRepository;
import com.example.starter.evidence.repo.CustodyEventRow;
import com.example.starter.evidence.repo.EvidenceRepository;
import com.example.starter.evidence.repo.EvidenceRow;
import com.example.starter.evidence.repo.RegistryRepository;

/**
 * 证据登记与查询服务。
 */
@Service
public class EvidenceService {

    private final EvidenceRepository evidenceRepository;
    private final CustodyEventRepository custodyEventRepository;
    private final RegistryRepository registryRepository;
    private final Clock clock;

    public EvidenceService(EvidenceRepository evidenceRepository,
            CustodyEventRepository custodyEventRepository,
            RegistryRepository registryRepository,
            Clock clock) {
        this.evidenceRepository = evidenceRepository;
        this.custodyEventRepository = custodyEventRepository;
        this.registryRepository = registryRepository;
        this.clock = clock;
    }

    /**
     * 登记证据；相同 ID 同摘要幂等重放，异摘要返回 409。
     */
    @Transactional
    public EvidenceView register(RegisterEvidenceRequest request) {
        String digest = request.sha256().toLowerCase(Locale.ROOT);
        var existing = evidenceRepository.findById(request.evidenceId());
        if (existing.isPresent()) {
            EvidenceRow row = existing.get();
            if (!row.sha256().equals(digest)) {
                throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_DIGEST_CONFLICT",
                        "证据 " + request.evidenceId() + " 已存在且摘要不一致");
            }
            return toView(row, true);
        }
        Instant now = Instant.now(clock);
        evidenceRepository.insert(request.evidenceId(), digest, request.collectedAt(), request.expiresAt(), now);
        custodyEventRepository.append(request.evidenceId(), CustodyEventType.REGISTERED, "证据登记", now);
        registryRepository.bump();
        return toView(evidenceRepository.findById(request.evidenceId()).orElseThrow(), false);
    }

    /**
     * 查询证据状态。
     */
    @Transactional(readOnly = true)
    public EvidenceView get(String evidenceId) {
        return toView(requireEvidence(evidenceId), false);
    }

    /**
     * 查询证据的不可变保管事件序列。
     */
    @Transactional(readOnly = true)
    public List<CustodyEventView> custodyEvents(String evidenceId) {
        requireEvidence(evidenceId);
        return custodyEventRepository.findByEvidenceId(evidenceId).stream()
                .map(this::toEventView)
                .toList();
    }

    private EvidenceRow requireEvidence(String evidenceId) {
        return evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND",
                        "证据 " + evidenceId + " 不存在"));
    }

    private EvidenceView toView(EvidenceRow row, boolean replayed) {
        return new EvidenceView(row.evidenceId(), row.sha256(), row.collectedAt(), row.expiresAt(),
                row.status().name(), row.version(), replayed);
    }

    private CustodyEventView toEventView(CustodyEventRow row) {
        return new CustodyEventView(row.id(), row.eventType().name(), row.detail(), row.occurredAt());
    }
}
