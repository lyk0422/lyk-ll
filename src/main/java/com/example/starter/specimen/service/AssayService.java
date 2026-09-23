package com.example.starter.specimen.service;

import com.example.starter.specimen.error.ApiException;
import com.example.starter.specimen.model.AliquotRow;
import com.example.starter.specimen.model.AliquotStatus;
import com.example.starter.specimen.model.AssayRow;
import com.example.starter.specimen.model.AssayStatus;
import com.example.starter.specimen.repo.AliquotRepository;
import com.example.starter.specimen.repo.AssayRepository;
import com.example.starter.specimen.web.dto.AssayCreateRequest;
import com.example.starter.specimen.web.dto.AssayResponse;
import com.example.starter.specimen.web.dto.QuarantineResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 检测服务：申请占用（幂等、原子）、开始、取消、结果回传与污染隔离。
 */
@Service
public class AssayService {

    private final AssayRepository assays;
    private final AliquotRepository aliquots;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public AssayService(AssayRepository assays, AliquotRepository aliquots,
                        IdempotencyService idempotency, Clock clock) {
        this.assays = assays;
        this.aliquots = aliquots;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 申请占用：分装从 AVAILABLE 原子转为 RESERVED；同键同参重放，同键异参 409。
     */
    @Transactional
    public AssayResponse create(AssayCreateRequest request) {
        String fingerprint = Fingerprints.of("ASSAY_CREATE", List.of(
                request.aliquotId(),
                String.valueOf(request.expectedVersion()),
                request.reservedVolumeUl().toPlainString(),
                request.project(),
                request.custodian()));
        return idempotency.execute(request.requestId(), "ASSAY_CREATE", fingerprint,
                AssayResponse.class, () -> doCreate(request));
    }

    private AssayResponse doCreate(AssayCreateRequest request) {
        AliquotRow aliquot = aliquots.findById(request.aliquotId())
                .orElseThrow(() -> ApiException.notFound("ALIQUOT_NOT_FOUND",
                        "分装不存在: " + request.aliquotId()));
        BigDecimal reserved = Volumes.normalize(request.reservedVolumeUl(), "reservedVolumeUl");
        if (aliquot.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "分装版本不匹配: 期望 " + request.expectedVersion() + "，实际 " + aliquot.version());
        }
        if (reserved.compareTo(aliquot.volumeUl()) > 0) {
            throw ApiException.conflict("VOLUME_OVERDRAW",
                    "占用量 " + reserved.toPlainString() + " 超过分装体积 "
                            + aliquot.volumeUl().toPlainString());
        }
        if (aliquot.status() != AliquotStatus.AVAILABLE) {
            throw ApiException.conflict("ALIQUOT_NOT_AVAILABLE",
                    "分装当前状态为 " + aliquot.status() + "，不能占用");
        }
        int updated = aliquots.reserve(request.aliquotId(), request.expectedVersion());
        if (updated == 0) {
            throw ApiException.conflict("ALIQUOT_NOT_AVAILABLE", "分装已被并发占用，请刷新后重试");
        }
        Instant now = Instant.now(clock);
        AssayRow row = new AssayRow("ASY-" + UUID.randomUUID(), request.aliquotId(),
                request.project(), reserved, AssayStatus.RESERVED, null,
                request.custodian(), 0, Instant.EPOCH, now);
        assays.insert(row);
        return toResponse(row);
    }

