package com.example.starter.tailings.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.idempotency.IdempotencyService;
import com.example.starter.tailings.repo.EventRepository;
import com.example.starter.tailings.repo.PlanRepository;
import com.example.starter.tailings.repo.PondRepository;

/**
 * 降雨预警与险情报送。险情报送与泄流方案共用同一处置链守卫，
 * 响应中返回当前生效方案ID以核对唯一处置链。
 */
@Service
public class WarningService {

    private final EventRepository eventRepository;
    private final PondRepository pondRepository;
    private final PlanRepository planRepository;
    private final IdempotencyService idempotencyService;

    public WarningService(EventRepository eventRepository, PondRepository pondRepository,
                          PlanRepository planRepository, IdempotencyService idempotencyService) {
        this.eventRepository = eventRepository;
        this.pondRepository = pondRepository;
        this.planRepository = planRepository;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 降雨预警结果。
     */
    public record WarningResult(long warningId, String requestId, long pondId) {
    }

    /**
     * 险情报送结果。activePlanId 为当前生效泄流方案ID，无则为 null。
     */
    public record DangerReportResult(long reportId, String requestId, long pondId, Long activePlanId) {
    }

    @Transactional
    public WarningResult warn(String requestId, long pondId, BigDecimal rainfallMm, LocalDateTime warnTime) {
        String canonical = FloodRoutingService.canonical(
                "pondId=" + pondId, "rainfallMm=" + rainfallMm, "warnTime=" + warnTime);
        return idempotencyService.execute(requestId, canonical, WarningResult.class, () -> {
            pondRepository.findPond(pondId)
                    .orElseThrow(() -> BusinessException.notFound("尾矿库不存在: " + pondId));
            long id = eventRepository.insertWarning(requestId, pondId, rainfallMm, warnTime);
            return new WarningResult(id, requestId, pondId);
        });
    }

    @Transactional
    public DangerReportResult reportDanger(String requestId, long pondId, String description) {
        String canonical = FloodRoutingService.canonical(
                "pondId=" + pondId, "description=" + description);
        return idempotencyService.execute(requestId, canonical, DangerReportResult.class, () -> {
            pondRepository.findPond(pondId)
                    .orElseThrow(() -> BusinessException.notFound("尾矿库不存在: " + pondId));
            long id = eventRepository.insertDangerReport(requestId, pondId, description);
            Long activePlanId = planRepository.findActivePlanId(pondId).orElse(null);
            return new DangerReportResult(id, requestId, pondId, activePlanId);
        });
    }
}
