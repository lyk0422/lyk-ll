package com.example.starter.service;

import com.example.starter.api.CreateSessionRequest;
import com.example.starter.api.CreateSessionResponse;
import com.example.starter.api.FamilyStatusResponse;
import com.example.starter.api.LogoutRequest;
import com.example.starter.api.RotateRequest;
import com.example.starter.api.RotateResponse;
import com.example.starter.api.SecurityEventResponse;
import com.example.starter.config.RefreshTokenProperties;
import com.example.starter.crypto.RequestFingerprinter;
import com.example.starter.crypto.TokenDeriver;
import com.example.starter.crypto.TokenHasher;
import com.example.starter.domain.FamilyStatus;
import com.example.starter.domain.RefreshTokenEntity;
import com.example.starter.domain.RotationRequestEntity;
import com.example.starter.domain.SecurityEventEntity;
import com.example.starter.domain.SecurityEventType;
import com.example.starter.domain.TokenFamily;
import com.example.starter.domain.TokenStatus;
import com.example.starter.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 刷新令牌轮换与重放遏制核心服务。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>数据库只保存令牌 SHA-256 与父子关系，明文仅在创建/派生当次响应中返回；
 *       安全重试通过 HMAC 确定性派生重新得到同一子令牌明文，无需落库明文。</li>
 *   <li>同族轮换在事务内先对族行加行锁（FOR UPDATE）串行化，
 *       配合 ACTIVE 条件消费，保证一个当前令牌至多被消费一次。</li>
 *   <li>已消费令牌以不同请求键再次出现即判定重放：原子撤销整族与所有后代，
 *       并写入唯一 REPLAY_DETECTED 事件。</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    /** 登出撤销原因。 */
    public static final String REASON_LOGOUT = "LOGOUT";

    private final RefreshTokenRepository repository;
    private final TokenHasher tokenHasher;
    private final TokenDeriver tokenDeriver;
    private final RequestFingerprinter fingerprinter;
    private final RefreshTokenProperties properties;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository repository,
                               TokenHasher tokenHasher,
                               TokenDeriver tokenDeriver,
                               RequestFingerprinter fingerprinter,
                               RefreshTokenProperties properties,
                               Clock clock) {
        this.repository = repository;
        this.tokenHasher = tokenHasher;
        this.tokenDeriver = tokenDeriver;
        this.fingerprinter = fingerprinter;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 创建会话：生成令牌族与首个随机刷新令牌，仅保存其 SHA-256。
     */
    @Transactional
    public CreateSessionResponse createSession(CreateSessionRequest request) {
        Instant now = Instant.now(clock);
        String familyId = newFamilyId();
        String rootPlaintext = tokenDeriver.generateRootToken();

        TokenFamily family = repository.insertFamily(familyId, request.userId(), request.clientId(), now);

        RefreshTokenEntity root = new RefreshTokenEntity(
                null,
                family.familyId(),
                tokenHasher.sha256(rootPlaintext),
                null,
                0,
                TokenStatus.ACTIVE,
                request.clientId(),
                now.plusSeconds(properties.ttlSeconds()),
                null,
                now);
        repository.insertToken(root);

        return new CreateSessionResponse(
                family.familyId(), rootPlaintext, root.expiresAt(), root.createdAt());
    }

    /**
     * 轮换刷新令牌。单事务内完成：族行锁串行化、幂等恢复、条件消费、
     * 确定性子令牌派生、异键重放检测与整族撤销。
     *
     * <p>业务异常不触发回滚：重放检测路径需在抛出 401 的同时提交整族撤销与安全事件；
     * 到期路径需提交 EXPIRED 标记。
     */
    @Transactional(noRollbackFor = ApiException.class)
    public RotateResponse rotate(RotateRequest request) {
        Instant now = Instant.now(clock);
        String presentedHash = tokenHasher.sha256(request.refreshToken());

        RefreshTokenEntity token = repository.findTokenByHash(presentedHash);
        if (token == null) {
            throw new InvalidTokenException("refresh token not recognized");
        }

        // 锁定族行：同族轮换/登出全局串行，避免并发产生多个可用后代
        TokenFamily family = repository.findFamilyForUpdate(token.familyId());
        if (family == null) {
            throw new NotFoundException("token family not found");
        }
        if (family.status() == FamilyStatus.REVOKED) {
            throw new FamilyRevokedException("token family has been revoked");
        }

        // 拿到族锁后重读令牌行，确保看到并发事务提交后的最新状态
        RefreshTokenEntity current = repository.findTokenByHashForUpdate(presentedHash);
        if (current == null) {
            throw new InvalidTokenException("refresh token not recognized");
        }

        String fingerprint = fingerprinter.fingerprint(
                family.familyId(), request.requestId(), request.clientId(), presentedHash);

        // 安全重试：同（族，请求键）已有成功记录
        RotationRequestEntity existing = repository.findRequest(family.familyId(), request.requestId());
        if (existing != null) {
            if (existing.requestFingerprint().equals(fingerprint)) {
                // 同键同令牌同客户端：重新确定性派生出首次子令牌明文返回
                RefreshTokenEntity child = repository.findTokenByHash(existing.childTokenHash());
                String childPlaintext = tokenDeriver.deriveChild(
                        family.familyId(),
                        existing.presentedTokenHash(),
                        existing.requestId(),
                        existing.clientId());
                return new RotateResponse(
                        family.familyId(), childPlaintext,
                        child == null ? null : child.expiresAt(),
                        existing.requestId(), true);
            }
            // 同键但令牌或客户端不同：请求冲突
            throw new RequestConflictException(
                    "requestId was already used with a different token or client");
        }

        // 客户端标识必须与令牌签发时绑定的客户端一致
        if (!current.clientId().equals(request.clientId())) {
            throw new InvalidTokenException("client does not match the token binding");
        }

        // 到期边界：now >= expiresAt 即失效
        if (now.compareTo(current.expiresAt()) >= 0) {
            repository.markTokenExpiredIfActive(presentedHash);
            throw new TokenExpiredException("refresh token has expired");
        }

        switch (current.status()) {
            case ACTIVE -> {
                return consumeAndIssueChild(family, current, request, presentedHash, fingerprint, now);
            }
            case CONSUMED -> {
                // 能走到这里说明请求键与历史记录不同（同键已在上面命中幂等），判定为重放
                handleReplay(family, current, request, presentedHash, fingerprint, now,
                        "consumed token presented with a different requestId");
                throw new InvalidTokenException("replay detected: token family revoked");
            }
            case EXPIRED -> throw new TokenExpiredException("refresh token has expired");
            case REVOKED -> throw new FamilyRevokedException("token family has been revoked");
        }
        throw new IllegalStateException("unexpected token status: " + current.status());
    }

    /**
     * 登出：撤销整个令牌族。对已撤销族以相同参数重复登出为无副作用成功，不新增事件。
     */
    @Transactional
    public void logout(LogoutRequest request) {
        Instant now = Instant.now(clock);
        TokenFamily family = repository.findFamilyForUpdate(request.familyId());
        if (family == null) {
            throw new NotFoundException("token family not found");
        }
        if (!family.clientId().equals(request.clientId())) {
            throw new FamilyRevokedException("client does not match the family binding");
        }
        if (family.status() == FamilyStatus.REVOKED) {
            // 幂等无副作用成功
            return;
        }
        repository.revokeFamily(family.familyId(), REASON_LOGOUT, now);
        repository.revokeAllTokens(family.familyId(), now);
    }

    /**
     * 查询令牌族状态与令牌计数。
     */
    public FamilyStatusResponse familyStatus(String familyId) {
        TokenFamily family = repository.findFamily(familyId);
        if (family == null) {
            throw new NotFoundException("token family not found");
        }
        int total = repository.countTokens(familyId);
        int active = repository.countActiveTokens(familyId);
        return new FamilyStatusResponse(
                family.familyId(),
                family.userId(),
                family.clientId(),
                family.status().name(),
                family.revokeReason(),
                family.createdAt(),
                family.revokedAt(),
                total,
                active);
    }

    /**
     * 查询族下安全事件（按发生顺序）。
     */
    public List<SecurityEventResponse> events(String familyId) {
        TokenFamily family = repository.findFamily(familyId);
        if (family == null) {
            throw new NotFoundException("token family not found");
        }
        return repository.findEventsByFamily(familyId).stream()
                .map(e -> new SecurityEventResponse(
                        e.id(), e.familyId(), e.eventType().name(), e.requestId(),
                        e.requestFingerprint(), e.detail(), e.createdAt()))
                .toList();
    }

    /**
     * 条件消费当前 ACTIVE 令牌并确定性派生子令牌，写入轮换请求记录。
     */
    private RotateResponse consumeAndIssueChild(TokenFamily family,
                                                RefreshTokenEntity token,
                                                RotateRequest request,
                                                String presentedHash,
                                                String fingerprint,
                                                Instant now) {
        int consumed = repository.consumeTokenIfActive(presentedHash, now);
        if (consumed == 0) {
            // 并发竞争失败：令牌已被另一请求消费，按异键重放处理
            RefreshTokenEntity latest = repository.findTokenByHashForUpdate(presentedHash);
            if (latest != null && latest.status() == TokenStatus.CONSUMED) {
                handleReplay(family, latest, request, presentedHash, fingerprint, now,
                        "concurrent consumption lost: token was consumed by another request");
                throw new InvalidTokenException("replay detected: token family revoked");
            }
            throw new FamilyRevokedException("token is no longer consumable");
        }

        String childPlaintext = tokenDeriver.deriveChild(
                family.familyId(), presentedHash, request.requestId(), request.clientId());
        String childHash = tokenHasher.sha256(childPlaintext);
        int sequenceNo = repository.nextSequenceNo(family.familyId());

        RefreshTokenEntity child = new RefreshTokenEntity(
                null,
                family.familyId(),
                childHash,
                presentedHash,
                sequenceNo,
                TokenStatus.ACTIVE,
                request.clientId(),
                now.plusSeconds(properties.ttlSeconds()),
                null,
                now);
        repository.insertToken(child);

        RotationRequestEntity rotationRequest = new RotationRequestEntity(
                null,
                family.familyId(),
                request.requestId(),
                request.clientId(),
                presentedHash,
                fingerprint,
                childHash,
                now);
        repository.insertRequest(rotationRequest);

        return new RotateResponse(
                family.familyId(), childPlaintext, child.expiresAt(), request.requestId(), false);
    }

    /**
     * 重放遏制：原子撤销整族与所有后代，并记录唯一 REPLAY_DETECTED 事件。
     */
    private void handleReplay(TokenFamily family,
                              RefreshTokenEntity token,
                              RotateRequest request,
                              String presentedHash,
                              String fingerprint,
                              Instant now,
                              String detail) {
        repository.revokeFamily(family.familyId(), SecurityEventType.REPLAY_DETECTED.name(), now);
        repository.revokeAllTokens(family.familyId(), now);
        SecurityEventEntity event = new SecurityEventEntity(
                null,
                family.familyId(),
                SecurityEventType.REPLAY_DETECTED,
                request.requestId(),
                presentedHash,
                fingerprint,
                detail,
                now);
        repository.insertSecurityEventIfAbsent(event);
    }

    private String newFamilyId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
