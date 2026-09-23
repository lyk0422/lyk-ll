package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.api.CreateSessionRequest;
import com.example.starter.api.CreateSessionResponse;
import com.example.starter.api.FamilyStatusResponse;
import com.example.starter.api.RotateRequest;
import com.example.starter.api.RotateResponse;
import com.example.starter.domain.RefreshTokenEntity;
import com.example.starter.domain.TokenStatus;
import com.example.starter.repository.RefreshTokenRepository;
import com.example.starter.testsupport.TestClockConfig;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 刷新令牌并发轮换的 H2 事务测试：用真实多连接并发验证行锁串行化、
 * 至多一个子令牌以及失败方触发整族撤销。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfig.class})
class RefreshTokenConcurrencyH2Test {

    private static final String CLIENT = "client-concurrency";

    @Autowired
    private RefreshTokenService service;
    @Autowired
    private RefreshTokenRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM security_event");
        jdbcTemplate.update("DELETE FROM rotation_request");
        jdbcTemplate.update("DELETE FROM refresh_token");
        jdbcTemplate.update("DELETE FROM token_family");
    }

    @Test
    void concurrentConsumptionOfSameTokenYieldsAtMostOneChildAndRevokesFamily() throws Exception {
        CreateSessionResponse session = service.createSession(
                new CreateSessionRequest("user-concurrency", CLIENT));
        String currentToken = session.refreshToken();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<RotateResponse> winner = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        try {
            Future<?> f1 = pool.submit(() ->
                    runRotation(ready, start, winner, loserError, "req-concurrent-1", currentToken));
            Future<?> f2 = pool.submit(() ->
                    runRotation(ready, start, winner, loserError, "req-concurrent-2", currentToken));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 恰好一方成功，另一方失败（失败方直接抛出 InvalidTokenException，非包装异常）
        assertThat(winner.get()).as("exactly one rotation succeeds").isNotNull();
        assertThat(loserError.get())
                .as("the loser is rejected")
                .isNotNull()
                .isInstanceOf(InvalidTokenException.class);

        FamilyStatusResponse status = service.familyStatus(session.familyId());
        assertThat(status.status()).isEqualTo("REVOKED");
        assertThat(status.revokeReason()).isEqualTo("REPLAY_DETECTED");
        assertThat(status.activeTokenCount()).isZero();
        assertThat(status.tokenCount()).isEqualTo(2);

        // 唯一子令牌处于 REVOKED，不留下可用后代
        List<RefreshTokenEntity> tokens = repository.findTokensByFamily(session.familyId());
        assertThat(tokens).extracting(RefreshTokenEntity::status)
                .containsExactlyInAnyOrder(TokenStatus.CONSUMED, TokenStatus.REVOKED);
        assertThat(service.events(session.familyId())).hasSize(1);
    }

    @Test
    void concurrentSameRequestSafeRetryProducesSingleChildAndBothSucceed() throws Exception {
        CreateSessionResponse session = service.createSession(
                new CreateSessionRequest("user-retry", CLIENT));
        String currentToken = session.refreshToken();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<RotateResponse> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    responses.add(service.rotate(
                            new RotateRequest("req-retry-same", currentToken, CLIENT)));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            Future<?> f2 = pool.submit(() -> {
                try {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    responses.add(service.rotate(
                            new RotateRequest("req-retry-same", currentToken, CLIENT)));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).isEmpty();
        assertThat(responses).hasSize(2);
        // 确定性派生：两个并发响应返回同一个首次子令牌
        assertThat(responses.get(0).refreshToken())
                .isEqualTo(responses.get(1).refreshToken());
        // 一方首次签发，另一方安全重试命中
        assertThat(responses).extracting(RotateResponse::replayed)
                .containsExactlyInAnyOrder(false, true);
        // 只产生一个子令牌，族保持 ACTIVE
        assertThat(repository.findTokensByFamily(session.familyId())).hasSize(2);
        assertThat(service.familyStatus(session.familyId()).status()).isEqualTo("ACTIVE");
        assertThat(service.events(session.familyId())).isEmpty();
    }

    private void runRotation(CountDownLatch ready,
                             CountDownLatch start,
                             AtomicReference<RotateResponse> winner,
                             AtomicReference<Throwable> loserError,
                             String requestId,
                             String currentToken) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start latch timeout");
            }
            RotateResponse response = service.rotate(
                    new RotateRequest(requestId, currentToken, CLIENT));
            winner.set(response);
        } catch (Throwable t) {
            loserError.set(t);
        }
    }
}
