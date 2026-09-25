package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.domain.Evaluation;
import com.example.starter.domain.Permit;
import com.example.starter.domain.PermitStatus;
import com.example.starter.dto.EvaluationRequest;
import com.example.starter.dto.EvaluationResponse;
import com.example.starter.error.ApiException;
import com.example.starter.repository.EvaluationRepository;
import com.example.starter.repository.PermitRepository;
import com.example.starter.repository.ReadingRepository;
import com.example.starter.repository.ZoneRepository;
import com.example.starter.util.Fingerprints;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 效果评估服务：振动回传齐全后方可提交，封档为终态，重复封档幂等成功。
 */
@Service
public class EvaluationService {

    private final EvaluationRepository evaluations;
    private final PermitRepository permits;
    private final ZoneRepository zones;
    private final ReadingRepository readings;
    private final Clock clock;

    public EvaluationService(EvaluationRepository evaluations, PermitRepository permits,
            ZoneRepository zones, ReadingRepository readings, Clock businessClock) {
        this.evaluations = evaluations;
        this.permits = permits;
        this.zones = zones;
        this.readings = readings;
        this.clock = businessClock;
    }

    /**
     * 提交评估并封档。
     */
    @Transactional
    public EvaluationResponse seal(long permitId, EvaluationRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), String.valueOf(permitId),
                request.conclusion());
        var existing = evaluations.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            Evaluation found = existing.get();
            if (!found.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return EvaluationResponse.from(found);
        }

        Permit permit = permits.findById(permitId)
                .orElseThrow(() -> ApiException.notFound("申请单不存在: " + permitId));
        // 重复封档：返回幂等成功
        var sealed = evaluations.findByPermitId(permitId);
        if (permit.status() == PermitStatus.SEALED && sealed.isPresent()) {
            return EvaluationResponse.from(sealed.get());
        }
        if (permit.status() != PermitStatus.FILED) {
            throw ApiException.unprocessable("ILLEGAL_STATE",
                    "仅已起爆备案的作业可评估，当前状态 " + permit.status());
        }

        List<String> required = zones.findPointCodes(permit.zoneId());
        Set<String> received = new HashSet<>(readings.findPointCodesByPermit(permitId));
        List<String> missing = required.stream().filter(p -> !received.contains(p)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("READINGS_INCOMPLETE",
                    "振动回传未齐全，缺少测点 " + String.join(",", missing));
        }

        Instant now = Instant.now(clock);
        int updated = permits.updateStatus(permitId, PermitStatus.FILED,
                PermitStatus.SEALED, null, now);
        if (updated == 0) {
            // 并发封档：另一事务已完成，返回幂等成功
            return EvaluationResponse.from(evaluations.findByPermitId(permitId).orElseThrow());
        }
        try {
            evaluations.insert(request.requestId(), fingerprint, permitId,
                    request.conclusion(), now);
        } catch (DuplicateKeyException e) {
            Evaluation raced = evaluations.findByRequestId(request.requestId())
                    .orElseThrow(() -> e);
            if (!raced.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return EvaluationResponse.from(raced);
        }
        return EvaluationResponse.from(evaluations.findByPermitId(permitId).orElseThrow());
    }
}
