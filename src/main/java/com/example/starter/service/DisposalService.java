package com.example.starter.service;

import com.example.starter.domain.CommandStatus;
import com.example.starter.domain.CommandType;
import com.example.starter.domain.DisposalCommand;
import com.example.starter.domain.EventType;
import com.example.starter.domain.SensorState;
import com.example.starter.domain.Thresholds;
import com.example.starter.domain.ZonePool;
import com.example.starter.repo.DisposalRepository;
import com.example.starter.repo.EventRepository;
import com.example.starter.repo.ZoneRepository;
import com.example.starter.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 联动处置服务：紧急状态自动下发指令、回执确认、恰满 5 分钟超时升级。
 */
@Service
public class DisposalService {

    private final DisposalRepository disposalRepository;
    private final ZoneRepository zoneRepository;
    private final EventRepository eventRepository;

    public DisposalService(DisposalRepository disposalRepository, ZoneRepository zoneRepository,
                           EventRepository eventRepository) {
        this.disposalRepository = disposalRepository;
        this.zoneRepository = zoneRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 下发处置指令，回执时限为当前时间起恰 5 分钟。返回指令ID。
     */
    public long dispatch(long zoneId, long alarmId, List<String> violatedParams, LocalDateTime now) {
        CommandType type = Thresholds.commandTypeFor(violatedParams);
        long commandId = disposalRepository.insert(zoneId, alarmId, type, now,
                now.plus(Thresholds.RECEIPT_TIMEOUT));
        eventRepository.insert(zoneId, EventType.COMMAND_ISSUED, null, null,
                "command#" + commandId + " " + type.name() + " alarm#" + alarmId, now);
        return commandId;
    }

    /**
     * 回执确认；恰满 5 分钟（含）后到达的回执视为超时，拒绝并即时升级。
     * 超时分支的升级落库须保留，故业务异常不回滚。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public DisposalCommand confirmReceipt(long commandId, LocalDateTime now) {
        DisposalCommand command = disposalRepository.findById(commandId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "处置指令不存在"));
        if (command.status() != CommandStatus.PENDING) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "指令状态为 " + command.status() + "，不允许确认回执");
        }
        if (!now.isBefore(command.confirmDeadline())) {
            escalate(command, now);
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "回执恰满或超过 5 分钟时限，指令已升级");
        }
        disposalRepository.confirm(commandId, now);
        eventRepository.insert(command.zoneId(), EventType.COMMAND_CONFIRMED, null, null,
                "command#" + commandId, now);
        return disposalRepository.findById(commandId).orElseThrow();
    }

    /**
     * 按展示池编码查询处置指令列表（只读）。
     */
    @Transactional(readOnly = true)
    public List<DisposalCommand> listByZone(String zoneCode) {
        ZonePool zone = zoneRepository.findByCode(zoneCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "展示池不存在: " + zoneCode));
        return disposalRepository.findByZone(zone.id());
    }

    /**
     * 扫描恰满或超过回执时限仍未确认的指令并升级，返回升级条数。
     */
    @Transactional
    public int processTimeouts(LocalDateTime now) {
        int escalated = 0;
        for (DisposalCommand command : disposalRepository.findTimedOut(now)) {
            escalate(command, now);
            escalated++;
        }
        return escalated;
    }

    /**
     * 升级单条指令：原指令标记 ESCALATED，并按升级链下发下一级指令（传感器失联时挂起下发）。
     */
    private void escalate(DisposalCommand command, LocalDateTime now) {
        disposalRepository.escalate(command.id(), now);
        eventRepository.insert(command.zoneId(), EventType.COMMAND_ESCALATED, null, null,
                "command#" + command.id() + " " + command.commandType() + " 回执超时升级", now);
        ZonePool zone = zoneRepository.findById(command.zoneId()).orElseThrow();
        if (zone.sensorState() == SensorState.ONLINE) {
            CommandType next = command.commandType().next();
            long nextId = disposalRepository.insert(command.zoneId(), command.alarmId(), next, now,
                    now.plus(Thresholds.RECEIPT_TIMEOUT));
            eventRepository.insert(command.zoneId(), EventType.COMMAND_ISSUED, null, null,
                    "command#" + nextId + " " + next.name() + " alarm#" + command.alarmId() + " 超时升级下发",
                    now);
        } else {
            eventRepository.insert(command.zoneId(), EventType.COMMAND_ESCALATED, null, null,
                    "传感器失联，升级指令挂起", now);
        }
    }
}