    /**
     * 开始检测：预留转为已消耗，占用量同步转为 CONSUMED。
     */
    @Transactional
    public AssayResponse start(String assayId, long expectedVersion) {
        AssayRow row = assays.findById(assayId)
                .orElseThrow(() -> ApiException.notFound("ASSAY_NOT_FOUND", "检测申请不存在: " + assayId));
        requireVersion(row.version(), expectedVersion);
        if (row.status() != AssayStatus.RESERVED) {
            throw ApiException.conflict("ASSAY_NOT_RESERVED",
                    "申请当前状态为 " + row.status() + "，不能开始");
        }
        if (assays.transition(assayId, AssayStatus.RESERVED, AssayStatus.CONSUMED, expectedVersion) == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "申请已被并发修改，请刷新后重试");
        }
        AliquotRow aliquot = aliquots.findById(row.aliquotId())
                .orElseThrow(() -> new IllegalStateException("分装缺失: " + row.aliquotId()));
        if (aliquots.transition(row.aliquotId(), AliquotStatus.RESERVED,
                AliquotStatus.CONSUMED, aliquot.version()) == 0) {
            throw new IllegalStateException("分装状态与申请不一致: " + row.aliquotId());
        }
        return get(assayId);
    }

    /**
     * 取消：只能释放尚未开始的预留，分装回到 AVAILABLE。
     */
    @Transactional
    public AssayResponse cancel(String assayId, long expectedVersion) {
        AssayRow row = assays.findById(assayId)
                .orElseThrow(() -> ApiException.notFound("ASSAY_NOT_FOUND", "检测申请不存在: " + assayId));
        requireVersion(row.version(), expectedVersion);
        if (row.status() == AssayStatus.CONSUMED) {
            throw ApiException.conflict("ASSAY_ALREADY_STARTED", "检测已开始，占用量不可释放");
        }
        if (row.status() != AssayStatus.RESERVED) {
            throw ApiException.conflict("ASSAY_NOT_RESERVED",
                    "申请当前状态为 " + row.status() + "，不能取消");
        }
        if (assays.transition(assayId, AssayStatus.RESERVED, AssayStatus.CANCELLED, expectedVersion) == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "申请已被并发修改，请刷新后重试");
        }
        AliquotRow aliquot = aliquots.findById(row.aliquotId())
                .orElseThrow(() -> new IllegalStateException("分装缺失: " + row.aliquotId()));
        if (aliquots.transition(row.aliquotId(), AliquotStatus.RESERVED,
                AliquotStatus.AVAILABLE, aliquot.version()) == 0) {
            throw new IllegalStateException("分装状态与申请不一致: " + row.aliquotId());
        }
        return get(assayId);
    }

    /**
     * 结果回传：仅已开始的申请可写结果；取消后到达的结果返回 409，不恢复占用也不写结果。
     */
    @Transactional
    public AssayResponse recordResult(String assayId, long expectedVersion, String result) {
        AssayRow row = assays.findById(assayId)
                .orElseThrow(() -> ApiException.notFound("ASSAY_NOT_FOUND", "检测申请不存在: " + assayId));
        requireVersion(row.version(), expectedVersion);
        if (row.status() == AssayStatus.CANCELLED) {
            throw ApiException.conflict("ASSAY_CANCELLED", "申请已取消，迟到结果不予受理");
        }
        if (row.status() != AssayStatus.CONSUMED) {
            throw ApiException.conflict("ASSAY_NOT_STARTED", "检测未开始，不能写入结果");
        }
        if (assays.recordResult(assayId, expectedVersion, result) == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "申请已被并发修改，请刷新后重试");
        }
        return get(assayId);
    }

    /**
     * 污染隔离：分装原子转为 QUARANTINED，其未开始申请连带取消；已消耗量与历史事件保留。
     */
    @Transactional
    public QuarantineResponse quarantine(String aliquotId, long expectedVersion) {
        AliquotRow aliquot = aliquots.findById(aliquotId)
                .orElseThrow(() -> ApiException.notFound("ALIQUOT_NOT_FOUND", "分装不存在: " + aliquotId));
        if (aliquot.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "分装版本不匹配: 期望 " + expectedVersion + "，实际 " + aliquot.version());
        }
        if (aliquot.status() == AliquotStatus.QUARANTINED) {
            throw ApiException.conflict("ALREADY_QUARANTINED", "分装已处于隔离状态");
        }
        if (aliquot.status() == AliquotStatus.CONSUMED) {
            throw ApiException.conflict("ALREADY_CONSUMED", "分装已消耗，不能隔离");
        }
        if (aliquots.transition(aliquotId, aliquot.status(),
                AliquotStatus.QUARANTINED, expectedVersion) == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "分装已被并发修改，请刷新后重试");
        }
        int cancelled = assays.cancelPendingByAliquot(aliquotId);
        return new QuarantineResponse(aliquotId, AliquotStatus.QUARANTINED, cancelled, expectedVersion + 1);
    }

    /**
     * 查询单个检测申请。
     */
    @Transactional(readOnly = true)
    public AssayResponse get(String assayId) {
        return assays.findById(assayId)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("ASSAY_NOT_FOUND", "检测申请不存在: " + assayId));
    }

    private void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "版本不匹配: 期望 " + expected + "，实际 " + actual);
        }
    }

    private AssayResponse toResponse(AssayRow row) {
        return new AssayResponse(row.assayId(), row.aliquotId(), row.project(),
                row.reservedVolumeUl(), row.status(), row.result(), row.custodian(), row.version());
    }
}
