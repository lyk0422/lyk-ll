package com.example.starter.service;

import com.example.starter.api.dto.AlarmDtos;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.support.IdempotencySupport;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 偏差告警查询与幂等确认：确认以 requestId 幂等，同键同参返回首次结果，同键异参返回 409。
 */
@Service
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final IdempotencySupport idempotencySupport;
    private final Clock clock;

    public AlarmService(AlarmRepository alarmRepository, IdempotencySupport idempotencySupport, Clock clock) {
        this.alarmRepository = alarmRepository;
        this.idempotencySupport = idempotencySupport;
        this.clock = clock;
    }

    @Transactional
    public AlarmDtos.AlarmResponse acknowledge(AlarmDtos.AckRequest request) {
        record AckPayload(Long alarmId) {
        }
        return idempotencySupport.execute(request.requestId(), "ALARM_ACK",
                new AckPayload(request.alarmId()), AlarmDtos.AlarmResponse.class, 200, () -> {
            AlarmRepository.Alarm alarm = alarmRepository.findById(request.alarmId())
                    .orElseThrow(() -> new NotFoundException("告警不存在：" + request.alarmId()));
            int updated = alarmRepository.acknowledgeIfActive(
                    request.alarmId(), request.requestId(), clock.instant());
            if (updated == 0 && !"ACKNOWLEDGED".equals(alarm.status())) {
                throw new BusinessRuleException("告警当前状态不可确认：" + alarm.status());
            }
            return toResponse(alarmRepository.findById(request.alarmId()).orElseThrow());
        });
    }

    @Transactional(readOnly = true)
    public List<AlarmDtos.AlarmResponse> listActive(String zoneCode) {
        return alarmRepository.findActive(zoneCode).stream().map(this::toResponse).toList();
    }

    private AlarmDtos.AlarmResponse toResponse(AlarmRepository.Alarm a) {
        return new AlarmDtos.AlarmResponse(a.id(), a.zoneCode(), a.strategyCode(),
                a.startSampleTime(), a.triggerTime(), a.readingCount(), a.status(),
                a.ackRequestId(), a.createdAt(), a.acknowledgedAt());
    }
}
