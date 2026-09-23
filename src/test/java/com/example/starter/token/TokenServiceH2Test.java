package com.example.starter.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.starter.token.api.CreateSessionRequest;
import com.example.starter.token.api.FamilyStatusResponse;
import com.example.starter.token.api.LogoutRequest;
import com.example.starter.token.api.RotateRequest;
import com.example.starter.token.api.SecurityEventResponse;
import com.example.starter.token.api.TokenIssuedResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 基于 H2（MySQL 兼容模式）真实事务的刷新令牌服务测试；
 * 覆盖明文不落库、安全重试、请求指纹冲突、异键重放、全族撤销、
 * 到期边界、终态登出幂等与并发消费竞争。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TokenServiceH2Test.TestClockConfig.class)
class TokenServiceH2Test {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.startAt(T0);
        }
    }

    @Autowired
    TokenService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        clock.setInstant(T0);
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of("security_event", "rotation_record", "refresh_token", "token_family")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    private TokenIssuedResponse newSession(String user, String client) {
        return service.createSession(new CreateSessionRequest(user, client));
    }

    private RotateRequest rotateReq(String requestId, String token, String client) {
        return new RotateRequest(requestId, token, client);
    }

    private TokenServiceException expectFailure(Runnable action, int status) {
        Throwable thrown = catchThrowable(action::run);
        assertThat(thrown).isInstanceOf(TokenServiceException.class);
        TokenServiceException ex = (TokenServiceException) thrown;
        assertThat(ex.status()).isEqualTo(status);
        return ex;
    }

    private Map<String, Object> tokenRow(String tokenHash) {
        return jdbc.queryForMap("SELECT * FROM refresh_token WHERE token_hash = ?", tokenHash);
    }

    private String familyStatus(String familyId) {
        return jdbc.queryForObject("SELECT status FROM token_family WHERE id = ?", String.class, familyId);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    /** 断言任何表的任何列都不存在明文令牌。 */
    private void assertPlaintextNeverPersisted(String... plainTokens) {
        List<Map<String, Object>> allRows = new ArrayList<>();
        for (String table : List.of("token_family", "refresh_token", "rotation_record", "security_event")) {
            allRows.addAll(jdbc.queryForList("SELECT * FROM " + table));
        }
        for (String plain : plainTokens) {
            for (Map<String, Object> row : allRows) {
                for (Object value : row.values()) {
                    assertThat(String.valueOf(value))
                            .as("明文令牌不得出现在持久化数据中: %s", plain)
                            .doesNotContain(plain);
                }
            }
            // 明文也绝不可能等于其 SHA-256 散列存储值。
            Integer hits = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?", Integer.class, plain);
            assertThat(hits).isZero();
        }
    }

    @Test
    void createSessionPersistsOnlyHashStatusAndExpiry() {
        TokenIssuedResponse created = newSession("user-1", "client-1");

        assertThat(created.refreshToken()).startsWith("rt.");
        assertThat(created.replayed()).isFalse();
        assertThat(created.expiresAt()).isEqualTo(T0.plusSeconds(3600));

        Map<String, Object> row = tokenRow(TokenCrypto.sha256(created.refreshToken()));
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("seq")).isEqualTo(0);
        assertThat(row.get("parent_hash")).isNull();
        assertThat(row.get("family_id")).isEqualTo(created.familyId());
        assertThat(count("rotation_record")).isZero();
        assertThat(count("security_event")).isZero();
        assertPlaintextNeverPersisted(created.refreshToken());
    }

    @Test
    void rotateConsumesParentAndIssuesDeterministicUniqueChild() {
        TokenIssuedResponse session = newSession("user-1", "client-1");
        TokenIssuedResponse child1 = service.rotate(
                rotateReq("req-1", session.refreshToken(), "client-1"));

        assertThat(child1.familyId()).isEqualTo(session.familyId());
        assertThat(child1.replayed()).isFalse();
        assertThat(child1.refreshToken()).isNotEqualTo(session.refreshToken());

        Map<String, Object> parentRow = tokenRow(TokenCrypto.sha256(session.refreshToken()));
        assertThat(parentRow.get("status")).isEqualTo("CONSUMED");
        assertThat(parentRow.get("consumed_at")).isNotNull();

        Map<String, Object> childRow = tokenRow(TokenCrypto.sha256(child1.refreshToken()));
        assertThat(childRow.get("status")).isEqualTo("ACTIVE");
        assertThat(childRow.get("seq")).isEqualTo(1);
        assertThat(childRow.get("parent_hash")).isEqualTo(TokenCrypto.sha256(session.refreshToken()));

        // 子令牌可继续轮换，形成父子链。
        TokenIssuedResponse child2 = service.rotate(
                rotateReq("req-2", child1.refreshToken(), "client-1"));
        assertThat(tokenRow(TokenCrypto.sha256(child2.refreshToken())).get("seq")).isEqualTo(2);
        assertPlaintextNeverPersisted(session.refreshToken(), child1.refreshToken(), child2.refreshToken());
    }

    @Test
    void sameRequestIdSafeRetryReturnsFirstChildWithoutSideEffects() {
        TokenIssuedResponse session = newSession("user-1", "client-1");
        TokenIssuedResponse first = service.rotate(
                rotateReq("req-retry", session.refreshToken(), "client-1"));

        TokenIssuedResponse retry = service.rotate(
                rotateReq("req-retry", session.refreshToken(), "client-1"));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.refreshToken()).isEqualTo(first.refreshToken());
        assertThat(retry.expiresAt()).isEqualTo(first.expiresAt());
        // 未新增令牌、指纹或事件。
        assertThat(count("refresh_token")).isEqualTo(2);
        assertThat(count("rotation_record")).isEqualTo(1);
        assertThat(count("security_event")).isZero();
    }

    @Test
    void sameRequestIdWithDifferentClientOrTokenConflicts() {
        TokenIssuedResponse session = newSession("user-1", "client-1");
        service.rotate(rotateReq("shared-req", session.refreshToken(), "client-1"));

        // 同键改客户端 → 409。
        expectFailure(() -> service.rotate(
                rotateReq("shared-req", session.refreshToken(), "client-2")), 409);

        // 同键改令牌（另一个会话）→ 409，且该会话令牌不被消费、不产生子嗣。
        TokenIssuedResponse other = newSession("user-2", "client-1");
        expectFailure(() -> service.rotate(
                rotateReq("shared-req", other.refreshToken(), "client-1")), 409);
        assertThat(tokenRow(TokenCrypto.sha256(other.refreshToken())).get("status"))
                .isEqualTo("ACTIVE");

        // 两个会话共 3 个令牌（根 + 首个子令牌 + 第二会话根），指纹仍只有 1 条。
        assertThat(count("refresh_token")).isEqualTo(3);
        assertThat(count("rotation_record")).isEqualTo(1);
    }

    @Test
    void differentRequestIdOnConsumedTokenRevokesWholeFamilyAndRecordsSingleEvent() {
        TokenIssuedResponse session = newSession("user-1", "client-1");
        TokenIssuedResponse child = service.rotate(
                rotateReq("req-1", session.refreshToken(), "client-1"));

        // 异键重放已消费父令牌。
        expectFailure(() -> service.rotate(
                rotateReq("req-attacker", session.refreshToken(), "client-1")), 403);

        assertThat(familyStatus(session.familyId())).isEqualTo("REVOKED");
        assertThat(tokenRow(TokenCrypto.sha256(session.refreshToken())).get("status"))
                .isEqualTo("CONSUMED");
        // 所有后代一并撤销，无可用令牌。
        assertThat(tokenRow(TokenCrypto.sha256(child.refreshToken())).get("status"))
                .isEqualTo("REVOKED");

        List<SecurityEventResponse> events = service.events(session.familyId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("REPLAY_DETECTED");
        assertThat(events.get(0).requestId()).isEqualTo("req-attacker");

        // 族已终态：之后任何令牌、任何请求键都拒绝轮换。
        expectFailure(() -> service.rotate(
                rotateReq("req-anything", child.refreshToken(), "client-1")), 403);
        expectFailure(() -> service.rotate(
                rotateReq("req-yet-another", session.refreshToken(), "client-1")), 403);
        // 不重复记录事件。
        assertThat(service.events(session.familyId())).hasSize(1);
    }

    @Test
    void safeRetryStillReturnsFirstChildEvenAfterFamilyLaterRevoked() {
        TokenIssuedResponse session = newSession("user-1", "client-1");
        TokenIssuedResponse first = service.rotate(
                rotateReq("req-1", session.refreshToken(), "client-1"));

        // 异键重放导致全族撤销。
        expectFailure(() -> service.rotate(
                rotateReq("req-2", session.refreshToken(), "client-1")), 403);

        // 原始请求键的安全重试仍可恢复首次结果（不执行新轮换）。
        TokenIssuedResponse retry = service.rotate(
                rotateReq("req-1", session.refreshToken(), "client-1"));
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.refreshToken()).isEqualTo(first.refreshToken());
        assertThat(count("rotation_record")).isEqualTo(1);
    }

    @Test
    void expiryBoundaryAtExactlyExpiresAtRejectsRotation() {
        TokenIssuedResponse session = newSession("user-1", "client-1");

        // 到期前 1 秒仍可轮换。
        clock.setInstant(session.expiresAt().minusSeconds(1));
        TokenIssuedResponse child = service.rotate(
                rotateReq("req-before", session.refreshToken(), "client-1"));

        // 子令牌在 T0+3599 签发，T0+7199 到期。恰好到期时刻 now == expiresAt 失效。
        clock.setInstant(child.expiresAt());
        expectFailure(() -> service.rotate(
                rotateReq("req-at-expiry", child.refreshToken(), "client-1")), 410);
        assertThat(tokenRow(TokenCrypto.sha256(child.refreshToken())).get("status"))
                .isEqualTo("EXPIRED");

        // 过期后再次使用仍是到期失败，且不会被当作重放。
        clock.advance(Duration.ofSeconds(60));
        expectFailure(() -> service.rotate(
                rotateReq("req-after-expiry", child.refreshToken(), "client-1")), 410);
        assertThat(service.events(session.familyId())).isEmpty();
    }

    @Test
    void logoutRevokesWholeFamilyAndRepeatedLogoutIsSideEffectFree() {
        TokenIssuedResponse session = newSession("user-1", "client-1");
        TokenIssuedResponse child = service.rotate(
                rotateReq("req-1", session.refreshToken(), "client-1"));

        // 携带已消费的父令牌也能登出整个族。
        service.logout(new LogoutRequest(session.refreshToken(), "client-1"));
        assertThat(familyStatus(session.familyId())).isEqualTo("REVOKED");
        assertThat(tokenRow(TokenCrypto.sha256(child.refreshToken())).get("status"))
                .isEqualTo("REVOKED");
        assertThat(service.events(session.familyId()))
                .extracting(SecurityEventResponse::eventType)
                .containsExactly("LOGOUT");

        // 终态重复同参登出：成功且无副作用。
        service.logout(new LogoutRequest(session.refreshToken(), "client-1"));
        assertThat(service.events(session.familyId())).hasSize(1);

        // 撤销族中的令牌拒绝轮换。
        expectFailure(() -> service.rotate(
                rotateReq("req-2", child.refreshToken(), "client-1")), 403);
    }

    @Test
    void familyStatusReportsTreeAndEventsQueryWorks() {
        TokenIssuedResponse session = newSession("user-7", "client-7");
        service.rotate(rotateReq("r1", session.refreshToken(), "client-7"));

        FamilyStatusResponse view = service.familyStatus(session.familyId());
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.userId()).isEqualTo("user-7");
        assertThat(view.tokenCount()).isEqualTo(2);
        assertThat(view.revokedAt()).isNull();

        expectFailure(() -> service.rotate(
                rotateReq("r2", session.refreshToken(), "client-7")), 403);
        FamilyStatusResponse revokedView = service.familyStatus(session.familyId());
        assertThat(revokedView.status()).isEqualTo("REVOKED");
        assertThat(revokedView.revokeReason()).isEqualTo("REPLAY_DETECTED");
        assertThat(revokedView.revokedAt()).isNotNull();

        expectFailure(() -> service.familyStatus("does-not-exist"), 404);
        expectFailure(() -> service.events("does-not-exist"), 404);
    }

    @Test
    void unknownTokenIsRejected() {
        expectFailure(() -> service.rotate(
                rotateReq("req-x", "rt.unknown-plain", "client-1")), 404);
        expectFailure(() -> service.logout(new LogoutRequest("rt.unknown-plain", "client-1")), 404);
    }

    @Test
    void reusedRequestIdWithUnknownTokenConflictsInsteadOfRevealingExistence() {
        TokenIssuedResponse session = newSession("user-1", "client-1");
        service.rotate(rotateReq("req-fingerprint", session.refreshToken(), "client-1"));

        // 指纹已存在，即使携带的令牌不存在也返回 409，而不是 404。
        expectFailure(() -> service.rotate(
                rotateReq("req-fingerprint", "rt.never-existed", "client-1")), 409);
    }

    @Test
    void concurrentSafeRetryWithFirstRequestReturnsSameChildWithoutRevoking() throws Exception {
        TokenIssuedResponse session = newSession("user-retry-race", "client-1");

        int parties = 2;
        CountDownLatch ready = new CountDownLatch(parties);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(parties);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            // 两个请求使用同一请求键并发提交：一次首次轮换 + 一次安全重试。
            for (int i = 0; i < parties; i++) {
                outcomes.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        TokenIssuedResponse resp = service.rotate(
                                rotateReq("req-same-key", session.refreshToken(), "client-1"));
                        return "SUCCESS:" + resp.refreshToken() + ":" + resp.replayed();
                    } catch (TokenServiceException e) {
                        return "FAILURE:" + e.status();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> results = new ArrayList<>();
            for (Future<String> future : outcomes) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).allMatch(r -> r.startsWith("SUCCESS:"));
            String childToken = results.get(0).split(":")[1];
            assertThat(results).extracting(r -> r.split(":")[1]).containsOnly(childToken);
            assertThat(results).extracting(r -> Boolean.parseBoolean(r.split(":")[2]))
                    .containsExactlyInAnyOrder(true, false);

            // 族保持 ACTIVE，只有一个子令牌、一条指纹、无事件。
            assertThat(familyStatus(session.familyId())).isEqualTo("ACTIVE");
            assertThat(count("refresh_token")).isEqualTo(2);
            assertThat(count("rotation_record")).isEqualTo(1);
            assertThat(service.events(session.familyId())).isEmpty();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentConsumptionLeavesAtMostOneChildAndNoUsableDescendant() throws Exception {
        TokenIssuedResponse session = newSession("user-concurrent", "client-1");

        int parties = 2;
        CountDownLatch ready = new CountDownLatch(parties);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(parties);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (String requestId : List.of("req-concurrent-a", "req-concurrent-b")) {
                outcomes.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        TokenIssuedResponse resp = service.rotate(
                                rotateReq(requestId, session.refreshToken(), "client-1"));
                        return "SUCCESS:" + resp.refreshToken();
                    } catch (TokenServiceException e) {
                        return "FAILURE:" + e.status();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int success = 0;
            int rejected = 0;
            String winningChild = null;
            for (Future<String> future : outcomes) {
                String outcome = future.get(30, TimeUnit.SECONDS);
                if (outcome.startsWith("SUCCESS:")) {
                    success++;
                    winningChild = outcome.substring("SUCCESS:".length());
                } else {
                    assertThat(outcome).isEqualTo("FAILURE:403");
                    rejected++;
                }
            }
            assertThat(success).as("至多一个并发轮换成功").isEqualTo(1);
            assertThat(rejected).isEqualTo(1);

            // 最终状态：族撤销、无任何 ACTIVE 后代、事件与指纹各一条。
            assertThat(familyStatus(session.familyId())).isEqualTo("REVOKED");
            List<Map<String, Object>> activeTokens = jdbc.queryForList(
                    "SELECT * FROM refresh_token WHERE family_id = ? AND status = 'ACTIVE'",
                    session.familyId());
            assertThat(activeTokens).isEmpty();

            Map<String, Object> winnerRow = tokenRow(TokenCrypto.sha256(winningChild));
            assertThat(winnerRow.get("status")).isEqualTo("REVOKED");
            assertThat(tokenRow(TokenCrypto.sha256(session.refreshToken())).get("status"))
                    .isEqualTo("CONSUMED");
            assertThat(count("rotation_record")).isEqualTo(1);
            List<Map<String, Object>> replayEvents = jdbc.queryForList(
                    "SELECT * FROM security_event WHERE event_type = 'REPLAY_DETECTED'");
            assertThat(replayEvents).hasSize(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
