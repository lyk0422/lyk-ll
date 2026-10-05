package com.example.starter.service;

import com.example.starter.api.dto.AlarmHandlingDtos;
import com.example.starter.domain.HandlingStatus;
import com.example.starter.repo.AlarmHandlingRepository;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.support.IdempotencySupport;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * 告警处置服务：针对 ACTIVE 偏差告警发起临时管控窗口，完结后退出仲裁。
 * 发起与完结均以 requestId 幂等，同键同参返回首次结果，同键异参 409。
 */
@Service
public class AlarmHandlingService {

    private final AlarmHandlingRepository handlingRepository;
    private final AlarmRepository alarmRepository;
    private final IdempotencySupport idempotencySupport;
    private final Clock clock;

    public AlarmHandlingService(AlarmHandlingRepository handlingRepository, AlarmRepository alarmRepository,
                                IdempotencySupport idempotencySupport, Clock clock) {
        this.handlingRepository = handlingRepository;
        this.alarmRepository = alarmRepository;
        this.idempotencySupport = idempotencySupport;
        this.clock = clock;
    }

    @Transactional
    public AlarmHandlingDtos.HandlingResponse start(AlarmHandlingDtos.StartRequest request) {
        record StartPayload(String handlingCode, Long alarmId, BigDecimal tempMin, BigDecimal tempMax,
                            BigDecimal humidityMin, BigDecimal humidityMax) {
        }
        return idempotencySupport.execute(request.requestId(), "HANDLING_START",
                new StartPayload(request.handlingCode(), request.alarmId(), request.tempMin(),
                        request.tempMax(), request.humidityMin(), request.humidityMax()),
                AlarmHandlingDtos.HandlingResponse.class, 201,
                () -> doStart(request));
    }

    private AlarmHandlingDtos.HandlingResponse doStart(AlarmHandlingDtos.StartRequest request) {
        AlarmRepository.Alarm alarm = alarmRepository.findById(request.alarmId())
                .orElseThrow(() -> new NotFoundException("告警不存在：" + request.alarmId()));
        if (!"ACTIVE".equals(alarm.status())) {
            throw new BusinessRuleException("仅 ACTIVE 告警可发起处置，当前状态：" + alarm.status());
        }
        if (request.tempMin().compareTo(request.tempMax()) > 0) {
            throw new BusinessRuleException("处置温度窗口下限不得大于上限");
        }
        if (request.humidityMin().compareTo(request.humidityMax()) > 0) {
            throw new BusinessRuleException("处置湿度窗口下限不得大于上限");
        }
        AlarmHandlingRepository.Handling handling = new AlarmHandlingRepository.Handling(
                null, request.handlingCode(), alarm.id(), alarm.zoneCode(),
                HandlingStatus.IN_PROGRESS.name(),
                request.tempMin().setScale(2), request.tempMax().setScale(2),
                request.humidityMin().setScale(2), request.humidityMax().setScale(2),
                request.requestId(), null, clock.instant(), null);
        handlingRepository.insert(handling);
        return toResponse(handlingRepository.findByCode(request.handlingCode()).orElseThrow());
    }

    @Transactional
    public AlarmHandlingDtos.HandlingResponse complete(String handlingCode,
                                                       AlarmHandlingDtos.CompleteRequest request) {
        record CompletePayload(String handlingCode) {
        }
        return idempotencySupport.execute(request.requestId(), "HANDLING_COMPLETE",
                new CompletePayload(handlingCode),
                AlarmHandlingDtos.HandlingResponse.class, 200,
                () -> doComplete(handlingCode, request.requestId()));
    }

    private AlarmHandlingDtos.HandlingResponse doComplete(String handlingCode, String requestId) {
        AlarmHandlingRepository.Handling handling = handlingRepository.findByCode(handlingCode)
                .orElseThrow(() -> new NotFoundException("处置不存在：" + handlingCode));
        int updated = handlingRepository.completeIfInProgress(handlingCode, requestId, clock.instant());
        if (updated == 0 && !HandlingStatus.COMPLETED.name().equals(handling.status())) {
            throw new BusinessRuleException("处置当前状态不可完结：" + handling.status());
        }
        return toResponse(handlingRepository.findByCode(handlingCode).orElseThrow());
    }

    @Transactional(readOnly = true)
    public AlarmHandlingDtos.HandlingResponse get(String handlingCode) {
        return handlingRepository.findByCode(handlingCode)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("处置不存在：" + handlingCode));
    }

    private AlarmHandlingDtos.HandlingResponse toResponse(AlarmHandlingRepository.Handling h) {
        return new AlarmHandlingDtos.HandlingResponse(h.handlingCode(), h.alarmId(), h.zoneCode(),
                h.status(), h.tempMin(), h.tempMax(), h.humidityMin(), h.humidityMax(),
                h.requestId(), h.completeRequestId(), h.createdAt(), h.completedAt());
    }
}
