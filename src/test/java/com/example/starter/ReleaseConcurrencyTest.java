package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.domain.TestResultValue;
import com.example.starter.domain.TestType;
import com.example.starter.domain.UnitStatus;
import com.example.starter.error.ConflictException;
import com.example.starter.repository.IssueRecordRepository;
import com.example.starter.service.BloodUnitService;
import com.example.starter.service.ReleaseService;
import com.example.starter.web.dto.InboundRequest;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import com.example.starter.web.dto.ResultCallbackRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发与竞态测试：并发同条码放行只允许成功一次；
 * 复检合格回传与过期判定竞态形成唯一终态（H2 行锁真实并发）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class ReleaseConcurrencyTest {

    private static final LocalDate EXPIRY = LocalDate.of(2026, 9, 25);
    private static final Instant WINDOW_START = Instant.parse("2026-09-25T08:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-09-25T20:00:00Z");

    @Autowired
    private BloodUnitService unitService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private IssueRecordRepository issues;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM issue_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM result_event");
        jdbc.update("DELETE FROM blood_unit");
        clock.setInstant(Instant.parse("2026-09-25T10:00:00Z"));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 并发执行两个任务，等待两者都就绪后同时放行。 */
    private <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<T> guardedFirst = () -> {
            ready.countDown();
            assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
            return first.call();
        };
        Callable<T> guardedSecond = () -> {
            ready.countDown();
            assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
            return second.call();
        };
        Future<T> f1 = executor.submit(guardedFirst);
        Future<T> f2 = executor.submit(guardedSecond);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
    }

    /** 放行调用的结果包装：成功为响应，冲突为异常携带的当前状态。 */
    private record ReleaseAttempt(ReleaseResponse success, ConflictException conflict) {
        boolean succeeded() {
            return success != null;
        }
    }

    private ReleaseAttempt attemptRelease(String requestId, String barcode) {
        try {
            return new ReleaseAttempt(
                    releaseService.release(new ReleaseRequest(requestId, barcode, WINDOW_START, WINDOW_END)),
                    null);
        } catch (ConflictException e) {
            return new ReleaseAttempt(null, e);
        }
    }

    @Test
    void 并发对同一条码放行只允许成功一次() throws Exception {
        unitService.inbound(new InboundRequest("CC-001", EXPIRY));
        unitService.recordResult("CC-001", new ResultCallbackRequest(TestType.INITIAL, TestResultValue.PASS));
        unitService.recordResult("CC-001", new ResultCallbackRequest(TestType.RECHECK, TestResultValue.PASS));

        List<ReleaseAttempt> results = runConcurrently(
                () -> attemptRelease("REQ-A", "CC-001"),
                () -> attemptRelease("REQ-B", "CC-001"));

        long succeeded = results.stream().filter(ReleaseAttempt::succeeded).count();
        assertThat(succeeded).isEqualTo(1);

        // 另一请求得到该单位当前真实状态：已放行
        ReleaseAttempt loser = results.stream().filter(r -> !r.succeeded()).findFirst().orElseThrow();
        assertThat(loser.conflict().getCurrentStatus()).isEqualTo(UnitStatus.RELEASED);

        // 放行扣减与发血记录同生：仅一条发血记录，终态为已放行
        assertThat(issues.countByBarcode("CC-001")).isEqualTo(1);
        assertThat(unitService.getByBarcode("CC-001").status()).isEqualTo(UnitStatus.RELEASED);
    }

    @Test
    void 复检合格回传与过期判定竞态形成唯一终态() throws Exception {
        unitService.inbound(new InboundRequest("CC-002", EXPIRY));
        unitService.recordResult("CC-002", new ResultCallbackRequest(TestType.INITIAL, TestResultValue.PASS));
        // 时钟越过效期边界：次日 00:30 UTC
        clock.setInstant(Instant.parse("2026-09-26T00:30:00Z"));

        List<Object> outcomes = runConcurrently(
                () -> {
                    try {
                        return unitService.recordResult("CC-002",
                                new ResultCallbackRequest(TestType.RECHECK, TestResultValue.PASS));
                    } catch (RuntimeException e) {
                        return e;
                    }
                },
                () -> {
                    try {
                        return releaseService.release(
                                new ReleaseRequest("REQ-RACE", "CC-002", WINDOW_START, WINDOW_END));
                    } catch (RuntimeException e) {
                        return e;
                    }
                });

        // 无论回传与过期判定谁先提交，终态唯一：过期报废，绝不出现既放行又报废
        assertThat(unitService.getByBarcode("CC-002").status()).isEqualTo(UnitStatus.EXPIRED_DISCARDED);
        assertThat(issues.countByBarcode("CC-002")).isZero();

        // 放行一侧必然因过期被拒
        Object releaseOutcome = outcomes.get(1);
        assertThat(releaseOutcome).isInstanceOf(ConflictException.class);
        assertThat(((ConflictException) releaseOutcome).getCode()).isEqualTo("UNIT_EXPIRED");
    }
}
