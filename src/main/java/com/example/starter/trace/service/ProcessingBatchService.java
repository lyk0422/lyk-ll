package com.example.starter.trace.service;

import com.example.starter.trace.BatchStatus;
import com.example.starter.trace.BizException;
import com.example.starter.trace.dto.CreateProcessingBatchRequest;
import com.example.starter.trace.dto.ProcessingBatchResponse;
import com.example.starter.trace.idem.IdempotencyService;
import com.example.starter.trace.repo.HarvestBatchRepository;
import com.example.starter.trace.repo.ProcessingBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 加工批次服务：引用与放行判定在同一事务内校验，冻结批不得被引用。
 */
@Service
public class ProcessingBatchService {

    private final ProcessingBatchRepository processingRepository;
    private final HarvestBatchRepository batchRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ProcessingBatchService(ProcessingBatchRepository processingRepository,
                                  HarvestBatchRepository batchRepository,
                                  IdempotencyService idempotency,
                                  Clock clock) {
        this.processingRepository = processingRepository;
        this.batchRepository = batchRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 幂等创建加工批次。同事务内逐个锁定并校验被引用采收批为已放行状态。
     */
    @Transactional
    public ProcessingBatchResponse createProcessingBatch(CreateProcessingBatchRequest request) {
        return idempotency.execute("CREATE_PROCESSING_BATCH", request.requestId(), request,
                ProcessingBatchResponse.class, () -> doCreate(request));
    }

    private ProcessingBatchResponse doCreate(CreateProcessingBatchRequest request) {
        List<Long> harvestBatchIds = List.copyOf(new LinkedHashSet<>(request.harvestBatchIds()));
        for (Long harvestBatchId : harvestBatchIds) {
            var batch = batchRepository.findByIdForUpdate(harvestBatchId)
                    .orElseThrow(() -> BizException.notFound("采收批不存在: " + harvestBatchId));
            if (batch.status() == BatchStatus.FROZEN) {
                throw BizException.conflict("采收批已冻结，不得进入加工: " + harvestBatchId);
            }
            if (batch.status() != BatchStatus.RELEASED) {
                throw BizException.conflict("采收批未放行，不得进入加工: " + harvestBatchId);
            }
        }
        long processingBatchId = processingRepository.insertProcessingBatch(
                request.batchNo(), LocalDateTime.now(clock));
        for (Long harvestBatchId : harvestBatchIds) {
            processingRepository.insertRef(processingBatchId, harvestBatchId);
        }
        return new ProcessingBatchResponse(processingBatchId, request.batchNo(),
                harvestBatchIds, LocalDateTime.now(clock));
    }
}
