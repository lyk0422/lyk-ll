package com.example.starter.greenhouse.service;

import com.example.starter.greenhouse.domain.AlertStatus;
import com.example.starter.greenhouse.domain.IdempotentBizType;
import com.example.starter.greenhouse.dto.AlertAckRequest;
import com.example.starter.greenhouse.dto.AlertAckResponse;
import com.example.starter.greenhouse.po.DeviationAlert;
import com.example.starter.greenhouse.repo.DeviationAlertRepository;
import com.example.starter.greenhouse.support.Fingerprints;
import com.example.starter.greenhouse.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 偏差告警确认：requestId 幂等，指纹覆盖 requestId 与 alertId；
 * 同键异参 409；对已确认告警再次确认返回幂等成功（同参时）。
 */
@Service
public class AlertService {

    private final DeviationAlertRepository alertRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public AlertService(DeviationAlertRepository alertRepository,
                        IdempotencyService idempotencyService,
                        Clock clock) {
        this.alertRepository = alertRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    public AlertAckResponse acknowledge(AlertAckRequest req) {
        DeviationAlert alert = alertRepository.findById(req.alertId())
                .orElseThrow(() -> ApiException.notFound("告警不存在: " + req.alertId()));
        String fingerprint = Fingerprints.sha256(req.requestId(), "ALERT_ACK", alert.getId());
        IdempotencyService.ActionResult<AlertAckResponse> result = idempotencyService.execute(
                IdempotentBizType.ALERT_ACKNOWLEDGE, req.requestId(), fingerprint,
                () -> new IdempotencyService.ActionResult<>(200, doAcknowledge(alert, req.requestId())),
                AlertAckResponse.class);
        return result.body();
    }

    @Transactional
    public AlertAckResponse doAcknowledge(DeviationAlert alert, String requestId) {
        DeviationAlert locked = alertRepository.findById(alert.getId()).orElseThrow();
        if (locked.getStatus() == AlertStatus.ACKNOWLEDGED) {
            return new AlertAckResponse(locked.getId(), locked.getStatus().name(), locked.getAcknowledgedAt());
        }
        Instant now = Instant.now(clock);
        alertRepository.acknowledge(locked.getId(), requestId, now);
        return new AlertAckResponse(locked.getId(), AlertStatus.ACKNOWLEDGED.name(), now);
    }
}
