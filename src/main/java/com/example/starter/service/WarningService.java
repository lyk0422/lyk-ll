package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.api.Requests.WarningCancelRequest;
import com.example.starter.api.Requests.WarningPublishRequest;
import com.example.starter.api.Responses.WarningCancelResponse;
import com.example.starter.api.Responses.WarningResponse;
import com.example.starter.domain.WarningRow;
import com.example.starter.repo.FreezeRepository;
import com.example.starter.repo.SegmentRepository;
import com.example.starter.repo.WarningRepository;

/**
 * 大风预警。发布预警与冻结高风险路段在同一事务内生效：
 * 任一冻结写入失败，预警记录一并回滚。解除预警同事务删除对应冻结。
 */
@Service
public class WarningService {

    private final WarningRepository warningRepository;
    private final FreezeRepository freezeRepository;
    private final SegmentRepository segmentRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public WarningService(WarningRepository warningRepository, FreezeRepository freezeRepository,
                          SegmentRepository segmentRepository, IdempotencyService idempotency,
                          Clock clock) {
        this.warningRepository = warningRepository;
        this.freezeRepository = freezeRepository;
        this.segmentRepository = segmentRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public ServiceResult publish(WarningPublishRequest request) {
        return idempotency.execute("warning.publish", request.requestId(), request,
                () -> doPublish(request));
    }

    private ServiceResult doPublish(WarningPublishRequest request) {
        if (warningRepository.findByNo(request.warningNo()).isPresent()) {
            throw new BusinessException(409, "WARNING_EXISTS", "预警编号已存在: " + request.warningNo());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long warningId = warningRepository.insert(request.warningNo(), request.level(), now);
        List<String> frozen = new ArrayList<>();
        for (Long segmentId : segmentRepository.findHighRiskSegmentIds()) {
            // segment_id 唯一约束冲突将抛 DuplicateKeyException，整个事务（含预警）回滚
            freezeRepository.insert(segmentId, warningId, now);
            frozen.add(segmentRepository.findById(segmentId)
                    .orElseThrow(() -> new IllegalStateException("路段缺失: " + segmentId)).code());
        }
        return new ServiceResult(200,
                new WarningResponse(request.warningNo(), request.level(), frozen));
    }

    public ServiceResult cancel(WarningCancelRequest request) {
        return idempotency.execute("warning.cancel", request.requestId(), request,
                () -> doCancel(request));
    }

    private ServiceResult doCancel(WarningCancelRequest request) {
        WarningRow warning = warningRepository.findByNo(request.warningNo())
                .orElseThrow(() -> new BusinessException(404, "WARNING_NOT_FOUND",
                        "预警不存在: " + request.warningNo()));
        if (!"ACTIVE".equals(warning.status())) {
            throw new BusinessException(409, "WARNING_NOT_ACTIVE", "预警非生效状态: " + warning.status());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> lifted = new ArrayList<>();
        for (Long segmentId : freezeRepository.findSegmentIds()) {
            lifted.add(segmentRepository.findById(segmentId)
                    .orElseThrow(() -> new IllegalStateException("路段缺失: " + segmentId)).code());
        }
        freezeRepository.deleteByWarningId(warning.id());
        warningRepository.cancel(warning.id(), now);
        return new ServiceResult(200, new WarningCancelResponse(request.warningNo(), lifted));
    }
}
