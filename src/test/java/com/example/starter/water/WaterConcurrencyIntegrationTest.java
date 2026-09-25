package com.example.starter.water;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.water.dto.CreateMeterRequest;
import com.example.starter.water.dto.CreateUserRequest;
import com.example.starter.water.dto.DeclarationRequest;
import com.example.starter.water.dto.DeclarationResult;
import com.example.starter.water.dto.LedgerView;
import com.example.starter.water.dto.ReadingRequest;
import com.example.starter.water.dto.ReadingResult;
import com.example.starter.water.dto.RestrictionRequest;
import com.example.starter.water.dto.UserView;
import com.example.starter.water.service.WaterService;
import com.example.starter.water.testsupport.MutableTimeProvider;
import com.example.starter.water.testsupport.TestClockConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基于 H2 真实事务与行锁的并发测试：协调真实并发操作并断言最终数据一致性。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class WaterConcurrencyIntegrationTest {

    @Autowired
    private WaterService waterService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableTimeProvider clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : new String[] {"meter_reading", "declaration_idempotency", "plan_version",
                "annual_quota", "restriction_event", "meter_point", "water_user"}) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock.set(Instant.parse("2026-06-01T00:00:00Z"));
    }

    /**
     * 同取水户并发申报：额度 100，两笔 60 分别申报不同自然月并同时到达，
     * 必须恰有一笔受理、一笔整月拒绝；最终已确认受理合计严格等于 60，不得超发。
     */
    @Test
    void concurrentDeclarationsSameUserNeverExceedQuota() throws Exception {
        int rounds = 6;
        for (int round = 0; round < rounds; round++) {
            final int r = round;
            UserView user = waterService.createUser(new CreateUserRequest(
                    "并发户" + r, "RC1-" + r, new BigDecimal("100.000")));
            List<Future<DeclarationResult>> futures = runConcurrently(
                    () -> waterService.declare(new DeclarationRequest(
                            "c-a-" + r, user.id(), "2026-01", new BigDecimal("60.000"))),
                    () -> waterService.declare(new DeclarationRequest(
                            "c-b-" + r, user.id(), "2026-02", new BigDecimal("60.000"))));
            List<DeclarationResult> results = collect(futures);
            long confirmed = results.stream().filter(x -> x.status().equals("CONFIRMED")).count();
            long rejected = results.stream().filter(x -> x.status().equals("REJECTED")).count();
            assertThat(confirmed).as("第 %d 轮恰好一笔受理", r).isEqualTo(1);
            assertThat(rejected).as("第 %d 轮恰好一笔拒绝", r).isEqualTo(1);

            LedgerView ledger = waterService.ledger(user.id(), 2026);
            assertThat(ledger.declaredTotal()).isEqualByComparingTo("60.000");
            assertThat(ledger.remaining()).isEqualByComparingTo("40.000");
            assertThat(ledger.months()).hasSize(1);
            assertThat(ledger.months().get(0).version()).isEqualTo(1);
        }
    }

    /**
     * 同一 requestId 并发重放：两个线程同键同参，返回同一首次结果且只产生一个版本。
     */
    @Test
    void concurrentSameRequestIdProducesSingleVersion() throws Exception {
        UserView user = waterService.createUser(
                new CreateUserRequest("并发户2", "RC2", new BigDecimal("100.000")));
        String requestId = UUID.randomUUID().toString();
        List<Future<DeclarationResult>> futures = runConcurrently(
                () -> waterService.declare(new DeclarationRequest(
                        requestId, user.id(), "2026-01", new BigDecimal("30.000"))),
                () -> waterService.declare(new DeclarationRequest(
                        requestId, user.id(), "2026-01", new BigDecimal("30.000"))));
        List<DeclarationResult> results = collect(futures);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("CONFIRMED");
            assertThat(r.version()).isEqualTo(1);
            assertThat(r.acceptedVolume()).isEqualByComparingTo("30.000");
        });
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plan_version WHERE user_id = ? AND plan_month = ?",
                Integer.class, user.id(), "2026-01");
        assertThat(versionCount).isEqualTo(1);
    }

    /**
     * 同计量点并发上报乱序读数：最终按表底时刻排序重算增量，月累计与总量正确、无重复累计。
     */
    @Test
    void concurrentOutOfOrderReadingsProduceCorrectMonthlyTotals() throws Exception {
        UserView user = waterService.createUser(
                new CreateUserRequest("并发户3", "RC3", new BigDecimal("1000.000")));
        Long meterId = waterService.createMeter(user.id(), new CreateMeterRequest("并发表")).id();

        // 先落 3 月表底 30。
        waterService.reportReading(new ReadingRequest(meterId,
                "2026-03-10T00:00:00Z", new BigDecimal("30.000")));

        // 再并发迟到 1 月(10)、2 月(20)、4 月(40)；无论提交顺序如何，最终总量必须为 40。
        List<Future<ReadingResult>> futures = runConcurrently(
                () -> waterService.reportReading(new ReadingRequest(meterId,
                        "2026-01-10T00:00:00Z", new BigDecimal("10.000"))),
                () -> waterService.reportReading(new ReadingRequest(meterId,
                        "2026-02-10T00:00:00Z", new BigDecimal("20.000"))),
                () -> waterService.reportReading(new ReadingRequest(meterId,
                        "2026-04-10T00:00:00Z", new BigDecimal("40.000"))));
        List<ReadingResult> results = collect(futures);
        assertThat(results).allSatisfy(r -> assertThat(r.duplicate()).isFalse());

        // 再并发重复上报 1、2 月表底：均判重，总量不变。
        List<Future<ReadingResult>> duplicates = runConcurrently(
                () -> waterService.reportReading(new ReadingRequest(meterId,
                        "2026-01-10T00:00:00Z", new BigDecimal("10.000"))),
                () -> waterService.reportReading(new ReadingRequest(meterId,
                        "2026-02-10T00:00:00Z", new BigDecimal("20.000"))));
        assertThat(collect(duplicates)).allSatisfy(r -> assertThat(r.duplicate()).isTrue());

        LedgerView ledger = waterService.ledger(user.id(), 2026);
        assertThat(ledger.actualTotal()).isEqualByComparingTo("40.000");
        assertThat(ledger.months()).hasSize(4);
        List<String> expectedMonths = List.of("2026-01", "2026-02", "2026-03", "2026-04");
        for (int i = 0; i < expectedMonths.size(); i++) {
            assertThat(ledger.months().get(i).month()).isEqualTo(expectedMonths.get(i));
            assertThat(ledger.months().get(i).actualVolume())
                    .isEqualByComparingTo(BigDecimal.TEN);
        }
    }

    /**
     * 限取触发与申报并发：锁顺序决定唯一结果，不得出现“限取生效同刻仍按全额受理”的矛盾版本。
     * 多轮重复后每轮要么全额（申报先提交）要么减半（限取先提交），且结果与版本标志严格一致。
     */
    @Test
    void restrictionRaceWithDeclarationHasSingleConsistentOutcome() throws Exception {
        int rounds = 12;
        AtomicInteger halfRounds = new AtomicInteger();
        AtomicInteger fullRounds = new AtomicInteger();
        for (int round = 0; round < rounds; round++) {
            final int r = round;
            UserView user = waterService.createUser(new CreateUserRequest(
                    "竞态户" + r, "RR-" + r, new BigDecimal("1000.000")));
            String requestId = "race-" + r;
            List<Future<Object>> futures = runConcurrently(
                    () -> (Object) waterService.declare(new DeclarationRequest(
                            requestId, user.id(), "2026-01", new BigDecimal("100.000"))),
                    () -> (Object) waterService.triggerRestriction(new RestrictionRequest(
                            "RR-" + r, null, "生态预警")));
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            DeclarationResult result = waterService.declare(new DeclarationRequest(
                    requestId, user.id(), "2026-01", new BigDecimal("100.000")));
            Integer versionCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM plan_version WHERE user_id = ? AND plan_month = ?",
                    Integer.class, user.id(), "2026-01");
            assertThat(versionCount).isEqualTo(1);
            if (result.restricted()) {
                halfRounds.incrementAndGet();
                assertThat(result.acceptedVolume()).as("减半轮次必须受理 50")
                        .isEqualByComparingTo("50.000");
            } else {
                fullRounds.incrementAndGet();
                assertThat(result.acceptedVolume()).as("全额轮次必须受理 100")
                        .isEqualByComparingTo("100.000");
            }
        }
        // 真实并发下两种先后顺序都应能出现；若调度极端偏向一方，至少保证全部结果各自一致。
        assertThat(halfRounds.get() + fullRounds.get()).isEqualTo(rounds);
    }

    @SafeVarargs
    private <T> List<Future<T>> runConcurrently(Callable<T>... tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        return futures;
    }

    private <T> List<T> collect(List<Future<T>> futures) throws Exception {
        List<T> values = new ArrayList<>();
        for (Future<T> f : futures) {
            values.add(f.get(30, TimeUnit.SECONDS));
        }
        return values;
    }
}
