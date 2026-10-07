package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.CheckItem;
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.LockRemovalRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceCreateRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceFinishRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceStartRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 检修挂牌并发边界测试（真实 H2 行锁串行化）：
 * 末锁撤除与结束竞争只能在清单完整后结束；同设备进行中检修单唯一；
 * 并发同键撤锁全部重放首次冻结响应。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class MaintenanceConcurrencyTest {

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private RideService rideService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private ExecutorService pool;
    private long rideId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM maintenance_lock_removal");
        jdbc.update("DELETE FROM maintenance_lock");
        jdbc.update("DELETE FROM maintenance_person");
        jdbc.update("DELETE FROM maintenance_order");
        jdbc.update("DELETE FROM supervision_inspection_item");
        jdbc.update("DELETE FROM supervision_inspection");
        jdbc.update("DELETE FROM overhaul_record");
        jdbc.update("DELETE FROM current_certificate");
        jdbc.update("DELETE FROM annual_certificate");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
        rideId = ((Number) rideService.createRide(
                new CreateRideRequest("受检跳楼机", 8, 60)).get("rideId")).longValue();
        rideService.dailyCheck(rideId, new DailyCheckRequest("c-check", List.of(
                new CheckItem("压杠", true),
                new CheckItem("安全带", true),
                new CheckItem("联锁装置", true))));
        for (int i = 1; i <= 3; i++) {
            rideService.trialRun(rideId, new TrialRunRequest("c-t" + i, true));
        }
        pool = Executors.newFixedThreadPool(6);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(ApiResult result) {
        try {
            return objectMapper.readValue(result.body(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long createOrder(String requestId, String... personnel) {
        ApiResult result = maintenanceService.create(rideId,
                new MaintenanceCreateRequest(requestId, List.of(personnel)));
        assertEquals(200, result.status());
        return ((Number) bodyOf(result).get("maintenanceId")).longValue();
    }

    /** 并发执行同一批任务，等待全部完成并收集结果。 */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> wrapped = tasks.stream()
                .<Callable<T>>map(task -> () -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return task.call();
                })
                .toList();
        List<Future<T>> futures = wrapped.stream().map(pool::submit).toList();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        return futures.stream().map(future -> {
            try {
                return future.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("并发任务失败", e);
            }
        }).toList();
    }

    @Test
    @DisplayName("末锁竞争：撤锁与结束并发，只能在清单完整后结束")
    void lastLockRemovalAndFinishRace() throws Exception {
        long mid = createOrder("m-c", "张三", "李四");
        ApiResult started = maintenanceService.start(rideId, mid,
                new MaintenanceStartRequest("m-s", 1L));
        assertEquals(200, started.status());
        // 先撤第一把锁（版本2→3）
        ApiResult first = maintenanceService.removeLock(rideId, mid,
                new LockRemovalRequest("m-u1", "张三", "李四", 2L));
        assertEquals(200, first.status());

        // 并发：撤末锁（v3）与结束（v3）——结束不得先于清单完整生效
        List<Object> results = runConcurrently(List.of(
                () -> {
                    try {
                        return maintenanceService.removeLock(rideId, mid,
                                new LockRemovalRequest("m-u2", "李四", "张三", 3L));
                    } catch (ApiException e) {
                        return e;
                    }
                },
                () -> {
                    try {
                        return maintenanceService.finish(rideId, mid,
                                new MaintenanceFinishRequest("m-fin", 3L));
                    } catch (ApiException e) {
                        return e;
                    }
                }));
        ApiResult removal = (ApiResult) results.get(0);
        assertEquals(200, removal.status());
        // 结束请求必然失败：先执行则清单不完整409，后执行则版本冲突409
        ApiException finishFailed = (ApiException) results.get(1);
        assertEquals(409, finishFailed.status().value());

        // 清单完整后用当前版本结束成功
        Map<String, Object> detail = maintenanceService.getMaintenance(rideId, mid);
        assertEquals(List.of(), detail.get("unremovedPersonnel"));
        long currentVersion = ((Number) detail.get("version")).longValue();
        ApiResult finished = maintenanceService.finish(rideId, mid,
                new MaintenanceFinishRequest("m-fin2", currentVersion));
        assertEquals(200, finished.status());
        assertEquals("FINISHED", bodyOf(finished).get("status"));
        // 双人签认恰两条，不可覆盖
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_lock_removal", Integer.class));
    }

    @Test
    @DisplayName("并发启动：同设备只允许一张进行中检修单")
    void concurrentStartsOnlyOneInProgress() throws Exception {
        long mid1 = createOrder("m-c1", "张三");
        long mid2 = createOrder("m-c2", "李四");

        List<Object> results = runConcurrently(List.of(
                () -> {
                    try {
                        return maintenanceService.start(rideId, mid1,
                                new MaintenanceStartRequest("m-s1", 1L));
                    } catch (ApiException e) {
                        return e;
                    }
                },
                () -> {
                    try {
                        return maintenanceService.start(rideId, mid2,
                                new MaintenanceStartRequest("m-s2", 1L));
                    } catch (ApiException e) {
                        return e;
                    }
                }));
        long successes = results.stream().filter(r -> r instanceof ApiResult).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException e && e.status().value() == 409).count();
        assertEquals(1, successes);
        assertEquals(1, conflicts);
        // 数据库层同样唯一：进行中占用标记唯一索引
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_order WHERE ride_id = ? AND status = 'IN_PROGRESS'",
                Integer.class, rideId));
        assertEquals("STOPPED", rideService.getRide(rideId).get("status"));
    }

    @Test
    @DisplayName("并发同键撤锁：全部重放首次冻结响应，签认只落一条")
    void concurrentSameKeyLockRemovalReplays() throws Exception {
        long mid = createOrder("m-c", "张三", "李四");
        maintenanceService.start(rideId, mid, new MaintenanceStartRequest("m-s", 1L));

        LockRemovalRequest request = new LockRemovalRequest("m-u1", "张三", "李四", 2L);
        List<Callable<ApiResult>> tasks = java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> (Callable<ApiResult>) () -> maintenanceService.removeLock(
                        rideId, mid, request))
                .toList();
        List<ApiResult> results = runConcurrently(tasks);
        assertEquals(4, results.size());
        String firstBody = results.get(0).body();
        for (ApiResult result : results) {
            assertEquals(200, result.status());
            assertEquals(firstBody, result.body());
        }
        // 签认只落一条，锁只撤一次
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_lock_removal", Integer.class));
        Map<String, Object> detail = maintenanceService.getMaintenance(rideId, mid);
        assertEquals(List.of("李四"), detail.get("unremovedPersonnel"));
        assertEquals(3L, ((Number) detail.get("version")).longValue());
    }

    @Test
    @DisplayName("并发异键撤同一把锁：仅首次生效，重复撤锁409且签认不覆盖")
    void concurrentDifferentKeyRemovalSameLockOnlyOneWins() throws Exception {
        long mid = createOrder("m-c", "张三");
        maintenanceService.start(rideId, mid, new MaintenanceStartRequest("m-s", 1L));

        List<Object> results = runConcurrently(List.of(
                () -> {
                    try {
                        return maintenanceService.removeLock(rideId, mid,
                                new LockRemovalRequest("m-u1", "张三", "李四", 2L));
                    } catch (ApiException e) {
                        return e;
                    }
                },
                () -> {
                    try {
                        return maintenanceService.removeLock(rideId, mid,
                                new LockRemovalRequest("m-u2", "张三", "王五", 2L));
                    } catch (ApiException e) {
                        return e;
                    }
                }));
        long successes = results.stream().filter(r -> r instanceof ApiResult).count();
        assertEquals(1, successes);
        // 签认恰一条：双人签认不可覆盖
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_lock_removal", Integer.class));
        Map<String, Object> detail = maintenanceService.getMaintenance(rideId, mid);
        assertEquals(List.of(), detail.get("unremovedPersonnel"));
        // 失败方若是版本冲突也不占键：用正确版本重试结束可成功
        long version = ((Number) detail.get("version")).longValue();
        ApiResult finished = maintenanceService.finish(rideId, mid,
                new MaintenanceFinishRequest("m-fin", version));
        assertEquals(200, finished.status());
        assertThrows(ApiException.class, () -> maintenanceService.finish(rideId, mid,
                new MaintenanceFinishRequest("m-fin-again", version)));
    }
}
