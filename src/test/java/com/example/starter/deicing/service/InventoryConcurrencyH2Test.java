package com.example.starter.deicing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.web.ApiException;
import com.example.starter.deicing.web.dto.ApplicationRequest;
import com.example.starter.deicing.web.dto.BorrowRequest;
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
 * 同一液罐并发借用仅一张未归还单；并发两步法申请预占不超卖。
 */
@SpringBootTest
class InventoryConcurrencyH2Test {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant EXPIRY_FAR = Instant.parse("2027-06-30T00:00:00Z");

    @Autowired
    private DeicingService deicingService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private TimeSource timeSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM deicing_allocation");
        jdbcTemplate.update("DELETE FROM deicing_request");
        jdbcTemplate.update("DELETE FROM inventory_consumption");
        jdbcTemplate.update("DELETE FROM inventory_reservation");
        jdbcTemplate.update("DELETE FROM tank_borrow_open");
        jdbcTemplate.update("DELETE FROM borrow_order");
        jdbcTemplate.update("DELETE FROM fluid_batch");
        timeSource.pin(Clock.fixed(T0, ZoneOffset.UTC));
    }

    private void seedBatch(String batchNo, String fluidCode, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO fluid_batch (batch_no, fluid_code, quantity_in, quantity_available, expiry_time)
                VALUES (?, ?, ?, ?, ?)
                """, batchNo, fluidCode, quantity, quantity, java.sql.Timestamp.from(EXPIRY_FAR));
    }

    @Test
    void concurrentBorrowsOnSameTankOnlyOneStaysOpen() throws Exception {
        seedBatch("B-T1", "TYPE_I", 100000);
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
                    inventoryService.borrow(new BorrowRequest(
                            "BOR-C" + index, "TANK-X", "TYPE_I", 10, "TEAM-A", "TEAM-" + index));
                    outcomes.put(index, "OPENED");
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

        long opened = outcomes.values().stream().filter(v -> v.equals("OPENED")).count();
        long rejected = outcomes.values().stream()
                .filter(v -> v.equals("REJECTED:CONFLICT")).count();
        assertThat(opened).as("同一液罐并发借用仅一张成功").isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);

        Integer openRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tank_borrow_open WHERE tank_id = 'TANK-X'", Integer.class);
        assertThat(openRows).isEqualTo(1);
        Integer openOrders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM borrow_order WHERE tank_id = 'TANK-X' AND status = 'OPEN'",
                Integer.class);
        assertThat(openOrders).isEqualTo(1);
        // 台账借出量恰为成功那一单
        Integer lent = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM borrow_order WHERE status = 'OPEN'",
                Integer.class);
        assertThat(lent).isEqualTo(10);
    }

    @Test
    void concurrentTwoStepApplicationsNeverOverReserve() throws Exception {
        // TYPE_I 仅 100 升：每个两步法申请需 1 车位 × 10 分钟 × 6 升 = 60 升，仅一个能预占成功
        seedBatch("B-T1", "TYPE_I", 100);
        seedBatch("B-T4", "TYPE_IV", 100000);

        String[] flights = {"CA101", "CA202", "MU510", "CZ303"};
        int threads = flights.length;
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
                var response = deicingService.apply(new ApplicationRequest(
                        "REQ-P" + index, flights[index], "STAND-" + flights[index],
                        DeicingType.TWO_STEP, 1, 10, T0.plus(Duration.ofMinutes(180))));
                outcomes.put(index, response.item().status().name());
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

        long queued = outcomes.values().stream().filter(v -> v.equals("QUEUED")).count();
        long manual = outcomes.values().stream().filter(v -> v.equals("MANUAL_REVIEW")).count();
        assertThat(queued).as("库存仅够一个申请预占").isEqualTo(1);
        assertThat(manual).as("其余转人工确认队列，不静默降级").isEqualTo(threads - 1);

        // 预占总量不得超过库存：ACTIVE 预占合计 60 升 ≤ 100 升
        Integer reserved = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_reservation
                WHERE fluid_code = 'TYPE_I' AND status = 'ACTIVE'
                """, Integer.class);
        assertThat(reserved).isEqualTo(60);
        // 所有申请均已受理落库（含人工确认队列）
        Integer requests = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deicing_request", Integer.class);
        assertThat(requests).isEqualTo(threads);
    }
}
