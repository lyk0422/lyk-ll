package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.api.CreateSessionRequest;
import com.example.starter.api.CreateSessionResponse;
import com.example.starter.api.FamilyStatusResponse;
import com.example.starter.api.LogoutRequest;
import com.example.starter.api.RotateRequest;
import com.example.starter.api.RotateResponse;
import com.example.starter.api.SecurityEventResponse;
import com.example.starter.domain.RefreshTokenEntity;
import com.example.starter.domain.TokenStatus;
import com.example.starter.repository.RefreshTokenRepository;
import com.example.starter.testsupport.MutableClock;
import com.example.starter.testsupport.TestClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 刷新令牌核心服务的 H2 事务测试：覆盖公开主流程、失败分支与重放遏制状态机。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfig.class})
class RefreshTokenServiceH2Test {

    private static final String USER = "user-synthetic-1";
    private static final String CLIENT = "client-synthetic-1";

    @Autowired
    private RefreshTokenService service;
    @Autowired
    private RefreshTokenRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM security_event");
        jdbcTemplate.update("DELETE FROM rotation_request");
        jdbcTemplate.update("DELETE FROM refresh_token");
        jdbcTemplate.update("DELETE FROM token_family");
        clock.setInstant(TestClockConfig.INITIAL);
    }

    private CreateSessionResponse newSession() {
        return service.createSession(new CreateSessionRequest(USER, CLIENT));
    }

    @Test
    void createSessionStoresOnlyHashNeverPlaintext() {
        CreateSessionResponse session = newSession();

        assertThat(session.refreshToken()).isNotBlank();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM refresh_token WHERE family_id = ?", session.familyId());
        // 保存的是 64 位十六进制 SHA-256，而非明文
        assertThat((String) row.get("token_hash"))
                .hasSize(64)
                .isNotEqualTo(session.refreshToken());
        // 全表任何列都不得出现明文
        List<Map<String, Object>> all = jdbcTemplate.queryForList("SELECT * FROM refresh_token");
        all.forEach(r -> r.values().forEach(value ->
                assertThat(String.valueOf(value)).doesNotContain(session.refreshToken())));
    }

    @Test
    void rotateConsumesParentAndIssuesSingleDeterministicChild() {
        CreateSessionResponse session = newSession();

        RotateResponse rotated = service.rotate(
                new RotateRequest("req-1", session.refreshToken(), CLIENT));

        assertThat(rotated.familyId()).isEqualTo(session.familyId());
        assertThat(rotated.refreshToken()).isNotEqualTo(session.refreshToken());
        assertThat(rotated.replayed()).isFalse();
        assertThat(rotated.expiresAt())
                .isEqualTo(TestClockConfig.INITIAL.plus(Duration.ofHours(1)));

        List<RefreshTokenEntity> tokens = repository.findTokensByFamily(session.familyId());
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).status()).isEqualTo(TokenStatus.CONSUMED);
        assertThat(tokens.get(0).consumedAt()).isEqualTo(TestClockConfig.INITIAL);
        assertThat(tokens.get(1).status()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(tokens.get(1).parentHash()).isEqualTo(tokens.get(0).tokenHash());
        assertThat(tokens.get(1).sequenceNo()).isEqualTo(1);
    }

    @Test
    void safeRetrySameRequestKeyAndTokenReturnsFirstChild() {
        CreateSessionResponse session = newSession();
        RotateResponse first = service.rotate(
                new RotateRequest("req-1", session.refreshToken(), CLIENT));

        RotateResponse retry = service.rotate(
                new RotateRequest("req-1", session.refreshToken(), CLIENT));

        assertThat(retry.refreshToken()).isEqualTo(first.refreshToken());
        assertThat(retry.replayed()).isTrue();
        // 未新增令牌、未新增请求记录
        assertThat(repository.findTokensByFamily(session.familyId())).hasSize(2);
        Integer requestCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rotation_request WHERE family_id = ?",
                Integer.class, session.familyId());
        assertThat(requestCount).isEqualTo(1);
    }

    @Test
    void sameRequestKeyWithDifferentTokenReturnsConflict() {
        CreateSessionResponse session = newSession();
        RotateResponse child = service.rotate(
                new RotateRequest("req-1", session.refreshToken(), CLIENT));

        // 同键 req-1 但携带不同的当前令牌（子令牌）→ 409，且子令牌不被消费
        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-1", child.refreshToken(), CLIENT)))
                .isInstanceOf(RequestConflictException.class);

        List<RefreshTokenEntity> tokens = repository.findTokensByFamily(session.familyId());
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(1).status()).isEqualTo(TokenStatus.ACTIVE);
    }

    @Test
    void sameRequestKeyWithDifferentClientReturnsConflict() {
        CreateSessionResponse session = newSession();
        service.rotate(new RotateRequest("req-1", session.refreshToken(), CLIENT));

        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-1", session.refreshToken(), "other-client")))
                .isInstanceOf(RequestConflictException.class);
    }

    @Test
    void replayWithDifferentRequestKeyRevokesWholeFamilyAndRecordsUniqueEvent() {
        CreateSessionResponse session = newSession();
        RotateResponse child = service.rotate(
                new RotateRequest("req-1", session.refreshToken(), CLIENT));

        // 已消费的父令牌以不同请求键再次出现 → 重放
        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-attacker", session.refreshToken(), CLIENT)))
                .isInstanceOf(InvalidTokenException.class);

        FamilyStatusResponse status = service.familyStatus(session.familyId());
        assertThat(status.status()).isEqualTo("REVOKED");
        assertThat(status.revokeReason()).isEqualTo("REPLAY_DETECTED");
        assertThat(status.activeTokenCount()).isZero();

        // 子令牌（后代）也被撤销，无可用后代
        List<RefreshTokenEntity> tokens = repository.findTokensByFamily(session.familyId());
        assertThat(tokens).extracting(RefreshTokenEntity::status)
                .containsOnly(TokenStatus.CONSUMED, TokenStatus.REVOKED);
        assertThat(repository.findTokenByHash(
                repository.findTokensByFamily(session.familyId()).get(1).tokenHash()).status())
                .isEqualTo(TokenStatus.REVOKED);

        List<SecurityEventResponse> events = service.events(session.familyId());
        assertThat(events).hasSize(1);
        SecurityEventResponse event = events.get(0);
        assertThat(event.eventType()).isEqualTo("REPLAY_DETECTED");
        assertThat(event.requestId()).isEqualTo("req-attacker");
        assertThat(event.requestFingerprint()).hasSize(64);

        // 再次重放不新增事件（唯一约束 + 族已撤销）
        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-attacker-2", session.refreshToken(), CLIENT)))
                .isInstanceOf(FamilyRevokedException.class);
        assertThat(service.events(session.familyId())).hasSize(1);

        // 撤销后子令牌也无法轮换
        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-9", child.refreshToken(), CLIENT)))
                .isInstanceOf(FamilyRevokedException.class);
    }

    @Test
    void revokedFamilyRejectsEveryToken() {
        CreateSessionResponse session = newSession();
        RotateResponse child = service.rotate(
                new RotateRequest("req-1", session.refreshToken(), CLIENT));

        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-bad", session.refreshToken(), CLIENT)))
                .isInstanceOf(InvalidTokenException.class);

        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-10", child.refreshToken(), CLIENT)))
                .isInstanceOf(FamilyRevokedException.class);
    }

    @Test
    void expirationBoundaryIsGreaterThanOrEqual() {
        CreateSessionResponse session = newSession();
        Instant expiresAt = session.expiresAt();

        // now == expiresAt - 1ms：仍可用
        clock.setInstant(expiresAt.minusMillis(1));
        RotateResponse child = service.rotate(
                new RotateRequest("req-before", session.refreshToken(), CLIENT));
        assertThat(child.refreshToken()).isNotBlank();

        // 新子令牌到期边界再验证：now == expiresAt 失效
        clock.setInstant(child.expiresAt());
        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-equal", child.refreshToken(), CLIENT)))
                .isInstanceOf(TokenExpiredException.class);

        // 令牌被标记为 EXPIRED；之后再用仍是到期错误，且不会重放撤销
        assertThat(repository.findTokensByFamily(session.familyId()).get(1).status())
                .isEqualTo(TokenStatus.EXPIRED);
        clock.setInstant(child.expiresAt().plusSeconds(60));
        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-after", child.refreshToken(), CLIENT)))
                .isInstanceOf(TokenExpiredException.class);
        assertThat(service.events(session.familyId())).isEmpty();
    }

    @Test
    void logoutRevokesFamilyAndIsIdempotentWithoutNewEvents() {
        CreateSessionResponse session = newSession();
        RotateResponse child = service.rotate(
                new RotateRequest("req-1", session.refreshToken(), CLIENT));

        service.logout(new LogoutRequest(session.familyId(), CLIENT));

        FamilyStatusResponse status = service.familyStatus(session.familyId());
        assertThat(status.status()).isEqualTo("REVOKED");
        assertThat(status.revokeReason()).isEqualTo("LOGOUT");
        assertThat(status.activeTokenCount()).isZero();
        assertThat(service.events(session.familyId())).isEmpty();

        // 同参重复登出：无副作用成功，不新增事件
        service.logout(new LogoutRequest(session.familyId(), CLIENT));
        assertThat(service.events(session.familyId())).isEmpty();

        // 登出后任何令牌轮换被拒绝
        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-2", child.refreshToken(), CLIENT)))
                .isInstanceOf(FamilyRevokedException.class);
    }

    @Test
    void unknownTokenAndFamilyAreRejected() {
        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-x", "totally-unknown-token", CLIENT)))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.familyStatus("unknown-family"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.logout(new LogoutRequest("unknown-family", CLIENT)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void wrongClientIsRejected() {
        CreateSessionResponse session = newSession();
        assertThatThrownBy(() -> service.rotate(
                new RotateRequest("req-1", session.refreshToken(), "wrong-client")))
                .isInstanceOf(InvalidTokenException.class);
    }
}
