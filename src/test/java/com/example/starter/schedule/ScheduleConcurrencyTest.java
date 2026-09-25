package com.example.starter.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.schedule.ScheduleRequests.CreateSchedule;
import com.example.starter.schedule.ScheduleRequests.ItemSpec;
import com.example.starter.schedule.ScheduleResponses.ScheduleView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界测试：并发下达唯一生效、并发同键幂等唯一执行。
 */
class ScheduleConcurrencyTest extends BaseScheduleTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 9, 25, 8, 0);

    private ItemSpec item(int seq, int widthMm) {
        LocalDateTime start = DAY.plusHours(seq - 1L);
        return new ItemSpec(seq, "C" + seq, widthMm, new BigDecimal("2"), false,
                start, start.plusHours(1));
    }

    private ScheduleView validatedSchedule(String requestId, String unitNo) {
        ScheduleView draft = scheduleService.create(new CreateSchedule(
                requestId, "L1", unitNo, List.of(item(1, 1500), item(2, 1400)), List.of()));
        scheduleService.validate(draft.scheduleId());
        return draft;
    }

    @Test
    @Timeout(30)
    void concurrentReleaseOnlyOneEffective() throws Exception {
        ScheduleView a = validatedSchedule("rc-a", "U-CA");
        ScheduleView b = validatedSchedule("rc-b", "U-CB");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<String> fa = pool.submit(() -> releaseOnce(a.scheduleId(), ready, go));
            Future<String> fb = pool.submit(() -> releaseOnce(b.scheduleId(), ready, go));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            String ra = fa.get(20, TimeUnit.SECONDS);
            String rb = fb.get(20, TimeUnit.SECONDS);

            // 并发下达唯一成功：恰一个 200，一个 409
            assertThat(List.of(ra, rb)).containsExactlyInAnyOrder("OK", "CONFLICT");
            Integer effectiveRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM line_effective_schedule", Integer.class);
            assertThat(effectiveRows).isEqualTo(1);
            Integer effectiveSchedules = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule WHERE effective = 1", Integer.class);
            assertThat(effectiveSchedules).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void concurrentSameRequestIdExecutesOnce() throws Exception {
        CreateSchedule request = new CreateSchedule("rc-idem", "L1", "U-CI",
                List.of(item(1, 1500), item(2, 1400)), List.of());
        String fingerprint = Fingerprint.ofSchedule(request.requestId(), request.lineCode(),
                request.unitNo(), request.items(), request.rollChangeWindows());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<IdempotencyService.Outcome> fa = pool.submit(
                    () -> createOnce(request, fingerprint, ready, go));
            Future<IdempotencyService.Outcome> fb = pool.submit(
                    () -> createOnce(request, fingerprint, ready, go));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            IdempotencyService.Outcome oa = fa.get(20, TimeUnit.SECONDS);
            IdempotencyService.Outcome ob = fb.get(20, TimeUnit.SECONDS);

            // 同键并发：一次执行一次重放，响应一致，只落一张排产单
            assertThat(List.of(oa.replayed(), ob.replayed()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(oa.bodyJson()).isEqualTo(ob.bodyJson());
            Integer schedules = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule WHERE unit_no = 'U-CI'", Integer.class);
            assertThat(schedules).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private String releaseOnce(long scheduleId, CountDownLatch ready, CountDownLatch go)
            throws Exception {
        ready.countDown();
        go.await(10, TimeUnit.SECONDS);
        try {
            scheduleService.release(scheduleId);
            return "OK";
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(409);
            return "CONFLICT";
        }
    }

    private IdempotencyService.Outcome createOnce(CreateSchedule request, String fingerprint,
                                                  CountDownLatch ready, CountDownLatch go)
            throws Exception {
        ready.countDown();
        go.await(10, TimeUnit.SECONDS);
        return idempotencyService.run(request.requestId(), fingerprint, 201,
                () -> scheduleService.create(request));
    }
}
