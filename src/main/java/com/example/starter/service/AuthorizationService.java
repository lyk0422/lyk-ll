package com.example.starter.service;

import com.example.starter.domain.AuthorizationStatus;
import com.example.starter.domain.Crane;
import com.example.starter.domain.OverlapZone;
import com.example.starter.domain.ZoneAuthorization;
import com.example.starter.repo.AuthorizationRepository;
import com.example.starter.repo.CraneRepository;
import com.example.starter.repo.ZoneRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 重叠区授权服务。规则：
 * <ul>
 *   <li>同一重叠区同一时段（半开区间 [start, end)，端点相接不算冲突）只允许一台塔机获得授权；</li>
 *   <li>吊重超额定载荷即拒绝（422），不落库、不占区域；</li>
 *   <li>按 requestId 幂等：同键同参返回首次结果，同键异参返回 409 且不占用区域；</li>
 *   <li>并发申请通过对重叠区行加锁串行化，同一时段只允许一个成功。</li>
 * </ul>
 */
@Service
public class AuthorizationService {

    private final AuthorizationRepository authRepo;
    private final CraneRepository craneRepo;
    private final ZoneRepository zoneRepo;
    private final Clock clock;

    public AuthorizationService(AuthorizationRepository authRepo, CraneRepository craneRepo,
                                ZoneRepository zoneRepo, Clock clock) {
        this.authRepo = authRepo;
        this.craneRepo = craneRepo;
        this.zoneRepo = zoneRepo;
        this.clock = clock;
    }

    /**
     * 授权申请结果。replayed=true 表示命中幂等重放，返回的是首次创建的授权。
     */
    public record AuthorizationResult(ZoneAuthorization authorization, boolean replayed) {
    }

    /**
     * 释放动作：COMPLETE=作业完成，CANCEL=取消作业。
     */
    public enum ReleaseAction {
        COMPLETE,
        CANCEL
    }

    @Transactional
    public AuthorizationResult request(String zoneId, String requestId, String craneId,
                                       Instant start, Instant end, BigDecimal loadTonnes) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.unprocessable("INVALID_REQUEST", "requestId 不能为空");
        }
        if (start == null || end == null || !start.isBefore(end)) {
            throw ApiException.unprocessable("INVALID_WINDOW", "作业起止时刻非法：要求 start < end（UTC）");
        }
        if (loadTonnes == null || loadTonnes.signum() <= 0) {
            throw ApiException.unprocessable("INVALID_LOAD", "吊重必须为正数（吨）");
        }
        Crane crane = craneRepo.findById(craneId)
                .orElseThrow(() -> ApiException.notFound("塔机不存在: " + craneId));
        OverlapZone zone = zoneRepo.findById(zoneId)
                .orElseThrow(() -> ApiException.notFound("重叠区不存在: " + zoneId));

        String fingerprint = fingerprint(requestId, craneId, zoneId, start, end, loadTonnes);

        // 幂等重放：同键同参返回首次结果；同键异参 409，且不占用区域
        Optional<ZoneAuthorization> existing = authRepo.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        // 吊重超额定载荷即拒绝（不落库）
        if (loadTonnes.compareTo(crane.ratedLoadTonnes()) > 0) {
            throw ApiException.unprocessable("OVERLOAD",
                    "吊重 " + loadTonnes + "t 超过塔机 " + craneId + " 额定载荷 " + crane.ratedLoadTonnes() + "t");
        }

        // 对重叠区行加锁，串行化并发申请与冲突检测
        zoneRepo.lockById(zone.id());
        List<ZoneAuthorization> conflicts = authRepo.findActiveOverlapping(zoneId, start, end);
        if (!conflicts.isEmpty()) {
            ZoneAuthorization first = conflicts.get(0);
            throw ApiException.conflict("ZONE_CONFLICT",
                    "重叠区 " + zoneId + " 在时段 [" + start + ", " + end + ") 已授权塔机 "
                            + first.craneId() + "（授权编号 " + first.id() + "）");
        }

        ZoneAuthorization auth = new ZoneAuthorization(null, requestId, fingerprint, craneId, zoneId,
                start, end, loadTonnes, AuthorizationStatus.ACTIVE, clock.instant(), null);
        try {
            return new AuthorizationResult(authRepo.insert(auth), false);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 已落库：按幂等规则处理
            ZoneAuthorization raced = authRepo.findByRequestId(requestId).orElseThrow(() -> e);
            return replayOrConflict(raced, fingerprint);
        }
    }

    private AuthorizationResult replayOrConflict(ZoneAuthorization existing, String fingerprint) {
        if (existing.fingerprint().equals(fingerprint)) {
            return new AuthorizationResult(existing, true);
        }
        throw ApiException.conflict("IDEMPOTENCY_MISMATCH",
                "requestId " + existing.requestId() + " 已存在且业务参数不一致");
    }

    /**
     * 释放授权（作业完成或取消）。幂等：已释放的授权重复释放返回当前状态。
     * 通过对授权行与重叠区行加锁，与位姿越限判定形成唯一结论。
     */
    @Transactional
    public ZoneAuthorization release(long authorizationId, ReleaseAction action) {
        ZoneAuthorization auth = authRepo.findByIdForUpdate(authorizationId)
                .orElseThrow(() -> ApiException.notFound("授权不存在: " + authorizationId));
        if (auth.status() != AuthorizationStatus.ACTIVE) {
            return auth;
        }
        zoneRepo.lockById(auth.zoneId());
        AuthorizationStatus newStatus = action == ReleaseAction.COMPLETE
                ? AuthorizationStatus.COMPLETED : AuthorizationStatus.CANCELLED;
        Instant releasedAt = clock.instant();
        authRepo.markReleased(auth.id(), newStatus, releasedAt);
        return new ZoneAuthorization(auth.id(), auth.requestId(), auth.fingerprint(), auth.craneId(), auth.zoneId(),
                auth.startTimeUtc(), auth.endTimeUtc(), auth.loadTonnes(), newStatus,
                auth.createdAtUtc(), releasedAt);
    }

    static String fingerprint(String requestId, String craneId, String zoneId,
                              Instant start, Instant end, BigDecimal loadTonnes) {
        String material = String.join("|", requestId, craneId, zoneId,
                start.toString(), end.toString(), loadTonnes.stripTrailingZeros().toPlainString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
