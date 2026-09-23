package com.example.starter.token;

import com.example.starter.token.api.CreateSessionRequest;
import com.example.starter.token.api.FamilyStatusResponse;
import com.example.starter.token.api.LogoutRequest;
import com.example.starter.token.api.RotateRequest;
import com.example.starter.token.api.SecurityEventResponse;
import com.example.starter.token.api.TokenIssuedResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 刷新令牌轮换与重放遏制核心服务。
 *
 * <p>轮换在单事务内完成：族行锁串行化同族消费，条件更新保证一个 ACTIVE 令牌只被消费一次；
 * 子令牌由测试密钥、请求键与族上下文 HMAC-SHA256 确定性派生；已消费令牌以异键再次出现即
 * 原子撤销整个令牌族并记录唯一 REPLAY_DETECTED 事件。</p>
 */
@Service
public class TokenService {

    private final RefreshTokenRepository repository;
    private final RefreshTokenProperties properties;
    private final Clock clock;

    public TokenService(RefreshTokenRepository repository,
                        RefreshTokenProperties properties,
                        Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 创建会话：生成令牌族与第一个随机刷新令牌，数据库仅保存散列。
     */
    @Transactional
    public TokenIssuedResponse createSession(CreateSessionRequest request) {
        Instant now = Instant.now(clock);
        String familyId = UUID.randomUUID().toString();
        String plainToken = TokenCrypto.newRootToken();
        String tokenHash = TokenCrypto.sha256(plainToken);
        Instant expiresAt = now.plusSeconds(properties.defaultTtlSeconds());

        repository.insertFamily(new TokenFamily(
                familyId, request.userId(), FamilyStatus.ACTIVE, now, null, null));
        repository.insertToken(new RefreshToken(
                tokenHash, familyId, null, 0, TokenStatus.ACTIVE,
                request.clientId(), now, expiresAt, null));

        return new TokenIssuedResponse(familyId, plainToken, expiresAt, false);
    }

    /**
     * 轮换刷新令牌。
     *
     * <p>业务拒绝异常不触发回滚：异键重放时的全族撤销与到期标记必须随拒绝结果落库；
     * 仅当令牌消费成功但指纹/子令牌写入失败时主动回滚，避免出现消费了却无子嗣的状态。</p>
     */
    @Transactional(noRollbackFor = TokenServiceException.class)
    public TokenIssuedResponse rotate(RotateRequest request) {
        Instant now = Instant.now(clock);
        String tokenHash = TokenCrypto.sha256(request.refreshToken());

        // 请求键指纹最先判定：同键改令牌或客户端一律 409（不泄露令牌是否存在）；
        // 同键同令牌同客户端的安全重试直接返回首次子令牌，即使族后来已被撤销。
        RotationRecord existing = repository.findRotationRecord(request.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.parentHash().equals(tokenHash) || !existing.clientId().equals(request.clientId())) {
                throw new TokenServiceException(409, "请求键已用于不同的令牌或客户端");
            }
            return replayFirstChild(existing);
        }

        RefreshToken current = repository.findToken(tokenHash)
                .orElseThrow(() -> new TokenServiceException(404, "刷新令牌不存在"));

        // 族行锁串行化同族内的所有轮换/登出，消除并发消费竞态。
        TokenFamily family = repository.lockFamily(current.familyId());
        if (family == null) {
            throw new TokenServiceException(404, "令牌族不存在");
        }
        // 锁内重读令牌，获取并发事务已提交的最新状态。
        current = repository.findToken(tokenHash).orElseThrow();

        // 拿到族锁后复查指纹：首次轮换可能恰好在本事务等待期间提交，
        // 此时同键重试必须走安全重试，不能把已消费令牌误判为重放攻击。
        if (existing == null) {
            RotationRecord raced = repository.findRotationRecord(request.requestId()).orElse(null);
            if (raced != null) {
                if (!raced.parentHash().equals(tokenHash) || !raced.clientId().equals(request.clientId())) {
                    throw new TokenServiceException(409, "请求键已用于不同的令牌或客户端");
                }
                return replayFirstChild(raced);
            }
        }

        if (family.status() == FamilyStatus.REVOKED) {
            throw new TokenServiceException(403, "令牌族已撤销，拒绝轮换");
        }

        // 到期端点语义：now >= expiresAt 即失效。
        if (current.status() == TokenStatus.ACTIVE && !now.isBefore(current.expiresAt())) {
            repository.markExpiredIfDue(tokenHash, now);
            throw new TokenServiceException(410, "刷新令牌已到期");
        }

        switch (current.status()) {
            case EXPIRED -> throw new TokenServiceException(410, "刷新令牌已到期");
            case REVOKED -> throw new TokenServiceException(403, "刷新令牌已撤销");
            case CONSUMED -> {
                // 同请求键已在上面指纹分支处理；到达此处即为异键重放。
                revokeFamilyForReplay(family.id(), tokenHash, request.requestId(), now);
                throw new TokenServiceException(403, "检测到重放攻击，令牌族已撤销");
            }
            case ACTIVE -> {
                boolean consumed = repository.consumeIfActive(tokenHash, now);
                if (!consumed) {
                    // 极端并发下状态已被其他事务改变，按最新状态重新判定。
                    RefreshToken refreshed = repository.findToken(tokenHash).orElseThrow();
                    if (!now.isBefore(refreshed.expiresAt())
                            || refreshed.status() == TokenStatus.EXPIRED) {
                        repository.markExpiredIfDue(tokenHash, now);
                        throw new TokenServiceException(410, "刷新令牌已到期");
                    }
                    if (refreshed.status() == TokenStatus.REVOKED) {
                        throw new TokenServiceException(403, "刷新令牌已撤销");
                    }
                    revokeFamilyForReplay(family.id(), tokenHash, request.requestId(), now);
                    throw new TokenServiceException(403, "检测到重放攻击，令牌族已撤销");
                }

                int childSeq = current.seq() + 1;
                String childPlain = TokenCrypto.deriveChildToken(
                        properties.derivationKey(), request.requestId(), family.id(),
                        tokenHash, request.clientId(), childSeq);
                String childHash = TokenCrypto.sha256(childPlain);
                Instant childExpiresAt = now.plusSeconds(properties.defaultTtlSeconds());

                repository.insertToken(new RefreshToken(
                        childHash, family.id(), tokenHash, childSeq, TokenStatus.ACTIVE,
                        request.clientId(), now, childExpiresAt, null));
                boolean recorded = repository.insertRotationRecord(new RotationRecord(
                        request.requestId(), family.id(), tokenHash,
                        request.clientId(), childHash, now));
                if (!recorded) {
                    // 指纹冲突或子令牌碰撞，理论不应发生；父令牌已被消费，必须回滚本次消费。
                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    throw new TokenServiceException(409, "请求键冲突，轮换已中止");
                }

                return new TokenIssuedResponse(family.id(), childPlain, childExpiresAt, false);
            }
        }
        throw new TokenServiceException(500, "未知令牌状态");
    }

