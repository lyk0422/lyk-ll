package com.example.starter.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.EventRow;
import com.example.starter.domain.EventStatus;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.error.BusinessException;
import com.example.starter.repository.EventRepository;
import com.example.starter.repository.ProtectionLimitRepository;
import com.example.starter.repository.TransmitterRepository;
import com.example.starter.web.dto.CloseEventRequest;
import com.example.starter.web.dto.EscalationScanResponse;
import com.example.starter.web.dto.EventResponse;
import com.example.starter.web.dto.ReviewRequest;
import com.example.starter.web.dto.ReviewResponse;

/**
 * 干扰事件处置：初查（确认干扰时同事务下达限功率指令）、结案、24 小时督办升级。
 * 初查按 requestId 幂等。
 */
@Service
public class EventService {

    /** 初查时限：事件创建后 24 小时（UTC），恰满仍未初查则升级督办。 */
    public static final Duration REVIEW_DEADLINE = Duration.ofHours(24);

    private static final String ACTION = "EVENT_REVIEW";

    private final EventRepository eventRepository;
    private final TransmitterRepository transmitterRepository;
    private final ProtectionLimitRepository protectionLimitRepository;
    private final DirectiveService directiveService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public EventService(EventRepository eventRepository,
            TransmitterRepository transmitterRepository,
            ProtectionLimitRepository protectionLimitRepository,
            DirectiveService directiveService,
            IdempotencyService idempotencyService,
            Clock clock) {
        this.eventRepository = eventRepository;
        this.transmitterRepository = transmitterRepository;
        this.protectionLimitRepository = protectionLimitRepository;
        this.directiveService = directiveService;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    public ReviewResponse review(long eventId, ReviewRequest request) {
        String fingerprint = Fingerprints.of(
                String.valueOf(eventId), request.reviewerId(), request.conclusion().name());
        return idempotencyService.run(ACTION, request.requestId(), fingerprint,
                ReviewResponse.class, () -> doReview(eventId, request));
    }

    /**
     * 初查：事件状态与新限功率指令在同一事务内落库；任一失败整体回滚。
     */
    @Transactional
    public ReviewResponse doReview(long eventId, ReviewRequest request) {
        EventRow event = eventRepository.findById(eventId)
                .orElseThrow(() -> BusinessException.notFound("干扰事件不存在: " + eventId));
        if (event.status() != EventStatus.OPEN) {
            throw BusinessException.unprocessable("事件已初查，当前状态: " + event.status());
        }
        if (request.conclusion() == ReviewConclusion.NOT_INTERFERENCE) {
            eventRepository.applyReview(eventId, EventStatus.REJECTED, request.reviewerId(),
                    request.conclusion().name(), clock.instant());
            return new ReviewResponse(eventId, EventStatus.REJECTED.name(), List.of());
        }
        // 确认干扰：限值必须来自合成保护表，否则整体回滚、事件保持 OPEN
        protectionLimitRepository.findLimitByFrequency(event.frequencyMhz())
                .orElseThrow(() -> BusinessException.unprocessable(
                        "合成保护表缺少频率 " + event.frequencyMhz() + " MHz 的限值，无法下达限功率指令"));
        eventRepository.applyReview(eventId, EventStatus.CONFIRMED, request.reviewerId(),
                request.conclusion().name(), clock.instant());
        List<Long> executingIds = new ArrayList<>();
        for (var transmitter : transmitterRepository.findByFrequency(event.frequencyMhz())) {
            directiveService.reconcile(transmitter.id())
                    .ifPresent(directive -> executingIds.add(directive.id()));
        }
        return new ReviewResponse(eventId, EventStatus.CONFIRMED.name(), List.copyOf(executingIds));
    }

    /**
     * 结案：仅 CONFIRMED 事件可结案；结案后按剩余未结事件重算最严格限值，
     * 不因关闭其中一件就提前恢复满功率。
     */
    @Transactional
    public EventResponse close(long eventId, CloseEventRequest request) {
        EventRow event = eventRepository.findById(eventId)
                .orElseThrow(() -> BusinessException.notFound("干扰事件不存在: " + eventId));
        if (event.status() != EventStatus.CONFIRMED) {
            throw BusinessException.unprocessable("仅 CONFIRMED 状态的事件可结案，当前状态: " + event.status());
        }
        eventRepository.applyClose(eventId, request.closedBy(), clock.instant());
        for (var transmitter : transmitterRepository.findByFrequency(event.frequencyMhz())) {
            directiveService.reconcile(transmitter.id());
        }
        return toResponse(eventRepository.findById(eventId).orElseThrow());
    }

    /**
     * 督办扫描：创建满 24 小时（UTC，恰满计入）仍未初查的 OPEN 事件升级为督办。
     */
    @Transactional
    public EscalationScanResponse escalationScan() {
        var now = clock.instant();
        List<Long> ids = eventRepository.escalateOverdue(now.minus(REVIEW_DEADLINE), now);
        return new EscalationScanResponse(ids);
    }

    public EventResponse get(long eventId) {
        return eventRepository.findById(eventId)
                .map(EventService::toResponse)
                .orElseThrow(() -> BusinessException.notFound("干扰事件不存在: " + eventId));
    }

    static EventResponse toResponse(EventRow row) {
        return new EventResponse(
                row.id(),
                row.frequencyMhz().stripTrailingZeros().toPlainString(),
                row.status().name(),
                row.escalated(),
                row.escalatedAt(),
                row.createdAt(),
                row.reviewedAt(),
                row.reviewerId(),
                row.reviewConclusion(),
                row.closedAt(),
                row.closedBy());
    }
}
