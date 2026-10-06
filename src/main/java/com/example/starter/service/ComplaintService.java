package com.example.starter.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.repository.ComplaintRepository;
import com.example.starter.repository.EventRepository;
import com.example.starter.web.dto.ComplaintRequest;
import com.example.starter.web.dto.ComplaintResponse;
import com.example.starter.error.BusinessException;

/**
 * 投诉登记：同监测站+频率+采样时刻只登记一次；
 * 同一频率 30 分钟（UTC，恰满计入）内恰第 3 个不同监测站的有效投诉触发干扰事件。
 * 按 requestId 幂等：同键同参返回首次结果，同键异参 409。
 */
@Service
public class ComplaintService {

    /** 干扰事件触发窗口：30 分钟。 */
    public static final Duration TRIGGER_WINDOW = Duration.ofMinutes(30);

    /** 触发事件所需的不同监测站投诉数。 */
    public static final int TRIGGER_STATION_COUNT = 3;

    private static final String ACTION = "COMPLAINT_REGISTER";

    private static final int STRIPES = 64;

    private final ComplaintRepository complaintRepository;
    private final EventRepository eventRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;
    private final java.util.concurrent.locks.ReentrantLock[] frequencyLocks =
            new java.util.concurrent.locks.ReentrantLock[STRIPES];

    public ComplaintService(ComplaintRepository complaintRepository,
            EventRepository eventRepository,
            IdempotencyService idempotencyService,
            Clock clock) {
        this.complaintRepository = complaintRepository;
        this.eventRepository = eventRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
        for (int i = 0; i < STRIPES; i++) {
            frequencyLocks[i] = new java.util.concurrent.locks.ReentrantLock();
        }
    }

    public ComplaintResponse register(ComplaintRequest request) {
        String fingerprint = fingerprintOf(request);
        // 按频率分段加锁，串行化“登记 + 窗口计数 + 触发事件”，保证恰第 3 个投诉触发且仅触发一次
        var lock = frequencyLocks[Math.floorMod(
                String.valueOf(request.frequencyMhz()).hashCode(), STRIPES)];
        lock.lock();
        try {
            return idempotencyService.run(ACTION, request.requestId(), fingerprint,
                    ComplaintResponse.class, () -> doRegister(request, fingerprint));
        } catch (DuplicateKeyException e) {
            // 并发下同业务键（监测站+频率+采样时刻）冲突：回滚后按已登记处理
            var existing = complaintRepository.findByBizKey(
                    request.stationId(), request.frequencyMhz(), request.sampledAt());
            if (existing.isPresent()) {
                return new ComplaintResponse(existing.get().id(), true, null, false);
            }
            throw e;
        } finally {
            lock.unlock();
        }
    }

    private ComplaintResponse doRegister(ComplaintRequest request, String fingerprint) {
        if (request.evidenceRef() == null || request.evidenceRef().isBlank()) {
            throw BusinessException.unprocessable("缺少合成证据标识，投诉不进入正式处置");
        }
        var existing = complaintRepository.findByBizKey(
                request.stationId(), request.frequencyMhz(), request.sampledAt());
        if (existing.isPresent()) {
            return new ComplaintResponse(existing.get().id(), true, null, false);
        }
        long complaintId = complaintRepository.insert(
                request.requestId(),
                request.stationId(),
                request.frequencyMhz(),
                request.sampledAt(),
                request.fieldStrengthDbuv(),
                request.evidenceRef(),
                fingerprint,
                clock.instant());

        int stations = complaintRepository.countDistinctStationsInWindow(
                request.frequencyMhz(),
                request.sampledAt().minus(TRIGGER_WINDOW),
                request.sampledAt());
        if (stations == TRIGGER_STATION_COUNT) {
            long eventId = eventRepository.insert(request.frequencyMhz(), clock.instant());
            return new ComplaintResponse(complaintId, false, eventId, true);
        }
        return new ComplaintResponse(complaintId, false, null, false);
    }

    private String fingerprintOf(ComplaintRequest request) {
        return Fingerprints.of(
                request.stationId(),
                request.frequencyMhz() == null ? null : request.frequencyMhz().stripTrailingZeros().toPlainString(),
                request.sampledAt() == null ? null : request.sampledAt().toString(),
                request.fieldStrengthDbuv() == null ? null
                        : request.fieldStrengthDbuv().stripTrailingZeros().toPlainString(),
                request.evidenceRef());
    }
}
