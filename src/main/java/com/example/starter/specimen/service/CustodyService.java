package com.example.starter.specimen.service;

import com.example.starter.specimen.error.ApiException;
import com.example.starter.specimen.model.CustodyEventRow;
import com.example.starter.specimen.model.EntityType;
import com.example.starter.specimen.model.IdempotencyRecordRow;
import com.example.starter.specimen.repo.AssayRepository;
import com.example.starter.specimen.repo.CustodyEventRepository;
import com.example.starter.specimen.repo.SpecimenRepository;
import com.example.starter.specimen.repo.AliquotRepository;
import com.example.starter.specimen.web.dto.CustodyEventResponse;
import com.example.starter.specimen.web.dto.TransferRequest;
import com.example.starter.specimen.web.dto.TransferResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 保管交接服务：校验当前持有人、时间不倒退与版本；同键重复交接重放首次结果且不新增事件。
 */
@Service
public class CustodyService {

    private final SpecimenRepository specimens;
    private final AliquotRepository aliquots;
    private final AssayRepository assays;
    private final CustodyEventRepository events;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public CustodyService(SpecimenRepository specimens, AliquotRepository aliquots,
                          AssayRepository assays, CustodyEventRepository events,
                          IdempotencyService idempotency, Clock clock) {
        this.specimens = specimens;
        this.aliquots = aliquots;
        this.assays = assays;
        this.events = events;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 交接：同键同参返回首次结果（replayed=true，不新增事件），同键异参返回 409。
     */
    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        String fingerprint = Fingerprints.of("TRANSFER", List.of(
                request.entityType().name(),
                request.entityId(),
                request.fromCustodian(),
                request.toCustodian(),
                String.valueOf(request.expectedVersion()),
                request.eventAt().toString()));
        Optional<IdempotencyRecordRow> existing = idempotency.find(request.requestId());
        if (existing.isPresent()) {
            TransferResponse first = idempotency.replay(existing.get(), fingerprint, TransferResponse.class);
            return new TransferResponse(first.entityType(), first.entityId(), first.custodian(),
                    first.version(), first.eventAt(), true);
        }
        return idempotency.execute(request.requestId(), "TRANSFER", fingerprint,
                TransferResponse.class, () -> doTransfer(request));
    }

    private TransferResponse doTransfer(TransferRequest request) {
        int updated = switch (request.entityType()) {
            case SPECIMEN -> {
                var row = specimens.findById(request.entityId())
                        .orElseThrow(() -> ApiException.notFound("SPECIMEN_NOT_FOUND",
                                "样本不存在: " + request.entityId()));
                validate(row.custodian(), row.version(), row.lastEventAt(), request);
                yield specimens.transfer(request.entityId(), request.expectedVersion(),
                        request.fromCustodian(), request.toCustodian(), request.eventAt());
            }
            case ALIQUOT -> {
                var row = aliquots.findById(request.entityId())
                        .orElseThrow(() -> ApiException.notFound("ALIQUOT_NOT_FOUND",
                                "分装不存在: " + request.entityId()));
                validate(row.custodian(), row.version(), row.lastEventAt(), request);
                yield aliquots.transfer(request.entityId(), request.expectedVersion(),
                        request.fromCustodian(), request.toCustodian(), request.eventAt());
            }
            case ASSAY -> {
                var row = assays.findById(request.entityId())
                        .orElseThrow(() -> ApiException.notFound("ASSAY_NOT_FOUND",
                                "检测申请不存在: " + request.entityId()));
                validate(row.custodian(), row.version(), row.lastEventAt(), request);
                yield assays.transfer(request.entityId(), request.expectedVersion(),
                        request.fromCustodian(), request.toCustodian(), request.eventAt());
            }
        };
        if (updated == 0) {
            throw ApiException.conflict("CUSTODY_CONFLICT", "持有人或版本已并发变化，请刷新后重试");
        }
        events.insert(new CustodyEventRow(0, request.entityType(), request.entityId(),
                request.fromCustodian(), request.toCustodian(), request.eventAt(),
                request.requestId(), Instant.now(clock)));
        return new TransferResponse(request.entityType(), request.entityId(), request.toCustodian(),
                request.expectedVersion() + 1, request.eventAt(), false);
    }

    private void validate(String currentCustodian, long currentVersion,
                          Instant lastEventAt, TransferRequest request) {
        if (!currentCustodian.equals(request.fromCustodian())) {
            throw ApiException.conflict("NOT_CURRENT_HOLDER",
                    "交出方 " + request.fromCustodian() + " 不是当前持有人 " + currentCustodian);
        }
        if (currentVersion != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "版本不匹配: 期望 " + request.expectedVersion() + "，实际 " + currentVersion);
        }
        if (request.eventAt().isBefore(lastEventAt)) {
            throw ApiException.conflict("EVENT_TIME_REGRESSION",
                    "交接时间早于上一保管事件时间 " + lastEventAt);
        }
    }

    /**
     * 查询指定实体的保管交接事件，按发生顺序返回。
     */
    @Transactional(readOnly = true)
    public List<CustodyEventResponse> events(EntityType entityType, String entityId) {
        return events.findByEntity(entityType, entityId).stream()
                .map(row -> new CustodyEventResponse(row.id(), row.entityType(), row.entityId(),
                        row.fromCustodian(), row.toCustodian(), row.eventAt(), row.requestId()))
                .toList();
    }
}
