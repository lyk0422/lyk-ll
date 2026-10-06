package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.domain.DirectiveRevision;
import com.example.starter.domain.InterferenceEvent;
import com.example.starter.domain.PowerDirective;
import com.example.starter.repo.DirectiveRepository;
import com.example.starter.repo.EventRepository;
import com.example.starter.repo.TransmitterRepository;
import com.example.starter.web.dto.CloseEventRequest;
import com.example.starter.web.dto.EventView;
import com.example.starter.web.dto.InvestigateRequest;
import com.example.starter.web.dto.InvestigateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.starter.error.ApiException.conflict;
import static com.example.starter.error.ApiException.notFound;
import static com.example.starter.error.ApiException.unprocessable;

/**
 * 干扰事件处置：初查（幂等）、结案与 24 小时督办判定。
 */
@Service
public class EventService {

    /** 初查时限：事件创建后 24 小时（UTC），恰满仍未初查则升级督办。 */
    static final long INVESTIGATE_DEADLINE_HOURS = 24;

    private final EventRepository eventRepo;
    private final DirectiveRepository directiveRepo;
    private final TransmitterRepository transmitterRepo;
    private final Clock clock;

    public EventService(EventRepository eventRepo, DirectiveRepository directiveRepo,
                        TransmitterRepository transmitterRepo, Clock clock) {
        this.eventRepo = eventRepo;
        this.directiveRepo = directiveRepo;
        this.transmitterRepo = transmitterRepo;
        this.clock = clock;
    }

    /**
     * 初查处置。确认干扰时与限功率指令在同一事务落库；按 requestId 幂等。
     */
    @Transactional
    public InvestigateResponse investigate(long eventId, InvestigateRequest req) {
        InterferenceEvent event = eventRepo.findByIdForUpdate(eventId)
                .orElseThrow(() -> notFound("干扰事件不存在: " + eventId));
        String fingerprint = Fingerprints.of(String.valueOf(eventId), String.valueOf(req.confirmed()),
                req.note() == null ? "" : req.note());

        if (event.investigateRequestId() != null) {
            if (!event.investigateFingerprint().equals(fingerprint)) {
                throw conflict("同一 requestId 的初查参数不一致，拒绝重复处置");
            }
            return replayResponse(event);
        }
        if (!InterferenceEvent.STATUS_OPEN.equals(event.status())) {
            throw conflict("事件当前状态不允许初查: " + event.status());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (!req.confirmed()) {
            eventRepo.completeInvestigation(eventId, InterferenceEvent.CONCLUSION_CLEARED,
                    InterferenceEvent.STATUS_CLOSED, null, now, now, req.requestId(), fingerprint);
            return new InvestigateResponse(eventId, InterferenceEvent.STATUS_CLOSED,
                    InterferenceEvent.CONCLUSION_CLEARED, null, null, false);
        }

        int limit = transmitterRepo.findProtectionLimit(event.frequencyMhz())
                .orElseThrow(() -> unprocessable("合成保护表缺少该频率限值: " + event.frequencyMhz()));
        eventRepo.completeInvestigation(eventId, InterferenceEvent.CONCLUSION_CONFIRMED,
                InterferenceEvent.STATUS_CONFIRMED, limit, now, null, req.requestId(), fingerprint);

        // 先锁发射机台账行，再判定执行中指令，防止并发确认各自新建指令（幻读）
        transmitterRepo.lockById(event.transmitterId());
        var executing = directiveRepo.findExecutingForUpdate(event.transmitterId());
        long directiveId;
        int effectiveLimit;
        if (executing.isEmpty()) {
            directiveId = directiveRepo.insert(event.transmitterId(), limit, now);
            directiveRepo.insertRevision(directiveId, eventId, DirectiveRevision.TYPE_ISSUE, limit, now,
                    "初查确认干扰，下达限功率指令");
            effectiveLimit = limit;
        } else {
            PowerDirective current = executing.get();
            directiveId = current.id();
            effectiveLimit = Math.min(current.currentLimitW(), limit);
            if (effectiveLimit < current.currentLimitW()) {
                directiveRepo.updateLimit(directiveId, effectiveLimit);
                directiveRepo.insertRevision(directiveId, eventId, DirectiveRevision.TYPE_TIGHTEN, effectiveLimit,
                        now, "新事件确认干扰，收紧至最严格限值");
            }
        }
        return new InvestigateResponse(eventId, InterferenceEvent.STATUS_CONFIRMED,
                InterferenceEvent.CONCLUSION_CONFIRMED, directiveId, effectiveLimit, false);
    }

    /**
     * 事件结案。仅已确认事件可结案；结案后按剩余未结事件重算最严格限值，不提前恢复满功率。
     */
    @Transactional
    public EventView close(long eventId, CloseEventRequest req) {
        InterferenceEvent event = eventRepo.findById(eventId)
                .orElseThrow(() -> notFound("干扰事件不存在: " + eventId));
        if (!InterferenceEvent.STATUS_CONFIRMED.equals(event.status())) {
            throw conflict("仅初查确认干扰的事件可结案，当前状态: " + event.status());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        eventRepo.close(eventId, now);

        List<Integer> remainingLimits = eventRepo.findConfirmedLimitsByTransmitter(event.transmitterId());
        var executing = directiveRepo.findExecutingForUpdate(event.transmitterId());
        if (executing.isPresent() && !remainingLimits.isEmpty()) {
            int strictest = remainingLimits.stream().mapToInt(Integer::intValue).min().orElseThrow();
            PowerDirective directive = executing.get();
            if (strictest != directive.currentLimitW()) {
                directiveRepo.updateLimit(directive.id(), strictest);
                directiveRepo.insertRevision(directive.id(), eventId, DirectiveRevision.TYPE_RELAX, strictest, now,
                        "事件结案，放宽至剩余未结事件最严格限值（不恢复满功率）");
            }
        }
        return toView(eventRepo.findById(eventId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<EventView> list() {
        return eventRepo.findAll().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public EventView get(long eventId) {
        return toView(eventRepo.findById(eventId).orElseThrow(() -> notFound("干扰事件不存在: " + eventId)));
    }

    /**
     * 督办判定：未初查且当前时刻不早于创建后 24 小时（恰满计入）。
     */
    private boolean isEscalated(InterferenceEvent event) {
        return event.concludedAt() == null
                && !LocalDateTime.now(clock).isBefore(event.createdAt().plusHours(INVESTIGATE_DEADLINE_HOURS));
    }

    private EventView toView(InterferenceEvent e) {
        return new EventView(e.id(), e.frequencyMhz(), e.transmitterId(), e.status(), e.conclusion(),
                e.confirmedLimitW(), e.createdAt(), e.concludedAt(), e.closedAt(), isEscalated(e));
    }

    private InvestigateResponse replayResponse(InterferenceEvent event) {
        if (InterferenceEvent.CONCLUSION_CONFIRMED.equals(event.conclusion())) {
            var revision = directiveRepo.findIssueRevisionByEvent(event.id());
            return new InvestigateResponse(event.id(), event.status(), event.conclusion(),
                    revision.map(DirectiveRevision::directiveId).orElse(null),
                    revision.map(DirectiveRevision::limitWAfter).orElse(null), true);
        }
        return new InvestigateResponse(event.id(), event.status(), event.conclusion(), null, null, true);
    }
}
