package com.example.starter.fishery;

import com.example.starter.fishery.api.BusinessRuleException;
import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.dto.TransferConfirmRequest;
import com.example.starter.fishery.api.dto.TransferCreateRequest;
import com.example.starter.fishery.api.dto.TransferResponse;
import com.example.starter.fishery.domain.TransferStatus;
import com.example.starter.fishery.init.SyntheticDataInitializer;
import com.example.starter.fishery.service.QuotaAdjustmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨船配额调剂：双方确认生效、恰等剩余额度边界、在途唯一、48 小时失效与幂等/并发。
 */
@SpringBootTest
class QuotaTransferTest extends AbstractFisheryTest {

    private static final int YEAR = T0.atZone(ZoneOffset.UTC).getYear();
    private static final String SPECIES = SyntheticDataInitializer.SPECIES_HAIRTAIL;

    @Autowired
    private QuotaAdjustmentService service;

    private String vesselA;
    private String vesselB;

    @BeforeEach
    void setUp() {
        vesselA = registerVesselWithQuota(SPECIES, "100.000");
        vesselB = registerVesselWithQuota(SPECIES, "50.000");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private TransferCreateRequest createRequest(String from, String to, String amount) {
        return new TransferCreateRequest(newRequestId(), from, to, SPECIES, YEAR, new BigDecimal(amount));
    }

    @Test
    void createTransfer_exactlyRemaining_acceptedAsPendingWith48hWindow() {
        // 调出船剩余额度 = 100 - 60 = 40，调剂量恰等剩余额度合法
        setUsedQuota(vesselA, SPECIES, YEAR, "60.000");

        TransferResponse created = service.createTransfer(createRequest(vesselA, vesselB, "40.000"));

        assertEquals(TransferStatus.PENDING, created.status());
        assertFalse(created.fromConfirmed());
        assertFalse(created.toConfirmed());
        assertFalse(created.replayed());
        // 确认时限按 UTC 计 48 小时
        assertEquals(created.createdAt().plus(java.time.Duration.ofHours(48)), created.expiresAt());
        assertNull(created.effectiveAt());
        // 未生效前双方额度不变
        assertEquals(0, quotaKg(vesselA, SPECIES, YEAR).compareTo(new BigDecimal("100.000")));
        assertEquals(0, quotaKg(vesselB, SPECIES, YEAR).compareTo(new BigDecimal("50.000")));
    }

    @Test
    void createTransfer_exceedsRemaining_rejectedAsWhole() {
        setUsedQuota(vesselA, SPECIES, YEAR, "60.000");

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.createTransfer(createRequest(vesselA, vesselB, "40.001")));
        assertTrue(ex.getMessage().contains("整笔拒绝"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota_transfer WHERE from_vessel = ?", Integer.class, vesselA));
        // 额度不变
        assertEquals(0, quotaKg(vesselA, SPECIES, YEAR).compareTo(new BigDecimal("100.000")));
    }

    @Test
    void createTransfer_sameVesselSameSpecies_onlyOnePendingAllowed() {
        service.createTransfer(createRequest(vesselA, vesselB, "10.000"));

        // 同船（调出方）同鱼种第二张进行中调剂单 → 409
        String vesselC = registerVesselWithQuota(SPECIES, "10.000");
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.createTransfer(createRequest(vesselA, vesselC, "5.000")));
        assertTrue(ex.getMessage().contains("唯一受理"));

        // 同船作为调入方同样受限
        assertThrows(ConflictException.class,
                () -> service.createTransfer(createRequest(vesselC, vesselB, "5.000")));
    }

    @Test
    void confirmTransfer_bothSidesConfirm_effectiveAndQuotasMovedAtomically() {
        TransferResponse created = service.createTransfer(createRequest(vesselA, vesselB, "40.000"));

        // 单方确认：仍 PENDING，双方额度均不变
        TransferResponse half = service.confirmTransfer(created.transferId(),
                new TransferConfirmRequest(newRequestId(), vesselA));
        assertEquals(TransferStatus.PENDING, half.status());
        assertTrue(half.fromConfirmed());
        assertFalse(half.toConfirmed());
        assertEquals(0, quotaKg(vesselA, SPECIES, YEAR).compareTo(new BigDecimal("100.000")));
        assertEquals(0, quotaKg(vesselB, SPECIES, YEAR).compareTo(new BigDecimal("50.000")));

        // 双方确认：生效与双方额度变更同一事务完成
        TransferResponse effective = service.confirmTransfer(created.transferId(),
                new TransferConfirmRequest(newRequestId(), vesselB));
        assertEquals(TransferStatus.EFFECTIVE, effective.status());
        assertNotNull(effective.effectiveAt());
        assertEquals(0, quotaKg(vesselA, SPECIES, YEAR).compareTo(new BigDecimal("60.000")),
                "调出方核减 40");
        assertEquals(0, quotaKg(vesselB, SPECIES, YEAR).compareTo(new BigDecimal("90.000")),
                "调入方核增 40");
        // 在途护栏已释放
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM transfer_pending_guard", Integer.class));
    }

    @Test
    void confirmTransfer_targetWithoutQuotaRow_createsRowOnEffect() {
        String vesselD = registerVessel("VT-D-" + System.nanoTime(), java.util.Map.of());
        TransferResponse created = service.createTransfer(createRequest(vesselA, vesselD, "30.000"));
        service.confirmTransfer(created.transferId(), new TransferConfirmRequest(newRequestId(), vesselA));
        service.confirmTransfer(created.transferId(), new TransferConfirmRequest(newRequestId(), vesselD));

        assertEquals(0, quotaKg(vesselA, SPECIES, YEAR).compareTo(new BigDecimal("70.000")));
        assertEquals(0, quotaKg(vesselD, SPECIES, YEAR).compareTo(new BigDecimal("30.000")),
                "调入方无配额行时按调剂量建行");
    }

    @Test
    void confirmTransfer_thirdPartyOrRepeatedSide_rejected() {
        TransferResponse created = service.createTransfer(createRequest(vesselA, vesselB, "10.000"));
        String outsider = registerVesselWithQuota(SPECIES, "5.000");

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.confirmTransfer(created.transferId(),
                        new TransferConfirmRequest(newRequestId(), outsider)));
        assertTrue(ex.getMessage().contains("不是调剂单"));

        service.confirmTransfer(created.transferId(), new TransferConfirmRequest(newRequestId(), vesselA));
        ConflictException repeated = assertThrows(ConflictException.class,
                () -> service.confirmTransfer(created.transferId(),
                        new TransferConfirmRequest(newRequestId(), vesselA)));
        assertTrue(repeated.getMessage().contains("已确认"));
    }