    /**
     * 登出：撤销整个令牌族；对已撤销族重复登出是无副作用成功，不新增事件。
     */
    @Transactional
    public void logout(LogoutRequest request) {
        Instant now = Instant.now(clock);
        String tokenHash = TokenCrypto.sha256(request.refreshToken());

        RefreshToken current = repository.findToken(tokenHash)
                .orElseThrow(() -> new TokenServiceException(404, "刷新令牌不存在"));
        TokenFamily family = repository.lockFamily(current.familyId());
        if (family == null) {
            throw new TokenServiceException(404, "令牌族不存在");
        }
        if (family.status() == FamilyStatus.REVOKED) {
            // 终态幂等：无副作用成功。
            return;
        }

        repository.markFamilyRevoked(family.id(), SecurityEventType.LOGOUT.name(), now);
        repository.revokeActiveTokensInFamily(family.id());
        repository.insertSecurityEventIfAbsent(
                SecurityEventType.LOGOUT, family.id(), tokenHash, null,
                "用户登出，全族撤销", now);
    }

    /**
     * 查询令牌族状态。
     */
    @Transactional(readOnly = true)
    public FamilyStatusResponse familyStatus(String familyId) {
        TokenFamily family = repository.findFamily(familyId)
                .orElseThrow(() -> new TokenServiceException(404, "令牌族不存在"));
        return new FamilyStatusResponse(
                family.id(),
                family.userId(),
                family.status().name(),
                family.createdAt(),
                family.revokedAt(),
                family.revokeReason(),
                repository.countTokens(familyId));
    }

    /**
     * 查询令牌族的安全事件列表。
     */
    @Transactional(readOnly = true)
    public List<SecurityEventResponse> events(String familyId) {
        repository.findFamily(familyId)
                .orElseThrow(() -> new TokenServiceException(404, "令牌族不存在"));
        return repository.findEvents(familyId);
    }

    /**
     * 异键重放处置：原子撤销全族（含所有后代）并记录唯一 REPLAY_DETECTED 事件。
     */
    private void revokeFamilyForReplay(String familyId, String tokenHash,
                                       String requestId, Instant now) {
        repository.markFamilyRevoked(familyId, SecurityEventType.REPLAY_DETECTED.name(), now);
        repository.revokeActiveTokensInFamily(familyId);
        repository.insertSecurityEventIfAbsent(
                SecurityEventType.REPLAY_DETECTED, familyId, tokenHash, requestId,
                "已消费令牌以不同请求键再次出现，判定为重放攻击", now);
    }

    /**
     * 安全重试：按首次轮换的上下文确定性重新派生子令牌明文返回，不执行任何新的状态变更。
     */
    private TokenIssuedResponse replayFirstChild(RotationRecord existing) {
        RefreshToken child = repository.findToken(existing.childHash())
                .orElseThrow(() -> new TokenServiceException(410, "首次轮换结果不可用"));
        String childPlain = TokenCrypto.deriveChildToken(
                properties.derivationKey(), existing.requestId(), existing.familyId(),
                existing.parentHash(), existing.clientId(), child.seq());
        return new TokenIssuedResponse(existing.familyId(), childPlain, child.expiresAt(), true);
    }
}
