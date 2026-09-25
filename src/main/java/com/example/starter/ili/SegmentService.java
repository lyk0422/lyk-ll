package com.example.starter.ili;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 管段登记服务。
 */
@Service
public class SegmentService {

    private final SegmentRepository segmentRepository;
    private final Clock clock;

    public SegmentService(SegmentRepository segmentRepository, Clock clock) {
        this.segmentRepository = segmentRepository;
        this.clock = clock;
    }

    /**
     * 创建管段并登记焊缝台帐；管段编码重复时返回 409。
     */
    @Transactional
    public Dtos.SegmentResponse createSegment(Dtos.CreateSegmentRequest request) {
        if (segmentRepository.findByCode(request.segmentCode()).isPresent()) {
            throw ApiException.conflict("管段编码已存在: " + request.segmentCode());
        }
        long segmentId;
        try {
            segmentId = segmentRepository.insertSegment(request.segmentCode(), request.segmentName(),
                    Instant.now(clock));
            List<Dtos.WeldPayload> welds = request.welds() == null ? List.of() : request.welds();
            for (Dtos.WeldPayload weld : welds) {
                segmentRepository.insertWeld(segmentId, weld.weldNo(), weld.absMileageM());
            }
            return new Dtos.SegmentResponse(segmentId, request.segmentCode(), request.segmentName(),
                    welds.size());
        } catch (DuplicateKeyException e) {
            // 并发创建同编码管段或同编号焊缝
            throw ApiException.conflict("管段编码或焊缝编号与现有数据冲突: " + request.segmentCode());
        }
    }
}
