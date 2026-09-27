package com.example.starter;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.dto.GrainDtos.ApplyFumigationRequest;
import com.example.starter.dto.GrainDtos.ApproveFumigationRequest;
import com.example.starter.dto.GrainDtos.BeginAerationRequest;
import com.example.starter.dto.GrainDtos.FumigationView;
import com.example.starter.dto.GrainDtos.GasReadingRequest;
import com.example.starter.dto.GrainDtos.GasReadingView;
import com.example.starter.dto.GrainDtos.ReleaseFumigationRequest;
import com.example.starter.dto.GrainDtos.ReleaseView;
import com.example.starter.dto.GrainDtos.StartFumigationRequest;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;
import com.example.starter.util.Fingerprints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熏蒸并发与幂等测试：
 * 高浓度补录与解除竞争不得用旧三点放行；双解除并发只一个成功；申请/采样/解除同键重放、异参 409；
 * 结构化指纹对含分隔符文本与集合无歧义。通过门闩协调真实并发，断言最终数据与资源状态，并设置超时。
 */
class FumigationConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void highReadingCompetingWithReleaseMustNotReleaseOnStaleVersion() throws Exception {
        var silo = newSilo("S-FC-1");
        var fan = newFan("F-FC-1");
        long orderId = aeratingOrder(silo.id(), fan.id(), "fc-1");
        // 已有点 1,2,3 安全，当前版本 8，三点首末恰 30 分钟可解除
        safeThreeReadings(orderId, 5, "fc-1");
        assertEquals(8, fumigationService.getOrder(orderId).version());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            // 线程 A 用旧版本 8 解除；线程 B 补录序号 4 的高浓度读数（同样带版本 8）
            Future<Result<ReleaseView>> releaseFuture = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.release(orderId,
                        new ReleaseFumigationRequest("fc-1-rel", 8, List.of("carol", "dave"))));
            });
            Future<Result<GasReadingView>> readingFuture = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.ingestGasReading(orderId,
                        new GasReadingRequest("fc-1-g4-high", 8, 4, new BigDecimal("50.000"),
                                U0.plusSeconds(9000))));
            });
            gate.countDown();
            Result<ReleaseView> releaseOutcome = releaseFuture.get(15, TimeUnit.SECONDS);
            Result<GasReadingView> readingOutcome = readingFuture.get(15, TimeUnit.SECONDS);

            boolean readingOk = readingOutcome.error() == null;
            boolean releaseOk = releaseOutcome.error() == null;
            // 真正禁止的是：高浓度读数作为正常证据（非迟到）入库的同时解除成功——那等于用旧三点放行
            assertFalse(readingOk && !readingOutcome.value().late() && releaseOk,
                    "高浓度正常补录与解除不得同时成功");
            if (releaseOk) {
                // 解除先于补录提交：补录只能成为迟到读数，释放证据仍只冻结到序号 3
                assertTrue(readingOk, "解除先提交时补录应作为迟到读数受理");
                assertTrue(readingOutcome.value().late());
                assertEquals(3, releaseOutcome.value().lastSeqNo());
                assertEquals(FumigationStatus.RELEASED, fumigationService.getOrder(orderId).status());
            } else {
                // 补录先成功（版本变 9）：旧版本解除必须拒绝，仓锁与风机不释放，不得用旧三点放行
                assertEquals("VERSION_CONFLICT", releaseOutcome.error().getCode());
                assertEquals(409, releaseOutcome.error().getStatus().value());
                assertFalse(readingOutcome.value().late());
                assertEquals(FumigationStatus.AERATING, fumigationService.getOrder(orderId).status());
                assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());
                assertEquals("LOCKED", siloService.getFan(fan.id()).status());
                assertEquals(0, countRows("fumigation_release"));
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentReleasesOnlyOneSucceeds() throws Exception {
        long orderId = aeratingOrder(newSilo("S-FC-2").id(), newFan("F-FC-2").id(), "fc-2");
        safeThreeReadings(orderId, 5, "fc-2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Result<ReleaseView>> f1 = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.release(orderId,
                        new ReleaseFumigationRequest("fc-2-rel-1", 8, List.of("carol", "dave"))));
            });
            Future<Result<ReleaseView>> f2 = pool.submit(() -> {
                gate.await();
                return call(() -> fumigationService.release(orderId,
                        new ReleaseFumigationRequest("fc-2-rel-2", 8, List.of("carol", "dave"))));
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
            assertEquals(FumigationStatus.RELEASED, fumigationService.getOrder(orderId).status());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void applyReplaySameParamsAndConflictOnDifferentParams() {
        var silo = newSilo("S-FC-3");
        var request = new ApplyFumigationRequest("fc-3-apply", U0, U0.plusSeconds(3600),
                "SYN-A", new BigDecimal("5.000"));

        FumigationView first = fumigationService.apply(silo.id(), request);
        FumigationView replay = fumigationService.apply(silo.id(), request);
        assertTrue(replay.replayed());
        assertEquals(first.id(), replay.id());
        assertEquals(1, countRows("fumigation_order"));

        ApiException conflict = assertThrows(ApiException.class, () -> fumigationService.apply(silo.id(),
                new ApplyFumigationRequest("fc-3-apply", U0, U0.plusSeconds(7200),
                        "SYN-A", new BigDecimal("5.000"))));
        assertEquals(409, conflict.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
        assertEquals(1, countRows("fumigation_order"));
    }

    @Test
    void readingReplayAndConflict() {
        long orderId = aeratingOrder(newSilo("S-FC-4").id(), newFan("F-FC-4").id(), "fc-4");
        var request = new GasReadingRequest("fc-4-g1", 5, 1, new BigDecimal("4.000"),
                U0.plusSeconds(3600));

        GasReadingView first = fumigationService.ingestGasReading(orderId, request);
        GasReadingView replay = fumigationService.ingestGasReading(orderId, request);
        assertTrue(replay.replayed());
        assertEquals(first.id(), replay.id());
        assertEquals(1, countRows("gas_reading"));

        ApiException conflict = assertThrows(ApiException.class, () -> fumigationService.ingestGasReading(
                orderId, new GasReadingRequest("fc-4-g1", 5, 1, new BigDecimal("4.500"),
                        U0.plusSeconds(3600))));
        assertEquals(409, conflict.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
        assertEquals(1, countRows("gas_reading"));
    }

    @Test
    void releaseReplayReturnsFirstFrozenResult() {
        long orderId = aeratingOrder(newSilo("S-FC-5").id(), newFan("F-FC-5").id(), "fc-5");
        safeThreeReadings(orderId, 5, "fc-5");
        var request = new ReleaseFumigationRequest("fc-5-rel", 8, List.of("carol", "dave"));

        ReleaseView first = fumigationService.release(orderId, request);
        ReleaseView replay = fumigationService.release(orderId, request);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.id(), replay.id());
        assertEquals(first.lastSeqNo(), replay.lastSeqNo());
        assertEquals(1, countRows("fumigation_release"));

        // 解除后高浓度补录只记迟到，重放证据仍冻结到序号 3
        GasReadingView late = fumigationService.ingestGasReading(orderId,
                new GasReadingRequest("fc-5-late", 8, 4, new BigDecimal("80.000"),
                        U0.plusSeconds(10000)));
        assertTrue(late.late());
        assertEquals(3, fumigationService.getRelease(orderId).lastSeqNo());
    }

    @Test
    void startAndAerationActionsAreIdempotent() {
        var silo = newSilo("S-FC-6");
        var fan = newFan("F-FC-6");
        var order = fumigationService.apply(silo.id(),
                new ApplyFumigationRequest("fc-6-apply", U0, U0.plusSeconds(3600),
                        "A", new BigDecimal("5.000")));
        fumigationService.approve(order.id(), new ApproveFumigationRequest("fc-6-a1", 1, "alice"));
        fumigationService.approve(order.id(), new ApproveFumigationRequest("fc-6-a2", 2, "bob"));

        var startRequest = new StartFumigationRequest("fc-6-start", 3, U0);
        FumigationView started = fumigationService.start(order.id(), startRequest);
        FumigationView startReplay = fumigationService.start(order.id(), startRequest);
        assertTrue(startReplay.replayed());
        assertEquals(started.id(), startReplay.id());
        assertEquals(4, startReplay.version());

        var aerationRequest = new BeginAerationRequest("fc-6-aer", 4, fan.id(), U0.plusSeconds(600));
        FumigationView aerating = fumigationService.beginAeration(order.id(), aerationRequest);
        FumigationView aerationReplay = fumigationService.beginAeration(order.id(), aerationRequest);
        assertTrue(aerationReplay.replayed());
        assertEquals(aerating.id(), aerationReplay.id());
        assertEquals(5, aerationReplay.version());
        assertEquals(1, countRows("fumigation_order"));

        // 同键异参 409
        ApiException conflict = assertThrows(ApiException.class, () -> fumigationService.start(order.id(),
                new StartFumigationRequest("fc-6-start", 3, U0.plusSeconds(1))));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
    }

    @Test
    void structuredFingerprintDistinguishesDelimiterTextAndCollections() {
        // 含竖线/冒号的合法文本不得伪造字段边界
        assertNotEquals(Fingerprints.structured("a|b", "c"), Fingerprints.structured("a", "b|c"));
        assertNotEquals(Fingerprints.structured("S:1:x"), Fingerprints.structured("S", 1, "x"));

        // 集合顺序参与指纹；空集合与 null 不同
        assertNotEquals(
                Fingerprints.structured(Fingerprints.stringList(List.of("a", "b"))),
                Fingerprints.structured(Fingerprints.stringList(List.of("b", "a"))));
        assertNotEquals(Fingerprints.stringList(List.of()), Fingerprints.stringList(null));
    }

    /** 建仓建风机、申请、双审批、开始、转散气，返回散气中单号（版本 5）。 */
    private long aeratingOrder(long siloId, long fanId, String prefix) {
        var order = fumigationService.apply(siloId,
                new ApplyFumigationRequest(prefix + "-apply", U0, U0.plusSeconds(3600),
                        "A", new BigDecimal("5.000")));
        fumigationService.approve(order.id(), new ApproveFumigationRequest(prefix + "-a1", 1, "alice"));
        fumigationService.approve(order.id(), new ApproveFumigationRequest(prefix + "-a2", 2, "bob"));
        fumigationService.start(order.id(), new StartFumigationRequest(prefix + "-start", 3, U0));
        fumigationService.beginAeration(order.id(),
                new BeginAerationRequest(prefix + "-aer", 4, fanId, U0.plusSeconds(600)));
        return order.id();
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
