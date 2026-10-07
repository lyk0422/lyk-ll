package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.example.starter.ride.dto.RideDtos.CreateMaintenanceOrderRequest;
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.FinishMaintenanceRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.ReadingRequest;
import com.example.starter.ride.dto.RideDtos.RemoveLockRequest;
import com.example.starter.ride.dto.RideDtos.StartMaintenanceRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;

/**
 * 检修挂牌并发边界测试：真实 H2 行锁串行化同设备事务，
 * 验证末锁竞争、双单启动唯一占用、同键撤锁重放与检修/开放终态唯一。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class MaintenanceConcurrencyTest {

    @Autowired
    private RideService service;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM maintenance_lock");
        jdbc.update("DELETE FROM maintenance_person");
        jdbc.update("DELETE FROM maintenance_order");
        jdbc.update("DELETE FROM idempotency_record");
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
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    private long createRide() {
        return ((Number) service.createRide(
                new CreateRideRequest("并发检修海盗船", 10, 50, false)).get("rideId")).longValue();
    }

    private void makeReady(long id, String keyPrefix) {
        service.dailyCheck(id, new DailyCheckRequest(keyPrefix + "-check", List.of(
                new CheckItem("压杠", true),
                new CheckItem("安全带", true),
                new CheckItem("联锁装置", true))));
        for (int i = 1; i <= 3; i++) {
            service.trialRun(id, new TrialRunRequest(keyPrefix + "-t" + i, true));
        }
    }

    private long preparedOrder(long rideId, String requestId, List<String> persons) {
        ApiResult result = service.createMaintenanceOrder(rideId,
                new CreateMaintenanceOrderRequest(requestId, persons));
        assertEquals(200, result.status());
        return ((Number) readBody(result).get("orderId")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(ApiResult result) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    result.body(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private Callable<String> removeTask(long orderId, String requestId, String person,
            String guardian) {
        return () -> {
            try {
                service.removeLock(orderId, new RemoveLockRequest(
                        requestId, person, person, guardian));
                return "REMOVE_OK";
            } catch (ApiException e) {
                return "REMOVE_" + e.status().value();
            }
        };
    }

    private Callable<String> finishTask(long orderId, String requestId) {
        return () -> {
            try {
                service.finishMaintenance(orderId, new FinishMaintenanceRequest(requestId, 2L));
                return "FINISH_OK";
            } catch (ApiException e) {
                return "FINISH_" + e.status().value();
            }
        };
    }

    @Test
    @DisplayName("末锁竞争：两个并发撤锁恰一个成功，结束只能在清单完整后成功，终态唯一")
    void lastLockRemovalRaceThenFinishOnlyAfterComplete() throws Exception {
        long rideId = createRide();
        makeReady(rideId, "ll");
        // 超阈值读数触发缓停 SUSPENDED 后再检修
        service.open(rideId, new OpenRequest("ll-open"));
        service.reading(rideId, new ReadingRequest(999, 999));
        long orderId = preparedOrder(rideId, "ll-create", List.of("钳工甲", "电工乙"));
        assertEquals(200, service.startMaintenance(orderId,
                new StartMaintenanceRequest("ll-start", 1L)).status());
        // 先串行撤掉第一把，留末锁竞争
        assertEquals(200, service.removeLock(orderId,
                new RemoveLockRequest("ll-rm-1", "钳工甲", "钳工甲", "监护员丙")).status());

        // 末锁并发双撤 + 并发结束，同一发令栅格同时释放
        List<String> results = runConcurrently(List.of(
                removeTask(orderId, "ll-rm-2-a", "电工乙", "监护员丙"),
                removeTask(orderId, "ll-rm-2-b", "电工乙", "监护员丁"),
                finishTask(orderId, "ll-fin")));

        long removeOk = results.stream().filter("REMOVE_OK"::equals).count();
        long removeConflict = results.stream().filter("REMOVE_409"::equals).count();
        assertEquals(1, removeOk, "末锁只能被撤除一次：" + results);
        assertEquals(1, removeConflict, "重复撤锁必须冲突失败：" + results);

        // 结束若抢在末锁撤除之前必须失败；之后重试结束成功，终态唯一
        if (results.contains("FINISH_422")) {
            assertEquals(200, service.finishMaintenance(orderId,
                    new FinishMaintenanceRequest("ll-fin-retry", 2L)).status());
        } else {
            assertTrue(results.contains("FINISH_OK"), "末锁撤完后结束应成功：" + results);
        }
        Map<String, Object> view = service.getMaintenanceOrder(orderId);
        assertEquals("FINISHED", view.get("status"));
        assertEquals(3L, ((Number) view.get("version")).longValue());
        assertEquals(List.of(), view.get("unremovedPersons"));
        assertEquals("SUSPENDED", service.getRide(rideId).get("status"),
                "结束只清除挂牌阻断，恢复缓停态，不自动开放");

        // 末锁只有一行 removed=TRUE，签认人为先提交方，历史不可覆盖
        Integer removedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_lock WHERE order_id = ? AND removed = TRUE",
                Integer.class, orderId);
        assertEquals(2, removedCount, "两把锁均撤除，末锁未被重复写入");
        Integer removeEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ride_event WHERE ride_id = ? AND event_type = 'LOCK_REMOVE'",
                Integer.class, rideId);
        assertEquals(2, removeEvents, "每人仅一条撤锁事件");
    }

    @Test
    @DisplayName("两张检修单并发启动：同设备恰一张进行中，另一张拒绝且保持PREPARED")
    void concurrentStartsOnlyOneActiveOrder() throws Exception {
        long rideId = createRide();
        makeReady(rideId, "cs");
        service.open(rideId, new OpenRequest("cs-open"));
        service.reading(rideId, new ReadingRequest(999, 999));
        long orderA = preparedOrder(rideId, "cs-create-a", List.of("钳工甲"));
        long orderB = preparedOrder(rideId, "cs-create-b", List.of("电工乙", "焊工戊"));

        List<String> results = runConcurrently(List.of(
                () -> {
                    try {
                        service.startMaintenance(orderA, new StartMaintenanceRequest("cs-start-a", 1L));
                        return "START_A_OK";
                    } catch (ApiException e) {
                        return "START_A_" + e.status().value();
                    }
                },
                () -> {
                    try {
                        service.startMaintenance(orderB, new StartMaintenanceRequest("cs-start-b", 1L));
                        return "START_B_OK";
                    } catch (ApiException e) {
                        return "START_B_" + e.status().value();
                    }
                }));

        assertEquals(1, results.stream().filter(r -> r.endsWith("_OK")).count(),
                "恰一张检修单启动成功：" + results);
        assertTrue(results.contains("START_A_409") || results.contains("START_B_409"),
                "占用方必须收到409：" + results);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_order WHERE ride_id = ? AND status = 'IN_PROGRESS'",
                Integer.class, rideId);
        assertEquals(1, active);
        assertEquals("MAINTENANCE", service.getRide(rideId).get("status"));
        // 败方单据仍 PREPARED、未建任何锁
        long loserId = results.contains("START_A_409") ? orderA : orderB;
        Map<String, Object> loser = service.getMaintenanceOrder(loserId);
        assertEquals("PREPARED", loser.get("status"));
        assertEquals(1L, ((Number) loser.get("version")).longValue());
        assertEquals(0, ((List<?>) loser.get("locks")).size());
    }

    @Test
    @DisplayName("并发同键撤锁：全部重放首次冻结结果，锁只撤一次、签认只写一份")
    void concurrentSameKeyRemoveReplaysOnce() throws Exception {
        long rideId = createRide();
        long orderId = preparedOrder(rideId, "ck-create", List.of("钳工甲", "电工乙", "焊工戊"));
        service.startMaintenance(orderId, new StartMaintenanceRequest("ck-start", 1L));

        RemoveLockRequest same = new RemoveLockRequest(
                "ck-rm-same", "钳工甲", "钳工甲", "监护员丙");
        int threads = 6;
        List<Callable<ApiResult>> tasks = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> (Callable<ApiResult>) () -> service.removeLock(orderId, same))
                .toList();
        List<ApiResult> results = runConcurrently(tasks);

        assertTrue(results.stream().allMatch(r -> r.status() == 200),
                "同键撤锁应全部重放成功：" + results);
        assertEquals(1, results.stream().map(ApiResult::body).distinct().count(),
                "所有重放响应体必须一致");
        Map<String, Object> lock = jdbc.queryForMap(
                "SELECT * FROM maintenance_lock WHERE order_id = ? AND person_name = '钳工甲'",
                orderId);
        assertEquals(Boolean.TRUE, lock.get("removed"));
        assertEquals("ck-rm-same", lock.get("remove_request_id"));
        assertEquals("监护员丙", lock.get("guardian"));
        Integer idem = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'ck-rm-same'",
                Integer.class);
        assertEquals(1, idem);
        // 其余两人的锁仍未撤
        assertEquals(List.of("电工乙", "焊工戊"),
                service.getMaintenanceOrder(orderId).get("unremovedPersons"));
    }

    @Test
    @DisplayName("检修启动与开放竞态：不得既整机停运又新开放，败方随后仍被门禁拒绝")
    void startAndOpenRaceKeepUniqueTerminalState() throws Exception {
        long rideId = createRide();
        makeReady(rideId, "ra");
        long orderId = preparedOrder(rideId, "ra-create", List.of("钳工甲", "电工乙"));

        List<String> results = runConcurrently(List.of(
                () -> {
                    try {
                        service.startMaintenance(orderId, new StartMaintenanceRequest("ra-start", 1L));
                        return "START_OK";
                    } catch (ApiException e) {
                        return "START_" + e.status().value();
                    }
                },
                () -> {
                    try {
                        service.open(rideId, new OpenRequest("ra-open"));
                        return "OPEN_OK";
                    } catch (ApiException e) {
                        return "OPEN_" + e.status().value();
                    }
                }));

        String status = (String) service.getRide(rideId).get("status");
        assertTrue(results.contains("START_OK") ^ results.contains("OPEN_OK"),
                "启动与开放恰一方成功：" + results);
        assertTrue("MAINTENANCE".equals(status) || "OPEN".equals(status),
                "终态必须唯一，实际=" + status + "，结果=" + results);
        if ("MAINTENANCE".equals(status)) {
            // 检修占先：开放失败且重试仍被未撤锁门禁拒绝
            assertTrue(results.contains("OPEN_409"));
            ApiException blocked = org.junit.jupiter.api.Assertions.assertThrows(
                    ApiException.class,
                    () -> service.open(rideId, new OpenRequest("ra-open-again")));
            assertEquals(409, blocked.status().value());
            assertTrue(blocked.getMessage().contains("未撤锁"));
        } else {
            // 开放占先：启动失败，设备保持载客且无进行中检修单
            assertTrue(results.contains("START_409"));
            Integer active = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_order WHERE status = 'IN_PROGRESS'",
                    Integer.class);
            assertEquals(0, active);
        }
    }
}
