package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.example.starter.api.Requests.SegmentRegisterRequest;
import com.example.starter.api.Responses.SegmentResponse;
import com.example.starter.domain.SegmentRow;
import com.example.starter.repo.SegmentRepository;

/**
 * 路段建档。
 */
@Service
public class SegmentService {

    private final SegmentRepository segmentRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public SegmentService(SegmentRepository segmentRepository, IdempotencyService idempotency,
                          Clock clock) {
        this.segmentRepository = segmentRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public ServiceResult register(SegmentRegisterRequest request) {
        return idempotency.execute("segment.register", request.requestId(), request,
                () -> doRegister(request));
    }

    private ServiceResult doRegister(SegmentRegisterRequest request) {
        if (segmentRepository.findByCode(request.code()).isPresent()) {
            throw new BusinessException(409, "SEGMENT_EXISTS", "路段编码已存在: " + request.code());
        }
        long id = segmentRepository.insert(request.code(), request.name(), LocalDateTime.now(clock));
        return new ServiceResult(200, new SegmentResponse(id, request.code(), request.name()));
    }

    public SegmentRow requireByCode(String code) {
        return segmentRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(404, "SEGMENT_NOT_FOUND", "路段不存在: " + code));
    }
}
