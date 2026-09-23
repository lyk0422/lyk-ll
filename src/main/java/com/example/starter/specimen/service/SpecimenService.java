package com.example.starter.specimen.service;

import com.example.starter.specimen.error.ApiException;
import com.example.starter.specimen.model.AliquotRow;
import com.example.starter.specimen.model.AliquotStatus;
import com.example.starter.specimen.model.SpecimenRow;
import com.example.starter.specimen.repo.AliquotRepository;
import com.example.starter.specimen.repo.SpecimenRepository;
import com.example.starter.specimen.web.dto.AliquotRequest;
import com.example.starter.specimen.web.dto.AliquotResponse;
import com.example.starter.specimen.web.dto.IntakeRequest;
import com.example.starter.specimen.web.dto.LedgerResponse;
import com.example.starter.specimen.web.dto.SpecimenResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 样本服务：入库、分装（幂等、守恒、整批回滚）与体积账本查询。
 */
@Service
public class SpecimenService {

    private final SpecimenRepository specimens;
    private final AliquotRepository aliquots;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public SpecimenService(SpecimenRepository specimens, AliquotRepository aliquots,
                           IdempotencyService idempotency, Clock clock) {
        this.specimens = specimens;
        this.aliquots = aliquots;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 入库：specimenId 唯一，重复返回 409。
     */
    @Transactional
    public SpecimenResponse intake(IntakeRequest request) {
        BigDecimal volume = Volumes.normalize(request.volumeUl(), "volumeUl");
        Instant now = Instant.now(clock);
        try {
            specimens.insert(new SpecimenRow(request.specimenId(), volume, volume,
                    request.custodian(), 0, Instant.EPOCH, now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SPECIMEN_EXISTS", "样本已存在: " + request.specimenId());
        }
        return new SpecimenResponse(request.specimenId(), volume, volume, request.custodian(), 0);
    }

    /**
     * 分装：同键同参重放首次结果；任一分装体积非法或总量超额则整批回滚。
     */
    @Transactional
    public AliquotResponse aliquot(String specimenId, AliquotRequest request) {
        String volumesKey = request.volumesUl().stream()
                .map(BigDecimal::toPlainString)
                .collect(Collectors.joining(","));
        String fingerprint = Fingerprints.of("ALIQUOT", List.of(
                specimenId, String.valueOf(request.expectedVersion()), volumesKey));
        return idempotency.execute(request.requestId(), "ALIQUOT", fingerprint,
                AliquotResponse.class, () -> doAliquot(specimenId, request));
    }

    private AliquotResponse doAliquot(String specimenId, AliquotRequest request) {
        SpecimenRow specimen = specimens.findById(specimenId)
                .orElseThrow(() -> ApiException.notFound("SPECIMEN_NOT_FOUND", "样本不存在: " + specimenId));
        List<BigDecimal> volumes = new ArrayList<>(request.volumesUl().size());
        for (BigDecimal raw : request.volumesUl()) {
            volumes.add(Volumes.normalize(raw, "volumesUl"));
        }
        BigDecimal sum = volumes.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (specimen.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "样本版本不匹配: 期望 " + request.expectedVersion() + "，实际 " + specimen.version());
        }
        if (sum.compareTo(specimen.remainingVolumeUl()) > 0) {
            throw ApiException.conflict("VOLUME_OVERDRAW",
                    "分装总量 " + sum.toPlainString() + " 超过剩余量 "
                            + specimen.remainingVolumeUl().toPlainString());
        }
        BigDecimal newRemaining = specimen.remainingVolumeUl().subtract(sum);
        int updated = specimens.deductRemaining(specimenId, request.expectedVersion(), sum, newRemaining);
        if (updated == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "样本已被并发修改，请刷新后重试");
        }
        Instant now = Instant.now(clock);
        List<String> aliquotIds = new ArrayList<>(volumes.size());
        for (BigDecimal volume : volumes) {
            String aliquotId = "ALQ-" + UUID.randomUUID();
            aliquots.insert(new AliquotRow(aliquotId, specimenId, volume, AliquotStatus.AVAILABLE,
                    specimen.custodian(), 0, Instant.EPOCH, now));
            aliquotIds.add(aliquotId);
        }
        return new AliquotResponse(specimenId, aliquotIds, newRemaining, request.expectedVersion() + 1);
    }

    /**
     * 体积账本：剩余量 + 各状态分装量之和必须等于入库总量。
     */
    @Transactional(readOnly = true)
    public LedgerResponse ledger(String specimenId) {
        SpecimenRow specimen = specimens.findById(specimenId)
                .orElseThrow(() -> ApiException.notFound("SPECIMEN_NOT_FOUND", "样本不存在: " + specimenId));
        Map<AliquotStatus, BigDecimal> sums = new EnumMap<>(AliquotStatus.class);
        long count = 0;
        for (AliquotRepository.StatusVolume sv : aliquots.sumByStatus(specimenId)) {
            sums.put(sv.status(), sv.totalVolumeUl());
            count += sv.count();
        }
        BigDecimal available = sums.getOrDefault(AliquotStatus.AVAILABLE, BigDecimal.ZERO);
        BigDecimal reserved = sums.getOrDefault(AliquotStatus.RESERVED, BigDecimal.ZERO);
        BigDecimal consumed = sums.getOrDefault(AliquotStatus.CONSUMED, BigDecimal.ZERO);
        BigDecimal quarantined = sums.getOrDefault(AliquotStatus.QUARANTINED, BigDecimal.ZERO);
        BigDecimal allocated = available.add(reserved).add(consumed).add(quarantined);
        boolean conserved = specimen.remainingVolumeUl().add(allocated)
                .compareTo(specimen.totalVolumeUl()) == 0;
        return new LedgerResponse(specimen.specimenId(), specimen.totalVolumeUl(),
                specimen.remainingVolumeUl(), available, reserved, consumed, quarantined,
                count, conserved);
    }
}
