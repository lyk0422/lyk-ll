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
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.SafetyFaultRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;

/**
 * 并发与幂等边界测试：真实 H2 行锁 + 唯一约束，协调真实并发操作并设置超时。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class RideConcurrencyTest {

    @Autowired
    private RideService service;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
        pool = Executors.newFixedThreadPool(6);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    private long createReadyRide(String keyPrefix) {
        long id = ((Number) service.createRide(
                new CreateRideRequest("跳楼机", 8, 60)).get("rideId")).longValue();
        service.dailyCheck(id, new DailyCheckRequest(keyPrefix + "-check", List.of(
                new CheckItem("压杠", true),
                new CheckItem("安全带", true),
                new CheckItem("联锁装置", true))));
        service.trialRun(id, new TrialRunRequest(keyPrefix + "-t1", true));
        service.trialRun(id, new TrialRunRequest(keyPrefix + "-t2", true));
        service.trialRun(id, new TrialRunRequest(keyPrefix + "-t3", true));
        return id;
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
    @DisplayName("并发开放唯一成功：同一设备同时刻只允许一条状态链")
    void concurrentOpenOnlyOneSucceeds() throws Exception {
        long rideId = createReadyRide("co");
        int threads = 6;
        List<Callable<Integer>> tasks = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> (Callable<Integer>) () -> {
                    try {
                        return service.open(rideId, new OpenRequest("co-open-" + i)).status();
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                })
                .toList();

        List<Integer> results = runConcurrently(tasks);
        assertEquals(1, results.stream().filter(s -> s == 200).count(),
                "并发开放必须唯一成功：" + results);
        assertEquals(threads - 1, results.stream().filter(s -> s == 409).count());
        assertEquals("OPEN", service.getRide(rideId).get("status"));
        // 启停链中仅一条 OPEN 事件
        long openEvents = service.listEvents(rideId).stream()
                .filter(ev -> "OPEN".equals(ev.get("event_type"))).count();
        assertEquals(1, openEvents);
    }

    @Test
    @DisplayName("停运开放竞态：终态唯一，不得既停运又显示开放")
    void openAndSafetyFaultRaceHasSingleTerminalState() throws Exception {
        long rideId = createReadyRide("race");
        Callable<String> open = () -> {
            try {
                service.open(rideId, new OpenRequest("race-open"));
                return "OPEN_OK";
            } catch (ApiException e) {
                return "OPEN_" + e.status().value();
            }
        };
        Callable<String> fault = () -> {
            try {
                service.safetyFault(rideId, new SafetyFaultRequest("race-fault", "压杠"));
                return "FAULT_OK";
            } catch (ApiException e) {
                return "FAULT_" + e.status().value();
            }
        };

        List<String> results = runConcurrently(List.of(open, fault));
        String finalStatus = (String) service.getRide(rideId).get("status");
        // 单状态字段保证唯一终态：开放后遇故障必停运，或故障先于开放则开放失败
        assertEquals("STOPPED", finalStatus,
                "竞态结果必须收敛为停运终态，结果=" + results);
        // 安全装置失效上报必须成功（立即停运语义不被竞态吞掉）
        assertTrue(results.contains("FAULT_OK"), "故障停运必须生效：" + results);
        // 启停链一致：若存在 OPEN 事件，其后必有 FAULT_STOP，且归档前不再开放
        List<String> types = service.listEvents(rideId).stream()
                .map(ev -> (String) ev.get("event_type")).toList();
        int openIndex = types.indexOf("OPEN");
        if (openIndex >= 0) {
            assertTrue(types.subList(openIndex, types.size()).contains("FAULT_STOP"),
                    "开放后停运必须出现在启停链中：" + types);
            assertEquals(openIndex, types.lastIndexOf("OPEN"), "不得重复开放：" + types);
        }
    }

    @Test
    @DisplayName("并发同键重放：全部返回首次结果，业务只执行一次")
    void concurrentSameRequestIdReplaysOnce() throws Exception {
        long rideId = createReadyRide("idem");
        int threads = 6;
        OpenRequest same = new OpenRequest("idem-open");
        List<Callable<ApiResult>> tasks = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> (Callable<ApiResult>) () -> service.open(rideId, same))
                .toList();

        List<ApiResult> results = runConcurrently(tasks);
        assertTrue(results.stream().allMatch(r -> r.status() == 200),
                "同键同参必须全部返回首次结果");
        assertEquals(1, results.stream().map(ApiResult::body).distinct().count(),
                "所有重放响应体必须一致");
        assertEquals("OPEN", service.getRide(rideId).get("status"));
        long openEvents = service.listEvents(rideId).stream()
                .filter(ev -> "OPEN".equals(ev.get("event_type"))).count();
        assertEquals(1, openEvents, "业务只执行一次");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'idem-open'",
                Integer.class);
        assertEquals(1, idemCount);
    }
}
