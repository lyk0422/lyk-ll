package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.domain.Filing;
import com.example.starter.domain.Permit;
import com.example.starter.domain.PermitStatus;
import com.example.starter.dto.FilingRequest;
import com.example.starter.dto.FilingResponse;
import com.example.starter.error.ApiException;
import com.example.starter.repository.FilingRepository;
import com.example.starter.repository.PermitRepository;
import com.example.starter.util.Fingerprints;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 起爆备案服务：仅已批准作业可备案，requestId 幂等。
 */
@Service
public class FilingService {

    private final FilingRepository filings;
    private final PermitRepository permits;
    private final Clock clock;

    public FilingService(FilingRepository filings, PermitRepository permits, Clock businessClock) {
        this.filings = filings;
        this.permits = permits;
        this.clock = businessClock;
    }

    /**
     * 备案起爆：未审批备案一律拒绝；同键同参返回首次结果，同键异参返回 409。
     */
    @Transactional
    public FilingResponse file(FilingRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), String.valueOf(request.permitId()));
        var existing = filings.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            Filing found = existing.get();
            if (!found.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return FilingResponse.from(found);
        }

        Permit permit = permits.findById(request.permitId())
                .orElseThrow(() -> ApiException.notFound("申请单不存在: " + request.permitId()));
        if (permit.status() != PermitStatus.APPROVED) {
            throw ApiException.unprocessable("NOT_APPROVED",
                    "未审批通过的作业禁止备案起爆，当前状态 " + permit.status());
        }

        Instant now = Instant.now(clock);
        int updated = permits.updateStatus(permit.id(), PermitStatus.APPROVED,
                PermitStatus.FILED, null, now);
        if (updated == 0) {
            throw ApiException.conflict("申请单状态已变更，备案失败");
        }
        permits.releaseSlot(permit.id());
        long id;
        try {
            id = filings.insert(request.requestId(), fingerprint, permit.id(), now);
        } catch (DuplicateKeyException e) {
            Filing raced = filings.findByRequestId(request.requestId())
                    .orElseThrow(() -> e);
            if (!raced.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return FilingResponse.from(raced);
        }
        return FilingResponse.from(filings.findByRequestId(request.requestId()).orElseThrow());
    }
}
