package com.example.starter;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.dto.GrainDtos.AddPointsRequest;
import com.example.starter.dto.GrainDtos.GasFrameRequest;
import com.example.starter.dto.GrainDtos.GasFrameView;
import com.example.starter.dto.GrainDtos.ReleaseFumigationRequest;
import com.example.starter.dto.GrainDtos.ReleaseView;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多测点解除竞态 H2 事务/并发测试：解除与新越限帧竞争、解除与散气中增点竞争，
 * 任何提交顺序下都不得出现“证据不完整/名单已变却已放锁”的中间态；并发解除仅一个成功。
 * 门闩协调真实并发，future 设置超时，断言最终单据、仓锁、风机与帧/名单数据。
 */
class MultiPointConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void unsafeFrameCompetingWithReleaseNeverReleasesOnStaleEvidence() throws Exception {
        var ctx = multiPointAeratingContext("S-MC-1", "F-MC-1", "mc-1");
        safeThreeFrames(ctx.orderId(), 6, ctx.points(), "mc-1");
        assertEquals(9, fumigationService.getOrder(ctx.orderId()).version());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Result<ReleaseView>> releaseFuture = pool.submit(() -> {
                gate.await();
                return call(() -> releaseFumigation(ctx.orderId(), 9, "mc-1-rel"));
            });
            Future<Result<GasFrameView>> frameFuture = pool.submit(() -> {
                gate.await();
                return call(() -> submitFrame(ctx.orderId(), 9, 4, U0.plusSeconds(9000),
                        "mc-1-f4-high", ctx.points(), "4.000", "50.000"));
            });
            gate.countDown();
            Result<ReleaseView> releaseOutcome = releaseFuture.get(15, TimeUnit.SECONDS);
            Result<GasFrameView> frameOutcome = frameFuture.get(15, TimeUnit.SECONDS);

            boolean released = releaseOutcome.error() == null;
            boolean frameAccepted = frameOutcome.error() == null;
            // 禁止态：越限帧作为正常证据（非迟到）成功的同时解除也成功——等于用旧三帧放行
            assertFalse(frameAccepted && released && !frameOutcome.value().late(),
                    "越限正常帧与解除不得同时成功");