    @Test
    void createTransfer_idempotencyReplayAndMismatch() {
        TransferCreateRequest request = createRequest(vesselA, vesselB, "10.000");
        TransferResponse first = service.createTransfer(request);

        TransferResponse replay = service.createTransfer(new TransferCreateRequest(
                request.requestId(), vesselA, vesselB, SPECIES, YEAR, new BigDecimal("10.000")));
        assertTrue(replay.replayed(), "同键同参重放首次结果");
        assertEquals(first.transferId(), replay.transferId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota_transfer WHERE from_vessel = ?", Integer.class, vesselA));

        // 同键异参（调剂量不同）→ 409
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.createTransfer(new TransferCreateRequest(
                        request.requestId(), vesselA, vesselB, SPECIES, YEAR, new BigDecimal("11.000"))));
        assertTrue(ex.getMessage().contains("不同业务参数"));
    }

    @Test
    void confirmTransfer_idempotencyReplay() {
        TransferResponse created = service.createTransfer(createRequest(vesselA, vesselB, "10.000"));
        TransferConfirmRequest confirm = new TransferConfirmRequest(newRequestId(), vesselA);
        TransferResponse first = service.confirmTransfer(created.transferId(), confirm);

        TransferResponse replay = service.confirmTransfer(created.transferId(), confirm);
        assertTrue(replay.replayed());
        assertEquals(first.status(), replay.status());
        // 重放不产生二次确认副作用
        TransferResponse current = service.getTransfer(created.transferId());
        assertEquals(TransferStatus.PENDING, current.status());
        assertTrue(current.fromConfirmed());
        assertFalse(current.toConfirmed());
    }

    @Test
    void transfer_expiresExactlyAt48h_quotaUnchangedAndCanReinitiate() {
        TransferResponse created = service.createTransfer(createRequest(vesselA, vesselB, "40.000"));

        // 白盒推进到确认时限恰满（expires_at 不晚于当前时刻即失效）
        jdbc.update("UPDATE quota_transfer SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), created.transferId());

        TransferResponse expired = service.getTransfer(created.transferId());
        assertEquals(TransferStatus.EXPIRED, expired.status(), "恰满 48 小时未确认自动失效");

        // 失效后确认 → 409，且双方额度不变
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.confirmTransfer(created.transferId(),
                        new TransferConfirmRequest(newRequestId(), vesselA)));
        assertTrue(ex.getMessage().contains("失效"));
        assertEquals(0, quotaKg(vesselA, SPECIES, YEAR).compareTo(new BigDecimal("100.000")));
        assertEquals(0, quotaKg(vesselB, SPECIES, YEAR).compareTo(new BigDecimal("50.000")));

        // 失效单可重新发起（护栏已释放）
        TransferResponse again = service.createTransfer(createRequest(vesselA, vesselB, "40.000"));
        assertEquals(TransferStatus.PENDING, again.status());
    }

    @Test
    void createTransfer_concurrentSameVesselSpecies_exactlyOneAccepted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    service.createTransfer(createRequest(vesselA, vesselB, "10.000"));
                    success.incrementAndGet();
                } catch (ConflictException ex) {
                    conflict.incrementAndGet();
                } catch (Throwable ex) {
                    unexpected.compareAndSet(null, ex);
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发调剂发起必须在超时前完成");

        assertNull(unexpected.get());
        assertEquals(1, success.get(), "同船同鱼种同时刻只允许一张进行中调剂单");
        assertEquals(1, conflict.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota_transfer WHERE from_vessel = ?", Integer.class, vesselA));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(1) FROM transfer_pending_guard", Integer.class),
                "唯一受理的调剂单占用调出/调入两行护栏");
    }

    @Test
    void confirmTransfer_concurrentBothSides_effectiveExactlyOnce() throws Exception {
        TransferResponse created = service.createTransfer(createRequest(vesselA, vesselB, "40.000"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        for (String vessel : new String[]{vesselA, vesselB}) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    service.confirmTransfer(created.transferId(),
                            new TransferConfirmRequest(newRequestId(), vessel));
                    success.incrementAndGet();
                } catch (Throwable ex) {
                    unexpected.compareAndSet(null, ex);
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发双方确认必须在超时前完成");

        assertNull(unexpected.get());
        assertEquals(2, success.get(), "双方确认都应受理（行锁串行化）");
        TransferResponse current = service.getTransfer(created.transferId());
        assertEquals(TransferStatus.EFFECTIVE, current.status());
        // 额度恰好变动一次，不出现重复核减/核增
        assertEquals(0, quotaKg(vesselA, SPECIES, YEAR).compareTo(new BigDecimal("60.000")));
        assertEquals(0, quotaKg(vesselB, SPECIES, YEAR).compareTo(new BigDecimal("90.000")));
    }
}
