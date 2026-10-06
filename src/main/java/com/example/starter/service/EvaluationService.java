package com.example.starter.service;

import com.example.starter.domain.Alarm;
import com.example.starter.domain.DisposalCommand;
import com.example.starter.domain.ZoneEvent;
import com.example.starter.domain.ZonePool;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.repo.DisposalRepository;
import com.example.starter.repo.EventRepository;
import com.example.starter.repo.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 日评估服务：输出各池稳定时长与告警处置链；纯事件流水推导，可重复复算。
 */
@Service
public class EvaluationService {

    private final ZoneRepository zoneRepository;
    private final EventRepository eventRepository;
    private final AlarmRepository alarmRepository;
    private final DisposalRepository disposalRepository;

    public EvaluationService(ZoneRepository zoneRepository, EventRepository eventRepository,
                             AlarmRepository alarmRepository, DisposalRepository disposalRepository) {
        this.zoneRepository = zoneRepository;
        this.eventRepository = eventRepository;
        this.alarmRepository = alarmRepository;
        this.disposalRepository = disposalRepository;
    }

    /**
     * 单池日评估结果。
     *
     * @param stableSeconds 当日 NORMAL 状态累计时长（秒）
     */
    public record ZoneEvaluation(String zoneCode, String zoneName, long stableSeconds,
                                 List<AlarmChain> alarms) {
    }

    /**
     * 告警及其处置指令链。
     */
    public record AlarmChain(long alarmId, String params, String detail, String status,
                             LocalDateTime createdAt, List<CommandLink> commands) {
    }

    /**
     * 处置指令链节。
     */
    public record CommandLink(long commandId, String commandType, String status,
                              LocalDateTime createdAt, LocalDateTime confirmedAt,
                              LocalDateTime escalatedAt) {
    }

    /**
     * 复算指定日期的全部展示池评估。
     */
    @Transactional(readOnly = true)
    public List<ZoneEvaluation> evaluate(LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<ZoneEvaluation> result = new ArrayList<>();
        for (ZonePool zone : zoneRepository.listAll()) {
            long stableSeconds = computeStableSeconds(zone.id(), dayStart, dayEnd);
            List<AlarmChain> chains = new ArrayList<>();
            for (Alarm alarm : alarmRepository.findByZone(zone.id())) {
                if (alarm.createdAt().isBefore(dayStart) || !alarm.createdAt().isBefore(dayEnd)) {
                    continue;
                }
                List<CommandLink> commands = new ArrayList<>();
                for (DisposalCommand command : disposalRepository.findByZone(zone.id())) {
                    if (command.alarmId() == alarm.id()) {
                        commands.add(new CommandLink(command.id(), command.commandType().name(),
                                command.status().name(), command.createdAt(), command.confirmedAt(),
                                command.escalatedAt()));
                    }
                }
                chains.add(new AlarmChain(alarm.id(), alarm.params(), alarm.detail(),
                        alarm.status().name(), alarm.createdAt(), commands));
            }
            result.add(new ZoneEvaluation(zone.code(), zone.name(), stableSeconds, chains));
        }
        return result;
    }

    /**
     * 依据状态变更事件流水累计当日 NORMAL 时长；区间起点状态取此前最近一次状态事件，缺省 NORMAL。
     */
    private long computeStableSeconds(long zoneId, LocalDateTime dayStart, LocalDateTime dayEnd) {
        ZoneStatus current = eventRepository.findLastStatusEventBefore(zoneId, dayStart)
                .map(event -> ZoneStatus.valueOf(event.toStatus()))
                .orElse(ZoneStatus.NORMAL);
        long stableSeconds = 0;
        LocalDateTime cursor = dayStart;
        for (ZoneEvent event : eventRepository.findStatusEventsBetween(zoneId, dayStart, dayEnd)) {
            if (current == ZoneStatus.NORMAL) {
                stableSeconds += Duration.between(cursor, event.occurredAt()).getSeconds();
            }
            current = ZoneStatus.valueOf(event.toStatus());
            cursor = event.occurredAt();
        }
        if (current == ZoneStatus.NORMAL) {
            stableSeconds += Duration.between(cursor, dayEnd).getSeconds();
        }
        return stableSeconds;
    }
}
