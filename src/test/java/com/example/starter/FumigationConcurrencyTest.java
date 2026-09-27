package com.example.starter;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.GasReadingRequest;
import com.example.starter.dto.FumigationDtos.GasReadingView;
import com.example.starter.dto.FumigationDtos.ReleaseConfirmRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熏蒸并发测试：高浓度补录与解除竞争不得用旧三点放行；同序号并发采样唯一受理；
 * 同审批人并发审批只记一次。门闩协调真实并发，断言最终结果与数据库状态，并设置超时。
 */
class FumigationConcurrencyTest extends FumigationTestSupport {

    @Test
    void releaseNeverUsesStaleThreePointsAgainstRacingHighReading() throws Exception {
        SiloView silo = newSilo("S-RACE");
        FanView fan = newFan("F-RACE");
        FumigationView aerating = newAeratingOrder(silo.id(), fan.id(), "race");
        sampleThreeGoodPoints(aerating.id(), "race");
        fumigationService.confirmRelease(aerating.id(),
                new ReleaseConfirmRequest("f-race-cf1", "复核人丙", 5));

        // 线程A：第二名复核人确认解除；线程B：高浓度补录（序号4，越限）
        List<Outcome> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.confirmRelease(aerating.id(),
                        new ReleaseConfirmRequest("f-race-cf2", "复核人丁", 6))),
                () -> capture(() -> fumigationService.sample(aerating.id(), new GasReadingRequest(
                        "f-race-g4", 4, new BigDecimal("9.999"), F0.plusHours(11)))));

        Outcome release = outcomes.get(0);
        Outcome reading = outcomes.get(1);
        FumigationView order = fumigationService.getOrder(aerating.id()).order();

        if (release.error() == null) {
            // 解除先胜出：补录只记迟到，已冻结证据不变
            assertEquals(FumigationStatus.RELEASED, order.status());
            assertEquals("LATE_RECORDED", ((GasReadingView) reading.value()).result());
            assertEquals(3, countRows("gas_reading"), "关闭后读数不得写入单据");
            assertEquals(1, countRows("late_event"));
            assertEquals(SiloStatus.MONITORING, siloService.getSilo(silo.id()).status());
            assertEquals("IDLE", siloService.getFan(fan.id()).status());
        } else {
            // 补录先胜出：越限打断连续计数，解除必须失败且均不释放
            assertEquals("RELEASE_CONDITION_NOT_MET", release.error().getCode());
            assertEquals(FumigationStatus.AERATING, order.status());
            assertEquals(4, countRows("gas_reading"));
            assertEquals(0, countRows("late_event"));
            assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());
            assertEquals("LOCKED", siloService.getFan(fan.id()).status());
            assertEquals(1, countRows("fumigation_confirmation"),
                    "失败的确认不得落库（不占键）");
        }
    }

    @Test
    void concurrentSameSeqReadingsOnlyOneAccepted() throws Exception {
        SiloView silo = newSilo("S-CSEQ");
        FumigationView aerating = newAeratingOrder(silo.id(), newFan("F-CSEQ").id(), "cseq");

        List<Outcome> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.sample(aerating.id(), new GasReadingRequest(
                        "f-cseq-g1", 1, new BigDecimal("0.400"), F0.plusHours(10)))),
                () -> capture(() -> fumigationService.sample(aerating.id(), new GasReadingRequest(
                        "f-cseq-g2", 1, new BigDecimal("0.450"), F0.plusHours(10).plusMinutes(1)))));

        long accepted = outcomes.stream().filter(o -> o.error() == null).count();
        long seqConflict = outcomes.stream()
                .filter(o -> o.error() != null && "SEQ_CONFLICT".equals(o.error().getCode()))
                .count();
        assertEquals(1, accepted, "同序号并发采样只能唯一受理");
        assertEquals(1, seqConflict, "落选请求应以 SEQ_CONFLICT 拒绝");
        assertEquals(1, countRows("gas_reading"));
    }

    @Test
    void concurrentSameApproverOnlyOneRecorded() throws Exception {
        SiloView silo = newSilo("S-CAPR");
        FumigationView order = newOrder(silo.id(), "capr");

        List<Outcome> outcomes = runConcurrently(
                () -> capture(() -> fumigationService.approve(order.id(),
                        new com.example.starter.dto.FumigationDtos.ApproveRequest(
                                "f-capr-a1", "审批人甲", 1))),
                () -> capture(() -> fumigationService.approve(order.id(),
                        new com.example.starter.dto.FumigationDtos.ApproveRequest(
                                "f-capr-a2", "审批人甲", 1))));

        long accepted = outcomes.stream().filter(o -> o.error() == null).count();
        long sameApprover = outcomes.stream()
                .filter(o -> o.error() != null && "SAME_APPROVER".equals(o.error().getCode()))
                .count();
        assertEquals(1, accepted, "同一审批人并发审批只受理一次");
        assertEquals(1, sameApprover, "落选请求应以 SAME_APPROVER 拒绝");
        assertEquals(1, countRows("fumigation_approval"));
        assertEquals(2, fumigationService.getOrder(order.id()).order().version());
    }

    private List<Outcome> runConcurrently(Callable<Outcome> first, Callable<Outcome> second)
            throws Exception {
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

    private Outcome capture(Callable<Object> call) {
        try {
            return new Outcome(call.call(), null);
        } catch (ApiException ex) {
            return new Outcome(null, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("并发用例出现非业务异常", ex);
        }
    }

    private record Outcome(Object value, ApiException error) {
    }
}
