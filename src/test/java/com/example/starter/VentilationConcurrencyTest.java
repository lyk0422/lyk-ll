package com.example.starter;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.JobView;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.error.ApiException;
import com.example.starter.model.SiloStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发测试：同一仓并发下达唯一受理；同一风机并发争抢只服务一个仓。
 * 通过门闩协调真实并发，断言最终结果与数据库状态，并设置超时。
 */
class VentilationConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void concurrentJobsOnSameSiloOnlyOneAccepted() throws Exception {
        SiloView silo = newSilo("S-CC");
        FanView fan1 = newFan("F-CC1");
        FanView fan2 = newFan("F-CC2");
        raiseAlarm(silo.id(), "r-cc-alarm");

        List<Outcome> outcomes = runConcurrently(
                () -> capture(() -> ventilationService.createJob(silo.id(), newJobRequest("j-cc-1", fan1.id()))),
                () -> capture(() -> ventilationService.createJob(silo.id(), newJobRequest("j-cc-2", fan2.id()))));

        long accepted = outcomes.stream().filter(o -> o.error() == null).count();
        long rejected = outcomes.stream()
                .filter(o -> o.error() != null && "JOB_ALREADY_EXECUTING".equals(o.error().getCode()))
                .count();
        assertEquals(1, accepted, "并发下达只能唯一受理");
        assertEquals(1, rejected, "另一个并发请求应以 409 拒绝");
        assertEquals(1, countRows("ventilation_job"));
        assertEquals(SiloStatus.VENTILATING, siloService.getSilo(silo.id()).status());
    }

    @Test
    void concurrentFanContentionLocksExactlyOneJob() throws Exception {
        SiloView siloA = newSilo("S-FCA");
        SiloView siloB = newSilo("S-FCB");
        FanView fan = newFan("F-CONT");
        raiseAlarm(siloA.id(), "r-fca-alarm");
        raiseAlarm(siloB.id(), "r-fcb-alarm");

        List<Outcome> outcomes = runConcurrently(
                () -> capture(() -> ventilationService.createJob(siloA.id(), newJobRequest("j-fc-1", fan.id()))),
                () -> capture(() -> ventilationService.createJob(siloB.id(), newJobRequest("j-fc-2", fan.id()))));

        long accepted = outcomes.stream().filter(o -> o.error() == null).count();
        long fanBusy = outcomes.stream()
                .filter(o -> o.error() != null && "FAN_BUSY".equals(o.error().getCode()))
                .count();
        assertEquals(1, accepted, "同一台风机同一时刻只服务一个仓");
        assertEquals(1, fanBusy, "落选请求应以 FAN_BUSY 拒绝");

        // 锁定原子性：只有一个作业开始，且风机确实锁定到该作业；落选仓无作业、状态未变
        assertEquals(1, countRows("ventilation_job"));
        FanView locked = siloService.getFan(fan.id());
        assertEquals("LOCKED", locked.status());
        JobView winner = ventilationService.windows(siloA.id()).stream().findFirst()
                .orElseGet(() -> ventilationService.windows(siloB.id()).get(0));
        assertEquals(winner.id(), locked.lockedJobId());
        long loserSiloId = winner.siloId() == siloA.id() ? siloB.id() : siloA.id();
        assertEquals(SiloStatus.ALARMING, siloService.getSilo(loserSiloId).status());
    }

    private List<Outcome> runConcurrently(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Outcome> f1 = pool.submit(() -> {
                gate.await();
                return first.call();
            });
            Future<Outcome> f2 = pool.submit(() -> {
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

    private Outcome capture(Callable<JobView> call) {
        try {
            return new Outcome(call.call(), null);
        } catch (ApiException ex) {
            return new Outcome(null, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("并发用例出现非业务异常", ex);
        }
    }

    private record Outcome(JobView job, ApiException error) {
    }
}
