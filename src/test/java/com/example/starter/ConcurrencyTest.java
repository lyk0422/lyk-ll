package com.example.starter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.service.ComplaintService;
import com.example.starter.service.EventService;
import com.example.starter.web.dto.ComplaintRequest;
import com.example.starter.web.dto.ComplaintResponse;
import com.example.starter.web.dto.InvestigateRequest;
import com.example.starter.web.dto.InvestigateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发边界：幂等并发、恰第 3 个并发触发、同发射机并发确认仅一条执行中指令。
 * 通过门闩协调真实并发，全部在超时内完成并断言最终数据。
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private ComplaintService complaintService;

    @Autowired
    private EventService eventService;

    private ComplaintRequest complaint(String requestId, String station, String frequency, OffsetDateTime sampledAt) {
        return new ComplaintRequest(requestId, station, new BigDecimal(frequency), sampledAt,
                new BigDecimal("72.5"), "EV-" + requestId);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> gated = new ArrayList<>();
        for (Callable<T> task : tasks) {
            gated.add(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return task.call();
            });
        }
        try {
            List<Future<T>> futures = gated.stream().map(pool::submit).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestIdRegisteredOnce() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            OffsetDateTime sampledAt = OffsetDateTime.of(2026, 10, 6, 1, 0, 0, 0, ZoneOffset.UTC);
            List<Callable<ComplaintResponse>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> complaintService.register(
                        complaint("req-conc-1", "ST-1", "98.100", sampledAt)));
            }

            List<ComplaintResponse> responses = runConcurrently(tasks);

            long firstId = responses.get(0).complaintId();
            assertTrue(responses.stream().allMatch(r -> r.complaintId() == firstId),
                    "并发同键同参全部返回首次结果");
            assertEquals(1, countRows("complaint"), "并发下同键只落库一次");
        });
    }

    @Test
    void concurrentThirdComplaintsTriggerSingleEvent() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            OffsetDateTime sampledAt = OffsetDateTime.of(2026, 10, 6, 2, 0, 0, 0, ZoneOffset.UTC);
            List<Callable<ComplaintResponse>> tasks = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                String station = "ST-C" + i;
                tasks.add(() -> complaintService.register(
                        complaint("req-conc-" + station, station, "98.100", sampledAt)));
            }

            List<ComplaintResponse> responses = runConcurrently(tasks);

            assertEquals(3, countRows("complaint"));
            assertEquals(1, countRows("interference_event"), "并发恰第 3 个只触发一个事件");
            assertEquals(1, responses.stream().filter(ComplaintResponse::eventTriggered).count(),
                    "恰有一个登记被判定为触发者");
        });
    }

    @Test
    void concurrentInvestigationIsIdempotent() throws Exception {
        long eventId = triggerEvent("98.100", TestClockConfiguration.T0);

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            List<Callable<InvestigateResponse>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> eventService.investigate(eventId, new InvestigateRequest("inv-conc-1", true, "确认干扰")));
            }

            List<InvestigateResponse> responses = runConcurrently(tasks);

            long directiveId = responses.get(0).directiveId();
            assertTrue(responses.stream().allMatch(r -> r.directiveId() == directiveId),
                    "并发同键初查返回同一指令");
            assertEquals(1, countRows("power_directive"), "同发射机同时刻仅一条执行中指令");
            assertEquals(1, countRows("power_directive_revision"), "并发下不产生重复轨迹");
            assertEquals("CONFIRMED", jdbc.queryForObject(
                    "SELECT status FROM interference_event WHERE id = ?", String.class, eventId));
        });
    }

    @Test
    void concurrentConfirmsKeepSingleExecutingDirective() throws Exception {
        long eventA = triggerEvent("98.100", TestClockConfiguration.T0);
        long eventB = triggerEvent("98.500", TestClockConfiguration.T0.plusSeconds(600));

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            List<Callable<InvestigateResponse>> tasks = List.of(
                    () -> eventService.investigate(eventA, new InvestigateRequest("inv-ca", true, "确认A")),
                    () -> eventService.investigate(eventB, new InvestigateRequest("inv-cb", true, "确认B")));

            List<InvestigateResponse> responses = runConcurrently(tasks);

            assertEquals(1, countRows("power_directive"), "并发确认仍只有一条执行中指令");
            assertEquals(responses.get(0).directiveId(), responses.get(1).directiveId());
            Integer limit = jdbc.queryForObject(
                    "SELECT current_limit_w FROM power_directive WHERE transmitter_id = 1", Integer.class);
            assertEquals(2000, limit, "并发确认后取最严格限值");
        });
    }
}