            if (released) {
                // 解除先提交：越限帧只能迟到受理，释放证据冻结到帧 3、名单版本 1
                assertTrue(frameAccepted, "解除先提交时越限帧应作为迟到帧受理");
                assertTrue(frameOutcome.value().late());
                assertFalse(frameOutcome.value().safe());
                assertEquals(3, releaseOutcome.value().lastSeqNo());
                assertEquals(1, releaseOutcome.value().listVersion());
                assertEquals(FumigationStatus.RELEASED,
                        fumigationService.getOrder(ctx.orderId()).status());
                assertEquals("IDLE", siloService.getFan(ctx.fanId()).status());
            } else {
                // 越限帧先提交（版本变 10）：旧版本解除必须拒绝，仓锁风机不释放
                assertEquals("VERSION_CONFLICT", releaseOutcome.error().getCode());
                assertFalse(frameOutcome.value().late());
                assertEquals(FumigationStatus.AERATING,
                        fumigationService.getOrder(ctx.orderId()).status());
                assertEquals(SiloStatus.AERATING, siloService.getSilo(ctx.siloId()).status());
                assertEquals("LOCKED", siloService.getFan(ctx.fanId()).status());
                assertEquals(0, countRows("fumigation_release"));
                // 新越限帧打断连续段，之后即便用新版本也不能解除
                ApiException blocked = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                        () -> releaseFumigation(ctx.orderId(), 10, "mc-1-rel-after"));
                assertEquals("GAS_NOT_SAFE", blocked.getCode());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void addPointCompetingWithReleaseKeepsListAndLockConsistent() throws Exception {
        var ctx = multiPointAeratingContext("S-MC-2", "F-MC-2", "mc-2");
        safeThreeFrames(ctx.orderId(), 6, ctx.points(), "mc-2");
        assertEquals(9, fumigationService.getOrder(ctx.orderId()).version());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Result<ReleaseView>> releaseFuture = pool.submit(() -> {
                gate.await();
                return call(() -> releaseFumigation(ctx.orderId(), 9, "mc-2-rel"));
            });
            Future<Result<Object>> addFuture = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.addPoints(ctx.orderId(),
                        new AddPointsRequest("mc-2-add", 9, List.of("P3"))));
            });
            gate.countDown();
            Result<ReleaseView> releaseOutcome = releaseFuture.get(15, TimeUnit.SECONDS);
            Result<Object> addOutcome = addFuture.get(15, TimeUnit.SECONDS);

            boolean released = releaseOutcome.error() == null;
            boolean added = addOutcome.error() == null;
            // 两者绝不允许同时成功：那会造成名单已变（v2）却已按 v1 放锁
            assertFalse(released && added, "增点与解除不得同时成功");

            Long pointCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gas_point WHERE fumigation_id = ?", Long.class, ctx.orderId());
            if (released) {
                // 解除先提交：增补在散气态校验处失败回滚，P3 不存在，名单版本仍 1，锁合法释放
                assertEquals("INVALID_ORDER_STATE", addOutcome.error().getCode());
                assertEquals(2L, pointCount, "解除后增补必须回滚，不得新增测点");
                assertEquals(1, fumigationService.getPointList(ctx.orderId()).listVersion());
                assertEquals(FumigationStatus.RELEASED,
                        fumigationService.getOrder(ctx.orderId()).status());
                assertEquals("IDLE", siloService.getFan(ctx.fanId()).status());
            } else {
                // 增点先提交：版本变 10、名单变 v2，旧版本解除拒绝，锁不释放，旧确认失效
                assertEquals("VERSION_CONFLICT", releaseOutcome.error().getCode());
                assertEquals(3L, pointCount);
                var order = fumigationService.getOrder(ctx.orderId());
                assertEquals(10, order.version());
                assertEquals(2, order.pointListVersion());
                assertEquals(FumigationStatus.AERATING, order.status());
                assertEquals("LOCKED", siloService.getFan(ctx.fanId()).status());
                assertEquals(0, countRows("fumigation_release"));
                assertFalse(fumigationService.frameChain(ctx.orderId()).releasable());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentMultipointReleasesOnlyOneSucceeds() throws Exception {
        var ctx = multiPointAeratingContext("S-MC-3", "F-MC-3", "mc-3");
        safeThreeFrames(ctx.orderId(), 6, ctx.points(), "mc-3");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Result<ReleaseView>> f1 = pool.submit(() -> {
                gate.await();
                return call(() -> releaseFumigation(ctx.orderId(), 9, "mc-3-rel-1"));
            });
            Future<Result<ReleaseView>> f2 = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.release(ctx.orderId(),
                        new ReleaseFumigationRequest("mc-3-rel-2", 9, List.of("carol", "dave"))));
            });
            gate.countDown();
            Result<ReleaseView> o1 = f1.get(15, TimeUnit.SECONDS);
            Result<ReleaseView> o2 = f2.get(15, TimeUnit.SECONDS);

            long succeeded = List.of(o1, o2).stream().filter(o -> o.error() == null).count();
            long rejected = List.of(o1, o2).stream()
                    .filter(o -> o.error() != null && List.of(
                            "RELEASE_ALREADY_DONE", "VERSION_CONFLICT", "INVALID_ORDER_STATE")
                            .contains(o.error().getCode()))
                    .count();
            assertEquals(1, succeeded, "并发解除只能成功一次");
            assertEquals(1, rejected, "落选解除必须被业务拒绝");
            assertEquals(1, countRows("fumigation_release"));
            assertEquals(FumigationStatus.RELEASED,
                    fumigationService.getOrder(ctx.orderId()).status());
            assertEquals("IDLE", siloService.getFan(ctx.fanId()).status());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
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
