package com.example.starter.trace.service;

import com.example.starter.trace.api.TraceDtos.ProcessingBatchRequest;
import com.example.starter.trace.api.TraceDtos.ProcessingBatchResponse;
import com.example.starter.trace.repo.HarvestRepository;
import com.example.starter.trace.repo.HarvestRepository.HarvestBatchRow;
import com.example.starter.trace.repo.ProcessingRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 加工批：仅可引用已放行采收批，引用与放行判定在同一事务内校验。
 */
@Service
public class ProcessingService {

    private final HarvestRepository harvestRepository;
    private final ProcessingRepository processingRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public ProcessingService(HarvestRepository harvestRepository, ProcessingRepository processingRepository,
                             IdempotencyService idempotencyService, Clock clock) {
        this.harvestRepository = harvestRepository;
        this.processingRepository = processingRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 创建加工批（幂等）。对采收批加行锁后判定放行状态，冻结 / 未放行批拒绝引用。
     */
    @Transactional
    public ProcessingBatchResponse create(ProcessingBatchRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                request.processNo(), String.valueOf(request.harvestBatchId()));
        return idempotencyService.execute(request.requestId(), fingerprint,
                ProcessingBatchResponse.class, () -> doCreate(request));
    }

    private ProcessingBatchResponse doCreate(ProcessingBatchRequest request) {
        HarvestBatchRow batch = harvestRepository.lockBatchById(request.harvestBatchId())
                .orElseThrow(() -> BusinessException.notFound("采收批不存在: " + request.harvestBatchId()));
        if (!"RELEASED".equals(batch.status())) {
            throw BusinessException.unprocessable(
                    "采收批未放行，不得进入加工: " + request.harvestBatchId() + "，当前状态 " + batch.status());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Long id;
        try {
            id = processingRepository.insertProcessingBatch(request.processNo(),
                    request.harvestBatchId(), request.requestId(), now);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("加工批号已存在: " + request.processNo());
        }
        return new ProcessingBatchResponse(id, request.processNo(), request.harvestBatchId(), now);
    }
}
