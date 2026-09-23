package com.example.starter.service;

import com.example.starter.domain.CustodyEvent;
import com.example.starter.domain.CustodyEventType;
import com.example.starter.domain.Evidence;
import com.example.starter.domain.EvidenceStatus;
import com.example.starter.repo.EvidenceRepository;
import com.example.starter.web.dto.RegisterEvidenceRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 证据登记与查询服务。登记幂等：相同 ID 同摘要可重放，异摘要返回 409。
 */
@Service
public class EvidenceService {

    private final EvidenceRepository evidenceRepository;
    private final TimeService timeService;

    public EvidenceService(EvidenceRepository evidenceRepository, TimeService timeService) {
        this.evidenceRepository = evidenceRepository;
        this.timeService = timeService;
    }

    @Transactional
    public Evidence register(RegisterEvidenceRequest request) {
        return registerResult(request).evidence();
    }

    /**
     * 登记并指示是否首次创建：首次创建 created=true（HTTP 201），同 ID 同摘要重放 created=false（HTTP 200）。
     */
    @Transactional
    public RegisterResult registerResult(RegisterEvidenceRequest request) {
        byte[] content;
        try {
            content = Base64.getDecoder().decode(request.contentBase64());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("contentBase64 is not valid Base64");
        }
        if (content.length == 0) {
            throw ApiException.badRequest("evidence content must not be empty");
        }
        String sha256 = Fingerprints.sha256Hex(content);
        Instant now = timeService.now();

        Evidence existing = evidenceRepository.findById(request.evidenceId()).orElse(null);
        if (existing != null) {
            if (!existing.sha256().equals(sha256)) {
                throw ApiException.conflict(
                        "evidenceId already registered with a different SHA-256 digest");
            }
            return new RegisterResult(existing, false);
        }

        Instant collectedAt = request.collectedAt() != null ? request.collectedAt() : now;
        Evidence evidence = new Evidence(request.evidenceId(), sha256, collectedAt,
                request.expiresAt(), EvidenceStatus.RETAINED, now);
        evidenceRepository.insert(evidence);
        evidenceRepository.appendEvent(request.evidenceId(), CustodyEventType.REGISTERED,
                "sha256=" + sha256, now);
        return new RegisterResult(evidence, true);
    }

    /** 登记结果：证据快照与是否首次创建。 */
    public record RegisterResult(Evidence evidence, boolean created) {
    }

    @Transactional(readOnly = true)
    public Evidence get(String evidenceId) {
        return evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> ApiException.notFound("evidence not found: " + evidenceId));
    }

    @Transactional(readOnly = true)
    public List<CustodyEvent> custodyTrail(String evidenceId) {
        get(evidenceId);
        return evidenceRepository.listEvents(evidenceId);
    }
}
