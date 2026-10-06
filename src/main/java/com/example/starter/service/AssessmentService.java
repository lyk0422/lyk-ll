package com.example.starter.service;

import com.example.starter.dto.AssessmentView;
import com.example.starter.dto.CompleteAssessmentRequest;
import com.example.starter.error.ApiException;
import com.example.starter.model.AssessmentRow;
import com.example.starter.repository.AssessmentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 根因评估：评估期间叶片维持限功率，完成后按需撤销。
 */
@Service
public class AssessmentService {

    private final AssessmentRepository assessmentRepository;
    private final PowerLimitService powerLimitService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public AssessmentService(AssessmentRepository assessmentRepository,
                             PowerLimitService powerLimitService,
                             IdempotencyService idempotencyService,
                             Clock clock) {
        this.assessmentRepository = assessmentRepository;
        this.powerLimitService = powerLimitService;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 完成根因评估（幂等）：完成后若叶片无其他限功率事由则撤销限功率。
     */
    public AssessmentView complete(long assessmentId, CompleteAssessmentRequest request) {
        return idempotencyService.execute("assessments:" + assessmentId, request.requestId(),
                request, AssessmentView.class, () -> doComplete(assessmentId));
    }

    private AssessmentView doComplete(long assessmentId) {
        AssessmentRow row = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> ApiException.notFound("根因评估不存在: " + assessmentId));
        if (!"IN_PROGRESS".equals(row.status())) {
            throw ApiException.conflict("根因评估已完成，不得重复完成");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        assessmentRepository.complete(assessmentId, now);
        powerLimitService.sync(row.bladeId(), now);
        AssessmentRow updated = assessmentRepository.findById(assessmentId).orElseThrow();
        return new AssessmentView(updated.id(), updated.bladeId(), updated.defectType(),
                updated.triggerCount(), updated.status(), updated.startedAt(),
                updated.completedAt(), powerLimitService.isActive(updated.bladeId()));
    }
}
