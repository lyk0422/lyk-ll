package com.example.starter;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.dto.GrainDtos.AddRosterPointsRequest;
import com.example.starter.dto.GrainDtos.FramePointReading;
import com.example.starter.dto.GrainDtos.GasFrameRequest;
import com.example.starter.dto.GrainDtos.GasFrameView;
import com.example.starter.dto.GrainDtos.RegisterRosterRequest;
import com.example.starter.dto.GrainDtos.ReleaseFumigationRequest;
import com.example.starter.dto.GrainDtos.ReleaseView;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多测点解除竞态并发测试：解除与增点、解除与新越限帧真实并发，
 * 不得留下“缺点却已放锁”或“越限帧已入库却按旧证据放行”的状态。
 * 通过门闩协调并发，断言最终数据与资源状态，并设置超时。
 */
class FumigationFrameConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void releaseCompetingWithAddPointLeavesConsistentState() throws Exception {
        var silo = newSilo("S-MC-1");
        var fan = newFan("F-MC-1");
        long orderId = controlledAeratingOrder(silo.id(), fan.id(), "mc-1");
        safeThreeFrames(orderId, 6, "mc-1");
        assertEquals(9, fumigationService.getOrder(orderId).version());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            // 线程 A 用版本 9 解除；线程 B 用版本 9 增测点 P3
            Future<Result<ReleaseView>> releaseFuture = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.release(orderId,
                        new ReleaseFumigationRequest("mc-1-rel", 9, List.of("carol", "dave"))));
            });
            Future<Result<com.example.starter.dto.GrainDtos.RosterView>> addFuture = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.addRosterPoints(orderId,
                        new AddRosterPointsRequest("mc-1-add", 9, List.of("P3"))));
            });
            gate.countDown();
            Result<ReleaseView> releaseOutcome = releaseFuture.get(15, TimeUnit.SECONDS);
            Result<com.example.starter.dto.GrainDtos.RosterView> addOutcome =
                    addFuture.get(15, TimeUnit.SECONDS);

            boolean releaseOk = releaseOutcome.error() == null;
            boolean addOk = addOutcome.error() == null;
            // 两者都持版本 9，至多一个成功；先提交者使另一方的期望版本失效
            assertTrue(releaseOk || addOk, "解除与增点必有一个先提交成功");
            assertFalse(releaseOk && addOk, "解除与增点不得同时成功");
            if (releaseOk) {
                // 解除先提交：增点必须失败，名单停留在版本 1，不存在缺点却已放锁
                assertEquals(FumigationStatus.RELEASED, fumigationService.getOrder(orderId).status());
                assertEquals(1, fumigationService.getRoster(orderId).rosterVersion());
                assertEquals(List.of("P1", "P2"), fumigationService.getRoster(orderId).points());
                assertEquals("IDLE", siloService.getFan(fan.id()).status());
                assertEquals(1, releaseOutcome.value().checkedRosterVersion());
            } else {
                // 增点先提交：解除必须 409，仓锁与风机不释放，待解除确认失效
                assertEquals("VERSION_CONFLICT", releaseOutcome.error().getCode());
                assertEquals(409, releaseOutcome.error().getStatus().value());
                assertEquals(FumigationStatus.AERATING, fumigationService.getOrder(orderId).status());
                assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());
                assertEquals("LOCKED", siloService.getFan(fan.id()).status());
                assertEquals(2, fumigationService.getRoster(orderId).rosterVersion());
                assertEquals(0, countRows("fumigation_release"));
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void releaseCompetingWithExceedingFrameMustNotReleaseOnStaleEvidence() throws Exception {
        var silo = newSilo("S-MC-2");
        var fan = newFan("F-MC-2");
        long orderId = controlledAeratingOrder(silo.id(), fan.id(), "mc-2");
        safeThreeFrames(orderId, 6, "mc-2");
        assertEquals(9, fumigationService.getOrder(orderId).version());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            // 线程 A 用版本 9 解除；线程 B 用版本 9 上报 P2 越限的第 4 帧
            Future<Result<ReleaseView>> releaseFuture = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.release(orderId,
                        new ReleaseFumigationRequest("mc-2-rel", 9, List.of("carol", "dave"))));
            });
            Future<Result<GasFrameView>> frameFuture = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.ingestGasFrame(orderId,
                        new GasFrameRequest("mc-2-f4", 9, 4, U0.plusSeconds(6300),
                                List.of(new FramePointReading("P1", new BigDecimal("4.000")),
                                        new FramePointReading("P2", new BigDecimal("50.000"))))));
            });
            gate.countDown();
            Result<ReleaseView> releaseOutcome = releaseFuture.get(15, TimeUnit.SECONDS);
            Result<GasFrameView> frameOutcome = frameFuture.get(15, TimeUnit.SECONDS);

            boolean releaseOk = releaseOutcome.error() == null;
            boolean frameOk = frameOutcome.error() == null;
            // 真正禁止的是：越限帧作为正常证据（非迟到）入库的同时解除成功
            assertFalse(frameOk && !frameOutcome.value().late() && releaseOk,
                    "越限正常帧与解除不得同时成功");
            if (releaseOk) {
                // 解除先提交：越限帧只能成为迟到帧，释放证据仍冻结到序号 3
                assertTrue(frameOk, "解除先提交时越限帧应作为迟到帧受理");
                assertTrue(frameOutcome.value().late());
                assertEquals(3, releaseOutcome.value().lastSeqNo());
                assertEquals(FumigationStatus.RELEASED, fumigationService.getOrder(orderId).status());
            } else {
                // 越限帧先成功（版本变 10）：旧版本解除必须拒绝，仓锁与风机不释放
                assertEquals("VERSION_CONFLICT", releaseOutcome.error().getCode());
                assertFalse(frameOutcome.value().late());
                assertFalse(frameOutcome.value().safe());
                assertEquals(FumigationStatus.AERATING, fumigationService.getOrder(orderId).status());
                assertEquals("LOCKED", siloService.getFan(fan.id()).status());
                assertEquals(0, countRows("fumigation_release"));
                // 最新三帧 2,3,4 含越限帧：用新版本解除也必须拒绝
                ApiException again = assertThrowsBusiness(() -> fumigationService.release(orderId,
                        new ReleaseFumigationRequest("mc-2-rel-2", 10, List.of("carol", "dave"))));
                assertEquals("GAS_NOT_SAFE", again.getCode());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentReleasesOnControlledOrderOnlyOneSucceeds() throws Exception {
        long orderId = controlledAeratingOrder(newSilo("S-MC-3").id(), newFan("F-MC-3").id(), "mc-3");
        safeThreeFrames(orderId, 6, "mc-3");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Result<ReleaseView>> f1 = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.release(orderId,
                        new ReleaseFumigationRequest("mc-3-rel-1", 9, List.of("carol", "dave"))));
            });
            Future<Result<ReleaseView>> f2 = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.release(orderId,
                        new ReleaseFumigationRequest("mc-3-rel-2", 9, List.of("carol", "dave"))));
            });
            gate.countDown();
            Result<ReleaseView> o1 = f1.get(15, TimeUnit.SECONDS);
            Result<ReleaseView> o2 = f2.get(15, TimeUnit.SECONDS);

            long succeeded = List.of(o1, o2).stream().filter(o -> o.error() == null).count();
            assertEquals(1, succeeded, "并发解除只能成功一次");
            assertEquals(1, countRows("fumigation_release"));
            ReleaseView stored = fumigationService.getRelease(orderId);
            assertNotNull(stored.checkedRosterVersion());
            assertEquals(1, stored.checkedRosterVersion());
            assertEquals(FumigationStatus.RELEASED, fumigationService.getOrder(orderId).status());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 建仓建风机、申请、双审批、登记 P1/P2 名单、开始、转散气，返回散气中单号（版本 6）。 */
    private long controlledAeratingOrder(long siloId, long fanId, String prefix) {
        var order = applyFumigation(siloId, prefix + "-apply");
        approveTwice(order.id(), prefix);
        fumigationService.registerRoster(order.id(),
                new RegisterRosterRequest(prefix + "-roster", 3, List.of("P1", "P2")));
        startFumigation(order.id(), 4, prefix + "-start");
        beginAeration(order.id(), 5, fanId, prefix + "-aer");
        return order.id();
    }

    /** 上报连续三帧安全帧（P1/P2 均 4.000ppm），首末恰间隔 30 分钟。 */
    private void safeThreeFrames(long orderId, int version, String prefix) {
        String[][] frames = {
                {"4.000", "4.000"}, {"4.000", "4.000"}, {"4.000", "4.000"}};
        for (int i = 0; i < frames.length; i++) {
            fumigationService.ingestGasFrame(orderId,
                    new GasFrameRequest(prefix + "-f" + (i + 1), version + i, i + 1,
                            U0.plusSeconds(3600L + 900L * i),
                            List.of(new FramePointReading("P1", new BigDecimal(frames[i][0])),
                                    new FramePointReading("P2", new BigDecimal(frames[i][1])))));
        }
    }

    private static ApiException assertThrowsBusiness(Runnable runnable) {
        try {
            runnable.run();
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("预期业务异常未抛出");
    }

    private static <T> Result<T> call(Callable<T> callable) {
        try {
            return new Result<>(callable.call(), null);
        } catch (ApiException ex) {
            return new Result<>(null, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("并发用例出现非业务异常", ex);
        }
    }

    private record Result<T>(T value, ApiException error) {
    }
}
