package com.example.starter.roaming.service;

import com.example.starter.roaming.api.AuthorizeRequest;
import com.example.starter.roaming.api.AuthorizeResponse;
import com.example.starter.roaming.error.ConflictException;
import com.example.starter.roaming.error.NotFoundException;
import com.example.starter.roaming.error.UnprocessableException;
import com.example.starter.roaming.repo.ReferenceDataRepository.PileRow;
import com.example.starter.roaming.repo.ReferenceDataRepository.VehicleRow;
import com.example.starter.roaming.repo.SessionRepository;
import com.example.starter.roaming.repo.SessionRepository.SessionRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 即插即充授权：证书指纹标识车辆；同车同时刻唯一进行中会话；授权有效期 5 分钟。
 */
@Service
public class AuthorizationService {

    /** 授权有效期（分钟）。 */
    public static final Duration AUTH_VALIDITY = Duration.ofMinutes(5);

    private final Clock clock;
    private final SessionRepository sessionRepository;
    private final com.example.starter.roaming.repo.ReferenceDataRepository referenceDataRepository;
    private final IdempotencySupport idempotency;

    public AuthorizationService(Clock clock,
                                SessionRepository sessionRepository,
                                com.example.starter.roaming.repo.ReferenceDataRepository referenceDataRepository,
                                IdempotencySupport idempotency) {
        this.clock = clock;
        this.sessionRepository = sessionRepository;
        this.referenceDataRepository = referenceDataRepository;
        this.idempotency = idempotency;
    }

    /**
     * 即插即充授权。
     * <p>同一车辆同一请求时刻只允许一个会话：有效期内重复请求（即使 requestId 不同）
     * 返回首次授权结果；超过 5 分钟须以新的请求时刻重新授权。</p>
     */
    @Transactional
    public AuthorizeResponse authorize(AuthorizeRequest request) {
        var result = idempotency.execute(
                request.requestId(), "AUTHORIZE", request, 201, AuthorizeResponse.class,
                () -> doAuthorize(request));
        AuthorizeResponse body = result.body();
        // 同 requestId 重放或有效期内重复授权，均返回首次授权结果。
        return new AuthorizeResponse(body.sessionNo(), body.certFingerprint(), body.pileNo(),
                body.status(), body.startTime(), body.expiresAt(),
                body.replayed() || result.replayed());
    }

    private AuthorizeResponse doAuthorize(AuthorizeRequest request) {
        Instant now = Instant.now(clock);
        VehicleRow vehicle = referenceDataRepository.findVehicle(request.certFingerprint())
                .orElseThrow(() -> new NotFoundException("车辆不存在：" + request.certFingerprint()));
        if (!vehicle.enabled()) {
            throw new UnprocessableException("车辆已停用，不允许授权");
        }
        PileRow pile = referenceDataRepository.findPile(request.pileNo())
                .orElseThrow(() -> new NotFoundException("充电桩不存在：" + request.pileNo()));
        if (!pile.enabled()) {
            throw new UnprocessableException("充电桩已停用，不允许授权");
        }
        if (request.requestTime().isAfter(now.plusSeconds(5))) {
            throw new UnprocessableException("请求时刻不能晚于当前时间");
        }

        Instant expiresAt = request.requestTime().plus(AUTH_VALIDITY);
        Optional<SessionRow> existing =
                sessionRepository.findByFingerprintAndTime(request.certFingerprint(), request.requestTime());
        if (existing.isPresent()) {
            return replayWithinValidity(existing.get(), expiresAt);
        }

        SessionRow row = new SessionRow(
                null,
                newSessionNo(request.requestTime()),
                request.certFingerprint(),
                request.pileNo(),
                vehicle.homeOperatorId(),
                pile.operatorId(),
                "AUTHORIZED",
                request.requestTime(),
                null, null, BigDecimal.ZERO, -1, null, now, now);
        try {
            sessionRepository.insert(row);
        } catch (DuplicateKeyException e) {
            // 授权并发：同车同刻唯一会话，竞争失败方返回唯一成功会话的首次授权结果。
            SessionRow winner = sessionRepository
                    .findByFingerprintAndTime(request.certFingerprint(), request.requestTime())
                    .orElseThrow(() -> new ConflictException("同车同刻授权冲突，请重试"));
            return replayWithinValidity(winner, expiresAt);
        }
        return new AuthorizeResponse(row.sessionNo(), request.certFingerprint(), request.pileNo(),
                "AUTHORIZED", request.requestTime(), expiresAt, false);
    }

    private AuthorizeResponse replayWithinValidity(SessionRow session, Instant expiresAt) {
        if (Instant.now(clock).isAfter(expiresAt)) {
            throw new ConflictException("授权已超过 5 分钟有效期，须以新的请求时刻重新授权");
        }
        return new AuthorizeResponse(session.sessionNo(), session.certFingerprint(), session.pileNo(),
                session.status(), session.startTime(), expiresAt, true);
    }

    private String newSessionNo(Instant startTime) {
        return "SESS-" + startTime.toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
