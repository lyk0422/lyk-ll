package com.example.starter.slot;

import com.example.starter.slot.dto.PublishRequest;
import com.example.starter.slot.dto.PublishResult;
import com.example.starter.slot.dto.ReleaseRequest;
import com.example.starter.slot.dto.ReleaseResult;
import com.example.starter.slot.dto.SlotApplicationRequest;
import com.example.starter.slot.dto.SlotApplicationResult;
import com.example.starter.slot.exception.FingerprintMismatchException;
import com.example.starter.slot.exception.SlotConflictException;
import com.example.starter.slot.exception.SlotNotFoundException;
import com.example.starter.slot.exception.SlotStateException;
import com.example.starter.slot.exception.SlotValidationException;
import com.example.starter.slot.service.SlotCoordinationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时刻协调服务 H2 集成测试：覆盖 80% 阈值口径、整周冲突、系列整体回滚、
 * 幂等重放与异参冲突、并发同刻唯一成功、退回原子释放、发布冻结。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class SlotCoordinationServiceTest {

    @Autowired
    private SlotCoordinationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM slot_snapshot");
        jdbcTemplate.update("DELETE FROM slot_allocation");
        jdbcTemplate.update("DELETE FROM slot_series");
        jdbcTemplate.update("DELETE FROM slot_season");
        jdbcTemplate.update("DELETE FROM historical_performance");
        jdbcTemplate.update("INSERT INTO slot_season (season, frozen, frozen_at, version, created_at) VALUES ('S26', 0, NULL, 0, 0)");
    }

    private void seedHistory(String airline, String flightNo, String airport, String slotTime, double rate) {
        jdbcTemplate.update(
                "INSERT INTO historical_performance (prev_season, airline, flight_no, airport, slot_time, execution_rate) VALUES ('S25', ?, ?, ?, ?, ?)",
                airline, flightNo, airport, slotTime, rate);
    }

    private SlotApplicationRequest request(String requestId, String airline, String flightNo,
                                           String airport, String slotTime, Integer... weeks) {
        return new SlotApplicationRequest(requestId, "S26", airline, flightNo, airport, slotTime, List.of(weeks));
    }

    private int activeCount(String season, String airport, String slotTime, int week) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM slot_allocation WHERE season = ? AND airport = ? AND slot_time = ? AND week = ? AND active = 1",
                Integer.class, season, airport, slotTime, week);
        return count == null ? 0 : count;
    }

    @Test
    void retentionThresholdExactlyEightyKeepsOriginalSlot() {
        seedHistory("CA", "1001", "PEK", "08:00", 80.0);

        SlotApplicationResult result = service.apply(request("REQ-80", "CA", "1001", "PEK", "08:00", 1, 2));

        assertThat(result.decision()).isEqualTo("RETAINED");
        assertThat(result.status()).isEqualTo("HELD");
        assertThat(result.replay()).isFalse();
        assertThat(result.allocations()).hasSize(2);
        assertThat(activeCount("S26", "PEK", "08:00", 1)).isEqualTo(1);
        assertThat(activeCount("S26", "PEK", "08:00", 2)).isEqualTo(1);
    }

    @Test
    void retentionThresholdSeventyNinePointNineGoesToPublicPool() {
        seedHistory("CA", "1002", "PEK", "08:30", 79.9);

        SlotApplicationResult result = service.apply(request("REQ-799", "CA", "1002", "PEK", "08:30", 1));

        assertThat(result.decision()).isEqualTo("POOL");
        assertThat(result.status()).isEqualTo("HELD");
        assertThat(activeCount("S26", "PEK", "08:30", 1)).isEqualTo(1);
    }

    @Test
    void noHistoryGoesToPublicPool() {
        SlotApplicationResult result = service.apply(request("REQ-NONE", "CZ", "3001", "PEK", "10:00", 1));

        assertThat(result.decision()).isEqualTo("POOL");
    }

    @Test
    void wholeWeekConflictRejectsSecondAirlineWithConflictList() {
        service.apply(request("REQ-A", "CA", "1001", "PEK", "08:00", 1, 2, 3));

        assertThatThrownBy(() -> service.apply(request("REQ-B", "MU", "2001", "PEK", "08:00", 2, 3, 4)))
                .isInstanceOfSatisfying(SlotConflictException.class, ex -> {
                    assertThat(ex.getConflicts()).hasSize(2);
                    assertThat(ex.getConflicts())
                            .allSatisfy(c -> {
                                assertThat(c.holderAirline()).isEqualTo("CA");
                                assertThat(c.holderFlightNo()).isEqualTo("1001");
                                assertThat(c.holderRequestId()).isEqualTo("REQ-A");
                            });
                    assertThat(ex.getConflicts().stream().map(c -> c.week()).sorted().toList())
                            .containsExactly(2, 3);
                });
    }

    @Test
    void disjointWeeksDoNotConflict() {
        service.apply(request("REQ-A", "CA", "1001", "PEK", "08:00", 1, 2));

        SlotApplicationResult result = service.apply(request("REQ-B", "MU", "2001", "PEK", "08:00", 3, 4));

        assertThat(result.status()).isEqualTo("HELD");
        assertThat(activeCount("S26", "PEK", "08:00", 3)).isEqualTo(1);
    }

    @Test
    void conflictingSeriesRollsBackEntirelyWithoutPartialOccupation() {
        service.apply(request("REQ-A", "CA", "1001", "PEK", "08:00", 2));

        assertThatThrownBy(() -> service.apply(request("REQ-B", "MU", "2001", "PEK", "08:00", 1, 2, 3)))
                .isInstanceOf(SlotConflictException.class);

        // 系列整体回滚：REQ-B 在 week 1/3 也不得留下占用，且无系列记录
        assertThat(activeCount("S26", "PEK", "08:00", 1)).isEqualTo(0);
        assertThat(activeCount("S26", "PEK", "08:00", 3)).isEqualTo(0);
        Integer seriesCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM slot_series WHERE request_id = 'REQ-B'", Integer.class);
        assertThat(seriesCount).isZero();
    }

    @Test
    void sameKeySameParamsReplaysFirstResult() {
        SlotApplicationResult first = service.apply(request("REQ-IDEM", "CA", "1001", "PEK", "08:00", 1, 2));

        SlotApplicationResult replay = service.apply(request("REQ-IDEM", "CA", "1001", "PEK", "08:00", 2, 1));

        assertThat(replay.replay()).isTrue();
        assertThat(replay.decision()).isEqualTo(first.decision());
        assertThat(replay.allocations()).hasSize(2);
        // 重放不得产生重复占用
        assertThat(activeCount("S26", "PEK", "08:00", 1)).isEqualTo(1);
        assertThat(activeCount("S26", "PEK", "08:00", 2)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturnsConflict() {
        service.apply(request("REQ-IDEM", "CA", "1001", "PEK", "08:00", 1, 2));

        assertThatThrownBy(() -> service.apply(request("REQ-IDEM", "CA", "1001", "PEK", "08:05", 1, 2)))
                .isInstanceOf(FingerprintMismatchException.class);
        assertThatThrownBy(() -> service.apply(request("REQ-IDEM", "CA", "1002", "PEK", "08:00", 1, 2)))
                .isInstanceOf(FingerprintMismatchException.class);
        assertThatThrownBy(() -> service.apply(request("REQ-IDEM", "CA", "1001", "PEK", "08:00", 1, 2, 3)))
                .isInstanceOf(FingerprintMismatchException.class);
    }

    @Test
    void concurrentApplicationsForSameSlotOnlyOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    service.apply(request("REQ-C-" + idx, "CA", "90" + idx, "PEK", "08:00", 1, 2));
                    success.incrementAndGet();
                } catch (SlotConflictException e) {
                    conflict.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(activeCount("S26", "PEK", "08:00", 1)).isEqualTo(1);
        assertThat(activeCount("S26", "PEK", "08:00", 2)).isEqualTo(1);
    }

    @Test
    void releaseFreesSlotImmediatelyForOthers() {
        service.apply(request("REQ-A", "CA", "1001", "PEK", "08:00", 1, 2));

        ReleaseResult release = service.release(new ReleaseRequest("REQ-A"));

        assertThat(release.status()).isEqualTo("RELEASED");
        assertThat(release.replay()).isFalse();
        assertThat(activeCount("S26", "PEK", "08:00", 1)).isEqualTo(0);

        SlotApplicationResult result = service.apply(request("REQ-B", "MU", "2001", "PEK", "08:00", 1, 2));
        assertThat(result.status()).isEqualTo("HELD");
        assertThat(activeCount("S26", "PEK", "08:00", 1)).isEqualTo(1);
    }

    @Test
    void releaseIsIdempotentAndUnknownRequestIdIsNotFound() {
        service.apply(request("REQ-A", "CA", "1001", "PEK", "08:00", 1));
        service.release(new ReleaseRequest("REQ-A"));

        ReleaseResult replay = service.release(new ReleaseRequest("REQ-A"));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.status()).isEqualTo("RELEASED");

        assertThatThrownBy(() -> service.release(new ReleaseRequest("REQ-UNKNOWN")))
                .isInstanceOf(SlotNotFoundException.class);
    }

    @Test
    void concurrentReleaseAndNewApplicationNeverDoubleOwnSlot() throws Exception {
        service.apply(request("REQ-A", "CA", "1001", "PEK", "08:00", 1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> releaseFuture = pool.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                service.release(new ReleaseRequest("REQ-A"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Future<?> applyFuture = pool.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                service.apply(request("REQ-B", "MU", "2001", "PEK", "08:00", 1));
            } catch (SlotConflictException e) {
                // 退回尚未生效时冲突拒绝，同样合法
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        releaseFuture.get(30, TimeUnit.SECONDS);
        applyFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同一时刻同一周全表至多一条存活占用
        assertThat(activeCount("S26", "PEK", "08:00", 1)).isLessThanOrEqualTo(1);
        // 退回完成后新申请必然可以成功（若此前冲突失败，现在重试应成功）
        if (activeCount("S26", "PEK", "08:00", 1) == 0) {
            SlotApplicationResult retry = service.apply(request("REQ-B", "MU", "2001", "PEK", "08:00", 1));
            assertThat(retry.status()).isEqualTo("HELD");
        }
        assertThat(activeCount("S26", "PEK", "08:00", 1)).isEqualTo(1);
    }

    @Test
    void publishFreezesSeasonAndRepeatPublishIsIdempotent() {
        service.apply(request("REQ-A", "CA", "1001", "PEK", "08:00", 1, 2));
        service.apply(request("REQ-B", "MU", "2001", "PKX", "09:15", 1));

        PublishResult first = service.publish(new PublishRequest("S26"));

        assertThat(first.alreadyPublished()).isFalse();
        assertThat(first.publishedAt()).isEqualTo(FixedClockTestConfig.FIXED);
        assertThat(first.allocations()).hasSize(3);

        PublishResult second = service.publish(new PublishRequest("S26"));
        assertThat(second.alreadyPublished()).isTrue();
        assertThat(second.publishedAt()).isEqualTo(FixedClockTestConfig.FIXED);
        assertThat(second.allocations()).hasSize(3);

        // 发布后再申请本期时刻一律拒绝
        assertThatThrownBy(() -> service.apply(request("REQ-C", "CZ", "3001", "PEK", "10:00", 1)))
                .isInstanceOf(SlotStateException.class);
    }

    @Test
    void lateReleaseAfterPublishDoesNotChangeSnapshot() {
        service.apply(request("REQ-A", "CA", "1001", "PEK", "08:00", 1));
        PublishResult published = service.publish(new PublishRequest("S26"));
        assertThat(published.allocations()).hasSize(1);

        // 迟到退回被允许，但不改变已发布快照
        ReleaseResult release = service.release(new ReleaseRequest("REQ-A"));
        assertThat(release.status()).isEqualTo("RELEASED");
        assertThat(activeCount("S26", "PEK", "08:00", 1)).isEqualTo(0);

        PublishResult republish = service.publish(new PublishRequest("S26"));
        assertThat(republish.alreadyPublished()).isTrue();
        assertThat(republish.allocations()).hasSize(1);
        assertThat(republish.allocations().get(0).slotTime()).isEqualTo("08:00");
    }

    @Test
    void slotTimeMustAlignToFiveMinuteGrid() {
        assertThatThrownBy(() -> service.apply(request("REQ-BAD", "CA", "1001", "PEK", "08:03", 1)))
                .isInstanceOf(SlotValidationException.class);
        assertThatThrownBy(() -> service.apply(request("REQ-BAD2", "CA", "1001", "PEK", "8:00", 1)))
                .isInstanceOf(SlotValidationException.class);
        assertThatThrownBy(() -> service.apply(request("REQ-BAD3", "CA", "1001", "PEK", "08:00", 0)))
                .isInstanceOf(SlotValidationException.class);
    }
}
