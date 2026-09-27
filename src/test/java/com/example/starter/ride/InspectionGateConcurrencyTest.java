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
import com.example.starter.ride.dto.RideDtos.IssueCertificateRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.RegisterOverhaulRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;

/**
 * 年度证书撤销/大修与开放竞态、以及新增写操作并发幂等测试（真实 H2 行锁 + 唯一约束）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class InspectionGateConcurrencyTest {

    private static final java.time.Instant NOW = TestClockConfig.FIXED_NOW;
    private static final List<CheckItem> FULL_CHECK = List.of(
            new CheckItem("压杠", true),
            new CheckItem("安全带", true),
            new CheckItem("联锁装置", true));

    @Autowired
    private RideService service;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM supervisory_check_item");
        jdbc.update("DELETE FROM supervisory_inspection");
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

    private long createReadyWithCertificate(String prefix) {
        long id = ((Number) service.createRide(
                new CreateRideRequest("海盗船", 8, 60, true, 1)).get("rideId")).longValue();
        service.dailyCheck(id, new DailyCheckRequest(prefix + "-check", FULL_CHECK));
        service.trialRun(id, new TrialRunRequest(prefix + "-t1", true));
        service.trialRun(id, new TrialRunRequest(prefix + "-t2", true));
        service.trialRun(id, new TrialRunRequest(prefix + "-t3", true));
        long version = ((Number) service.getRide(id).get("version")).longValue();
        service.issueCertificate(id,
                new IssueCertificateRequest(prefix + "-cert", version, NOW, null));
        return id;
    }

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

    private List<String> eventTypes(long rideId) {
        return service.listEvents(rideId).stream()
                .map(ev -> (String) ev.get("event_type")).toList();
    }

    @Test
    @DisplayName("撤销证书与开放竞态：撤销提交后不得再有新开放；事件链顺序可证")
    void revokeAndOpenRaceNeverOpensWithInvalidQualification() throws Exception {
        long rideId = createReadyWithCertificate("race-cert");
        long version = ((Number) service.getRide(rideId).get("version")).longValue();

        Callable<String> open = () -> {
            try {
                service.open(rideId, new OpenRequest("race-cert-open"));
                return "OPEN_OK";
            } catch (ApiException e) {
                return "OPEN_" + e.status().value();
            }
        };
        Callable<String> revoke = () -> {
            try {
                service.revokeCertificate(rideId,
                        new RevokeCertificateRequest("race-cert-revoke", version, NOW));
                return "REVOKE_OK";
            } catch (ApiException e) {
                return "REVOKE_" + e.status().value();
            }
        };

        List<String> results = runConcurrently(List.of(open, revoke));
        String finalStatus = (String) service.getRide(rideId).get("status");
        List<String> types = eventTypes(rideId);
        int revokeIndex = types.indexOf("CERT_REVOKED");
        int openIndex = types.indexOf("OPEN");

        // 行锁串行化：恰一方先提交。开放先提交时撤销因乐观锁409，需重试
        boolean openWon = results.contains("OPEN_OK");
        if (openWon) {
            assertTrue(results.contains("REVOKE_409"), "开放先提交时撤销应版本冲突409：" + results);
            assertEquals("OPEN", finalStatus);
            assertTrue(openIndex >= 0 && revokeIndex < 0);
            return;
        }

        // 撤销先提交：撤销必须生效，开放被资格门禁422，设备保持READY，无OPEN事件
        assertTrue(results.contains("REVOKE_OK"), "撤销必须成功：" + results);
        assertTrue(results.contains("OPEN_422"), "撤销后开放应422：" + results);
        assertEquals("READY", finalStatus);
        assertTrue(revokeIndex >= 0);
        assertEquals(-1, openIndex, "撤销先完成时不得有OPEN事件：" + types);
        // 当前指针必须已清空，证书终态 REVOKED
        assertEquals(null, service.getRide(rideId).get("currentCertificateId"));
        Map<String, Object> evidence = service.listCertificates(rideId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> certs =
                (List<Map<String, Object>>) evidence.get("certificates");
        assertEquals("REVOKED", certs.get(certs.size() - 1).get("status"));
    }

    @Test
    @DisplayName("登记大修与开放竞态：终态必收敛为大修暂停，大修后不得新开放")
    void overhaulAndOpenRaceConvergesToOverhaul() throws Exception {
        long rideId = createReadyWithCertificate("race-oh");

        Callable<String> open = () -> {
            try {
                service.open(rideId, new OpenRequest("race-oh-open"));
                return "OPEN_OK";
            } catch (ApiException e) {
                return "OPEN_" + e.status().value();
            }
        };
        Callable<String> overhaul = () -> {
            // 真实客户端：若开放先提交导致版本过期，读取新版本后重试，大修语义最终必须生效
            for (int attempt = 0; attempt < 5; attempt++) {
                long currentVersion =
                        ((Number) service.getRide(rideId).get("version")).longValue();
                try {
                    service.registerOverhaul(rideId,
                            new RegisterOverhaulRequest("race-oh-register", currentVersion));
                    return "OVERHAUL_OK";
                } catch (ApiException e) {
                    if (e.status().value() != 409) {
                        return "OVERHAUL_" + e.status().value();
                    }
                }
            }
            return "OVERHAUL_RETRY_EXHAUSTED";
        };

        List<String> results = runConcurrently(List.of(open, overhaul));
        String finalStatus = (String) service.getRide(rideId).get("status");
        List<String> types = eventTypes(rideId);
        int overhaulIndex = types.indexOf("OVERHAUL_REGISTERED");
        int openIndex = types.indexOf("OPEN");

        assertTrue(results.contains("OVERHAUL_OK"), "大修登记必须最终生效：" + results);
        // 大修一旦提交必暂停开放：最终状态必须是 OVERHAUL
        assertEquals("OVERHAUL", finalStatus, "竞态结果必须收敛为大修暂停，结果=" + results);
        assertEquals(1, service.getRide(rideId).get("overhaulVersion"));
        if (openIndex >= 0) {
            // 开放只能发生在大修登记之前，且大修后不再有 OPEN
            assertTrue(openIndex < overhaulIndex, "大修登记后不得新开放：" + types);
            assertEquals(openIndex, types.lastIndexOf("OPEN"), "大修后不得重复开放：" + types);
        } else {
            assertTrue(results.contains("OPEN_409"),
                    "大修先完成时开放应被状态机拒绝409：" + results);
        }
    }

    @Test
    @DisplayName("并发同键登记大修：全部重放首次冻结结果，大修版本只递增一次")
    void concurrentSameOverhaulRequestReplaysOnce() throws Exception {
        long rideId = createReadyWithCertificate("idem-oh");
        long version = ((Number) service.getRide(rideId).get("version")).longValue();
        int threads = 6;
        RegisterOverhaulRequest same =
                new RegisterOverhaulRequest("idem-oh-register", version);
        List<Callable<ApiResult>> tasks = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> (Callable<ApiResult>) () ->
                        service.registerOverhaul(rideId, same))
                .toList();

        List<ApiResult> results = runConcurrently(tasks);
        assertTrue(results.stream().allMatch(r -> r.status() == 200),
                "同键同参必须全部返回首次冻结结果");
        assertEquals(1, results.stream().map(ApiResult::body).distinct().count(),
                "所有重放响应体必须一致");
        assertEquals(1, service.getRide(rideId).get("overhaulVersion"),
                "大修版本只递增一次");
        assertEquals(version + 1,
                ((Number) service.getRide(rideId).get("version")).longValue(),
                "设备版本只加1");
        long overhaulEvents = eventTypes(rideId).stream()
                .filter("OVERHAUL_REGISTERED"::equals).count();
        assertEquals(1, overhaulEvents, "业务只执行一次");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'idem-oh-register'",
                Integer.class);
        assertEquals(1, idemCount);
    }
}
