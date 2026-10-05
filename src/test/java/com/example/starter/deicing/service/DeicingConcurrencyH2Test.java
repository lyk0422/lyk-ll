package com.example.starter.deicing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.web.ApiException;
import com.example.starter.deicing.web.dto.ApplicationRequest;
import com.example.starter.deicing.web.dto.MutationResponse;
import java.sql.Timestamp;
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
 * 基于真实线程与 H2 行锁的并发/事务边界测试：
 * 同航班并发申请唯一受理、同键并发重放一致、并发出队不产生内部重叠占用。
 */
@SpringBootTest
class DeicingConcurrencyH2Test {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    @Autowired
    private DeicingService service;
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
        jdbcTemplate.update("""
                INSERT INTO fluid_batch (batch_no, fluid_code, quantity_in, quantity_available, expiry_time)
                VALUES ('BATCH-T1-SEED', 'TYPE_I', 100000, 100000, '2027-06-30 00:00:00')
                """);
        jdbcTemplate.update("""
                INSERT INTO fluid_batch (batch_no, fluid_code, quantity_in, quantity_available, expiry_time)
                VALUES ('BATCH-T4-SEED', 'TYPE_IV', 100000, 100000, '2027-06-30 00:00:00')
                """);
        timeSource.pin(Clock.fixed(T0, ZoneOffset.UTC));
    }

    private ApplicationRequest oneStep(String requestId, String flight) {
        return new ApplicationRequest(requestId, flight, "STAND-" + flight,
                DeicingType.ONE_STEP, 1, 30, T0.plus(Duration.ofMinutes(120)));
    }

    @Test
    void concurrentApplicationsForSameFlightOnlyOneAccepted() throws Exception {
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
                    MutationResponse response = service.apply(oneStep("REQ-F" + index, "CA101"));
                    outcomes.put(index, "ACCEPTED:" + response.item().status());
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

        long accepted = outcomes.values().stream().filter(v -> v.startsWith("ACCEPTED")).count();
        long rejected = outcomes.values().stream().filter(v -> v.startsWith("REJECTED")).count();
        assertThat(accepted).as("恰好一个并发申请被受理").isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);

        Integer activeCount = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM deicing_request
                        WHERE flight_number = 'CA101'
                          AND status IN ('QUEUED', 'SCHEDULED', 'IN_PROGRESS', 'HOT_RISK')
                        """,
                Integer.class);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyApplicationsYieldSingleRecord() throws Exception {
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
                MutationResponse response = service.apply(oneStep("REQ-SAME", "CA202"));
                outcomes.put(index, response.item().status() + ":" + response.item().sequence());
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

        assertThat(outcomes.values()).as("所有并发同键请求看到同一条记录")
                .hasSize(threads).containsOnly("QUEUED:" + outcomes.values().iterator().next()
                        .substring("QUEUED:".length()));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deicing_request WHERE request_id = 'REQ-SAME'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentDequeueNeverCreatesOverlappingAllocations() throws Exception {
        // 4 个不同航班，各需全部 4 个车位、截止时刻相同 → 按申请序号串行出队
        String[] flights = {"CA101", "CA202", "MU510", "CZ303"};
        for (int i = 0; i < flights.length; i++) {
            ApplicationRequest request = new ApplicationRequest(
                    "REQ-D" + i, flights[i], "STAND-" + flights[i],
                    DeicingType.ONE_STEP, 4, 20, T0.plus(Duration.ofMinutes(180)));
            service.apply(request);
        }

        int threads = flights.length;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        // 多轮并发竞争出队，直到 4 个航班全部排定（非队首的并发尝试返回 409）
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            List<String> queuedIds = jdbcTemplate.queryForList(
                    "SELECT request_id FROM deicing_request WHERE status = 'QUEUED'",
                    String.class);
            if (queuedIds.isEmpty()) {
                break;
            }
            CountDownLatch ready = new CountDownLatch(queuedIds.size());
            CountDownLatch fire = new CountDownLatch(1);
            List<Future<?>> round = new ArrayList<>();
            for (String requestId : queuedIds) {
                round.add(pool.submit(() -> {
                    ready.countDown();
                    fire.await(10, TimeUnit.SECONDS);
                    try {
                        service.dequeue(requestId);
                    } catch (ApiException ignored) {
                        // 竞争失败的非队首请求下一轮重试
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            fire.countDown();
            for (Future<?> future : round) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        Integer scheduledCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deicing_request WHERE status = 'SCHEDULED'",
                Integer.class);
        assertThat(scheduledCount).isEqualTo(flights.length);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bay_code, start_time, end_time FROM deicing_allocation
                ORDER BY bay_code, start_time
                """);
        Map<String, List<Map<String, Object>>> byBay = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byBay.computeIfAbsent((String) row.get("bay_code"), k -> new ArrayList<>()).add(row);
        }
        assertThat(byBay).hasSize(4);
        for (List<Map<String, Object>> occupancies : byBay.values()) {
            for (int i = 1; i < occupancies.size(); i++) {
                Timestamp prevEnd = (Timestamp) occupancies.get(i - 1).get("end_time");
                Timestamp nextStart = (Timestamp) occupancies.get(i).get("start_time");
                assertThat(nextStart.getTime())
                        .as("半开区间端点相接允许，内部重叠禁止")
                        .isGreaterThanOrEqualTo(prevEnd.getTime());
            }
        }

        // SQL 自连接复核：不存在内部重叠的两条占用
        Integer overlaps = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM deicing_allocation a
                JOIN deicing_allocation b
                  ON a.bay_code = b.bay_code
                 AND a.request_id < b.request_id
                 AND a.start_time < b.end_time AND b.start_time < a.end_time
                """, Integer.class);
        assertThat(overlaps).isZero();

        // 第一条（申请序号最小）必须已排定
        Integer scheduled = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deicing_request WHERE request_id = 'REQ-D0' "
                        + "AND status = 'SCHEDULED'", Integer.class);
        assertThat(scheduled).isEqualTo(1);
    }
}
