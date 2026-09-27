package com.example.starter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.CreateFumigationRequest;
import com.example.starter.dto.FumigationDtos.FinishFumigationRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.GasReadingRequest;
import com.example.starter.dto.FumigationDtos.GasReadingResponse;
import com.example.starter.dto.FumigationDtos.ReleaseConfirmRequest;
import com.example.starter.dto.FumigationDtos.StartFumigationRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熏蒸并发测试：同键并发申请唯一落单、并发开始唯一受理、
 * 高浓度补录与解除竞争不得用旧三点放行、风机并发争抢只服务一单。
 * 通过门闩协调真实并发，断言最终结果与数据库状态，并设置超时。
 */
class FumigationConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant W0 = Instant.parse("2026-09-26T02:00:00Z");
    private static final BigDecimal THRESHOLD = new BigDecimal("50.0000");

    @Test
    void concurrentCreateWithSameRequestIdCreatesExactlyOne() throws Exception {
        SiloView silo = newSilo("S-CCC");
        CreateFumigationRequest request = new CreateFumigationRequest("fm-ccc",
                W0, W0.plusSeconds(3600), THRESHOLD, List.of("密封", "熏蒸"));

        List<Outcome> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.create(silo.id(), request)),
                () -> capture(() -> fumigationService.create(silo.id(), request)));

        long fresh = outcomes.stream().filter(o -> o.view() != null && !o.view().replayed()).count();
        long replayed = outcomes.stream().filter(o -> o.view() != null && o.view().replayed()).count();
        assertEquals(1, fresh, "同键并发只能首次受理一次");
        assertEquals(1, replayed, "另一个并发请求应重放首次冻结结果");
        assertEquals(1, countRows("fumigation"));
        assertEquals(1, countRows("fumigation_request"));
        // 重放结果与首次冻结结果一致
        FumigationView a = outcomes.get(0).view();
        FumigationView b = outcomes.get(1).view();
        assertEquals(a.id(), b.id());
        assertEquals(1, b.version());
    }

    @Test
    void concurrentStartOnlyOneAccepted() throws Exception {
        SiloView silo = newSilo("S-CST");
        FumigationView f = createApproved(silo.id(), "cst");

        List<Outcome> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.start(f.id(),
                        new StartFumigationRequest("fs-cst-1", 3))),
                () -> capture(() -> fumigationService.start(f.id(),
                        new StartFumigationRequest("fs-cst-2", 3))));

        long accepted = outcomes.stream().filter(o -> o.view() != null).count();
        long versionConflict = outcomes.stream()
                .filter(o -> o.error() != null && "VERSION_CONFLICT".equals(o.error().getCode()))
                .count();
        assertEquals(1, accepted, "并发开始只能唯一受理");
        assertEquals(1, versionConflict, "落选请求应以版本冲突拒绝");
        FumigationView current = fumigationService.getFumigation(f.id());
        assertEquals(FumigationStatus.FUMIGATING, current.status());
        assertEquals(4, current.version(), "版本只增加一次");
        assertEquals(SiloStatus.FUMIGATING, siloService.getSilo(silo.id()).status());
    }

    @Test
    void highReadingRacingReleaseNeverUsesStalePoints() throws Exception {
        SiloView silo = newSilo("S-RCR");
        FanView fan = newFan("F-RCR");
        FumigationView f = createAerating(silo.id(), fan.id(), "rcr");
        // 三个连续安全点，首末间隔 40 分钟
        sample(f.id(), "fg-rcr-1", 1, "10.00", W0);
        sample(f.id(), "fg-rcr-2", 2, "10.00", W0.plusSeconds(20 * 60));
        sample(f.id(), "fg-rcr-3", 3, "10.00", W0.plusSeconds(40 * 60));
        fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rcr-1", 5, "复核人甲"));

        // 高浓度补录与最终解除确认真实并发
        List<Object> results = runConcurrently(
                () -> captureReading(() -> fumigationService.sample(f.id(),
                        new GasReadingRequest("fg-rcr-4", 4, new BigDecimal("99.00"),
                                W0.plusSeconds(50 * 60)))),
                () -> capture(() -> fumigationService.confirmRelease(f.id(),
                        new ReleaseConfirmRequest("fr-rcr-2", 6, "复核人乙"))));

        Outcome release = (Outcome) results.get(1);
        ReadingOutcome reading = (ReadingOutcome) results.get(0);
        FumigationView current = fumigationService.getFumigation(f.id());
        if (release.view() != null) {
            // 解除先持锁：解除成功，补录读数只能记迟到，不改已冻结证据
            assertEquals(FumigationStatus.RELEASED, current.status());
            assertNotNull(reading.response());
            assertEquals("LATE_RECORDED", reading.response().result());
            assertEquals(3, countRows("gas_reading"));
            assertEquals(1, countRows("late_event"));
            assertEquals("IDLE", siloService.getFan(fan.id()).status());
        } else {
            // 补录先持锁：解除不得用旧三点放行
            assertEquals(422, release.error().getStatus().value());
            assertEquals("RELEASE_NOT_SAFE", release.error().getCode());
            assertEquals(FumigationStatus.AERATING, current.status());
            assertEquals(4, countRows("gas_reading"));
            assertEquals(0, countRows("late_event"));
            assertEquals("LOCKED", siloService.getFan(fan.id()).status());
        }
    }

    @Test
    void concurrentFanContentionOnFinishLocksExactlyOne() throws Exception {
        SiloView siloA = newSilo("S-FFA");
        SiloView siloB = newSilo("S-FFB");
        FanView fan = newFan("F-FSH");
        FumigationView fa = createFumigating(siloA.id(), "ffa");
        FumigationView fb = createFumigating(siloB.id(), "ffb");

        List<Outcome> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.finish(fa.id(),
                        new FinishFumigationRequest("ff-ffa", 4, fan.id()))),
                () -> capture(() -> fumigationService.finish(fb.id(),
                        new FinishFumigationRequest("ff-ffb", 4, fan.id()))));

        long accepted = outcomes.stream().filter(o -> o.view() != null).count();
        long fanBusy = outcomes.stream()
                .filter(o -> o.error() != null && "FAN_BUSY".equals(o.error().getCode()))
                .count();
        assertEquals(1, accepted, "同一台风机同一时刻只服务一个熏蒸单");
        assertEquals(1, fanBusy, "落选请求应以 FAN_BUSY 拒绝");
        FanView locked = siloService.getFan(fan.id());
        assertEquals("LOCKED", locked.status());
        long winnerId = outcomes.stream().filter(o -> o.view() != null).findFirst().orElseThrow().view().id();
        assertEquals(winnerId, locked.lockedJobId());
    }

    // ---------- 测试辅助 ----------

    private FumigationView createApproved(long siloId, String tag) {
        FumigationView f = fumigationService.create(siloId, new CreateFumigationRequest(
                "fm-" + tag, W0, W0.plusSeconds(7200), THRESHOLD, List.of("密封", "熏蒸")));
        fumigationService.approve(f.id(), new ApproveRequest("fa-" + tag + "-1", 1, "审批人甲"));
        fumigationService.approve(f.id(), new ApproveRequest("fa-" + tag + "-2", 2, "审批人乙"));
        return fumigationService.getFumigation(f.id());
    }

    private FumigationView createFumigating(long siloId, String tag) {
        FumigationView f = createApproved(siloId, tag);
        return fumigationService.start(f.id(), new StartFumigationRequest("fs-" + tag, 3));
    }

    private FumigationView createAerating(long siloId, long fanId, String tag) {
        FumigationView f = createFumigating(siloId, tag);
        return fumigationService.finish(f.id(), new FinishFumigationRequest("ff-" + tag, 4, fanId));
    }

    private GasReadingResponse sample(long fumigationId, String requestId, int seq,
                                      String concentration, Instant measuredAt) {
        return fumigationService.sample(fumigationId, new GasReadingRequest(requestId, seq,
                new BigDecimal(concentration), measuredAt));
    }

    private <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<T> f1 = pool.submit(() -> {
                gate.await();
                return first.call();
            });
            Future<T> f2 = pool.submit(() -> {
                gate.await();
                return second.call();
            });
            gate.countDown();
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Outcome capture(Callable<FumigationView> call) {
        try {
            return new Outcome(call.call(), null);
        } catch (ApiException ex) {
            return new Outcome(null, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("并发用例出现非业务异常", ex);
        }
    }

    private ReadingOutcome captureReading(Callable<GasReadingResponse> call) {
        try {
            return new ReadingOutcome(call.call(), null);
        } catch (ApiException ex) {
            return new ReadingOutcome(null, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("并发用例出现非业务异常", ex);
        }
    }

    private record Outcome(FumigationView view, ApiException error) {
    }

    private record ReadingOutcome(GasReadingResponse response, ApiException error) {
    }
}
