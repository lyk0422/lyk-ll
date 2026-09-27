package com.example.starter.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * 年度检验门禁并发边界测试：真实 H2 行锁 + 唯一约束，
 * 证书撤销/大修与开放竞态不得出现失效资格仍新开放。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class InspectionConcurrencyTest {

    @Autowired
    private RideService rideService;

    @Autowired
    private InspectionService inspectionService;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM supervision_inspection_item");
        jdbc.update("DELETE FROM supervision_inspection");
        jdbc.update("DELETE FROM overhaul_record");
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

    private long versionOf(long rideId) {
        return ((Number) rideService.getRide(rideId).get("version")).longValue();
    }

    /** 创建受检设备并完成日检+3次试运行+签发有效证书，进入可开放状态。 */
    private long createCertifiedReadyRide(String keyPrefix) {
        long id = ((Number) rideService.createRide(new CreateRideRequest(
                "大摆锤", 12, 50, true)).get("rideId")).longValue();
        rideService.dailyCheck(id, new DailyCheckRequest(keyPrefix + "-check", List.of(
                new CheckItem("压杠", true),
                new CheckItem("安全带", true),
                new CheckItem("联锁装置", true))));
        rideService.trialRun(id, new TrialRunRequest(keyPrefix + "-t1", true));
        rideService.trialRun(id, new TrialRunRequest(keyPrefix + "-t2", true));
        rideService.trialRun(id, new TrialRunRequest(keyPrefix + "-t3", true));
        ApiResult issued = inspectionService.issueCertificate(id, new IssueCertificateRequest(
                keyPrefix + "-cert", versionOf(id), null, null));
        assertEquals(200, issued.status());
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
        return rideService.listEvents(rideId).stream()
                .map(ev -> (String) ev.get("event_type")).toList();
    }

    @Test
    @DisplayName("并发换证：同一expectedVersion仅一个成功，当前指针唯一")
    void concurrentIssueWithSameVersionOnlyOneSucceeds() throws Exception {
        long rideId = ((Number) rideService.createRide(new CreateRideRequest(
                "大摆锤", 12, 50, true)).get("rideId")).longValue();
        int threads = 4;
        List<Callable<Integer>> tasks = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> (Callable<Integer>) () -> {
                    try {
                        return inspectionService.issueCertificate(rideId,
                                new IssueCertificateRequest("ci-" + i, 1L, null, null)).status();
                    } catch (ApiException e) {
                        return e.status().value();
                    }
                })
                .toList();

        List<Integer> results = runConcurrently(tasks);
        assertEquals(1, results.stream().filter(s -> s == 200).count(),
                "同一版本并发换证必须唯一成功：" + results);
        assertEquals(threads - 1, results.stream().filter(s -> s == 409).count());
        assertEquals(1, inspectionService.listCertificates(rideId).size());
        assertEquals(2, versionOf(rideId), "版本从1开始，成功变更加1");
    }

    @Test
    @DisplayName("并发同键签发：全部重放首次冻结结果，证书只签发一次")
    void concurrentSameKeyIssueReplaysOnce() throws Exception {
        long rideId = ((Number) rideService.createRide(new CreateRideRequest(
                "大摆锤", 12, 50, true)).get("rideId")).longValue();
        IssueCertificateRequest same = new IssueCertificateRequest("ck-cert", 1L,
                TestClockConfig.FIXED_NOW, null);
        int threads = 6;
        List<Callable<ApiResult>> tasks = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> (Callable<ApiResult>) () -> inspectionService
                        .issueCertificate(rideId, same))
                .toList();

        List<ApiResult> results = runConcurrently(tasks);
        assertTrue(results.stream().allMatch(r -> r.status() == 200),
                "同键同参必须全部返回首次结果");
        assertEquals(1, results.stream().map(ApiResult::body).distinct().count(),
                "所有重放响应体必须一致");
        assertEquals(1, inspectionService.listCertificates(rideId).size());
        assertEquals(2, versionOf(rideId));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'ck-cert'",
                Integer.class);
        assertEquals(1, idemCount);
    }

    @Test
    @DisplayName("撤销与开放竞态：不得出现失效资格仍新开放")
    void revokeAndOpenRaceNeverOpensWithInvalidEligibility() throws Exception {
        long rideId = createCertifiedReadyRide("rv");
        long certId = ((Number) rideService.getRide(rideId).get("currentCertificateId"))
                .longValue();
        long version = versionOf(rideId);

        Callable<Integer> open = () -> {
            try {
                return rideService.open(rideId, new OpenRequest("rv-open")).status();
            } catch (ApiException e) {
                return e.status().value();
            }
        };
        Callable<Integer> revoke = () -> {
            try {
                return inspectionService.revokeCertificate(rideId, certId,
                        new RevokeCertificateRequest("rv-revoke", version)).status();
            } catch (ApiException e) {
                return e.status().value();
            }
        };

        List<Integer> results = runConcurrently(List.of(open, revoke));
        int openResult = results.get(0);
        int revokeResult = results.get(1);
        assertTrue(openResult == 200 || openResult == 422,
                "开放只能成功（先于撤销）或被业务拒绝：" + openResult);
        assertTrue(revokeResult == 200 || revokeResult == 409,
                "撤销要么生效要么因版本竞态冲突：" + revokeResult);
        if (revokeResult == 409) {
            // 开放先提交导致版本推进：以最新版本重试撤销，必须生效
            revokeResult = inspectionService.revokeCertificate(rideId, certId,
                    new RevokeCertificateRequest("rv-revoke-2", versionOf(rideId))).status();
        }
        assertEquals(200, revokeResult, "撤销必须生效：" + results);

        List<String> types = eventTypes(rideId);
        int openIndex = types.indexOf("OPEN");
        int revokeIndex = types.indexOf("CERT_REVOKE");
        assertTrue(revokeIndex >= 0, "撤销事件必须上链");
        if (openIndex >= 0) {
            assertTrue(openIndex < revokeIndex,
                    "开放只能发生在撤销之前，不得失效资格仍新开放：" + types);
        } else {
            assertNotEquals("OPEN", rideService.getRide(rideId).get("status"),
                    "开放被拒绝后不得处于开放状态");
        }
        // 撤销后任何新的开放尝试都必须失败
        try {
            rideService.open(rideId, new OpenRequest("rv-open-2"));
            throw new IllegalStateException("撤销后不得再开放");
        } catch (ApiException expected) {
            assertTrue(expected.status().value() == 422 || expected.status().value() == 409);
        }
    }

    @Test
    @DisplayName("大修与开放竞态：终态不得为无监督检验的开放")
    void overhaulAndOpenRaceNeverLeavesInvalidOpen() throws Exception {
        long rideId = createCertifiedReadyRide("oh");
        long version = versionOf(rideId);

        Callable<Integer> open = () -> {
            try {
                return rideService.open(rideId, new OpenRequest("oh-open")).status();
            } catch (ApiException e) {
                return e.status().value();
            }
        };
        Callable<Integer> overhaul = () -> {
            try {
                return inspectionService.registerOverhaul(rideId,
                        new OverhaulRequest("oh-ov", version, null, "竞态大修")).status();
            } catch (ApiException e) {
                return e.status().value();
            }
        };

        List<Integer> results = runConcurrently(List.of(open, overhaul));
        int openResult = results.get(0);
        int overhaulResult = results.get(1);
        assertTrue(openResult == 200 || openResult == 422,
                "开放只能成功（先于大修）或被业务拒绝：" + openResult);
        assertTrue(overhaulResult == 200 || overhaulResult == 409,
                "大修要么生效要么因版本竞态冲突：" + overhaulResult);
        if (overhaulResult == 409) {
            // 开放先提交导致版本推进：以最新版本重试大修登记，必须生效
            overhaulResult = inspectionService.registerOverhaul(rideId,
                    new OverhaulRequest("oh-ov-2", versionOf(rideId), null, "竞态大修")).status();
        }
        assertEquals(200, overhaulResult, "大修登记必须生效：" + results);

        Map<String, Object> ride = rideService.getRide(rideId);
        assertEquals(1L, ride.get("overhaulVersion"));
        // 无论竞态顺序，终态不得停留在开放：先开后大修必被暂停，先大修后开放必被拒绝
        assertNotEquals("OPEN", ride.get("status"),
                "大修后无监督检验不得保持开放：" + results);
        List<String> types = eventTypes(rideId);
        int openIndex = types.indexOf("OPEN");
        int overhaulIndex = types.indexOf("OVERHAUL");
        assertTrue(overhaulIndex >= 0, "大修事件必须上链");
        if (openIndex >= 0) {
            assertTrue(openIndex < overhaulIndex,
                    "开放只能发生在大修之前：" + types);
            assertEquals("STOPPED", ride.get("status"), "开放后登记大修必须暂停开放");
        }
    }
}
