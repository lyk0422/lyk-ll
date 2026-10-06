package com.example.starter.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.starter.domain.DirectiveRow;
import com.example.starter.error.BusinessException;
import com.example.starter.repository.DirectiveRepository;
import com.example.starter.repository.EventRepository;
import com.example.starter.repository.TransmitterRepository;
import com.example.starter.web.dto.DirectiveResponse;

/**
 * 限功率指令：同一发射机同时刻仅一条 EXECUTING 指令；
 * 多个未结 CONFIRMED 事件并存时采用最严格限值，限值变化以“取代 + 新指令”留痕，轨迹可复算。
 */
@Service
public class DirectiveService {

    private final DirectiveRepository directiveRepository;
    private final EventRepository eventRepository;
    private final TransmitterRepository transmitterRepository;
    private final Clock clock;

    public DirectiveService(DirectiveRepository directiveRepository,
            EventRepository eventRepository,
            TransmitterRepository transmitterRepository,
            Clock clock) {
        this.directiveRepository = directiveRepository;
        this.eventRepository = eventRepository;
        this.transmitterRepository = transmitterRepository;
        this.clock = clock;
    }

    /**
     * 使发射机的执行中指令与当前最严格限值一致；无 CONFIRMED 事件时不做变更
     * （恢复满功率必须走双人确认解除流程）。须在事务内调用。
     *
     * @return 当前执行中指令；无 CONFIRMED 事件且原本无指令时为 empty
     */
    public Optional<DirectiveRow> reconcile(long transmitterId) {
        transmitterRepository.lockById(transmitterId);
        var strictest = eventRepository.findStrictestLimitForTransmitter(transmitterId);
        var current = directiveRepository.findExecutingByTransmitter(transmitterId);
        if (strictest.isEmpty()) {
            return current;
        }
        int limit = strictest.get().limitWatts();
        if (current.isPresent() && current.get().limitWatts() == limit) {
            return current;
        }
        current.ifPresent(row -> directiveRepository.supersede(row.id(), clock.instant()));
        long id = directiveRepository.insertExecuting(
                strictest.get().eventId(), transmitterId, limit, clock.instant());
        return directiveRepository.findById(id);
    }

    /**
     * 执行确认：实际功率恰等限值合法，超过限值拒绝确认。
     */
    public DirectiveResponse confirmExecution(long directiveId, int actualPowerWatts, String confirmedBy) {
        DirectiveRow directive = directiveRepository.findById(directiveId)
                .orElseThrow(() -> BusinessException.notFound("限功率指令不存在: " + directiveId));
        if (directive.status() != com.example.starter.domain.DirectiveStatus.EXECUTING) {
            throw BusinessException.unprocessable("仅执行中的指令可确认执行，当前状态: " + directive.status());
        }
        if (actualPowerWatts > directive.limitWatts()) {
            throw BusinessException.unprocessable(
                    "实际功率 " + actualPowerWatts + "W 超过限值 " + directive.limitWatts() + "W，拒绝确认");
        }
        directiveRepository.confirmExecution(directiveId, actualPowerWatts, confirmedBy, clock.instant());
        return toResponse(directiveRepository.findById(directiveId).orElseThrow());
    }

    public List<DirectiveResponse> listByTransmitter(long transmitterId) {
        return directiveRepository.findByTransmitter(transmitterId).stream()
                .map(DirectiveService::toResponse)
                .toList();
    }

    public static DirectiveResponse toResponse(DirectiveRow row) {
        return new DirectiveResponse(
                row.id(),
                row.eventId(),
                row.transmitterId(),
                row.limitWatts(),
                row.status().name(),
                row.actualPowerWatts(),
                row.confirmedBy(),
                row.confirmedAt(),
                row.createdAt(),
                row.supersededAt(),
                row.releasedAt());
    }
}
