package com.example.starter.deicing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.web.ApiException;
import com.example.starter.deicing.web.dto.ApplicationRequest;
import com.example.starter.deicing.web.dto.BorrowRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 库存并发边界测试（真实线程 + H2 行锁）：
 * 同液罐并发借用仅一张未归还单；并发两步法申请不超卖库存。
 */
@SpringBootTest
class InventoryConcurrencyH2Test {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    @Autowired
    private DeicingService deicingService;
    @Autowired
    private BorrowService borrowService;
    @Autowired
    private TimeSource timeSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM deicing_allocation");
        jdbcTemplate.update("DELETE FROM deicing_request");
        jdbcTemplate.update("DELETE FROM fluid_reservation");
        jdbcTemplate.update("DELETE FROM fluid_consumption");
        jdbcTemplate.update("DELETE FROM fluid_borrow");
        jdbcTemplate.update("DELETE FROM manual_review_queue");
        jdbcTemplate.update("DELETE FROM fluid_batch WHERE source = 'INBOUND'");
        jdbcTemplate.update("UPDATE fluid_batch SET remaining_liters = quantity_liters");
        jdbcTemplate.update("UPDATE fluid_batch SET expiry_time = '2027-12-31 00:00:00'");
        timeSource.pin(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void concurrentBorrowsOnSameTankOnlyOneAccepted() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        Map<Integer, String> outcomes = new ConcurrentHashMap<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                fire.await(10, TimeUnit.SECONDS);
                try {
                    borrowService.borrow(new BorrowRequest("BOR-C" + index, "TYPE-I",
                            "TANK-C", new BigDecimal("100"), "TEAM-A", "TEAM-B"));
                    outcomes.put(index, "ACCEPTED");
                } catch (ApiException e) {
                    outcomes.put(index, "REJECTED:" + e.getStatus().name());
                }
                return null;
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        fire.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        long accepted = outcomes.values().stream().filter("ACCEPTED"::equals).count();
        assertThat(accepted).as("同一液罐同时刻只允许一张未归还借用单").isEqualTo(1);
        Integer openCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fluid_borrow WHERE tank_code = 'TANK-C'"
                        + " AND status = 'OPEN'",
                Integer.class);
        assertThat(openCount).isEqualTo(1);
    }

    @Test
    void concurrentTwoStepApplicationsNeverOversellStock() throws Exception {
        // 可预占库存 500 升；4 个航班各需 250 升（10 分钟 × 25 升/分钟）→ 恰好 2 个受理
        jdbcTemplate.update("UPDATE fluid_batch SET remaining_liters = 500"
                + " WHERE batch_no = 'OPENING-TYPE-II'");
        String[] flights = {"CA101", "CA202", "MU510", "CZ303"};

        int threads = flights.length;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        Map<String, String> outcomes = new ConcurrentHashMap<>();

        List<Future<?>> futures = new ArrayList<>();
        for (String flight : flights) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                fire.await(10, TimeUnit.SECONDS);
                try {
                    deicingService.apply(new ApplicationRequest("REQ-" + flight, flight,
                            "STAND-" + flight, DeicingType.TWO_STEP, 1, 10,
                            T0.plus(Duration.ofMinutes(120))));
                    outcomes.put(flight, "ACCEPTED");
                } catch (ApiException e) {
                    outcomes.put(flight, "REJECTED:" + e.getStatus().name());
                }
                return null;
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        fire.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        long accepted = outcomes.values().stream().filter("ACCEPTED"::equals).count();
        assertThat(accepted).as("500 升库存恰好受理 2 个 250 升申请").isEqualTo(2);
        long rejected = outcomes.values().stream().filter(v -> v.startsWith("REJECTED")).count();
        assertThat(rejected).isEqualTo(2);

        // 预占合计不超卖；库存不足的申请全部进入人工确认队列
        BigDecimal reserved = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity_liters), 0) FROM fluid_reservation"
                        + " WHERE status = 'ACTIVE'",
                BigDecimal.class);
        assertThat(reserved).isEqualByComparingTo("500.00");
        Integer reviews = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM manual_review_queue", Integer.class);
        assertThat(reviews).isEqualTo(2);
        Integer queued = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deicing_request WHERE status = 'QUEUED'", Integer.class);
        assertThat(queued).isEqualTo(2);
    }
}
