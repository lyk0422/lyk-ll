package com.example.starter.service;

import com.example.starter.domain.Alarm;
import com.example.starter.domain.DisposalCommand;
import com.example.starter.domain.EventType;
import com.example.starter.domain.Thresholds;
import com.example.starter.domain.ZonePool;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.repo.DisposalRepository;
import com.example.starter.repo.EventRepository;
import com.example.starter.repo.RetestRepository;
import com.example.starter.repo.ZoneRepository;
import com.example.starter.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 恢复服务：复测合格方可恢复，状态变更与复测记录在同一事务内完成。
 */
@Service
public class RecoveryService {

    private final ZoneRepository zoneRepository;
    private final RetestRepository retestRepository;
    private final AlarmRepository alarmRepository;
    private final DisposalRepository disposalRepository;
    private final EventRepository eventRepository;

    public RecoveryService(ZoneRepository zoneRepository, RetestRepository retestRepository,
                           AlarmRepository alarmRepository, DisposalRepository disposalRepository,
                           EventRepository eventRepository) {
        this.zoneRepository = zoneRepository;
        this.retestRepository = retestRepository;
        this.alarmRepository = alarmRepository;
        this.disposalRepository = disposalRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 恢复结果。
     *
     * @param qualified 复测是否合格（恰等限值视为合格）
     * @param recovered 是否已恢复正常展示
     */
    public record RecoveryResult(long retestId, boolean qualified, boolean recovered,
                                 ZoneStatus zoneStatus) {
    }

    /**
     * 复测恢复：复测记录与状态变更同事务提交；不合格仅留复测记录，不变更状态。
     */
    @Transactional
    public RecoveryResult recover(String zoneCode, String requestId, BigDecimal dissolvedOxygen,
                                  BigDecimal ammoniaNitrogen, BigDecimal temperature,
                                  LocalDateTime testedAt) {
        ZonePool zone = zoneRepository.findByCode(zoneCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "展示池不存在: " + zoneCode));
        boolean qualified = Thresholds.isQualified(dissolvedOxygen, ammoniaNitrogen, temperature);
        long retestId = retestRepository.insert(zone.id(), dissolvedOxygen, ammoniaNitrogen, temperature,
                qualified, testedAt, requestId);
        boolean recovered = false;
        ZoneStatus status = zone.status();
        if (qualified && status != ZoneStatus.NORMAL) {
            for (Alarm alarm : alarmRepository.findUnresolved(zone.id())) {
                alarmRepository.resolve(alarm.id(), testedAt);
            }
            for (DisposalCommand command : disposalRepository.findActiveByZone(zone.id())) {
                disposalRepository.complete(command.id());
            }
            eventRepository.insert(zone.id(), EventType.RECOVERY, status.name(), ZoneStatus.NORMAL.name(),
                    "复测合格恢复展示 retest#" + retestId, testedAt);
            zoneRepository.updateState(new ZonePool(zone.id(), zone.code(), zone.name(), ZoneStatus.NORMAL,
                    0, zone.sensorState(), zone.lastHeartbeatAt(), zone.missingSince(), testedAt));
            status = ZoneStatus.NORMAL;
            recovered = true;
        }
        return new RecoveryResult(retestId, qualified, recovered, status);
    }
}
