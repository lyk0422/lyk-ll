package com.example.starter.fishery;

import com.example.starter.fishery.api.BusinessRuleException;
import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.dto.TransferConfirmRequest;
import com.example.starter.fishery.api.dto.TransferRequest;
import com.example.starter.fishery.api.dto.TransferResponse;
import com.example.starter.fishery.domain.TransferStatus;
import com.example.starter.fishery.init.SyntheticDataInitializer;
import com.example.starter.fishery.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨船配额调剂：双方确认生效、恰等剩余额度边界、在途唯一、48 小时恰满失效、
 * 幂等重放/异参 409，以及真实并发确认/并发发起（H2 行锁与护栏唯一约束）。
 */
@SpringBootTest
class TransferServiceTest extends AbstractFisheryTest {

    private static final String SPECIES = SyntheticDataInitializer.SPECIES_HAIRTAIL;
    private static final int YEAR = 2026;
    private static final Instant CLOCK_ZERO = Instant.parse("2026-12-01T00:00:00Z");

    /** 可控时钟：48 小时确认时限边界测试使用。 */
    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void set(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(CLOCK_ZERO);
        }
    }

    @Autowired
    private TransferService transferService;

    @Autowired
    private Clock clock;

    private String vesselA;
    private String vesselB;

    @BeforeEach
    void setUp() {
        ((MutableClock) clock).set(CLOCK_ZERO);
        vesselA = registerVessel("VTA-" + System.nanoTime(), Map.of());
        vesselB = registerVessel("VTB-" + System.nanoTime(), Map.of());
        addQuota(vesselA, SPECIES, YEAR, "100.000");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private TransferRequest transferRequest(String requestId, String amountKg) {
        return new TransferRequest(requestId, vesselA, vesselB, SPECIES, YEAR, new BigDecimal(amountKg));
    }

    private BigDecimal quotaKg(String vessel) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT quota_kg FROM quota WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                (rs, n) -> rs.getBigDecimal(1), vessel, SPECIES, YEAR);
        return rows.isEmpty() ? null : rows.get(0).stripTrailingZeros();
    }

    private int guardCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM transfer_active_guard", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void createThenConfirm_movesQuotaAtomicallyForBothParties() {
        TransferResponse created = transferService.create(transferRequest(newRequestId(), "40.000"));
        assertEquals(TransferStatus.PENDING, created.status());
        assertFalse(created.replayed());
        assertEquals(CLOCK_ZERO.plus(Duration.ofHours(48)), created.expiresAt());
        assertEquals(2, guardCount(), "双方各占一行在途护栏");

        TransferResponse confirmed = transferService.confirm(created.id(),
                new TransferConfirmRequest(newRequestId()));
        assertEquals(TransferStatus.EFFECTIVE, confirmed.status());
        assertEquals(CLOCK_ZERO, confirmed.confirmedAt());

        // 同一事务内双方额度变更：调出 100→60，调入 0→40（无配额行则初始化）
        assertEquals(0, quotaKg(vesselA).compareTo(new BigDecimal("60.000")));
        assertEquals(0, quotaKg(vesselB).compareTo(new BigDecimal("40.000")));
        assertEquals(0, guardCount(), "生效后在途护栏必须释放");
    }

    @Test
    void transferExactlyEqualToRemaining_isLegal() {
        // 调剂量恰等调出船剩余额度（100kg）合法
        TransferResponse created = transferService.create(transferRequest(newRequestId(), "100.000"));
        transferService.confirm(created.id(), new TransferConfirmRequest(newRequestId()));
        assertEquals(0, quotaKg(vesselA).compareTo(new BigDecimal("0.000")));
        assertEquals(0, quotaKg(vesselB).compareTo(new BigDecimal("100.000")));
    }

    @Test
    void transferExceedingRemaining_isRejectedWhole() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> transferService.create(transferRequest(newRequestId(), "100.001")));
        assertTrue(ex.getMessage().contains("整笔拒绝"));
        // 整笔拒绝：无调剂单、无护栏、额度不变
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM quota_transfer", Integer.class));
        assertEquals(0, guardCount());
        assertEquals(0, quotaKg(vesselA).compareTo(new BigDecimal("100.000")));
    }

    @Test
    void sameVesselSpeciesPendingTransfer_secondCreateConflict() {
        transferService.create(transferRequest(newRequestId(), "10.000"));
        // 同船（调出方）同鱼种同年度第二张进行中调剂单 → 409
        ConflictException ex = assertThrows(ConflictException.class,
                () -> transferService.create(transferRequest(newRequestId(), "5.000")));
        assertTrue(ex.getMessage().contains("只允许一张"));
        // 调入方被他人占用同样冲突
        String vesselC = registerVessel("VTC-" + System.nanoTime(), Map.of());
        addQuota(vesselC, SPECIES, YEAR, "50.000");
        assertThrows(ConflictException.class, () -> transferService.create(
                new TransferRequest(newRequestId(), vesselC, vesselB, SPECIES, YEAR,
                        new BigDecimal("5.000"))));
    }

    @Test
    void idempotency_sameKeySameParamsReplays_differentParamsConflict() {
        String requestId = newRequestId();
        TransferResponse first = transferService.create(transferRequest(requestId, "10.000"));
        TransferResponse replay = transferService.create(transferRequest(requestId, "10.000"));
        assertTrue(replay.replayed(), "同键同参必须重放首次结果");
        assertEquals(first.id(), replay.id());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(1) FROM quota_transfer", Integer.class));

        // 同 requestId 异参 → 409
        ConflictException ex = assertThrows(ConflictException.class,
                () -> transferService.create(transferRequest(requestId, "11.000")));
        assertTrue(ex.getMessage().contains("不同业务参数"));
    }

    @Test
    void confirmIdempotency_sameKeyReplaysFirstResult() {
        TransferResponse created = transferService.create(transferRequest(newRequestId(), "10.000"));
        String confirmId = newRequestId();
        TransferResponse first = transferService.confirm(created.id(), new TransferConfirmRequest(confirmId));
        TransferResponse replay = transferService.confirm(created.id(), new TransferConfirmRequest(confirmId));
        assertTrue(replay.replayed());
        assertEquals(first.confirmedAt(), replay.confirmedAt());
        // 额度只变动一次
        assertEquals(0, quotaKg(vesselA).compareTo(new BigDecimal("90.000")));
        // 新 requestId 重复确认已生效单 → 409
        assertThrows(ConflictException.class,
                () -> transferService.confirm(created.id(), new TransferConfirmRequest(newRequestId())));
    }

    @Test
    void confirmAfterQuotaPartiallyConsumed_isRejectedWholeAndStaysPending() {
        TransferResponse created = transferService.create(transferRequest(newRequestId(), "80.000"));
        // 确认前调出船额度被核销 50kg，剩余 50kg < 调剂量 80kg
        jdbc.update("UPDATE quota SET used_kg = 50.000 WHERE vessel_code = ? AND species_code = ? "
                + "AND quota_year = ?", vesselA, SPECIES, YEAR);
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> transferService.confirm(created.id(), new TransferConfirmRequest(newRequestId())));
        assertTrue(ex.getMessage().contains("整笔拒绝"));
        // 整笔拒绝：双方额度均不变，调剂单仍在途
        assertEquals(0, quotaKg(vesselA).compareTo(new BigDecimal("100.000")));
        assertNull(quotaKg(vesselB));
        assertEquals(TransferStatus.PENDING, transferService.get(created.id()).status());
    }

    @Test
    void exactly48HoursUnconfirmed_autoExpiresQuotaUnchangedAndCanReinitiate() {
        TransferResponse created = transferService.create(transferRequest(newRequestId(), "60.000"));

        // 恰满 48 小时未确认：确认时自动失效，双方额度不变
        ((MutableClock) clock).set(CLOCK_ZERO.plus(Duration.ofHours(48)));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> transferService.confirm(created.id(), new TransferConfirmRequest(newRequestId())));
        assertTrue(ex.getMessage().contains("已失效"));
        assertEquals(TransferStatus.EXPIRED, transferService.get(created.id()).status());
        assertEquals(0, quotaKg(vesselA).compareTo(new BigDecimal("100.000")));
        assertNull(quotaKg(vesselB));
        assertEquals(0, guardCount(), "失效后在途护栏必须释放");

        // 失效单可重新发起
        TransferResponse again = transferService.create(transferRequest(newRequestId(), "60.000"));
        assertEquals(TransferStatus.PENDING, again.status());
        transferService.confirm(again.id(), new TransferConfirmRequest(newRequestId()));
        assertEquals(0, quotaKg(vesselA).compareTo(new BigDecimal("40.000")));
        assertEquals(0, quotaKg(vesselB).compareTo(new BigDecimal("60.000")));
    }

    @Test
    void justBefore48Hours_confirmStillEffective() {
        TransferResponse created = transferService.create(transferRequest(newRequestId(), "60.000"));
        // 差 1 秒满 48 小时：仍在确认时限内
        ((MutableClock) clock).set(CLOCK_ZERO.plus(Duration.ofHours(48)).minusSeconds(1));
        TransferResponse confirmed = transferService.confirm(created.id(),
                new TransferConfirmRequest(newRequestId()));
        assertEquals(TransferStatus.EFFECTIVE, confirmed.status());
        assertEquals(0, quotaKg(vesselB).compareTo(new BigDecimal("60.000")));
    }

    @Test
    void concurrentConfirms_exactlyOneEffectiveAndQuotaMovedOnce() throws Exception {
        TransferResponse created = transferService.create(transferRequest(newRequestId(), "60.000"));
        List<String> confirmIds = List.of(newRequestId(), newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        for (String confirmId : confirmIds) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    transferService.confirm(created.id(), new TransferConfirmRequest(confirmId));
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发确认必须在超时前完成");

        assertNull(unexpected.get());
        assertEquals(1, success.get(), "恰好一个确认生效");
        assertEquals(1, conflict.get(), "另一个必须冲突拒绝");
        // 额度只变动一次，不出现双方重复变动
        assertEquals(0, quotaKg(vesselA).compareTo(new BigDecimal("40.000")));
        assertEquals(0, quotaKg(vesselB).compareTo(new BigDecimal("60.000")));
        assertEquals(TransferStatus.EFFECTIVE, transferService.get(created.id()).status());
    }

    @Test
    void concurrentCreates_sameVesselSpeciesExactlyOnePending() throws Exception {
        List<String> requestIds = List.of(newRequestId(), newRequestId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        for (String requestId : requestIds) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    transferService.create(transferRequest(requestId, "10.000"));
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发发起必须在超时前完成");

        assertNull(unexpected.get());
        assertEquals(1, success.get(), "同船同鱼种同时刻只允许一张进行中调剂单");
        assertEquals(1, conflict.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota_transfer WHERE status = 'PENDING'", Integer.class));
    }
}
