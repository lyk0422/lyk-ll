package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.example.starter.domain.Complaint;
import com.example.starter.repo.ComplaintRepository;
import com.example.starter.repo.EventRepository;
import com.example.starter.repo.FrequencyGuardRepository;
import com.example.starter.repo.TransmitterRepository;
import com.example.starter.web.dto.ComplaintRequest;
import com.example.starter.web.dto.ComplaintResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.starter.error.ApiException.badRequest;
import static com.example.starter.error.ApiException.conflict;

/**
 * 投诉登记：证据校验、requestId 幂等、自然键去重与 30 分钟三站触发判定。
 */
@Service
public class ComplaintService {

    /** 触发窗口：30 分钟（UTC），恰满计入。 */
    static final long TRIGGER_WINDOW_MINUTES = 30;
    /** 触发所需的不同监测站数量。 */
    static final int TRIGGER_STATION_COUNT = 3;

    private final ComplaintRepository complaintRepo;
    private final EventRepository eventRepo;
    private final FrequencyGuardRepository guardRepo;
    private final TransmitterRepository transmitterRepo;
    private final Clock clock;

    public ComplaintService(ComplaintRepository complaintRepo, EventRepository eventRepo,
                            FrequencyGuardRepository guardRepo, TransmitterRepository transmitterRepo, Clock clock) {
        this.complaintRepo = complaintRepo;
        this.eventRepo = eventRepo;
        this.guardRepo = guardRepo;
        this.transmitterRepo = transmitterRepo;
        this.clock = clock;
    }

    /**
     * 登记投诉。缺少证据不进入正式处置；同 requestId 同参返回首次结果，异参 409。
     */
    @Transactional
    public ComplaintResponse register(ComplaintRequest req) {
        if (req.evidenceId() == null || req.evidenceId().isBlank()) {
            throw badRequest("缺少合成证据标识，不进入正式处置");
        }
        LocalDateTime sampledAt = req.sampledAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        String fingerprint = Fingerprints.of(req.stationCode(), req.frequencyMhz().toPlainString(),
                sampledAt.toString(), req.fieldStrengthDbuv().toPlainString(), req.evidenceId());

        var byRequest = complaintRepo.findByRequestId(req.requestId());
        if (byRequest.isPresent()) {
            return replayOrConflict(byRequest.get(), fingerprint);
        }
        var byNatural = complaintRepo.findByNaturalKey(req.stationCode(), req.frequencyMhz(), sampledAt);
        if (byNatural.isPresent()) {
            return toResponse(byNatural.get(), false, true);
        }

        long complaintId;
        try {
            complaintId = complaintRepo.insert(req.requestId(), req.stationCode(), req.frequencyMhz(), sampledAt,
                    req.fieldStrengthDbuv(), req.evidenceId(), fingerprint, LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            // 并发下同键落库：重读既有记录裁决，保证同参返回首次结果、异参 409
            var again = complaintRepo.findByRequestId(req.requestId());
            if (again.isPresent()) {
                return replayOrConflict(again.get(), fingerprint);
            }
            var natural = complaintRepo.findByNaturalKey(req.stationCode(), req.frequencyMhz(), sampledAt);
            if (natural.isPresent()) {
                return toResponse(natural.get(), false, true);
            }
            throw e;
        }

        Long eventId = tryTriggerEvent(req, sampledAt, complaintId);
        return new ComplaintResponse(complaintId, false, false, eventId != null, eventId);
    }

    /**
     * 在频率守卫锁内判定：30 分钟窗口内满 3 个不同监测站且无未结事件时，恰第 3 个触发干扰事件。
     */
    private Long tryTriggerEvent(ComplaintRequest req, LocalDateTime sampledAt, long complaintId) {
        guardRepo.lock(req.frequencyMhz());
        int stations = complaintRepo.countDistinctStations(req.frequencyMhz(),
                sampledAt.minusMinutes(TRIGGER_WINDOW_MINUTES), sampledAt);
        if (stations < TRIGGER_STATION_COUNT || eventRepo.existsUnresolvedByFrequency(req.frequencyMhz())) {
            return null;
        }
        return transmitterRepo.findByFrequency(req.frequencyMhz())
                .map(tx -> {
                    long eventId = eventRepo.insert(req.frequencyMhz(), tx.id(), complaintId,
                            LocalDateTime.now(clock));
                    complaintRepo.markTriggered(complaintId, eventId);
                    return eventId;
                })
                .orElse(null);
    }

    private ComplaintResponse replayOrConflict(Complaint existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw conflict("同一 requestId 的业务参数不一致，拒绝重复登记");
        }
        return toResponse(existing, true, false);
    }

    private ComplaintResponse toResponse(Complaint c, boolean idempotentReplay, boolean naturalDuplicate) {
        return new ComplaintResponse(c.id(), idempotentReplay, naturalDuplicate,
                c.triggeredEventId() != null, c.triggeredEventId());
    }
}
