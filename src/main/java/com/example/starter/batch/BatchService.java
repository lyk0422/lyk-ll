package com.example.starter.batch;

import java.time.Clock;
import java.time.LocalDateTime;

import com.example.starter.api.ConflictException;
import com.example.starter.api.Fingerprints;
import com.example.starter.domain.BatchStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产地报备服务：requestId 幂等，并发报备由 request_id 唯一约束保证唯一受理。
 */
@Service
public class BatchService {

    private final BatchRepository batchRepository;
    private final Clock clock;

    public BatchService(BatchRepository batchRepository, Clock clock) {
        this.batchRepository = batchRepository;
        this.clock = clock;
    }

    /**
     * 报备进场批次。同键同参返回首次结果；同键异参返回 409；并发同键由唯一约束兜底。
     */
    @Transactional
    public BatchResponse register(BatchCreateRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), request.origin(),
                request.variety(), request.quantity().toPlainString());
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            long id = batchRepository.insert(request.requestId(), fingerprint, request.origin(),
                    request.variety(), request.quantity(), BatchStatus.REGISTERED, now);
            return BatchResponse.from(batchRepository.findById(id).orElseThrow());
        } catch (DuplicateKeyException e) {
            Batch existing = batchRepository.findByRequestId(request.requestId())
                    .orElseThrow(() -> e);
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new ConflictException("同一 requestId 业务参数不一致，拒绝重放");
            }
            return BatchResponse.from(existing);
        }
    }
}
