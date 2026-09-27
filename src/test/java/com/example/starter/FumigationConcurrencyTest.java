package com.example.starter;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.CreateFumigationRequest;
import com.example.starter.dto.FumigationDtos.FinishRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.ReleaseRequest;
import com.example.starter.dto.FumigationDtos.SampleRequest;
import com.example.starter.dto.FumigationDtos.SampleView;
import com.example.starter.dto.FumigationDtos.StartRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熏蒸并发测试：同键并发批准只生效一次；同仓重叠窗并发申请唯一受理；
 * 同一风机并发转散气只锁一台；高浓度补录与解除竞争不得用旧三点放行。
 * 通过门闩协调真实并发，断言最终结果与数据库状态，并设置超时。
 */
class FumigationConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void concurrentApproveSameKeyTakesEffectOnce() throws Exception {
        SiloView silo = newSilo("S-FC1");
        FumigationView created = newFumigation(silo.id(), "fc1-create");
        ApproveRequest request = new ApproveRequest("fc1-ap", 1, "approver-a", I0.plusSeconds(3600));

        List<Outcome<FumigationView>> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.approve(created.id(), request)),
                () -> capture(() -> fumigationService.approve(created.id(), request)));

        long succeeded = outcomes.stream().filter(o -> o.error() == null).count();
        assertEquals(2, succeeded, "同键同参并发都应返回成功（一个为重放）");
        assertEquals(1, outcomes.stream().filter(o -> o.error() == null && !o.value().replayed()).count());
        assertEquals(1, outcomes.stream().filter(o -> o.error() == null && o.value().replayed()).count());
        assertEquals(1, countRows("fumigation_approval"));
        assertEquals(2, fumigationService.getFumigation(created.id()).version());
    }

    @Test
    void concurrentCreateOverlappingWindowOnlyOneAccepted() throws Exception {
        SiloView silo = newSilo("S-FC2");
        CreateFumigationRequest first = new CreateFumigationRequest("fc2-a",
                I0.plusSeconds(7200), I0.plusSeconds(14400), new BigDecimal("0.5"));
        CreateFumigationRequest second = new CreateFumigationRequest("fc2-b",
                I0.plusSeconds(10800), I0.plusSeconds(18000), new BigDecimal("0.5"));

        List<Outcome<FumigationView>> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.create(silo.id(), first)),
                () -> capture(() -> fumigationService.create(silo.id(), second)));

        long accepted = outcomes.stream().filter(o -> o.error() == null).count();
        long rejected = outcomes.stream()
                .filter(o -> o.error() != null && "WINDOW_CONFLICT".equals(o.error().getCode()))
                .count();
        assertEquals(1, accepted, "同仓重叠窗并发申请只能唯一受理");
        assertEquals(1, rejected, "另一个应以 422 窗口冲突拒绝");
        assertEquals(1, countRows("fumigation_order"));
    }

    @Test
    void concurrentFinishSameFanOnlyOneLocks() throws Exception {
        SiloView siloA = newSilo("S-FC3A");
        SiloView siloB = newSilo("S-FC3B");
        FanView fan = newFan("F-FC3");
        FumigationView first = newFumigation(siloA.id(), "fc3-create-a");
        FumigationView second = newFumigation(siloB.id(), "fc3-create-b");
        for (FumigationView f : List.of(first, second)) {
            approveNow(f.id(), "fc3-ap1-" + f.id(), "approver-a");
            approveNow(f.id(), "fc3-ap2-" + f.id(), "approver-b");
            fumigationService.start(f.id(), new StartRequest("fc3-st-" + f.id(), 3,
                    I0.plusSeconds(7200)));
        }

        List<Outcome<FumigationView>> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.finish(first.id(),
                        new FinishRequest("fc3-fi-a", 4, fan.id(), I0.plusSeconds(10800)))),
                () -> capture(() -> fumigationService.finish(second.id(),
                        new FinishRequest("fc3-fi-b", 4, fan.id(), I0.plusSeconds(10800)))));

        long accepted = outcomes.stream().filter(o -> o.error() == null).count();
        long fanBusy = outcomes.stream()
                .filter(o -> o.error() != null && "FAN_BUSY".equals(o.error().getCode()))
                .count();
        assertEquals(1, accepted, "同一台风机同一时刻只服务一个散气作业");
        assertEquals(1, fanBusy, "落选请求应以 FAN_BUSY 拒绝");
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_order WHERE status = 'AERATING'", Integer.class).intValue());
    }

    @Test
    void releaseAndHighConcentrationBackfillNeverUsesStalePoints() throws Exception {
        SiloView silo = newSilo("S-FC4");
        FanView fan = newFan("F-FC4");
        FumigationView created = newFumigation(silo.id(), "fc4-create");
        toAerating(created.id(), fan.id());
        // 已有连续三点安全读数（首末恰等30分钟）
        sample(created.id(), "fc4-s1", 1, "0.4", I0.plusSeconds(11000));
        sample(created.id(), "fc4-s2", 2, "0.4", I0.plusSeconds(11900));
        sample(created.id(), "fc4-s3", 3, "0.4", I0.plusSeconds(12800));

        ReleaseRequest release = new ReleaseRequest("fc4-rl", 5, "reviewer-a", "reviewer-b",
                I0.plusSeconds(14000));
        SampleRequest backfill = new SampleRequest("fc4-s4", 4, new BigDecimal("0.9"),
                I0.plusSeconds(13100));

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.release(created.id(), release)),
                () -> capture(() -> fumigationService.sample(created.id(), backfill)));

        Outcome<Object> releaseOutcome = outcomes.get(0);
        Outcome<Object> sampleOutcome = outcomes.get(1);
        FumigationView current = fumigationService.getFumigation(created.id());

        if (releaseOutcome.error() == null) {
            // 解除先提交：补录只能记迟到，已冻结证据不变
            assertEquals(FumigationStatus.RELEASED, current.status());
            assertTrue(sampleOutcome.error() == null && ((SampleView) sampleOutcome.value()).late(),
                    "解除后补录只记迟到");
            assertEquals(3, countRows("fumigation_reading"));
            assertEquals(1, countRows("late_event"));
            assertEquals("IDLE", siloService.getFan(fan.id()).status());
            assertEquals(SiloStatus.MONITORING, siloService.getSilo(silo.id()).status());
        } else {
            // 补录先提交：解除不得用旧三点放行，仓锁与风机保持
            assertEquals("CONCENTRATION_ABOVE_THRESHOLD", releaseOutcome.error().getCode());
            assertEquals(FumigationStatus.AERATING, current.status());
            assertEquals(4, countRows("fumigation_reading"));
            assertEquals(0, countRows("late_event"));
            assertEquals("LOCKED", siloService.getFan(fan.id()).status());
            assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());
        }
    }

    @Test
    void concurrentSampleSameKeyOnlyOneRow() throws Exception {
        SiloView silo = newSilo("S-FC5");
        FanView fan = newFan("F-FC5");
        FumigationView created = newFumigation(silo.id(), "fc5-create");
        toAerating(created.id(), fan.id());
        SampleRequest request = new SampleRequest("fc5-s1", 1, new BigDecimal("0.4"),
                I0.plusSeconds(11000));

        List<Outcome<SampleView>> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.sample(created.id(), request)),
                () -> capture(() -> fumigationService.sample(created.id(), request)));

        long succeeded = outcomes.stream().filter(o -> o.error() == null).count();
        assertEquals(2, succeeded, "同键同参并发采样都应返回成功（一个为重放）");
        assertEquals(1, countRows("fumigation_reading"));
        Long idemRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_idempotency WHERE request_id = 'fc5-s1'", Long.class);
        assertEquals(1, idemRows == null ? 0 : idemRows.longValue());
    }

    private <T> List<Outcome<T>> runConcurrently(Callable<Outcome<T>> first,
                                                 Callable<Outcome<T>> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Outcome<T>> f1 = pool.submit(() -> {
                gate.await();
                return first.call();
            });
            Future<Outcome<T>> f2 = pool.submit(() -> {
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

    private <T> Outcome<T> capture(Callable<T> call) {
        try {
            return new Outcome<>(call.call(), null);
        } catch (ApiException ex) {
            return new Outcome<>(null, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("并发用例出现非业务异常", ex);
        }
    }

    private record Outcome<T>(T value, ApiException error) {
    }
}
