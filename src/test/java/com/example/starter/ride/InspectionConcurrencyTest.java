package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
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
import com.example.starter.ride.dto.RideDtos.OverhaulRequest;
import com.example.starter.ride.dto.RideDtos.RegisterCertificateRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;

/**
 * 年度检验门禁并发边界测试：真实 H2 行锁 + 唯一约束，
 * 验证证书撤销/大修与开放竞态不得出现失效资格仍新开放。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class InspectionConcurrencyTest {

    private static final Instant VALID_PASSED_AT = Instant.parse("2026-09-20T00:00:00Z");

    @Autowired
    private RideService service;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM overhaul_inspection_item");
        jdbc.update("DELETE FROM overhaul_inspection");
        jdbc.update("DELETE FROM annual_certificate");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    private long versionOf(long id) {
        return ((Number) service.getRide(id).get("version")).longValue();
    }

    /** 创建受检设备并完成日检+3次试运行（READY），返回设备ID。 */
    private long createReadyInspectedRide(String keyPrefix) {
        long id = ((Number) service.createRide(
                new CreateRideRequest("受检跳楼机", 8, 60, true)).get("rideId")).longValue();
        service.dailyCheck(id, new DailyCheckRequest(keyPrefix + "-check", List.of(
                new CheckItem("压杠", true),
                new CheckItem("安全带", true),
                new CheckItem("联锁装置", true))));
        for (int i = 1; i <= 3; i++) {
            service.trialRun(id, new TrialRunRequest(keyPrefix + "-t" + i, true));
        }
        return id;
    }

    private void registerCertificate(long id, String requestId) {
        ApiResult result = service.registerCertificate(id,
                new RegisterCertificateRequest(requestId, versionOf(id), VALID_PASSED_AT, null));
        assertEquals(200, result.status());
    }

    /** 并发执行两个任务，等待全部完成。 */
    private <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> tasks = List.of(first, second);
        List<Future<T>> futures = tasks.stream()
                .map(task -> pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return task.call();
                }))
                .toList();
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
    @DisplayName("撤销与开放竞态：不得出现证书已撤销仍新开放")
    void revokeAndOpenRaceNeverOpensWithRevokedCertificate() throws Exception {
        long rideId = createReadyInspectedRide("rv");
        registerCertificate(rideId, "rv-cert");
        long certId = ((Number) service.getRide(rideId).get("currentCertificateId")).longValue();
        long version = versionOf(rideId);

        Callable<Integer> open = () -> {
            try {
                return service.open(rideId, new OpenRequest("rv-open")).status();
            } catch (ApiException e) {
                return e.status().value();
            }
        };
        Callable<Integer> revoke = () -> {
            try {
                return service.revokeCertificate(rideId, certId,
                        new RevokeCertificateRequest("rv-revoke", version, "合成撤销")).status();
            } catch (ApiException e) {
                return e.status().value();
            }
        };

        List<Integer> results = runConcurrently(open, revoke);
        int openResult = results.get(0);
        int revokeResult = results.get(1);
        boolean revoked = (Boolean) service.listCertificates(rideId).get(0).get("revoked");

        if (revoked) {
            // 撤销先生效：开放必须被门禁拒绝
            assertEquals(200, revokeResult);
            assertEquals(422, openResult, "证书已撤销不得新开放：" + results);
            assertFalse(service.listEvents(rideId).stream()
                    .anyMatch(ev -> "OPEN".equals(ev.get("event_type"))));
        } else {
            // 开放先生效：撤销因版本冲突失败，资格链一致
            assertEquals(200, openResult);
            assertEquals(409, revokeResult, "开放已推进版本，撤销须版本冲突：" + results);
            assertEquals("OPEN", service.getRide(rideId).get("status"));
        }
    }

    @Test
    @DisplayName("大修与开放竞态：不得出现大修已登记仍新开放")
    void overhaulAndOpenRaceNeverOpensAfterOverhaul() throws Exception {
        long rideId = createReadyInspectedRide("oh");
        registerCertificate(rideId, "oh-cert");
        long version = versionOf(rideId);

        Callable<Integer> open = () -> {
            try {
                return service.open(rideId, new OpenRequest("oh-open")).status();
            } catch (ApiException e) {
                return e.status().value();
            }
        };
        Callable<Integer> overhaul = () -> {
            try {
                return service.registerOverhaul(rideId,
                        new OverhaulRequest("oh-oh", version)).status();
            } catch (ApiException e) {
                return e.status().value();
            }
        };

        List<Integer> results = runConcurrently(open, overhaul);
        int openResult = results.get(0);
        int overhaulResult = results.get(1);
        int overhaulVersion = ((Number) service.getRide(rideId).get("overhaulVersion")).intValue();

        if (overhaulResult == 200) {
            // 大修先生效：开放必须被门禁拒绝（监督检验未确认）
            assertEquals(1, overhaulVersion);
            assertEquals(422, openResult, "大修后监督检验未确认不得新开放：" + results);
            assertFalse(service.listEvents(rideId).stream()
                    .anyMatch(ev -> "OPEN".equals(ev.get("event_type"))));
        } else {
            // 开放先生效：大修因版本冲突失败
            assertEquals(200, openResult);
            assertEquals(409, overhaulResult, "开放已推进版本，大修须版本冲突：" + results);
            assertEquals(0, overhaulVersion);
        }
    }

    @Test
    @DisplayName("同 expectedVersion 并发登记证书：唯一成功，版本只推进一次")
    void concurrentRegisterWithSameVersionOnlyOneSucceeds() throws Exception {
        long rideId = createReadyInspectedRide("sv");
        long version = versionOf(rideId);

        Callable<Integer> first = () -> {
            try {
                return service.registerCertificate(rideId,
                        new RegisterCertificateRequest("sv-cert-a", version,
                                VALID_PASSED_AT, null)).status();
            } catch (ApiException e) {
                return e.status().value();
            }
        };
        Callable<Integer> second = () -> {
            try {
                return service.registerCertificate(rideId,
                        new RegisterCertificateRequest("sv-cert-b", version,
                                VALID_PASSED_AT, null)).status();
            } catch (ApiException e) {
                return e.status().value();
            }
        };

        List<Integer> results = runConcurrently(first, second);
        assertEquals(1, results.stream().filter(s -> s == 200).count(),
                "同版本并发登记必须唯一成功：" + results);
        assertEquals(1, results.stream().filter(s -> s == 409).count());
        assertEquals(1, service.listCertificates(rideId).size(), "证书只登记一条");
        assertEquals(version + 1, versionOf(rideId), "版本只推进一次");
    }

    @Test
    @DisplayName("同键并发登记证书：全部重放首次结果，证书与幂等记录均唯一")
    void concurrentSameRequestIdRegistersOnce() throws Exception {
        long rideId = createReadyInspectedRide("idem");
        long version = versionOf(rideId);
        RegisterCertificateRequest same = new RegisterCertificateRequest(
                "idem-cert", version, VALID_PASSED_AT, null);

        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiResult>> futures = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.registerCertificate(rideId, same);
                }))
                .toList();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        List<ApiResult> results = futures.stream().map(future -> {
            try {
                return future.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("并发任务失败", e);
            }
        }).toList();

        assertTrue(results.stream().allMatch(r -> r.status() == 200),
                "同键同参必须全部返回首次结果");
        assertEquals(1, results.stream().map(ApiResult::body).distinct().count(),
                "所有重放响应体必须一致");
        assertEquals(1, service.listCertificates(rideId).size(), "证书只登记一条");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'idem-cert'",
                Integer.class);
        assertEquals(1, idemCount);
        Map<String, Object> evidence = service.eligibilityEvidence(rideId);
        assertEquals(true, evidence.get("eligible"));
    }
}
