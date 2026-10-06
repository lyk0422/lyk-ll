package com.example.starter.service;

import com.example.starter.dto.CreateDefectRequest;
import com.example.starter.dto.DefectResponse;
import com.example.starter.error.ApiException;
import com.example.starter.model.DefectLevel;
import com.example.starter.repository.AssessmentRepository;
import com.example.starter.repository.BladeRepository;
import com.example.starter.repository.DefectRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 缺陷登记：结构级缺陷立即限功率；同类缺陷 90 日内恰第 2 次触发根因评估。
 */
@Service
public class DefectService {

    /** 复发统计窗口天数。 */
    public static final int RECURRENCE_WINDOW_DAYS = 90;

    private final DefectRepository defectRepository;
    private final BladeRepository bladeRepository;
    private final AssessmentRepository assessmentRepository;
    private final PowerLimitService powerLimitService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public DefectService(DefectRepository defectRepository,
                         BladeRepository bladeRepository,
                         AssessmentRepository assessmentRepository,
                         PowerLimitService powerLimitService,
                         IdempotencyService idempotencyService,
                         Clock clock) {
        this.defectRepository = defectRepository;
        this.bladeRepository = bladeRepository;
        this.assessmentRepository = assessmentRepository;
        this.powerLimitService = powerLimitService;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 登记缺陷（幂等）。
     */
    public DefectResponse register(CreateDefectRequest request) {
        return idempotencyService.execute("defects", request.requestId(), request,
                DefectResponse.class, () -> doRegister(request));
    }

    private DefectResponse doRegister(CreateDefectRequest request) {
        long bladeId = request.bladeId();
        if (!bladeRepository.exists(bladeId)) {
            throw ApiException.notFound("叶片不存在: " + bladeId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime detectedAt = request.detectedAt() != null ? request.detectedAt() : now;
        long defectId = defectRepository.insert(bladeId, request.bladePosition(),
                request.defectLevel(), request.defectType(), detectedAt, now);

        if (request.defectLevel() == DefectLevel.STRUCTURAL) {
            powerLimitService.sync(bladeId, now);
        }

        Long assessmentId = null;
        LocalDateTime windowStart = detectedAt.minusDays(RECURRENCE_WINDOW_DAYS);
        long count = defectRepository.countSameTypeWithin(
                bladeId, request.defectType(), windowStart, detectedAt);
        if (count == 2
                && assessmentRepository.findActiveByBladeAndType(bladeId, request.defectType()).isEmpty()) {
            assessmentId = assessmentRepository.insert(bladeId, request.defectType(), (int) count,
                    windowStart, detectedAt, now, now);
            powerLimitService.sync(bladeId, now);
        }

        return new DefectResponse(defectId, bladeId, request.bladePosition(),
                request.defectLevel(), request.defectType(), detectedAt,
                powerLimitService.isActive(bladeId), assessmentId != null, assessmentId);
    }
}
