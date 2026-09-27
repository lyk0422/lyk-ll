package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.example.starter.ride.dto.RideDtos.OverhaulRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;

/**
 * 年度检验门禁并发边界测试：真实 H2 行锁串行化，
 * 证书撤销/大修与开放竞态不得出现失效资格仍新开放。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class InspectionConcurrencyTest {

    @Autowired
    private RideService service;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
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
        pool = Executors.newFixedThreadPool(6);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    private long createReadyInspectionRide(String keyPrefix) {
        long id = ((Number) service.createRide(
                new CreateRideRequest("受检跳楼机", 8, 60, true)).get("rideId")).longValue();
        service.dailyCheck(id, new DailyCheckRequest(keyPrefix + "-check", List.of(
                new CheckItem("压杠", true),
                new CheckItem("安全带", true),
                new CheckItem("联锁装置", true))));
        for (int i = 1; i <= 3; i++) {
            service.trialRun(id, new TrialRunRequest(keyPrefix + "-t" + i, true));
        }
        ApiResult cert = service.issueCertificate(id,
                new IssueCertificateRequest(keyPrefix + "-cert", null, null));
        assertEquals(200, cert.status());
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

    private List<String> eventTypes(long rideId) {
        return service.listEvents(rideId).stream()
                .map(ev -> (String) ev.get("event_type")).toList();
    }

    @Test
    @DisplayName("撤销与开放竞态：不得出现证书失效后仍新开放")
    void revokeAndOpenRaceNeverOpensWithInvalidCertificate() throws Exception {
        long rideId = createReadyInspectionRide("rv");
        Callable<String> open = () -> {
            try {
                service.open(rideId, new OpenRequest("rv-open"));
                return "OPEN_OK";
            } catch (ApiException e) {
                return "OPEN_" + e.status().value();
            }
        };
        Callable<String> revoke = () -> {
            try {
                service.revokeCertificate(rideId,
                        new RevokeCertificateRequest("rv-revoke", 1L, "竞态撤销"));
                return "REVOKE_OK";
            } catch (ApiException e) {
                return "REVOKE_" + e.status().value();
            }
        };

        List<String> results = runConcurrently(List.of(open, revoke));
        assertTrue(results.contains("REVOKE_OK"), "撤销必须生效：" + results);
        // 撤销生效后无当前证书
        Integer pointerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM current_certificate WHERE ride_id = ?",
                Integer.class, rideId);
        assertEquals(0, pointerCount);
        // 开放只能在撤销之前成功；若开放成功，OPEN事件必须先于CERT_REVOKE事件
        List<String> types = eventTypes(rideId);
        int openIndex = types.indexOf("OPEN");
        int revokeIndex = types.indexOf("CERT_REVOKE");
        assertTrue(revokeIndex >= 0, "撤销事件必须存在：" + types);
        if (openIndex >= 0) {
            assertTrue(openIndex < revokeIndex,
                    "不得出现证书失效后仍新开放：" + types + "，结果=" + results);
        }
        // 竞态结束后资格证据判定不可开放，再次开放必被拒
        assertTrue(service.listRevocations(rideId).size() == 1);
        if (!"OPEN".equals(service.getRide(rideId).get("status"))) {
            ApiException denied = org.junit.jupiter.api.Assertions.assertThrows(
                    ApiException.class,
                    () -> service.open(rideId, new OpenRequest("rv-open-again")));
            assertEquals(422, denied.status().value());
        }
    }

    @Test
    @DisplayName("大修与开放竞态：不得出现大修后无监督检验仍新开放")
    void overhaulAndOpenRaceNeverOpensWithoutSupervision() throws Exception {
        long rideId = createReadyInspectionRide("oh");
        Callable<String> open = () -> {
            try {
                service.open(rideId, new OpenRequest("oh-open"));
                return "OPEN_OK";
            } catch (ApiException e) {
                return "OPEN_" + e.status().value();
            }
        };
        Callable<String> overhaul = () -> {
            try {
                service.registerOverhaul(rideId, new OverhaulRequest("oh-overhaul"));
                return "OVERHAUL_OK";
            } catch (ApiException e) {
                return "OVERHAUL_" + e.status().value();
            }
        };

        List<String> results = runConcurrently(List.of(open, overhaul));
        assertTrue(results.contains("OVERHAUL_OK"), "大修必须生效：" + results);
        assertEquals(1L, ((Number) service.getRide(rideId).get("overhaulVersion")).longValue());
        // 开放只能在大修之前成功；OPEN事件不得出现在OVERHAUL事件之后
        List<String> types = eventTypes(rideId);
        int openIndex = types.indexOf("OPEN");
        int overhaulIndex = types.indexOf("OVERHAUL");
        assertTrue(overhaulIndex >= 0, "大修事件必须存在：" + types);
        if (openIndex >= 0) {
            assertTrue(openIndex < overhaulIndex,
                    "不得出现大修后无监督检验仍新开放：" + types + "，结果=" + results);
            // 开放后遇大修必被暂停开放
            assertEquals("SUSPENDED", service.getRide(rideId).get("status"));
        }
        // 大修后未完成监督检验：资格证据判定不可开放
        assertEquals(false, service.getEligibility(rideId).get("openEligible"));
    }

    @Test
    @DisplayName("并发换证：唯一当前指针，历史证书全部保留")
    void concurrentRenewalKeepsUniqueCurrentPointer() throws Exception {
        long rideId = createReadyInspectionRide("rn");
        int threads = 6;
        List<Callable<ApiResult>> tasks = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> (Callable<ApiResult>) () -> service.issueCertificate(rideId,
                        new IssueCertificateRequest("rn-cert-" + i, null, null)))
                .toList();

        List<ApiResult> results = runConcurrently(tasks);
        assertTrue(results.stream().allMatch(r -> r.status() == 200),
                "并发换证应全部成功（行锁串行切换）：" + results);
        // 历史证书全部保留（含建夹具时签发的1张），当前指针唯一
        assertEquals(threads + 1, service.listCertificates(rideId).size());
        Integer pointerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM current_certificate WHERE ride_id = ?",
                Integer.class, rideId);
        assertEquals(1, pointerCount, "当前指针必须唯一");
        Map<String, Object> current = service.getCurrentCertificate(rideId);
        assertNotNull(current.get("certificateId"));
        // 指针必须指向本设备真实存在的证书
        Integer pointerValid = jdbc.queryForObject(
                "SELECT COUNT(*) FROM current_certificate p"
                        + " JOIN annual_certificate c ON c.id = p.certificate_id"
                        + " WHERE p.ride_id = ? AND c.ride_id = ?",
                Integer.class, rideId, rideId);
        assertEquals(1, pointerValid);
    }

    @Test
    @DisplayName("并发同键签发：全部重放首次结果，证书只签发一次")
    void concurrentSameKeyIssueReplaysOnce() throws Exception {
        long rideId = createReadyInspectionRide("idem");
        int threads = 6;
        IssueCertificateRequest same = new IssueCertificateRequest("idem-cert-same", null, null);
        List<Callable<ApiResult>> tasks = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> (Callable<ApiResult>) () -> service.issueCertificate(rideId, same))
                .toList();

        List<ApiResult> results = runConcurrently(tasks);
        assertTrue(results.stream().allMatch(r -> r.status() == 200));
        assertEquals(1, results.stream().map(ApiResult::body).distinct().count(),
                "所有重放响应体必须一致");
        // 建夹具时已签发1张，并发同键只新增1张
        assertEquals(2, service.listCertificates(rideId).size());
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'idem-cert-same'",
                Integer.class);
        assertEquals(1, idemCount);
    }
}
