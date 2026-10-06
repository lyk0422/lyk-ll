package com.example.starter.segment;

import java.time.Clock;
import java.time.LocalDateTime;

import com.example.starter.common.ApiException;
import com.example.starter.common.Fingerprints;
import com.example.starter.common.IdempotencyService;
import com.example.starter.segment.SegmentDtos.CreateSegmentRequest;
import com.example.starter.segment.SegmentDtos.SegmentResponse;
import org.springframework.stereotype.Service;

/**
 * 路段建档服务。
 */
@Service
public class SegmentService {

    private final SegmentRepository segmentRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public SegmentService(SegmentRepository segmentRepository, IdempotencyService idempotencyService, Clock clock) {
        this.segmentRepository = segmentRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 幂等建档：同键同参返回首次结果，同键异参 409；路段名全局唯一。
     */
    public SegmentResponse create(CreateSegmentRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), request.name());
        return idempotencyService.execute(request.requestId(), fingerprint, SegmentResponse.class, () -> {
            if (segmentRepository.existsByName(request.name())) {
                throw ApiException.conflict("路段名称已存在：" + request.name());
            }
            long id = segmentRepository.insert(request.name(), LocalDateTime.now(clock));
            return new SegmentResponse(id, request.name());
        });
    }
}
